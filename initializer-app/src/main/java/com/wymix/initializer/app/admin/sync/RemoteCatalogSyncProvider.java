package com.wymix.initializer.app.admin.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.sync.CatalogSyncProvider;
import com.wymix.initializer.core.sync.SyncedStarter;
import com.wymix.initializer.core.sync.SyncResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;

/**
 * 制品库同步 HTTP 实现（M3.3，概要设计 M3.3）：从内部制品库 REST 接口拉取 starter 清单。
 *
 * <p>接口契约（配置化，{@code initializer.sync.remote.endpoint} 未配置时本通道
 * 禁用（{@code enabled()} 返回 false）——同步链路仅 Mock 通道生效；配置后
 * Mock/Remote 双通道并存，：{@code GET {endpoint}} 返回 JSON 数组，每元素字段
 * {@code id / category / description / groupId / artifactId / version}。
 * 鉴权通过 {@code Authorization} 请求头透传（token 配置化，可为空）。
 *
 * <p>失败语义：HTTP 非 2xx、响应解析失败、字段缺失均抛 4005，由
 * {@link CatalogSyncService} 审计留痕，不阻断生成服务。
 *
 * <p>线程安全性：无状态单例（RestClient 不可变），线程安全。
 */
@Component
public class RemoteCatalogSyncProvider implements CatalogSyncProvider {

    private static final Logger log = LoggerFactory.getLogger(RemoteCatalogSyncProvider.class);

    /** Remote provider 来源标识。 */
    public static final String PROVIDER_ID = "internal-repo";

    /** 制品库接口路径（{@code initializer.sync.remote.endpoint}，未配置时同步链路禁用）。 */
    private final String endpoint;

    /** 鉴权 token（{@code initializer.sync.remote.token}，可空）。 */
    private final String token;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    /**
     * @param endpoint     制品库 REST 接口路径（可空 = 未配置）
     * @param token        鉴权 token（可空）
     * @param builder      RestClient 构建器（Spring Boot 自动装配）
     * @param objectMapper JSON 解析
     */
    public RemoteCatalogSyncProvider(
            @Value("${initializer.sync.remote.endpoint:}") String endpoint,
            @Value("${initializer.sync.remote.token:}") String token,
            RestClient.Builder builder,
            ObjectMapper objectMapper) {
        this.endpoint = endpoint;
        this.token = token;
        this.restClient = builder.build();
        this.objectMapper = objectMapper;
    }

    /**
     * 通道启用条件（M3.3 SPI 覆盖）：endpoint 已配置才参与同步，
     * 未配置时由 {@code CatalogSyncService} 过滤出通道清单（Mock 兜底不受影响）。
     */
    @Override
    public boolean enabled() {
        return isConfigured();
    }

    /** @return endpoint 是否已配置（同步链路 remote 通道启用条件） */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank();
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public SyncResult fetch() {
        if (!isConfigured()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: remote endpoint 未配置（initializer.sync.remote.endpoint）");
        }
        String body;
        try {
            RestClient.RequestHeadersSpec<?> spec = restClient.get().uri(endpoint);
            if (token != null && !token.isBlank()) {
                spec = spec.header("Authorization", "Bearer " + token);
            }
            body = spec.retrieve().body(String.class);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: HTTP 拉取异常: " + e.getMessage(), e);
        }
        return parse(body);
    }

    /**
     * 解析制品库响应为同步结果（JSON 数组 → 归一化条目）。
     *
     * @param body HTTP 响应体
     * @return 同步结果
     * @throws BusinessException 响应结构非法或字段缺失（4005）
     */
    SyncResult parse(String body) {
        JsonNode root;
        try {
            root = objectMapper.readTree(body == null || body.isBlank() ? "[]" : body);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: 响应 JSON 解析失败: " + e.getMessage(), e);
        }
        if (!root.isArray()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: 响应顶层必须为 JSON 数组");
        }
        List<SyncedStarter> starters = new ArrayList<>();
        for (JsonNode node : root) {
            starters.add(new SyncedStarter(
                    textOrNull(node, "id"),
                    textOrNull(node, "category"),
                    textOrNull(node, "description"),
                    textOrNull(node, "groupId"),
                    textOrNull(node, "artifactId"),
                    textOrNull(node, "version")));
        }
        log.info("制品库同步拉取完成: {} 个条目", starters.size());
        return new SyncResult(PROVIDER_ID, starters);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
