package com.starter.auth.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

/**
 * SQLite 持久化的演示用户。
 *
 * <ul>
 *   <li>启动时自动建表 users(username PK, password, login_id, roles, phone)，缺列自动迁移
 *       （ALTER TABLE ADD COLUMN）；</li>
 *   <li>表为空时写入种子数据：优先取 application.yml 的 starter.demo-users，缺省内置 alice/bob；
 *       注意：老库已有数据不会回填 phone，删除 data/auth.db 重启即可按新种子重建；</li>
 *   <li>库文件由 spring.datasource.url 决定（默认 jdbc:sqlite:data/auth.db，可用 STARTER_DB_PATH 或
 *       --spring.datasource.url 覆盖），父目录不存在时自动创建；</li>
 *   <li>对外行为与端点契约不变。注意：token 会话仍存内存（Sa-Token 默认），重启后旧 token 失效，
 *       仅用户数据由 SQLite 持久化。</li>
 * </ul>
 */
@Service
public class DemoUserService {

    private static final String URL_PREFIX = "jdbc:sqlite:";

    private static final List<SeedUser> DEFAULT_SEEDS = List.of(
            new SeedUser("alice", "alice123", "1", List.of("user"), "13800000001"),
            new SeedUser("bob", "bob123", "2", List.of("user", "admin"), "13800000002"),
            new SeedUser("carol", "carol123", "3", List.of("user"), "13800000003"),
            new SeedUser("dave", "dave123", "4", List.of("user"), "13800000004"));

    private final JdbcTemplate jdbc;
    private final String datasourceUrl;
    private final List<SeedUser> seeds;

    public DemoUserService(JdbcTemplate jdbc, Environment env) {
        this.jdbc = jdbc;
        this.datasourceUrl = env.getProperty("spring.datasource.url", "");
        this.seeds = Binder.get(env)
                .bind("starter.demo-users", Bindable.listOf(SeedUser.class))
                .orElse(DEFAULT_SEEDS);
    }

    @PostConstruct
    void initDatabase() throws IOException {
        ensureSqliteParentDirectory();
        jdbc.execute("CREATE TABLE IF NOT EXISTS users ("
                + "username TEXT PRIMARY KEY, "
                + "password TEXT NOT NULL, "
                + "login_id TEXT NOT NULL, "
                + "roles TEXT NOT NULL, "
                + "phone TEXT)");
        migrateAddPhoneColumn();
        // 自主注册要求手机号唯一;唯一索引允许多个 NULL(老库无手机号的种子行不受影响)
        jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_users_phone ON users(phone)");
        // 第三方登录(微信/QQ 等)的身份绑定表:provider+open_id → login_id
        jdbc.execute("CREATE TABLE IF NOT EXISTS user_identities ("
                + "provider TEXT NOT NULL, "
                + "open_id TEXT NOT NULL, "
                + "login_id TEXT NOT NULL, "
                + "PRIMARY KEY (provider, open_id))");
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        if (count != null && count == 0) {
            for (SeedUser u : seeds) {
                jdbc.update("INSERT INTO users(username, password, login_id, roles, phone) "
                        + "VALUES (?, ?, ?, ?, ?)",
                        u.username(), u.password(), u.loginId(), String.join(",", u.roles()),
                        u.phone());
            }
        }
    }

    /** 老库升级：缺 phone 列时补一列（SQLite ADD COLUMN 不带 NOT NULL 约束限制）。 */
    private void migrateAddPhoneColumn() {
        boolean hasPhone = Boolean.TRUE.equals(jdbc.query(
                "PRAGMA table_info(users)",
                rs -> {
                    while (rs.next()) {
                        if ("phone".equalsIgnoreCase(rs.getString("name"))) {
                            return true;
                        }
                    }
                    return false;
                }));
        if (!hasPhone) {
            jdbc.execute("ALTER TABLE users ADD COLUMN phone TEXT");
        }
    }

    /** sqlite 库文件的父目录不存在时创建（SQLite 不会自动建目录）；:memory: 等特殊形式跳过。 */
    private void ensureSqliteParentDirectory() throws IOException {
        if (!datasourceUrl.startsWith(URL_PREFIX)) {
            return;
        }
        String file = datasourceUrl.substring(URL_PREFIX.length());
        int query = file.indexOf('?');
        if (query >= 0) {
            file = file.substring(0, query);
        }
        if (file.isBlank() || ":memory:".equals(file)) {
            return;
        }
        Path parent = Path.of(file).toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }

    public Optional<DemoUser> authenticate(String username, String password) {
        List<DemoUser> found = jdbc.query(
                "SELECT username, password, login_id, roles, phone FROM users WHERE username = ? AND password = ?",
                DemoUserService::mapRow, username, password);
        return found.stream().findFirst();
    }

    public Optional<DemoUser> findByPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        List<DemoUser> found = jdbc.query(
                "SELECT username, password, login_id, roles, phone FROM users WHERE phone = ?",
                DemoUserService::mapRow, phone.trim());
        return found.stream().findFirst();
    }

    public boolean existsByUsername(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE username = ?", Integer.class, username.trim());
        return count != null && count > 0;
    }

    public boolean existsByPhone(String phone) {
        return findByPhone(phone).isPresent();
    }

    /**
     * 自主注册：login_id 取现有最大数字 +1（种子为 1..N），默认角色 user，密码明文存储（演示定位）。
     *
     * @return 新用户的 loginId
     */
    public String createUser(String username, String password, String phone) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(login_id AS INTEGER)), 0) FROM users", Integer.class);
        String loginId = String.valueOf((max == null ? 0 : max) + 1);
        jdbc.update(
                "INSERT INTO users(username, password, login_id, roles, phone) VALUES (?, ?, ?, 'user', ?)",
                username.trim(), password, loginId, phone == null ? null : phone.trim());
        return loginId;
    }

    /** 第三方身份查找登录ID;未绑定返回 empty。 */
    public Optional<String> findLoginIdByIdentity(String provider, String openId) {
        if (provider == null || openId == null) {
            return Optional.empty();
        }
        List<String> found = jdbc.query(
                "SELECT login_id FROM user_identities WHERE provider = ? AND open_id = ?",
                (rs, i) -> rs.getString("login_id"), provider, openId);
        return found.stream().findFirst();
    }

    /** 绑定第三方身份到用户。 */
    public void saveIdentity(String provider, String openId, String loginId) {
        jdbc.update("INSERT INTO user_identities(provider, open_id, login_id) VALUES (?, ?, ?)",
                provider, openId, loginId);
    }

    public Optional<DemoUser> findByLoginId(String loginId) {
        List<DemoUser> found = jdbc.query(
                "SELECT username, password, login_id, roles, phone FROM users WHERE login_id = ?",
                DemoUserService::mapRow, loginId);
        return found.stream().findFirst();
    }

    private static DemoUser mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        List<String> roles = Arrays.stream(rs.getString("roles").split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new DemoUser(rs.getString("username"), rs.getString("password"),
                rs.getString("login_id"), roles);
    }

    /** yml 种子数据条目（starter.demo-users）。 */
    public record SeedUser(String username, String password, String loginId, List<String> roles,
            String phone) {
    }

    /** 对外用户模型，字段语义与端点契约一致。 */
    public record DemoUser(String username, String password, String loginId, List<String> roles) {
    }
}
