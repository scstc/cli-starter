package com.starter.authapp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 极简 HTTP 客户端(平台自带 HttpURLConnection,零三方依赖)。 */
public class HttpApi {

    private final String base;

    public HttpApi(String base) {
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public static class Result {
        public final int status;
        public final String body;

        public Result(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    public Result get(String path, String token) throws IOException {
        return request("GET", path, null, token);
    }

    public Result post(String path, String json, String token) throws IOException {
        return request("POST", path, json, token);
    }

    private Result request(String method, String path, String json, String token) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod(method);
        if (json != null) {
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        if (token != null) {
            conn.setRequestProperty("satoken", token);
        }
        if (json != null) {
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(json.getBytes(StandardCharsets.UTF_8));
            }
        }
        int status = conn.getResponseCode();
        InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        if (is != null) {
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) > 0) {
                buf.write(chunk, 0, n);
            }
            is.close();
        }
        return new Result(status, buf.toString("UTF-8"));
    }
}
