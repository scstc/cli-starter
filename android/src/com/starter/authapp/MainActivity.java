package com.starter.authapp;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.InputType;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.Window;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements CameraScanner.Listener {

    // 默认走 adb reverse USB 隧道(adb reverse tcp:8090 tcp:8090),免防火墙;
    // 同一局域网 Wi-Fi 时可改为电脑局域网地址(如 http://192.168.9.143:8090)
    private static final String DEFAULT_BASE = "http://192.168.9.143:8090";
    private static final Pattern USER_CODE = Pattern.compile("([A-Z2-9]{4})-?([A-Z2-9]{4})", Pattern.CASE_INSENSITIVE);

    private final ExecutorService exec = Executors.newSingleThreadExecutor();

    private String base;
    private String token;

    private LinearLayout loginPanel;
    private LinearLayout mainPanel;
    private FrameLayout scanPanel;
    private FrameLayout serverOverlay;
    private EditText serverInput;
    private FrameLayout manualOverlay;
    private EditText manualCodeEt;
    private EditText userEt;
    private EditText passEt;
    private EditText captchaEt;
    private ImageView captchaIv;
    private TextView statusTv;
    private TextView userTv;
    private SurfaceView scanSurface;
    private CameraScanner scanner;

    private String captchaId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Ui.NAVY);
        base = getSharedPreferences("authapp", MODE_PRIVATE).getString("base", DEFAULT_BASE);
        token = getSharedPreferences("authapp", MODE_PRIVATE).getString("token", null);
        buildUi();
        if (token != null) {
            validateSession();
        } else {
            show(loginPanel);
            loadCaptcha();
        }
    }

    /* ---------------- 基础控件 ---------------- */

    private TextView text(String s, int sp, int color, boolean bold) {
        return Ui.text(this, s, sp, color, bold);
    }

    private TextView mutedText(String s, int sp) {
        return Ui.text(this, s, sp, Ui.MUTED, false);
    }

    private EditText input(String hint, boolean password) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextColor(Ui.TEXT);
        et.setHintTextColor(0xFFA4ADC0);
        et.setTextSize(16);
        et.setBackground(Ui.input());
        et.setPadding(Ui.dp(12), 0, Ui.dp(12), 0);
        et.setMinimumHeight(Ui.dp(48));
        et.setMaxLines(1);
        if (password) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return et;
    }

    private Button primaryBtn(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Ui.WHITE);
        b.setTextSize(16);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(Ui.btnPrimary());
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(0, Ui.dp(14), 0, Ui.dp(14));
        return b;
    }

    private Button outlineBtn(String label, int textColor) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(textColor);
        b.setTextSize(14);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setBackground(Ui.btnOutline());
        b.setMinimumWidth(0);
        b.setMinimumHeight(0);
        b.setPadding(0, Ui.dp(12), 0, Ui.dp(12));
        return b;
    }

    private LinearLayout.LayoutParams match(int topMarginDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = Ui.dp(topMarginDp);
        return p;
    }

    /* ---------------- 界面 ---------------- */

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.PAGE_BG);
        setContentView(root);

        /* ---- 登录面板 ---- */
        loginPanel = new LinearLayout(this);
        loginPanel.setOrientation(LinearLayout.VERTICAL);
        ScrollView loginScroll = new ScrollView(this);
        loginScroll.setFillViewport(true);
        loginScroll.addView(loginPanel, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 品牌头部
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setBackground(Ui.brandHeader());
        header.setPadding(Ui.dp(22), Ui.dp(30), Ui.dp(22), Ui.dp(30));
        LinearLayout logoRow = new LinearLayout(this);
        logoRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("C", 17, Ui.WHITE, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(Ui.gradient(9, Ui.BRAND, Ui.VIOLET));
        logoRow.addView(logo, new LinearLayout.LayoutParams(Ui.dp(36), Ui.dp(36)));
        TextView brandName = text("cli-starter", 16, Ui.WHITE, true);
        brandName.setPadding(Ui.dp(10), 0, 0, 0);
        logoRow.addView(brandName);
        header.addView(logoRow);
        TextView title = text("移动授权助手", 23, Ui.WHITE, true);
        title.setPadding(0, Ui.dp(24), 0, Ui.dp(2));
        header.addView(title);
        header.addView(text("扫码授权,更安全", 13, 0xB3E2E8FF, false));
        loginPanel.addView(header, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 登录卡片(压在品牌头部上,做层次)
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(16, Ui.WHITE, 0, 0));
        card.setElevation(Ui.dp(4));
        card.setPadding(Ui.dp(14), Ui.dp(20), Ui.dp(14), Ui.dp(20));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(Ui.dp(8), Ui.dp(-22), Ui.dp(8), Ui.dp(8));
        loginPanel.addView(card, cardParams);

        card.addView(text("欢迎登录", 20, Ui.TEXT, true));
        card.addView(mutedText("登录后即可扫描网页二维码完成授权", 13));

        statusTv = text("", 13, Ui.MUTED, false);
        statusTv.setBackground(Ui.statusBg());
        statusTv.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(8));
        statusTv.setVisibility(View.GONE);
        card.addView(statusTv, match(10));

        // 品牌 logo;连续点击 10 次进入服务器地址设置(隐藏入口)
        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER);
        TextView miniLogo = text("C", 14, Ui.WHITE, true);
        miniLogo.setGravity(Gravity.CENTER);
        miniLogo.setBackground(Ui.gradient(8, Ui.BRAND, Ui.VIOLET));
        brandRow.addView(miniLogo, new LinearLayout.LayoutParams(Ui.dp(28), Ui.dp(28)));
        TextView miniName = text("cli-starter", 13, Ui.MUTED, false);
        miniName.setPadding(Ui.dp(8), 0, 0, 0);
        brandRow.addView(miniName);
        brandRow.setOnClickListener(v -> onLogoTap());
        card.addView(brandRow, match(10));

        Button oneClick = primaryBtn("本机号码一键登录");
        oneClick.setOnClickListener(v -> doOneClick());
        card.addView(oneClick, match(16));

        TextView divider = text("其他方式:账号密码登录", 12, Ui.MUTED, false);
        divider.setGravity(Gravity.CENTER);
        card.addView(divider, match(12));

        userEt = input("用户名", false);
        card.addView(userEt, match(4));
        passEt = input("密码", true);
        card.addView(passEt, match(10));

        LinearLayout captchaRow = new LinearLayout(this);
        captchaRow.setOrientation(LinearLayout.HORIZONTAL);
        captchaRow.setGravity(Gravity.CENTER_VERTICAL);
        captchaEt = input("图形验证码", false);
        LinearLayout.LayoutParams capParams = new LinearLayout.LayoutParams(
                0, Ui.dp(48), 1f);
        captchaRow.addView(captchaEt, capParams);
        captchaIv = new ImageView(this);
        captchaIv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        captchaIv.setBackground(Ui.rounded(10, 0xFFEEF2F8, dp1(), Ui.BORDER));
        captchaIv.setPadding(Ui.dp(2), Ui.dp(2), Ui.dp(2), Ui.dp(2));
        captchaIv.setOnClickListener(v -> loadCaptcha());
        LinearLayout.LayoutParams ivParams = new LinearLayout.LayoutParams(Ui.dp(104), Ui.dp(48));
        ivParams.setMargins(Ui.dp(8), 0, 0, 0);
        captchaIv.setLayoutParams(ivParams);
        captchaRow.addView(captchaIv);
        card.addView(captchaRow, match(10));

        Button loginBtn = primaryBtn("登 录");
        loginBtn.setOnClickListener(v -> doPasswordLogin());
        card.addView(loginBtn, match(14));

        TextView oauthDivider = text("其他登录方式", 12, Ui.MUTED, false);
        oauthDivider.setGravity(Gravity.CENTER);
        card.addView(oauthDivider, match(16));

        LinearLayout oauthRow = new LinearLayout(this);
        oauthRow.setGravity(Gravity.CENTER);
        oauthRow.addView(providerColumn(R.drawable.ic_wechat, "微信", 0xFF07C160,
                () -> oauthLogin("wechat", "微信", 0xFF07C160)));
        oauthRow.addView(providerColumn(R.drawable.ic_qq, "QQ", 0xFF12B7F5,
                () -> oauthLogin("qq", "QQ", 0xFF12B7F5)));
        oauthRow.addView(providerColumn(R.drawable.ic_weibo, "微博", 0xFFE6162D,
                () -> oauthLogin("weibo", "微博", 0xFFE6162D)));
        card.addView(oauthRow, match(8));

        TextView footer = text("cli-starter · 移动授权客户端", 11, Ui.MUTED, false);
        footer.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        footerParams.setMargins(0, Ui.dp(8), 0, Ui.dp(12));
        loginPanel.addView(footer, footerParams);
        root.addView(loginScroll);

        /* ---- 已登录面板 ---- */
        mainPanel = new LinearLayout(this);
        mainPanel.setOrientation(LinearLayout.VERTICAL);
        LinearLayout mainHeader = new LinearLayout(this);
        mainHeader.setOrientation(LinearLayout.VERTICAL);
        mainHeader.setBackground(Ui.brandHeader());
        mainHeader.setPadding(Ui.dp(22), Ui.dp(30), Ui.dp(22), Ui.dp(26));
        userTv = text("", 17, Ui.WHITE, true);
        mainHeader.addView(userTv);
        mainHeader.addView(text("扫描网页二维码,即可授权登录", 13, 0xB3E2E8FF, false));
        mainPanel.addView(mainHeader, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout mainCard = new LinearLayout(this);
        mainCard.setOrientation(LinearLayout.VERTICAL);
        mainCard.setBackground(Ui.rounded(16, Ui.WHITE, 0, 0));
        mainCard.setElevation(Ui.dp(4));
        mainCard.setPadding(Ui.dp(14), Ui.dp(20), Ui.dp(14), Ui.dp(20));
        LinearLayout.LayoutParams mainCardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mainCardParams.setMargins(Ui.dp(8), Ui.dp(-20), Ui.dp(8), 0);
        mainPanel.addView(mainCard, mainCardParams);

        mainCard.addView(text("设备码授权", 18, Ui.TEXT, true));
        mainCard.addView(mutedText("扫描网页登录页的二维码,或直接输入它显示的用户码。", 13));

        Button scanBtn = primaryBtn("扫一扫 · 网页二维码");
        scanBtn.setOnClickListener(v -> startScan());
        mainCard.addView(scanBtn, match(16));

        Button manualBtn = outlineBtn("手动输入用户码", Ui.TEXT);
        manualBtn.setOnClickListener(v -> {
            manualCodeEt.setText("");
            manualOverlay.setVisibility(View.VISIBLE);
        });
        mainCard.addView(manualBtn, match(10));

        Button logoutBtn = outlineBtn("退出登录", Ui.MUTED);
        logoutBtn.setOnClickListener(v -> doLogout());
        mainCard.addView(logoutBtn, match(10));
        root.addView(mainPanel);

        /* ---- 扫码面板 ---- */
        scanPanel = new FrameLayout(this);
        scanPanel.setBackgroundColor(0xFF10182E);
        scanSurface = new SurfaceView(this);
        scanPanel.addView(scanSurface, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        TextView scanHint = text("将网页二维码对准取景框", 13, Ui.WHITE, false);
        scanHint.setBackgroundColor(0xAA10182E);
        scanHint.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(44), Gravity.BOTTOM);
        scanPanel.addView(scanHint, hintParams);
        TextView closeScan = text("✕ 关闭", 13, Ui.WHITE, false);
        closeScan.setBackground(Ui.rounded(18, 0x66000000, 0, 0));
        closeScan.setPadding(Ui.dp(16), Ui.dp(6), Ui.dp(16), Ui.dp(6));
        closeScan.setGravity(Gravity.CENTER);
        closeScan.setOnClickListener(v -> stopScan());
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        closeParams.setMargins(Ui.dp(10), Ui.dp(10), Ui.dp(10), 0);
        scanPanel.addView(closeScan, closeParams);
        root.addView(scanPanel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scanPanel.setVisibility(View.GONE);

        /* ---- 服务器地址编辑覆盖层(Dialog 窗口内 EditText 不渲染,改用同窗口覆盖层) ---- */
        serverOverlay = buildServerOverlay();
        root.addView(serverOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        serverOverlay.setVisibility(View.GONE);

        manualOverlay = buildManualOverlay();
        root.addView(manualOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        manualOverlay.setVisibility(View.GONE);
    }

    /** 暗色遮罩 + 居中白卡 + 渐变标题头的通用弹层骨架。 */
    private LinearLayout overlayModal(TextView head) {
        LinearLayout modal = new LinearLayout(this);
        modal.setOrientation(LinearLayout.VERTICAL);
        modal.setBackground(Ui.rounded(16, Ui.WHITE, 0, 0));
        modal.setElevation(Ui.dp(6));
        head.setBackground(Ui.dialogHeader(16));
        head.setPadding(Ui.dp(18), Ui.dp(13), Ui.dp(18), Ui.dp(13));
        modal.addView(head, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(16));
        modal.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        cardParams.setMargins(Ui.dp(24), 0, Ui.dp(24), 0);
        modal.setLayoutParams(cardParams);
        return modal;
    }

    private LinearLayout.LayoutParams fieldParams() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(48));
    }

    private FrameLayout buildServerOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        LinearLayout modal = overlayModal(text("服务器地址", 15, Ui.WHITE, true));
        LinearLayout body = (LinearLayout) modal.getChildAt(1);

        serverInput = input("http://192.168.9.143:8090", false);
        serverInput.setLayoutParams(fieldParams());
        body.addView(serverInput);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = outlineBtn("取消", Ui.MUTED);
        cancel.setOnClickListener(v -> serverOverlay.setVisibility(View.GONE));
        Button save = primaryBtn("保 存");
        save.setOnClickListener(ignore -> {
            String value = serverInput.getText().toString().trim();
            if (value.isEmpty()) {
                Toast.makeText(this, "地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            base = value;
            prefs().edit().putString("base", base).apply();
            Toast.makeText(this, "服务器地址已更新", Toast.LENGTH_SHORT).show();
            serverOverlay.setVisibility(View.GONE);
            if (loginPanel.getVisibility() == View.VISIBLE) {
                loadCaptcha();
            }
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(0, Ui.dp(48), 1f);
        saveParams.setMargins(Ui.dp(10), 0, 0, 0);
        row.addView(save, saveParams);
        body.addView(row, match(16));
        overlay.addView(modal);
        return overlay;
    }

    private FrameLayout buildManualOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0x99000000);
        LinearLayout modal = overlayModal(text("输入用户码", 15, Ui.WHITE, true));
        LinearLayout body = (LinearLayout) modal.getChildAt(1);

        manualCodeEt = input("XXXX-XXXX", false);
        manualCodeEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        manualCodeEt.setLayoutParams(fieldParams());
        body.addView(manualCodeEt);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button cancel = outlineBtn("取消", Ui.MUTED);
        cancel.setOnClickListener(v -> manualOverlay.setVisibility(View.GONE));
        Button auth = primaryBtn("确认授权");
        auth.setOnClickListener(v -> {
            String code = extractUserCode(manualCodeEt.getText().toString());
            if (code == null) {
                Toast.makeText(this, "用户码格式不正确", Toast.LENGTH_SHORT).show();
                return;
            }
            manualOverlay.setVisibility(View.GONE);
            confirmAuthorize(code);
        });
        row.addView(cancel, new LinearLayout.LayoutParams(0, Ui.dp(48), 1f));
        LinearLayout.LayoutParams authParams = new LinearLayout.LayoutParams(0, Ui.dp(48), 1f);
        authParams.setMargins(Ui.dp(10), 0, 0, 0);
        row.addView(auth, authParams);
        body.addView(row, match(16));
        overlay.addView(modal);
        return overlay;
    }

    private int dp1() {
        return Math.max(1, Ui.dp(1));
    }

    private void show(View panel) {
        loginPanel.setVisibility(panel == loginPanel ? View.VISIBLE : View.GONE);
        mainPanel.setVisibility(panel == mainPanel ? View.VISIBLE : View.GONE);
        scanPanel.setVisibility(View.GONE);
    }

    /* ---------------- 后台任务助手 ---------------- */

    private void bg(Runnable r) {
        exec.execute(() -> {
            try {
                r.run();
            } catch (Exception e) {
                setStatus("请求失败: " + e.getMessage());
            }
        });
    }

    private void setStatus(String s) {
        runOnUiThread(() -> {
            statusTv.setText(s);
            statusTv.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        });
    }

    /* ---------------- 验证码 / 登录 ---------------- */

    private void loadCaptcha() {
        bg(() -> {
            try {
                HttpApi.Result r = api().get("/api/auth/captcha", null);
                JSONObject body = new JSONObject(r.body);
                JSONObject data = body.getJSONObject("data");
                captchaId = data.getString("captchaId");
                String image = data.getString("image");
                String b64 = image.substring(image.indexOf(";base64,") + 8);
                byte[] png = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                runOnUiThread(() -> captchaIv.setImageBitmap(bmp));
            } catch (Exception e) {
                setStatus("加载验证码失败: " + e.getMessage());
            }
        });
    }

    private void doPasswordLogin() {
        final String capCode = captchaEt.getText().toString().trim();
        final String user = userEt.getText().toString().trim();
        final String pass = passEt.getText().toString();
        if (capCode.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            setStatus("请填写完整登录信息");
            return;
        }
        setStatus("登录中...");
        bg(() -> {
            try {
                HttpApi.Result r = api().post("/api/auth/login", "{\"username\":\"" + user
                        + "\",\"password\":\"" + pass + "\",\"captchaId\":\"" + captchaId
                        + "\",\"captchaCode\":\"" + capCode + "\"}", null);
                JSONObject body = new JSONObject(r.body);
                if (r.ok() && body.getInt("code") == 200) {
                    onLogin(body.getJSONObject("data").getString("tokenValue"));
                } else {
                    setStatus("登录失败: " + body.optString("msg", "HTTP " + r.status));
                    runOnUiThread(this::loadCaptcha);
                }
            } catch (Exception e) {
                setStatus("登录失败: " + e.getMessage());
            }
        });
    }

    private void doOneClick() {
        setStatus("正在识别本机号码...");
        bg(() -> {
            try {
                HttpApi.Result p = api().get("/api/auth/oneclick/preview", null);
                String token1 = new JSONObject(p.body).getJSONObject("data").getString("token");
                HttpApi.Result r = api().post("/api/auth/oneclick/login",
                        "{\"token\":\"" + token1 + "\"}", null);
                JSONObject body = new JSONObject(r.body);
                if (r.ok() && body.getInt("code") == 200) {
                    onLogin(body.getJSONObject("data").getString("tokenValue"));
                } else {
                    setStatus("一键登录失败: " + body.optString("msg", "HTTP " + r.status));
                }
            } catch (Exception e) {
                setStatus("一键登录失败: " + e.getMessage());
            }
        });
    }

    /** 第三方授权登录(demo 模拟授权):昵称确认 → 授权票 → 换登录态。 */
    private void oauthLogin(String provider, String label, int headerColor) {
        final EditText nick = input("昵称(同昵称登录同一账号)", false);
        Dialog d = brandedDialog(label + "安全授权", Ui.dialogHeaderSolid(headerColor, 16),
                nick, "确认授权", v -> {
            String nickname = nick.getText().toString().trim();
            bg(() -> {
                try {
                    String authJson = new org.json.JSONObject().put("nickname", nickname).toString();
                    HttpApi.Result a = api().post("/api/auth/oauth/" + provider + "/authorize",
                            authJson, null);
                    String ticket = new JSONObject(a.body).getJSONObject("data").getString("ticket");
                    HttpApi.Result r = api().post("/api/auth/oauth/" + provider + "/callback",
                            "{\"ticket\":\"" + ticket + "\"}", null);
                    JSONObject body = new JSONObject(r.body);
                    if (r.ok() && body.getInt("code") == 200) {
                        String t = body.getJSONObject("data").getString("tokenValue");
                        runOnUiThread(() -> {
                            Toast.makeText(this, label + "授权成功,已登录", Toast.LENGTH_SHORT).show();
                            onLogin(t);
                        });
                    } else {
                        String msg = body.optString("msg", "HTTP " + r.status);
                        runOnUiThread(() -> Toast.makeText(this,
                                label + "登录失败: " + msg, Toast.LENGTH_LONG).show());
                    }
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            label + "登录失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            });
        });
        d.show();
    }

    private void onLogin(String newToken) {
        token = newToken;
        prefs().edit().putString("token", token).apply();
        runOnUiThread(() -> {
            show(mainPanel);
            refreshUser();
        });
    }

    private void refreshUser() {
        bg(() -> {
            try {
                HttpApi.Result r = api().get("/api/user/me", token);
                if (r.ok()) {
                    String name = new JSONObject(r.body).getJSONObject("data").getString("username");
                    runOnUiThread(() -> userTv.setText("当前用户: " + name));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void validateSession() {
        show(mainPanel);
        bg(() -> {
            try {
                HttpApi.Result r = api().get("/api/user/me", token);
                if (r.ok()) {
                    String name = new JSONObject(r.body).getJSONObject("data").getString("username");
                    runOnUiThread(() -> {
                        show(mainPanel);
                        userTv.setText("当前用户: " + name);
                    });
                } else {
                    token = null;
                    prefs().edit().remove("token").apply();
                    runOnUiThread(() -> {
                        show(loginPanel);
                        loadCaptcha();
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    show(loginPanel);
                    setStatus("无法连接服务器,请检查地址");
                });
            }
        });
    }

    private void doLogout() {
        token = null;
        prefs().edit().remove("token").apply();
        show(loginPanel);
        loadCaptcha();
    }

    /* ---------------- 扫码 / 授权 ---------------- */

    private void startScan() {
        if (checkSelfPermission(android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 100);
            return;
        }
        openScanner();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100 && grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            openScanner();
        } else {
            Toast.makeText(this, "未授予相机权限,可使用手动输入用户码", Toast.LENGTH_LONG).show();
        }
    }

    private void openScanner() {
        show(scanPanel);
        scanner = new CameraScanner(this, scanSurface, this);
        scanner.start();
    }

    private void stopScan() {
        if (scanner != null) {
            scanner.stop();
            scanner = null;
        }
        show(mainPanel);
    }

    @Override
    public void onDetected(String text) {
        runOnUiThread(() -> {
            String code = extractUserCode(text);
            if (code == null) {
                Toast.makeText(this, "二维码内容不是授权码", Toast.LENGTH_SHORT).show();
                if (scanner != null) {
                    scanner.start();
                }
                return;
            }
            stopScan();
            confirmAuthorize(code);
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void confirmAuthorize(String userCode) {
        TextView msg = text("允许用户码 " + userCode + " 以当前账号登录?", 14, Ui.TEXT, false);
        brandedDialog("确认授权", Ui.dialogHeader(16), msg, "确认授权", v -> doAuthorize(userCode)).show();
    }

    private void doAuthorize(String userCode) {
        bg(() -> {
            try {
                HttpApi.Result r = api().post("/api/auth/device/authorize",
                        "{\"userCode\":\"" + userCode + "\"}", token);
                JSONObject body = new JSONObject(r.body);
                if (r.ok() && body.getInt("code") == 200) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "授权成功,网页端将自动登录", Toast.LENGTH_LONG).show());
                } else if (r.status == 401) {
                    token = null;
                    prefs().edit().remove("token").apply();
                    runOnUiThread(() -> {
                        show(loginPanel);
                        loadCaptcha();
                        setStatus("会话已过期,请重新登录");
                    });
                } else {
                    String msg = body.optString("msg", "HTTP " + r.status);
                    runOnUiThread(() -> Toast.makeText(this, "授权失败: " + msg, Toast.LENGTH_LONG).show());
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "授权失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    /** 从二维码文本(可能是完整 URL)中提取 8 位用户码。 */
    static String extractUserCode(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toUpperCase();
        int idx = t.indexOf("USER_CODE=");
        if (idx >= 0) {
            t = t.substring(idx + "USER_CODE=".length());
        }
        Matcher m = USER_CODE.matcher(t);
        if (!m.find()) {
            return null;
        }
        return m.group(1) + "-" + m.group(2);
    }

    private LinearLayout providerColumn(int iconRes, String label, int color, Runnable onClick) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setOnClickListener(v -> onClick.run());
        ImageView circle = new ImageView(this);
        circle.setImageResource(iconRes);
        circle.setBackground(Ui.gradient(22, color, color));
        circle.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10));
        col.addView(circle, new LinearLayout.LayoutParams(Ui.dp(44), Ui.dp(44)));
        TextView lbl = text(label, 12, Ui.MUTED, false);
        lbl.setGravity(Gravity.CENTER);
        col.addView(lbl, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams colParams = new LinearLayout.LayoutParams(
                Ui.dp(76), ViewGroup.LayoutParams.WRAP_CONTENT);
        col.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4));
        col.setLayoutParams(colParams);
        return col;
    }

    /* ---------------- 品牌化弹窗(替代系统 AlertDialog) ---------------- */

    /** 厂商色/渐变标题头 + 白卡圆角 + 渐变确认按钮;点确认后自动关闭。 */
    private Dialog brandedDialog(String title, Drawable headerBg, View body, String positiveLabel,
            View.OnClickListener onPositive) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Ui.rounded(16, Ui.WHITE, 0, 0));

        LinearLayout head = new LinearLayout(this);
        head.setBackground(headerBg);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(Ui.dp(18), Ui.dp(13), Ui.dp(18), Ui.dp(13));
        head.addView(text(title, 15, Ui.WHITE, true));
        root.addView(head, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout bodyWrap = new LinearLayout(this);
        bodyWrap.setOrientation(LinearLayout.VERTICAL);
        bodyWrap.setPadding(Ui.dp(18), Ui.dp(18), Ui.dp(18), Ui.dp(4));
        ViewGroup.LayoutParams bp = body.getLayoutParams();
        if (bp == null) {
            bp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        bp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        body.setLayoutParams(bp);
        bodyWrap.addView(body);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(12), Ui.dp(2), Ui.dp(12), Ui.dp(12));
        TextView cancel = text("取消", 14, Ui.MUTED, true);
        cancel.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8));
        cancel.setOnClickListener(v -> d.dismiss());
        row.addView(cancel);
        Button ok = primaryBtn(positiveLabel);
        ok.setTextSize(14);
        LinearLayout.LayoutParams okParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        okParams.setMargins(Ui.dp(10), 0, 0, 0);
        ok.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), Ui.dp(8));
        ok.setOnClickListener(v -> {
            d.dismiss();
            onPositive.onClick(v);
        });
        row.addView(ok, okParams);
        root.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        d.setContentView(root);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(0x00000000));
            d.getWindow().setLayout(Ui.dp(340), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    /* ---------------- 隐藏入口:连点 logo 10 次改服务器地址 ---------------- */

    private int logoTaps;
    private long lastLogoTap;

    private void onLogoTap() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastLogoTap > 1500) {
            logoTaps = 0; // 间隔过长视为重新开始
        }
        lastLogoTap = now;
        logoTaps++;
        if (logoTaps >= 10) {
            logoTaps = 0;
            serverInput.setText(base);
            serverOverlay.setVisibility(View.VISIBLE);
        } else if (logoTaps >= 6) {
            Toast.makeText(this, "再点 " + (10 - logoTaps) + " 次进入服务器设置", Toast.LENGTH_SHORT).show();
        }
    }

    /* ---------------- 杂项 ---------------- */

    private android.content.SharedPreferences prefs() {
        return getSharedPreferences("authapp", MODE_PRIVATE);
    }

    private HttpApi api() {
        return new HttpApi(base);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (scanner != null) {
            scanner.stop();
        }
        exec.shutdown();
    }
}
