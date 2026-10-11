package com.mogum;

import com.mogum.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FrontendBundleIT extends PostgresTestConfiguration {
    @Value("${local.server.port}")
    private int port;

    @Test
    void 루트_GET이_빌드된_프론트_index_html을_반환한다() throws Exception {
        try (var resource = getClass().getResourceAsStream("/static/index.html")) {
            assertNotNull(resource, "프론트 빌드 결과가 static/index.html에 있어야 한다");
            String index = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/"))
                        .GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

                assertEquals(200, response.statusCode());
                assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
                assertEquals(index, response.body());
                assertTrue(index.contains("<title>머금</title>"));
                assertTrue(index.contains("/assets/"), "Vite가 번들링한 자산을 참조해야 한다");
            }
        }
    }
}
