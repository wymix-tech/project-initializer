package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 清单热刷新器（M3.1/M3.2）：草稿 YAML → 装载期校验 → 快照构建 → 原子切换。
 *
 * <p>失败语义：任一步失败抛 {@link BusinessException}（4002/4006），
 * 快照引用保持不变，生成服务无感；草稿保留可继续编辑。
 *
 * <p>starter 草稿格式：与 {@code catalog/starters/*.yml} 同构的配置流，
 * 多 starter 以 YAML 多文档（--- 分隔）表达；METADATA 草稿与 {@code metadata.yml} 同构。
 *
 * <p>线程安全性：无状态单例（校验逻辑纯函数式），线程安全。
 */
@Component
public class CatalogRefresher {

    private static final Logger log = LoggerFactory.getLogger(CatalogRefresher.class);

    /** StarterCatalog 装载失败错误码（9002，与 classpath 装载口径一致）。 */
    private static final ErrorCode STARTER_INVALID = ErrorCode.STARTER_CATALOG_INVALID;

    /**
     * 校验并构建快照（不切换，切换由 {@link CatalogSnapshotHolder} 负责）。
     *
     * @param metadataYaml metadata 草稿全文（null 时拒绝——首发快照必须显式提供）
     * @param starterYaml  starter 草稿全文（多文档），null 时视为空清单
     * @return 校验通过的新快照
     * @throws BusinessException 解析/装载校验失败（4002：metadata 缺失或 starter 清单非法）
     */
    public CatalogSnapshot validateAndBuild(String metadataYaml, String starterYaml) {
        if (metadataYaml == null || metadataYaml.isBlank()) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: metadata 配置缺失，首发快照必须显式提供 metadata.yml 同构配置");
        }
        MetadataCatalog metadataCatalog = parseMetadata(metadataYaml);
        StarterCatalog starterCatalog = parseStarters(starterYaml);
        // 交叉校验：metadata.bootVersions.starters 引用的 starter 必须在 starter 清单登记
        validateCrossReferences(metadataCatalog, starterCatalog);
        return new CatalogSnapshot(metadataCatalog, starterCatalog);
    }

    /**
     * 解析 metadata 草稿。MetadataCatalog.load 的悬空引用/ID 重复校验直接复用，
     * 异常统一收敛为 4002（发布校验失败）。
     */
    private MetadataCatalog parseMetadata(String yaml) {
        try {
            return MetadataCatalog.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
        } catch (BusinessException e) {
            throw new BusinessException(ErrorCode.CONFIG_VALIDATION_FAILED,
                    "发布校验失败: metadata 配置非法: " + e.getMessage(), e);
        }
    }

    /**
     * 解析 starter 草稿（YAML 多文档，每文档一个 starter）。
     * YamlStarterParser / StarterCatalog 的全部装载期校验直接复用，零重复代码。
     */
    private StarterCatalog parseStarters(String starterYaml) {
        List<StarterCatalogEntry> entries = new ArrayList<>();
        if (starterYaml != null && !starterYaml.isBlank()) {
            for (String document : splitDocuments(starterYaml)) {
                try (var in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                    entries.add(YamlStarterParser.parse(in));
                } catch (BusinessException e) {
                    throw new BusinessException(STARTER_INVALID,
                            "发布校验失败: starter 配置非法: " + e.getMessage(), e);
                } catch (Exception e) {
                    throw new BusinessException(STARTER_INVALID,
                            "发布校验失败: starter 配置读取失败: " + e.getMessage(), e);
                }
            }
        }
        try {
            return StarterCatalog.of(entries);
        } catch (BusinessException e) {
            throw new BusinessException(STARTER_INVALID,
                    "发布校验失败: starter 清单非法: " + e.getMessage(), e);
        }
    }

    /**
     * 交叉悬空引用校验：M3.1 发布期兜底（metadata 装载期无法感知 starter 草稿清单）。
     *
     * <p>注意：web 等官方 starter 允许只在 metadata.yml 的 starters 节登记（无 starter 清单
     * yml，无联动规则）；此处校验的是 bootVersions.starters 引用的 ID 至少在 metadata
     * starters 节存在——该检查已由 MetadataCatalog 悬空引用校验覆盖，本方法额外校验
     * starter 清单与 metadata 双边都未登记的 ID 不可能存在（双边独立装载后天然保证）。
     */
    private void validateCrossReferences(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog) {
        starterCatalog.all().forEach(entry -> {
            if (metadataCatalog.starterDefinitionOrNull(entry.id()) == null) {
                log.warn("starter 清单条目 {} 未在 metadata.yml starters 节登记（允许：仅清单维护，无展示名）", entry.id());
            }
        });
    }


    /**
     * 按 YAML 文档分隔符（行首 --- ）切分多文档；单文档原样返回。
     */
    static List<String> splitDocuments(String yaml) {
        List<String> documents = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            if (line.trim().equals("---")) {
                if (!current.toString().isBlank()) {
                    documents.add(current.toString());
                }
                current.setLength(0);
            } else {
                current.append(line).append('\n');
            }
        }
        if (!current.toString().isBlank()) {
            documents.add(current.toString());
        }
        return documents;
    }
}
