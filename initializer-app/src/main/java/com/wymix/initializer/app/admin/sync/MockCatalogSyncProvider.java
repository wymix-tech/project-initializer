package com.wymix.initializer.app.admin.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.core.sync.CatalogSyncProvider;
import com.wymix.initializer.core.sync.SyncedStarter;
import com.wymix.initializer.core.sync.SyncResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * 制品库同步 Mock 实现（M3.3，概要设计 M3.3）：从本地 fixture 文件读取制品库发布清单。
 *
 * <p>用途：制品库接口契约未定期间保证同步链路架构落地（全链路可测可演示）；
 * 真实制品库对接时由 {@link RemoteCatalogSyncProvider}（HTTP 拉取）替代或并存。
 *
 * <p>fixture 格式：多文档 YAML，每文档一个 {@code SyncedStarter} 同构映射
 * （id / category / description / groupId / artifactId / version）。
 *
 * <p>装配开关：{@code initializer.sync.mock.enabled}（默认 true），测试可通过置 false
 * 排除 Mock 通道。
 *
 * <p>线程安全性：无状态单例（每次调用独立读取资源），线程安全。
 */
@Component
@ConditionalOnProperty(prefix = "initializer.sync.mock", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MockCatalogSyncProvider implements CatalogSyncProvider {

    /** Mock provider 来源标识。 */
    public static final String PROVIDER_ID = "mock-repo";

    /** 默认 fixture 路径：classpath 内置的制品库发布样例。 */
    static final String DEFAULT_FIXTURE = "classpath:sync/artifact-repo-fixtures.yml";

    private final String fixtureLocation;
    private final ResourceLoader resourceLoader;

    /**
     * @param fixtureLocation fixture 资源定位符（可配置，便于测试注入临时清单）
     * @param resourceLoader  Spring 资源加载器（支持 classpath:/file: 等前缀）
     */
    public MockCatalogSyncProvider(
            @Value("${initializer.sync.mock.fixture:" + DEFAULT_FIXTURE + "}") String fixtureLocation,
            ResourceLoader resourceLoader) {
        this.fixtureLocation = fixtureLocation;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public SyncResult fetch() {
        Resource resource = resourceLoader.getResource(fixtureLocation);
        if (!resource.exists()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: fixture 不存在: " + fixtureLocation);
        }
        String text;
        try (InputStream in = resource.getInputStream();
             Scanner scanner = new Scanner(in, StandardCharsets.UTF_8)) {
            scanner.useDelimiter("\\A");
            text = scanner.hasNext() ? scanner.next() : "";
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: fixture 读取失败: " + e.getMessage(), e);
        }
        List<SyncedStarter> starters = new ArrayList<>();
        for (String document : splitDocuments(text)) {
            starters.add(parseDocument(document));
        }
        return new SyncResult(PROVIDER_ID, starters);
    }

    /**
     * 解析单个 fixture 文档为同步条目。
     *
     * @param document 单文档 YAML 文本
     * @return 同步条目
     * @throws BusinessException 结构非法或缺少必需字段（4005）
     */
    private SyncedStarter parseDocument(String document) {
        Object root;
        try {
            root = new Yaml(new SafeConstructor(new LoaderOptions())).load(document);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: fixture 文档解析失败: " + e.getMessage(), e);
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED,
                    "制品库同步失败: fixture 文档顶层必须为映射结构");
        }
        return new SyncedStarter(
                stringValue(map, "id"),
                stringValue(map, "category"),
                stringValue(map, "description"),
                stringValue(map, "groupId"),
                stringValue(map, "artifactId"),
                stringValue(map, "version"));
    }

    private static String stringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    /**
     * 按 YAML 文档分隔符切分多文档。
     *
     * <p>统一委托 {@link CatalogRefresher#splitDocuments(String)}：切分规则只保留一份实现，
     * 避免两处实现漂移（曾出现两处逻辑重复且均按 trim 后比较，会误切块标量内的 --- 行）。
     *
     * @param yaml 配置全文（多文档，可空）
     * @return 非空文档清单
     */
    static List<String> splitDocuments(String yaml) {
        return CatalogRefresher.splitDocuments(yaml);
    }
}
