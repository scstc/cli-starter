package com.starter.auth.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

/**
 * 图形验证码（Java2D 生成，无第三方依赖）：内存态、一次性消费（无论对错，验证即销毁）、
 * TTL 内有效。debug-echo 开启时把答案随响应回显，仅供本地演示/联调。
 */
@Service
public class CaptchaService {

    /** 去易混字符（0/O/1/I）的大写字母数字。 */
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 4;
    private static final int WIDTH = 124;
    private static final int HEIGHT = 44;
    private static final Color[] PALETTE = {
            new Color(0x1F, 0x6F, 0xEB), new Color(0xCF, 0x22, 0x2E),
            new Color(0x1A, 0x7F, 0x37), new Color(0x82, 0x5F, 0xDF) };

    private final CaptchaProperties props;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    private record Entry(String answer, Instant expiresAt) {
    }

    /** 单次生成的验证码：id + base64 data URI + 可选答案回显。 */
    public record GeneratedCaptcha(String captchaId, String image, String debugCode) {
    }

    public CaptchaService(CaptchaProperties props) {
        this.props = props;
    }

    public GeneratedCaptcha create() {
        String code = randomCode();
        String id = UUID.randomUUID().toString();
        store.put(id, new Entry(code, Instant.now().plus(Duration.ofSeconds(props.ttlSeconds()))));
        return new GeneratedCaptcha(id, toDataUri(render(code)),
                props.debugEcho() ? code : null);
    }

    /** 校验：大小写不敏感；一次性（成败都销毁）；过期/不存在一律 false。 */
    public synchronized boolean verify(String captchaId, String input) {
        if (captchaId == null || input == null || input.isBlank()) {
            return false;
        }
        Entry entry = store.remove(captchaId);
        if (entry == null || Instant.now().isAfter(entry.expiresAt())) {
            return false;
        }
        return entry.answer().equalsIgnoreCase(input.trim());
    }

    public boolean isRequired() {
        return props.required();
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    private BufferedImage render(String code) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(0xF6, 0xF8, 0xFA));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        for (int i = 0; i < 5; i++) {
            g.setColor(pick());
            g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
        }
        int charBox = (WIDTH - 24) / CODE_LENGTH;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        for (int i = 0; i < CODE_LENGTH; i++) {
            int x = 14 + i * charBox;
            int y = HEIGHT / 2 + 11 - random.nextInt(7);
            double theta = (random.nextDouble() - 0.5) * 0.5;
            g.setColor(pick());
            g.rotate(theta, x, y - 10);
            g.drawString(String.valueOf(code.charAt(i)), x, y);
            g.rotate(-theta, x, y - 10);
        }
        g.dispose();
        return img;
    }

    private Color pick() {
        return PALETTE[random.nextInt(PALETTE.length)];
    }

    private String toDataUri(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return "data:image/png;base64,"
                    + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new IllegalStateException("render captcha failed", e);
        }
    }
}
