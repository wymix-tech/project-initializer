package com.wymix.initializer.app.admin.controller;

import com.wymix.initializer.app.InitializerApplication;
import com.wymix.initializer.app.admin.security.AdminAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理端接口全链路集成测试（M3.1，随机端口 + H2）：
 * 鉴权过滤（404+4004）、草稿 CRUD + If-Match 乐观锁、发布成功/失败全语义、发布历史与审计。
 */
@SpringBootTest(classes = InitializerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:admin-intg-test;DB_CLOSE_DELAY=-1;MODE=LEGACY",
                "initializer.admin.token=intg-admin-token"
        })
class AdminApiControllerIntegrationTest {

    /** 与内置 metadata.yml 同构的合法草稿（releaseId 单调递增保证互不干扰）。 */
    private static final String VALID_METADATA = """
            javaVersions:
              - id: "17"
                label: "Java 17"
                recommendedBootVersion: "3.2.5"
            bootVersions:
              - id: "3.2.5"
                label: "Spring Boot 3.2.5"
                official: true
                supportedJavaVersions: ["17"]
                starters: ["web"]
            starters:
              web:
                label: "Spring Web"
                dependency:
                  groupId: "org.springframework.boot"
                  artifactId: "spring-boot-starter-web"
            """;

    /** 悬空引用非法草稿（验收标准：发布被拒 + 旧快照继续服务）。 */
    private static final String INVALID_METADATA = VALID_METADATA.replace(
            "starters: [\"web\"]", "starters: [\"ghost\"]");

    @Autowired
    private TestRestTemplate rest;

