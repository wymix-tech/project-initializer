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
 * /api/generate 接口集成测试（随机端口起真实上下文）。
 */
@SpringBootTest(classes = InitializerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GenerateControllerTest {

    @Autowired
    private TestRestTemplate rest;

    private static final String VALID_BODY = """
            {
              "group": "com.example",
              "artifact": "demo-app",
              "packageName": "com.example.demo",
              "javaVersion": "17",
              "bootVersion": "3.2.5",
              "starters": ["web"]
            }
            """;

    @Test
    void generateShouldReturnZipAttachment() throws Exception {
        ResponseEntity<byte[]> resp = post(VALID_BODY);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isNotNull();
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("demo-app.zip");
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(resp.getBody()))) {
            List<String> names = new java.util.ArrayList<>();
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName());
            }
            assertThat(names).contains("demo-app/pom.xml", "demo-app/README.md");
        }
    }

    @Test
    void invalidMetadataShouldReturn422WithDetail() {
        String body = """
                {
                  "group": "Invalid",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"]
                }
                """;
        ResponseEntity<String> resp = rest.postForEntity("/api/generate", jsonEntity(body), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("1001").contains("groupId");
    }

    @Test
    void incompatibleVersionShouldReturn422With2001() {
        String body = """
                {
                  "group": "com.example",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "8",
                  "bootVersion": "3.2.5",
                  "starters": ["web"]
                }
                """;
        ResponseEntity<String> resp = rest.postForEntity("/api/generate", jsonEntity(body), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("2001");
    }

    @Test
    void unknownBootVersionShouldReturn422With2002() {
        String body = """
                {
                  "group": "com.example",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "9.9.9",
                  "starters": ["web"]
                }
                """;
        ResponseEntity<String> resp = rest.postForEntity("/api/generate", jsonEntity(body), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("2002");
    }

    @Test
    void missingFieldShouldReturn422() {
        String body = """
                {
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5"
                }
                """;
        ResponseEntity<String> resp = rest.postForEntity("/api/generate", jsonEntity(body), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("1001");
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
