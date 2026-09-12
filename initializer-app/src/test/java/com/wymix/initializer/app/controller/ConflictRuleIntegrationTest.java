package com.wymix.initializer.app.controller;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.generator.TestTemplates;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshot;
import com.wymix.initializer.app.admin.refresh.CatalogSnapshotHolder;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRule;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M3.5 全链路集成测试（随机端口真实上下文）：快照携带冲突规则 →
 * {@code /api/metadata/conflict-rules} 返回规则集；{@code POST /api/generate}
 * 命中规则 422 + 4007，未命中正常放行。
 */
@SpringBootTest(classes = InitializerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:conflict-it-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
                "initializer.admin.token=test-admin-token"
        })
class ConflictRuleIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CatalogSnapshotHolder snapshotHolder;

    @Autowired
    private MetadataCatalog fallbackCatalog;

    @Autowired
    private StarterCatalog fallbackStarterCatalog;

    /** 以带冲突规则的快照播种（替代发布链路，聚焦读路径与生成兜底语义）。 */
    private void seedWithRules(ConflictCatalog rules) {
        // T5：模板集只来自快照（DB 全量模板集），测试显式注入 classpath 种子模板集
        snapshotHolder.seed(new CatalogSnapshot(fallbackCatalog, fallbackStarterCatalog,
                TestTemplates.load(), rules));
    }

    @org.junit.jupiter.api.AfterEach
    void resetSnapshot() {
        seedWithRules(ConflictCatalog.empty());
    }

    @Test
    void conflictRules_endpointReturnsSeededRules() {
        seedWithRules(new ConflictCatalog(List.of(
                new ConflictRule("it-pair", ConflictRule.Type.PAIR,
                        List.of("web", "data-redis"), "Web 与 Redis 缓存互斥"))));
        ResponseEntity<String> resp = rest.getForEntity("/api/metadata/conflict-rules", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"id\":\"it-pair\"");
        assertThat(resp.getBody()).contains("\"type\":\"pair\"");
        assertThat(resp.getBody()).contains("Web 与 Redis 缓存互斥");
    }

    @Test
    void conflictRules_noRulesSeeded_returnsEmptyList() {
        seedWithRules(ConflictCatalog.empty());
        ResponseEntity<String> resp = rest.getForEntity("/api/metadata/conflict-rules", String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("[]");
    }

    @Test
    void generate_conflictingSelection_rejected422With4007() {
        seedWithRules(new ConflictCatalog(List.of(
                new ConflictRule("it-pair", ConflictRule.Type.PAIR,
                        List.of("web", "data-redis"), "Web 与 Redis 缓存互斥"))));
        String body = """
                {
                  "group": "com.demo",
                  "artifact": "demo",
                  "packageName": "com.demo.app",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web", "data-redis"]
                }
                """;
        ResponseEntity<String> resp = postGenerate(body);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("4007");
        assertThat(resp.getBody()).contains("it-pair");
    }

    @Test
    void generate_nonConflictingSelection_passes() {
        seedWithRules(new ConflictCatalog(List.of(
                new ConflictRule("it-pair", ConflictRule.Type.PAIR,
                        List.of("data-jpa", "data-redis"), "JPA 与 Redis 互斥"))));
        String body = """
                {
                  "group": "com.demo",
                  "artifact": "demo",
                  "packageName": "com.demo.app",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web", "data-redis"]
                }
                """;
        ResponseEntity<String> resp = postGenerate(body);
        assertThat(resp.getStatusCode().value())
                .as("generate 响应: %s", resp.getBody())
                .isEqualTo(200);
    }

    private ResponseEntity<String> postGenerate(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/generate", HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }
}