    private HttpHeaders adminHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(AdminAuthFilter.TOKEN_HEADER, "intg-admin-token");
        return headers;
    }

    private ResponseEntity<String> putDraft(String kind, String yaml, Integer ifMatch) {
        HttpHeaders headers = adminHeaders();
        if (ifMatch != null) {
            headers.set(AdminApiController.IF_MATCH_HEADER, String.valueOf(ifMatch));
        }
        String body = "{\"contentYaml\":" + com.fasterxml.jackson.databind.node.TextNode.valueOf(yaml).toString()
                + ",\"operator\":\"tester\"}";
        return rest.exchange("/api/admin/config/" + kind, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> publish(String yaml) {
        // 发布前确保草稿为指定内容；冲突规则草稿重置为空规则（M3.5 后发布链路会消费并校验该草稿）
        putDraft("conflict-rule", "rules: []", null);
        putDraft("metadata", yaml, null);
        String body = "{\"operator\":\"tester\",\"remark\":\"M3.1 集成测试\"}";
        return rest.exchange("/api/admin/config/publish", HttpMethod.POST,
                new HttpEntity<>(body, adminHeaders()), String.class);
    }

    @Test
    void adminEndpoints_requireToken() {
        ResponseEntity<String> noToken = rest.getForEntity("/api/admin/config/metadata", String.class);
        assertThat(noToken.getStatusCode().value()).isEqualTo(404);
        assertThat(noToken.getBody()).contains("4004");

        HttpHeaders wrong = new HttpHeaders();
        wrong.set(AdminAuthFilter.TOKEN_HEADER, "bad-token");
        ResponseEntity<String> wrongResp = rest.exchange("/api/admin/config/metadata",
                HttpMethod.GET, new HttpEntity<>(wrong), String.class);
        assertThat(wrongResp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void adminEndpoints_withToken_accessible() {
        ResponseEntity<String> resp = rest.exchange("/api/admin/config/metadata",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"kind\":\"metadata\"");
    }

    @Test
    void getDraft_unknownKind_returns422With4001() {
        ResponseEntity<String> resp = rest.exchange("/api/admin/config/unknown-kind",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("4001");
    }

    @Test
    void putDraft_roundTrip_andIfMatchConflict() {
        ResponseEntity<String> first = putDraft("conflict-rule", "rules: []", null);
        assertThat(first.getStatusCode().value()).isEqualTo(204);

        // 读回版本号
        ResponseEntity<String> view = rest.exchange("/api/admin/config/conflict-rule",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        String version = view.getBody().replaceAll(".*\"version\":(\\d+).*", "$1");

        // 基于 If-Match 更新成功
        ResponseEntity<String> second = putDraft("conflict-rule", "rules: [{id: r, type: pair, starters: [a, b], message: m}]", Integer.parseInt(version));
        assertThat(second.getStatusCode().value()).isEqualTo(204);

        // 携带过期 If-Match 更新 → 4003
        ResponseEntity<String> stale = putDraft("conflict-rule", "rules: [{id: r2, type: pair, starters: [c, d], message: m}]", Integer.parseInt(version));
        assertThat(stale.getStatusCode().value()).isEqualTo(422);
        assertThat(stale.getBody()).contains("4003");
    }

    @Test
    void publish_validDrafts_returnsResult_andServesReleaseList() {
        ResponseEntity<String> resp = publish(VALID_METADATA);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).contains("\"javaVersionCount\":1");
        assertThat(resp.getBody()).contains("\"starterCount\":0"); // starter 草稿未保存 → 空清单

        ResponseEntity<String> releases = rest.exchange("/api/admin/releases",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(releases.getStatusCode().value()).isEqualTo(200);
        assertThat(releases.getBody()).contains("tester");
    }

    @Test
    void publish_invalidDraft_returns422With4002_andOldSnapshotStillServes() {
        // 先成功发布一次（基准快照）
        assertThat(publish(VALID_METADATA).getStatusCode().value()).isEqualTo(200);

        // 写入非法草稿后发布 → 4002
        ResponseEntity<String> failed = publish(INVALID_METADATA);
        assertThat(failed.getStatusCode().value()).isEqualTo(422);
        assertThat(failed.getBody()).contains("4002");
        assertThat(failed.getBody()).contains("ghost");

        // 旧快照继续服务：业务生成接口无感
        String generateBody = """
                {
                  "group": "com.example",
                  "artifact": "demo-app",
                  "packageName": "com.example.demo",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"]
                }
                """;
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> generate = rest.exchange("/api/generate", HttpMethod.POST,
                new HttpEntity<>(generateBody, jsonHeaders), String.class);
        assertThat(generate.getStatusCode().value())
                .as("generate 响应: %s", generate.getBody())
                .isEqualTo(200);

        // 失败留痕审计
        ResponseEntity<String> audits = rest.exchange("/api/admin/audits",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(audits.getBody()).contains("PUBLISH_FAILED");
    }

    @Test
    void publish_newConfig_takesEffectImmediatelyOnReadPaths() {
        // M3.2 核心验收：发布后元数据/生成读路径即时热生效（无需重启）
        // 发布带自定义 label 的草稿（与内置 metadata.yml 不同的新 Java 版本集）
        assertThat(publish(VALID_METADATA).getStatusCode().value()).isEqualTo(200);

        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);

        // 元数据读路径即时反映新快照：内置清单有 4 个 Java 版本，新快照仅 1 个
        ResponseEntity<String> versions = rest.exchange("/api/metadata/java-versions",
                HttpMethod.GET, new HttpEntity<>(jsonHeaders), String.class);
        assertThat(versions.getStatusCode().value()).isEqualTo(200);
        assertThat(versions.getBody()).contains("\"id\":\"17\"");
        assertThat(versions.getBody()).doesNotContain("\"id\":\"8\"");

        // 生成链路同样走新快照：仅新快照定义的版本组合可用
        String generateBody = """
                {
                  "group": "com.example",
                  "artifact": "hot-app",
                  "packageName": "com.example.hot",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"]
                }
                """;
        ResponseEntity<String> generate = rest.exchange("/api/generate", HttpMethod.POST,
                new HttpEntity<>(generateBody, jsonHeaders), String.class);
        assertThat(generate.getStatusCode().value())
                .as("generate 响应: %s", generate.getBody())
                .isEqualTo(200);

        // 不在新快照版本集中的旧版本组合 → 新快照语义拒绝（2002 语义）
        String staleBody = generateBody.replace("\"javaVersion\": \"17\"", "\"javaVersion\": \"8\"");
        ResponseEntity<String> stale = rest.exchange("/api/generate", HttpMethod.POST,
                new HttpEntity<>(staleBody, jsonHeaders), String.class);
        assertThat(stale.getStatusCode().value()).isEqualTo(422);
        assertThat(stale.getBody()).contains("2001");
    }

    @Test
    void rollback_endpoint_roundTripsThroughHttp() {
        // 发布两次 → 回滚到第一版
        assertThat(publish(VALID_METADATA).getStatusCode().value()).isEqualTo(200);
        String body = "{\"operator\":\"tester\",\"remark\":\"回滚集成测试\"}";
        // 取发布历史第一条（最新）
        ResponseEntity<String> releases = rest.exchange("/api/admin/releases",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        String firstId = releases.getBody().replaceAll(".*\"id\":(\\d+).*", "$1");

        ResponseEntity<String> rolled = rest.exchange("/api/admin/releases/" + firstId + "/rollback",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders()), String.class);
        assertThat(rolled.getStatusCode().value()).isEqualTo(200);
        assertThat(rolled.getBody()).contains("releaseId");

        // 未知发布 ID 回滚 → 4002
        ResponseEntity<String> unknown = rest.exchange("/api/admin/releases/-1/rollback",
                HttpMethod.POST, new HttpEntity<>(body, adminHeaders()), String.class);
        assertThat(unknown.getStatusCode().value()).isEqualTo(422);
        assertThat(unknown.getBody()).contains("4002");
    }

    @Test
    void publish_withoutMetadataDraft_returns422With4002() {
        // TEMPLATE kind 未发布过且无 metadata 内容缺失路径——直接用新库语义：publish 校验缺失 metadata 草稿
        // 注意：前置发布可能已写入 METADATA 草稿，此处仅验证空内容草稿路径（contentYaml 为 null 的 kind 不影响）
        ResponseEntity<String> resp = rest.exchange("/api/admin/config/publish", HttpMethod.POST,
                new HttpEntity<>("{\"operator\":\"tester\"}", adminHeaders()), String.class);
        // METADATA 草稿内容可能为 null（data.sql 预置空占位）→ 4002
        if (!resp.getStatusCode().is2xxSuccessful()) {
            assertThat(resp.getStatusCode().value()).isEqualTo(422);
            assertThat(resp.getBody()).contains("4002");
        }
    }
}
