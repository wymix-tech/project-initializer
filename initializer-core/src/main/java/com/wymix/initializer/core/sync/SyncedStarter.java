package com.wymix.initializer.core.sync;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.starter.DependencyCoord;
import com.wymix.initializer.core.starter.LinkageSpec;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 制品库同步条目（M3.3）：制品库发布的单个 starter 元数据的归一化模型。
 *
 * <p>与 starter yml（{@link StarterCatalogEntry}）同构的精简子集：制品库只提供
 * 坐标与描述，无联动规则（linkages 恒为空）；维护方固定为内部（INTERNAL）。
 *
 * <p>线程安全性：不可变对象，线程安全。
 *
 * @param id          starter ID，全局唯一（如 {@code xxx-oss-spring-boot-starter}）
 * @param category    分类：web / data / middleware / tool
 * @param description 展示描述（可空，回退为 id）
 * @param groupId     依赖坐标 groupId
 * @param artifactId  依赖坐标 artifactId
 * @param version     依赖版本（制品库发布的当前版本，Gradle GAV 透传）
 */
public record SyncedStarter(String id, String category, String description,
                            String groupId, String artifactId, String version) {

    public SyncedStarter {
        if (id == null || id.isBlank()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "制品库同步条目缺少必需字段: id");
        }
        if (category == null || category.isBlank()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "制品库同步条目 " + id + " 缺少必需字段: category");
        }
        if (groupId == null || groupId.isBlank()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "制品库同步条目 " + id + " 缺少必需字段: groupId");
        }
        if (artifactId == null || artifactId.isBlank()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "制品库同步条目 " + id + " 缺少必需字段: artifactId");
        }
        if (version == null || version.isBlank()) {
            throw new BusinessException(ErrorCode.SYNC_PROVIDER_FAILED, "制品库同步条目 " + id + " 缺少必需字段: version");
        }
        description = description == null || description.isBlank() ? id : description;
    }

    /**
     * 转换为 starter 清单条目（进入快照构建通道）。
     *
     * @return 清单条目（maintainer 固定 INTERNAL，linkages 为空规则集）
     */
    public StarterCatalogEntry toEntry() {
        return new StarterCatalogEntry(id, category.trim(), com.wymix.initializer.core.starter.Maintainer.INTERNAL,
                description.trim(), List.of(new DependencyCoord(groupId.trim(), artifactId.trim(), version.trim())),
                new LinkageSpec(null, List.of(), List.of(), List.of()));
    }

    /**
     * 序列化为 starter yml 同构文本（多文档通道的单文档，同步层草稿与发布历史存储用）。
     *
     * <p>SnakeYAML dump 负责转义，回滚通道可经 {@code YamlStarterParser} 无损重载。
     *
     * @return 单文档 YAML 文本（以 {@code linkages: {}} 收尾，无联动规则）
     */
    public String toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("id", id);
        root.put("category", category.trim());
        root.put("maintainer", "internal");
        root.put("description", description.trim());
        root.put("dependencies", List.of(new LinkedHashMap<>(Map.of(
                "groupId", groupId.trim(),
                "artifactId", artifactId.trim(),
                "version", version.trim()))));
        root.put("linkages", new LinkedHashMap<>());
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(root);
    }
}
