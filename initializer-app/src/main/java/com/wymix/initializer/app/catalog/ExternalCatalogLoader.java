package com.wymix.initializer.app.catalog;

import com.wymix.initializer.app.InitializationProperties;
import com.wymix.initializer.app.admin.refresh.CatalogRefresher;
import com.wymix.initializer.core.conflict.ConflictCatalog;
import com.wymix.initializer.core.conflict.ConflictRuleParser;
import com.wymix.initializer.core.metadata.MetadataCatalog;
import com.wymix.initializer.core.starter.StarterCatalog;
import com.wymix.initializer.core.starter.StarterCatalogEntry;
import com.wymix.initializer.core.starter.YamlStarterParser;
import com.wymix.initializer.core.validation.ValidationRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 外部清单目录装载器：把 jar 外的配置目录作为清单数据源（启动时装载一次，重启生效）。
 *
 * <p>目录定位（优先级从高到低）：
 * <ol>
 *   <li>显式配置 {@code initializer.catalog.dir}（支持绝对/相对路径与 {@code ~/}）</li>
 *   <li>未配置时取 jar 所在目录下的 {@code catalog} 目录</li>
 * </ol>
 *
 * <p>目录结构（全部按需可选，缺哪个就用哪个，不要求与内置 {@code metadata.yml} 结构一致）：
 * <pre>
 * catalog/
 *   metadata.yml          # Java/Boot 版本矩阵 + starter 登记（可选）
 *   starters/*.yml        # starter 清单，可递归子目录，单文件支持多文档（可选）
 *   conflict-rules.yml    # 依赖冲突规则（可选）
 *   templates/**          # 模板覆盖，键为相对 templates/ 的路径（可选，未覆盖走内置模板）
 *   validation.yml        # 元信息校验规则（可选）
 * </pre>
 *
 * <p>装载口径：格式正确的文件即生效；单个文件解析失败只跳过该文件并告警（不阻断启动），
 * starter 清单条目无需在 {@code metadata.yml} 登记（由
 * {@link com.wymix.initializer.core.metadata.MetadataCatalog#merge} 联动合并为全部 Boot 版本可选）。
 *
 * <p>线程安全性：构造期一次性装载，之后只读，线程安全。
 */
@Component
public class ExternalCatalogLoader {

    private static final Logger log = LoggerFactory.getLogger(ExternalCatalogLoader.class);

    /** 元数据文件名（相对清单目录）。 */
    public static final String METADATA_FILE = "metadata.yml";
    /** starter 清单子目录名。 */
    public static final String STARTERS_DIR = "starters";
    /** 模板覆盖子目录名。 */
    public static final String TEMPLATES_DIR = "templates";
    /** 冲突规则文件名。 */
    public static final String CONFLICT_RULES_FILE = "conflict-rules.yml";
    /** 校验规则文件名。 */
    public static final String VALIDATION_FILE = "validation.yml";

    /** 外部清单目录（可能不存在）。 */
    private final Path directory;
    /** 装载结果（目录不存在时为全空结果）。 */
    private final ExternalCatalog catalog;

    /**
     * @param configuredDir 配置的清单目录（可空/空白表示未指定，回退 jar 所在目录下的 catalog）
     */
    public ExternalCatalogLoader(
            @Value("${" + InitializationProperties.CATALOG_DIR_PROPERTY + ":"
                    + InitializationProperties.CATALOG_DIR_DEFAULT + "}") String configuredDir) {
        this.directory = resolveDirectory(configuredDir);
        this.catalog = load(this.directory);
    }

    /** @return 外部清单目录绝对路径（目录本身可能不存在） */
    public Path directory() {
        return directory;
    }

    /** @return 装载结果（不返回 null） */
    public ExternalCatalog catalog() {
        return catalog;
    }

    /**
     * 解析清单目录：显式配置优先，未配置取 jar 所在目录下的 {@code catalog}。
     *
     * @param configuredDir 配置值（可空/空白）
     * @return 绝对路径
     */
    static Path resolveDirectory(String configuredDir) {
        if (configuredDir != null && !configuredDir.isBlank()) {
            String raw = configuredDir.trim();
            if (raw.startsWith("~/")) {
                raw = System.getProperty("user.home") + raw.substring(1);
            }
            return Path.of(raw).toAbsolutePath().normalize();
        }
        return jarDirectory().resolve("catalog").normalize();
    }

