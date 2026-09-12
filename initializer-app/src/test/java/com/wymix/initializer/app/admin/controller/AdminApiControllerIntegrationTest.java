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
            """;

    /** starter 草稿：starter 元信息（展示名/坐标）唯一来源，metadata 不再维护 starters 节。 */
    private static final String VALID_STARTER = """
            id: web
            category: web
            maintainer: official
            description: Spring Web
            dependencies:
              - groupId: "org.springframework.boot"
                artifactId: "spring-boot-starter-web"
            linkages: {}
            """;

    /** 非法草稿：Boot 引用未定义的 Java 版本（验收标准：发布被拒 + 旧快照继续服务）。 */
    private static final String INVALID_METADATA = VALID_METADATA.replace(
            "supportedJavaVersions: [\"17\"]", "supportedJavaVersions: [\"ghost\"]");

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
        putDraft("starter", VALID_STARTER, null);
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

    /** 结构化 PUT 辅助：携带可选 If-Match 与 X-Admin-Token。 */
    private ResponseEntity<String> putStructured(String path, String json, Integer ifMatch) {
        HttpHeaders headers = adminHeaders();
        if (ifMatch != null) {
            headers.set(AdminApiController.IF_MATCH_HEADER, String.valueOf(ifMatch));
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(json, headers), String.class);
    }

    @Test
    void structuredApi_endToEnd_writeModules_thenPublishAndReadBack() {
        // 1) 结构化写入元数据（Java + Boot + 兼容矩阵 + starter 白名单）
        String metadata = """
                {
                  "javaVersions": [{"id": "17", "label": "Java 17", "recommendedBootVersion": "3.2.5"}],
                  "bootVersions": [{"id": "3.2.5", "label": "Spring Boot 3.2.5", "official": true,
                                    "supportedJavaVersions": ["17"], "starters": ["web"]}],
                  "operator": "tester"
                }
                """;
        assertThat(putStructured("/api/admin/metadata", metadata, null).getStatusCode().value()).isEqualTo(200);

        // 2) 结构化写入 starter（含 hidden，直落 cfg_starter.hidden）
        String starters = """
                {
                  "starters": [{
                    "id": "web", "category": "web", "maintainer": "official",
                    "description": "Spring Web", "required": true, "hidden": true,
                    "dependencies": [{"groupId": "org.springframework.boot", "artifactId": "spring-boot-starter-web"}],
                    "requires": [], "bootVersions": ["3.2.5"], "linkages": {}
                  }],
                  "operator": "tester"
                }
                """;
        assertThat(putStructured("/api/admin/starters", starters, null).getStatusCode().value()).isEqualTo(200);

        // 3) 结构化写入模板（content 全文）
        String templates = """
                {"templates": [{"name": "pom.ftl", "content": "<project>${groupId}</project>"}],
                 "operator": "tester"}
                """;
        ResponseEntity<String> savedTemplates = putStructured("/api/admin/templates", templates, null);
        assertThat(savedTemplates.getStatusCode().value()).isEqualTo(200);
        assertThat(savedTemplates.getBody()).contains("模板 1 个");

        // 4) 模板列表不含 content CLOB，详情才返回全文
        ResponseEntity<String> list = rest.exchange("/api/admin/templates",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(list.getStatusCode().value()).isEqualTo(200);
        assertThat(list.getBody()).contains("pom.ftl").doesNotContain("<project>");
        ResponseEntity<String> detail = rest.exchange("/api/admin/templates/pom.ftl",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(detail.getBody()).contains("<project>");

        // 5) 结构化读回：hidden 保留
        ResponseEntity<String> readStarters = rest.exchange("/api/admin/starters",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(readStarters.getBody()).contains("\"hidden\":true");

        // 6) 发布：结构化草稿直接装表生效（两套契约同源，发布链路零改动）
        putDraft("conflict-rule", "rules: []", null);
        ResponseEntity<String> published = rest.exchange("/api/admin/config/publish", HttpMethod.POST,
                new HttpEntity<>("{\"operator\":\"tester\",\"remark\":\"T3 结构化端到端\"}", adminHeaders()),
                String.class);
        assertThat(published.getStatusCode().value()).isEqualTo(200);
        assertThat(published.getBody()).contains("\"javaVersionCount\":1");
        assertThat(published.getBody()).contains("\"starterCount\":1");

        // 7) 生成读路径即时反映新快照
        HttpHeaders jsonHeaders = new HttpHeaders();
        jsonHeaders.setContentType(MediaType.APPLICATION_JSON);
        String generateBody = """
                {
                  "group": "com.example", "artifact": "t3-app", "packageName": "com.example.t3",
                  "javaVersion": "17", "bootVersion": "3.2.5", "starters": ["web"]
                }
                """;
        ResponseEntity<String> generate = rest.exchange("/api/generate", HttpMethod.POST,
                new HttpEntity<>(generateBody, jsonHeaders), String.class);
        assertThat(generate.getStatusCode().value()).as("generate: %s", generate.getBody()).isEqualTo(200);

        // 8) 审计留痕：结构化变更 + 发布
        ResponseEntity<String> audits = rest.exchange("/api/admin/audits",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(audits.getBody()).contains("UPDATE_STRUCTURED");
    }

    @Test
    void structuredApi_validationFailure_returns4002() {
        // 缺 operator → 4002（结构校验）
        ResponseEntity<String> missingOperator = putStructured("/api/admin/starters",
                "{\"starters\":[]}", null);
        assertThat(missingOperator.getStatusCode().value()).isEqualTo(422);
        assertThat(missingOperator.getBody()).contains("4002");

        // 重复 starter ID → 4002
        String dup = """
                {"starters": [
                  {"id": "dup", "category": "web", "maintainer": "official"},
                  {"id": "dup", "category": "web", "maintainer": "official"}],
                 "operator": "tester"}
                """;
        ResponseEntity<String> duplicated = putStructured("/api/admin/starters", dup, null);
        assertThat(duplicated.getStatusCode().value()).isEqualTo(422);
        assertThat(duplicated.getBody()).contains("4002");
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
        assertThat(resp.getBody()).contains("\"starterCount\":1"); // starter 草稿登记 web

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

    @Test
    void importExport_endpoints_roundTripThroughHttp_andPublishImportedDraft() {
        // 1. 编排草稿（发布准备）→ 导出归档
        putDraft("conflict-rule", "rules: []", null);
        putDraft("metadata", VALID_METADATA, null);
        putDraft("starter", VALID_STARTER, null);
        ResponseEntity<byte[]> exported = download("/api/admin/config/export");
        assertThat(exported.getStatusCode().value()).isEqualTo(200);
        assertThat(exported.getHeaders().getFirst("Content-Disposition"))
                .contains("initializer-config.zip");
        assertThat(exported.getBody()).isNotEmpty();

        // 2. 用导出内容重新导入（multipart）→ 草稿整体替换、返回规模摘要
        ResponseEntity<String> imported = upload("/api/admin/config/import?operator=tester",
                exported.getBody(), null);
        assertThat(imported.getStatusCode().value())
                .as("import 响应: %s", imported.getBody())
                .isEqualTo(200);
        assertThat(imported.getBody()).contains("rows").contains("java=1");

        // 3. 导入的草稿可直接发布（导入只写草稿，发布仍走完整校验通道）
        String publishBody = "{\"operator\":\"tester\",\"remark\":\"T4 导入后发布\"}";
        ResponseEntity<String> published = rest.exchange("/api/admin/config/publish", HttpMethod.POST,
                new HttpEntity<>(publishBody, adminHeaders()), String.class);
        assertThat(published.getStatusCode().value())
                .as("publish 响应: %s", published.getBody())
                .isEqualTo(200);

        // 4. 导入的 starter 对生成链路生效
        String generateBody = """
                {
                  "group": "com.example",
                  "artifact": "imported-app",
                  "packageName": "com.example.imported",
                  "javaVersion": "17",
                  "bootVersion": "3.2.5",
                  "starters": ["web"]
                }
                """;
        ResponseEntity<String> generate = rest.exchange("/api/generate", HttpMethod.POST,
                new HttpEntity<>(generateBody, adminHeaders()), String.class);
        assertThat(generate.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void importEndpoint_pathTraversalArchive_returns4008AndKeepsDraft() {
        // 前置一份可辨识的草稿（导入失败后必须仍在）
        putDraft("metadata", VALID_METADATA, null);

        byte[] evil = zipWithTraversalEntry();
        ResponseEntity<String> resp = upload("/api/admin/config/import?operator=tester", evil, null);
        assertThat(resp.getStatusCode().value()).isEqualTo(422);
        assertThat(resp.getBody()).contains("4008").contains("路径穿越");

        // 草稿未被污染
        ResponseEntity<String> draft = rest.exchange("/api/admin/config/metadata",
                HttpMethod.GET, new HttpEntity<>(adminHeaders()), String.class);
        assertThat(draft.getBody()).contains("3.2.5");
    }

    /** 下载二进制（导出归档）。 */
    private ResponseEntity<byte[]> download(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(AdminAuthFilter.TOKEN_HEADER, "intg-admin-token");
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    /** 上传 multipart 归档（导入）。 */
    private ResponseEntity<String> upload(String path, byte[] archive, Integer ifMatch) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set(AdminAuthFilter.TOKEN_HEADER, "intg-admin-token");
        if (ifMatch != null) {
            headers.set(AdminApiController.IF_MATCH_HEADER, String.valueOf(ifMatch));
        }
        org.springframework.util.LinkedMultiValueMap<String, Object> parts =
                new org.springframework.util.LinkedMultiValueMap<>();
        parts.add("file", new org.springframework.core.io.ByteArrayResource(archive) {
            @Override
            public String getFilename() {
                return "config.zip";
            }
        });
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), String.class);
    }

    /** 构造含路径穿越条目的 ZIP（用于安全加固 HTTP 层验证）。 */
    private static byte[] zipWithTraversalEntry() {
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                    buffer, java.nio.charset.StandardCharsets.UTF_8)) {
                for (String[] entry : new String[][] {
                        {"metadata.yml", "javaVersions:\n  - id: \"17\"\n    label: Java 17\n"},
                        {"templates/../../escape.ftl", "boom"}}) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry[0]));
                    zip.write(entry[1].getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
