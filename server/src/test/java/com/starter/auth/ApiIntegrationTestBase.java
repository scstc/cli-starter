package com.starter.auth;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 集成测试公共底座：RestClient 与请求助手。
 * 每个测试类自带 @TempDir + @DynamicPropertySource（SQLite 库按类隔离），
 * 并用 @SpringBootTest(webEnvironment = RANDOM_PORT[, properties = ...]) 声明场景。
 */
abstract class ApiIntegrationTestBase {

    @Value("${local.server.port}")
    int port;

    RestClient rest;

    @BeforeEach
    void setUpRest() {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /** datasource 属性模板：各测试类以自己的 @TempDir 调用，保证库文件隔离。 */
    static void datasourceProperties(DynamicPropertyRegistry registry, java.nio.file.Path dbFile) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:"
                + dbFile.toAbsolutePath().toString().replace('\\', '/'));
        registry.add("spring.datasource.type",
                () -> "org.springframework.jdbc.datasource.SimpleDriverDataSource");
    }

    record ExchangeResult(int status, String body) {
    }

    ExchangeResult get(String uri, String token) {
        try {
            RestClient.RequestHeadersSpec<?> spec = rest.get().uri(uri);
            if (token != null) {
                spec = spec.header("satoken", token);
            }
            var resp = spec.retrieve().toEntity(String.class);
            return new ExchangeResult(resp.getStatusCode().value(), resp.getBody());
        } catch (RestClientResponseException e) {
            return new ExchangeResult(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    ExchangeResult post(String uri, Object body, String token) {
        try {
            RestClient.RequestBodySpec spec = rest.post().uri(uri);
            if (token != null) {
                spec = spec.header("satoken", token);
            }
            ResponseEntity<String> resp;
            if (body != null) {
                resp = spec.contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().toEntity(String.class);
            } else {
                resp = spec.retrieve().toEntity(String.class);
            }
            return new ExchangeResult(resp.getStatusCode().value(), resp.getBody());
        } catch (RestClientResponseException e) {
            return new ExchangeResult(e.getStatusCode().value(), e.getResponseBodyAsString());
        }
    }

    /** 带自定义 header 的请求，返回含响应头的结果（CORS 放行头断言用）。 */
    record HeaderedResult(int status, String body,
            org.springframework.http.HttpHeaders headers) {
    }

    HeaderedResult exchangeFull(org.springframework.http.HttpMethod method, String uri,
            java.util.Map<String, String> headers) {
        try {
            RestClient.RequestHeadersSpec<?> spec = rest.method(method).uri(uri);
            headers.forEach(spec::header);
            var resp = spec.retrieve().toEntity(String.class);
            return new HeaderedResult(resp.getStatusCode().value(), resp.getBody(),
                    resp.getHeaders());
        } catch (RestClientResponseException e) {
            return new HeaderedResult(e.getStatusCode().value(), e.getResponseBodyAsString(),
                    e.getResponseHeaders());
        }
    }
}
