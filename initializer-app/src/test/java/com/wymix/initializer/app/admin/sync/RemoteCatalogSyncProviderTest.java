package com.wymix.initializer.app.admin.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RemoteCatalogSyncProvider} 单元测试（M3.3）：响应解析、非数组拒绝、缺字段 4005、装配开关。
 */
class RemoteCatalogSyncProviderTest {

    private final RemoteCatalogSyncProvider provider = new RemoteCatalogSyncProvider(
            "http://repo.internal/starters", "secret-token",
            RestClient.builder(), new ObjectMapper());

    @Test
    void providerId_isInternalRepo() {
        assertThat(provider.providerId()).isEqualTo(RemoteCatalogSyncProvider.PROVIDER_ID);
        assertThat(RemoteCatalogSyncProvider.PROVIDER_ID).isEqualTo("internal-repo");
    }

    @Test
    void isConfigured_endpointSet_meansTrue() {
        assertThat(provider.isConfigured()).isTrue();
        RemoteCatalogSyncProvider unconfigured = new RemoteCatalogSyncProvider(
                "", "", RestClient.builder(), new ObjectMapper());
        assertThat(unconfigured.isConfigured()).isFalse();
    }

    @Test
    void fetch_unconfigured_throws4005() {
        RemoteCatalogSyncProvider unconfigured = new RemoteCatalogSyncProvider(
                "", "", RestClient.builder(), new ObjectMapper());
        assertThatThrownBy(unconfigured::fetch)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED))
                .hasMessageContaining("endpoint 未配置");
    }

    @Test
    void parse_validArray_mapsToStarters() {
        String body = """
                [
                  {"id":"xxx-oss","category":"middleware","description":"OSS","groupId":"com.company","artifactId":"xxx-oss","version":"1.0.0"},
                  {"id":"xxx-sms","category":"tool","description":null,"groupId":"com.company","artifactId":"xxx-sms","version":"2.0.0"}
                ]
                """;
        var result = provider.parse(body);
        assertThat(result.providerId()).isEqualTo("internal-repo");
        assertThat(result.starters()).hasSize(2);
        assertThat(result.starters().get(1).description()).isEqualTo("xxx-sms"); // null 描述回退 id
    }

    @Test
    void parse_notArray_throws4005() {
        assertThatThrownBy(() -> provider.parse("{\"id\":\"x\"}"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED))
                .hasMessageContaining("JSON 数组");
    }

    @Test
    void parse_badJson_throws4005() {
        assertThatThrownBy(() -> provider.parse("not-json"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void parse_missingField_propagatesAs4005() {
        String body = "[{\"id\":\"x\",\"category\":\"web\"}]";
        assertThatThrownBy(() -> provider.parse(body))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED));
    }

    @Test
    void parse_blankBody_meansEmptyList() {
        assertThat(provider.parse("").starters()).isEmpty();
        assertThat(provider.parse(null).starters()).isEmpty();
    }

    @Test
    void enabled_followsConfiguredState() {
        assertThat(provider.enabled()).isTrue();
        RemoteCatalogSyncProvider unconfigured = new RemoteCatalogSyncProvider(
                "", "", RestClient.builder(), new ObjectMapper());
        assertThat(unconfigured.enabled()).isFalse();
    }

    @Test
    void fetch_httpError_throws4005() {
        // 不可达端点 → HTTP 拉取异常收敛为 4005（审计留痕由服务层负责）
        RemoteCatalogSyncProvider unreachable = new RemoteCatalogSyncProvider(
                "http://localhost:1/no-such-endpoint", "", RestClient.builder(), new ObjectMapper());
        assertThatThrownBy(unreachable::fetch)
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.SYNC_PROVIDER_FAILED))
                .hasMessageContaining("HTTP 拉取异常");
    }
}