    /** jar（或 classes 根目录）所在目录；无法定位时回退当前工作目录。 */
    private static Path jarDirectory() {
        try {
            Path code = Path.of(ExternalCatalogLoader.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path parent = Files.isDirectory(code) ? code : code.getParent();
            return parent != null ? parent : Path.of("").toAbsolutePath();
        } catch (Exception e) {
            return Path.of("").toAbsolutePath();
        }
    }

    /**
     * 装载目录内全部配置文件；目录不存在返回全空结果（不抛异常，交由内置/管理端配置兜底）。
     *
     * @param dir 清单目录
     * @return 装载结果
     */
    private static ExternalCatalog load(Path dir) {
        if (!Files.isDirectory(dir)) {
            log.info("外部清单目录不存在，跳过（classpath 内置清单 / 管理端发布配置仍可生效）: {}", dir);
            return ExternalCatalog.empty();
        }
        log.info("装载外部清单目录: {}", dir);
        log.warn("外部清单目录（initializer.catalog.dir）已被配置结构化入库（M3）取代，"
                + "属保留的兼容通道：它会在启动期直接覆盖 DB 发布快照，与「DB 为唯一数据源」并存时会互相遮蔽。"
                + "新环境请改用管理端表单维护或 ZIP 归档导入（POST /api/admin/config/import），"
                + "外部目录将在后续版本下线。详见 docs/guide/configuration.md §14");
        MetadataCatalog metadata = loadMetadata(dir.resolve(METADATA_FILE));
        StarterCatalog starters = loadStarters(dir.resolve(STARTERS_DIR));
        ConflictCatalog conflicts = loadConflictRules(dir.resolve(CONFLICT_RULES_FILE));
        Map<String, String> templates = loadTemplates(dir.resolve(TEMPLATES_DIR));
        ValidationRules validation = loadValidation(dir.resolve(VALIDATION_FILE));
        log.info("外部清单装载完成: metadata={} starter={} 模板覆盖={} 冲突规则={} 校验规则={}",
                metadata == null ? "无" : metadata.javaVersions().size() + " 个 Java 版本",
                starters == null ? "无" : starters.size() + " 项",
                templates.size(), conflicts.size(), validation == null ? "无" : "已启用");
        return new ExternalCatalog(metadata, starters, templates, conflicts, validation);
    }

    /** 装载 metadata.yml；不存在或格式非法返回 null（跳过并告警）。 */
    private static MetadataCatalog loadMetadata(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return MetadataCatalog.load(in);
        } catch (IOException | RuntimeException e) {
            log.warn("外部清单 {} 装载失败，已跳过该文件: {}", file, e.getMessage());
            return null;
        }
    }

    /** 装载 starters 目录下全部 yml（递归、可多文档）；单文件失败仅跳过该文件。 */
    private static StarterCatalog loadStarters(Path startersDir) {
        if (!Files.isDirectory(startersDir)) {
            return null;
        }
        List<StarterCatalogEntry> entries = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(startersDir)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(ExternalCatalogLoader::isYaml)
                    .sorted()
                    .toList();
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                int index = 0;
                for (String document : CatalogRefresher.splitDocuments(text)) {
                    index++;
                    try (InputStream in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8))) {
                        entries.add(YamlStarterParser.parse(in));
                    } catch (IOException | RuntimeException e) {
                        log.warn("外部 starter 文件 {} 第 {} 个文档格式非法，已跳过: {}", file, index, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            log.warn("外部 starter 目录读取失败: {} - {}", startersDir, e.getMessage());
            return null;
        }
        if (entries.isEmpty()) {
            return null;
        }
        try {
            return StarterCatalog.of(entries);
        } catch (RuntimeException e) {
            log.warn("外部 starter 清单装载失败，已跳过: {}", e.getMessage());
            return null;
        }
    }

    /** 装载 conflict-rules.yml；不存在返回空规则集。 */
    private static ConflictCatalog loadConflictRules(Path file) {
        if (!Files.isRegularFile(file)) {
            return ConflictCatalog.empty();
        }
        try {
            return ConflictRuleParser.parseText(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.warn("外部冲突规则 {} 装载失败，已跳过: {}", file, e.getMessage());
            return ConflictCatalog.empty();
        }
    }

    /** 装载 templates 目录下全部文件为模板覆盖（键为相对路径，/ 分隔）；单文件失败仅跳过。 */
    private static Map<String, String> loadTemplates(Path templatesDir) {
        Map<String, String> overrides = new LinkedHashMap<>();
        if (!Files.isDirectory(templatesDir)) {
            return overrides;
        }
        try (Stream<Path> walk = Files.walk(templatesDir)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String name = templatesDir.relativize(file).toString().replace('\\', '/');
                try {
                    overrides.put(name, Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    log.warn("外部模板 {} 读取失败，已跳过: {}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("外部模板目录读取失败: {} - {}", templatesDir, e.getMessage());
        }
        return overrides;
    }

    /** 装载 validation.yml；不存在或格式非法返回 null。 */
    private static ValidationRules loadValidation(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (InputStream in = Files.newInputStream(file)) {
            return ValidationRules.load(in);
        } catch (IOException | RuntimeException e) {
            log.warn("外部校验规则 {} 装载失败，已跳过: {}", file, e.getMessage());
            return null;
        }
    }

    private static boolean isYaml(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    /**
     * 外部清单装载结果：未提供的数据项为 null，集合类为空集合（非 null）。
     *
     * <p>线程安全性：不可变对象，线程安全。
     *
     * @param metadataCatalog  外部 metadata（未提供为 null）
     * @param starterCatalog   外部 starter 清单（未提供为 null）
     * @param templateOverrides 模板覆盖（键为模板名，未提供为空映射）
     * @param conflictCatalog  冲突规则（未提供为空目录）
     * @param validationRules  校验规则（未提供为 null）
     */
    public record ExternalCatalog(MetadataCatalog metadataCatalog, StarterCatalog starterCatalog,
                                  Map<String, String> templateOverrides, ConflictCatalog conflictCatalog,
                                  ValidationRules validationRules) {

        /** @return 空结果（外部目录未提供任何配置） */
        static ExternalCatalog empty() {
            return new ExternalCatalog(null, null, Map.of(), ConflictCatalog.empty(), null);
        }

        /** @return 是否提供了 metadata.yml */
        public boolean hasMetadata() {
            return metadataCatalog != null;
        }

        /** @return 是否提供了 starter 清单 */
        public boolean hasStarters() {
            return starterCatalog != null;
        }

        /** @return 是否提供了任一清单数据（可据此决定是否覆盖内置/DB 快照） */
        public boolean hasAnyCatalog() {
            return hasMetadata() || hasStarters();
        }
    }
}
