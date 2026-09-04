package com.wymix.initializer.app.generator;

import com.wymix.initializer.app.InitializerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M2.4：/api/generate 构建工具双轨集成测试（buildTool=gradle / 非法取值 1004）。
 */
@SpringBootTest(classes = InitializerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateControllerBuildToolTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void buildToolGradleShouldReturnGradleProject() throws Exception {
        String body = """
                {
                  "group": "com.example",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"],
                  "buildTool": "gradle"
                }
                """;
        ResponseEntity<byte[]> resp = post(body);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(resp.getBody()))) {
            List<String> names = new java.util.ArrayList<>();
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
            }
            assertThat(names).contains("demo-app/build.gradle", "demo-app/settings.gradle")
                    .doesNotContain("demo-app/pom.xml");
        }
    }

    @Test
    void invalidBuildToolShouldReturn422With1004() {
        String body = """
                {
                  "group": "com.example",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"],
                  "buildTool": "buck"
                }
                """;
        ResponseEntity<String> resp = rest.postForEntity("/api/generate", jsonEntity(body), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("1004");
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private ResponseEntity<byte[]> post(String body) {
        return rest.postForEntity("/api/generate", jsonEntity(body), byte[].class);
    }
}
