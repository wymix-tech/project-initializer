package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import com.wymix.initializer.app.admin.entity.CfgBootJavaEntity;
import com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity;
import com.wymix.initializer.app.admin.entity.CfgBootVersionEntity;
import com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 配置归档编解码器（结构化改造 T4）：ZIP 与「目录结构一致的 YAML 文档集」之间的转换，
 * 内含**导入侧安全加固**（路径穿越、条目数、单文件大小、解压总量、UTF-8）。
 *
 * <p><b>归档布局</b>（与设计文档 §1.1 决策 4 的目录结构一致）：
 * <pre>
 * metadata.yml                     # 元数据（Java/Boot 版本、兼容矩阵、starter 白名单）
 * starters/&lt;id&gt;.yml                # 一 starter 一文件（也接受 starters/*.yml 递归子目录）
 * conflict-rules.yml               # 冲突规则（可选）
 * templates/&lt;相对路径&gt;.ftl          # 模板全文（可选，路径即模板名）
 * </pre>
 *
 * <p><b>安全加固（一票否决项）</b>：
 * <ul>
 *   <li>条目名必须为相对路径，拒绝绝对路径（{@code /x}、{@code C:\x}）与任何 {@code ..} 段</li>
 *   <li>只接受目录项与普通文件；条目数 ≤ {@link #MAX_ENTRIES}，单文件 ≤ {@link #MAX_FILE_BYTES}，
 *       解压总量 ≤ {@link #MAX_TOTAL_BYTES}（防压缩炸弹）</li>
 *   <li>拒绝非法 UTF-8 字节序列（{@code CodingErrorAction.REPORT}），不静默替换为 {@code U+FFFD}</li>
 *   <li>目录层级 ≤ {@link #MAX_DEPTH}，条目名长度 ≤ {@link #MAX_NAME_LENGTH}</li>
 * </ul>
 * 以上任一不满足一律抛 {@link ErrorCode#CONFIG_IMPORT_ARCHIVE_INVALID}，
 * 明细指出具体违规条目，便于用户修正后再上传。
 *
 * <p>线程安全性：无状态单例（全部状态在入参/局部变量中），线程安全。
 */
@Component
public class ConfigArchiveCodec {

    private static final Logger log = LoggerFactory.getLogger(ConfigArchiveCodec.class);

    /** 归档条目数上限（防超多小文件拖垮解析）。 */
    public static final int MAX_ENTRIES = 2048;
    /** 单条目解压后字节数上限（模板/清单单文件 2 MiB）。 */
    public static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    /** 归档解压总量上限（防压缩炸弹）。 */
    public static final long MAX_TOTAL_BYTES = 32L * 1024 * 1024;
    /** 目录层级上限。 */
    public static final int MAX_DEPTH = 16;
    /** 单条目名长度上限。 */
    public static final int MAX_NAME_LENGTH = 512;

    private static final String METADATA_ENTRY = "metadata.yml";
    private static final String CONFLICT_ENTRY = "conflict-rules.yml";
    private static final String STARTERS_PREFIX = "starters/";
    private static final String TEMPLATES_PREFIX = "templates/";

    /**
     * 解析 ZIP 归档为「条目名 → UTF-8 文本」映射（含安全校验）。
     *
     * @param archive 上传的 ZIP 字节流
     * @return 条目名 → 文本（保持归档内的声明顺序）
     * @throws BusinessException 归档非法（4008；含路径穿越、超限、非法 UTF-8 等明细）
     */
    public Map<String, String> readArchive(InputStream archive) {
        Map<String, String> entries = new LinkedHashMap<>();
        long totalBytes = 0L;
        int count = 0;
        try (ZipInputStream zip = new ZipInputStream(archive, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                count++;
                if (count > MAX_ENTRIES) {
                    throw archiveInvalid("归档条目数超限（最多 " + MAX_ENTRIES + " 个）");
                }
                String name = normalizeEntryName(entry.getName());
                if (name == null || name.isEmpty() || entry.isDirectory()) {
                    continue;
                }
                byte[] bytes = readEntry(zip, name);
                totalBytes += bytes.length;
                if (totalBytes > MAX_TOTAL_BYTES) {
                    throw archiveInvalid("归档解压总量超限（最多 " + MAX_TOTAL_BYTES + " 字节）");
                }
                if (entries.putIfAbsent(name, decodeUtf8(bytes, name)) != null) {
                    throw archiveInvalid("归档存在重复条目: " + name);
                }
            }
        } catch (IOException e) {
            throw archiveInvalid("归档读取失败: " + e.getMessage());
        }
        if (entries.isEmpty()) {
            throw archiveInvalid("归档内容为空（未包含任何 metadata.yml / starters/*.yml / templates/** 文件）");
        }
        return entries;
    }

    /**
     * 从归档条目集合中提取元数据文本。
     *
     * @param entries 条目名 → 文本
     * @return metadata.yml 文本；归档未包含时返回 {@code null}
     */
    public String metadataOf(Map<String, String> entries) {
        return entries.get(METADATA_ENTRY);
    }

    /**
     * 从归档条目集合中提取冲突规则文本。
     *
     * @param entries 条目名 → 文本
     * @return conflict-rules.yml 文本；归档未包含时返回 {@code null}
     */
    public String conflictRulesOf(Map<String, String> entries) {
        return entries.get(CONFLICT_ENTRY);
    }

    /**
     * 从归档条目集合中提取 starter 清单：每条目一个 YAML 文档，按条目名排序保证稳定顺序。
     *
     * @param entries 条目名 → 文本
     * @return 多文档 starter 文本（{@code ---} 分隔）；无 starter 文件时返回 {@code null}
     */
    public String startersOf(Map<String, String> entries) {
        List<String> documents = new ArrayList<>();
        // 容忍 .yml / .yaml 扩展名，也容忍无扩展名（导出侧写的是 starters/<id>）；
        // 只要求前缀之后至少有一个字符，避免把 "starters/" 这类空名条目当文档
        for (String name : sortedKeys(entries, entry -> entry.startsWith(STARTERS_PREFIX)
                && entry.length() > STARTERS_PREFIX.length())) {
            documents.add(entries.get(name));
        }
        return documents.isEmpty() ? null : String.join("\n---\n", documents);
    }

    /**
     * 从归档条目集合中提取模板：{@code templates/<相对路径>} 即模板名（相对 templates/ 的路径）。
     *
     * @param entries 条目名 → 文本
     * @return 多文档 {@code {name, content}} 文本；无模板文件时返回 {@code null}
     */
    public String templatesOf(Map<String, String> entries) {
        List<String> documents = new ArrayList<>();
        for (String name : sortedKeys(entries, entry -> entry.startsWith(TEMPLATES_PREFIX)
                && entry.length() > TEMPLATES_PREFIX.length())) {
            String templateName = name.substring(TEMPLATES_PREFIX.length());
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("name", templateName);
            document.put("content", entries.get(name));
            documents.add(YamlTexts.dump(document));
        }
        return documents.isEmpty() ? null : String.join("---\n", documents);
    }

    /**
     * 渲染元数据 YAML 文本（结构行 → metadata.yml 同构文本）。
     *
     * @param snapshot 含元数据结构行的快照
     * @return YAML 文本；无元数据行时返回 {@code null}
     */
    public String renderMetadata(StructuredSnapshot snapshot) {
        if (snapshot.javaVersions().isEmpty() && snapshot.bootVersions().isEmpty()) {
            return null;
        }
        Map<String, Object> root = new LinkedHashMap<>();
        List<Map<String, Object>> javaVersions = new ArrayList<>();
        for (CfgJavaVersionEntity row : sorted(snapshot.javaVersions(), CfgJavaVersionEntity::getSort)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getVersionId());
            item.put("label", row.getLabel());
            item.put("deprecated", Boolean.TRUE.equals(row.getDeprecated()));
            if (row.getRecommendedBootVersion() != null && !row.getRecommendedBootVersion().isBlank()) {
                item.put("recommendedBootVersion", row.getRecommendedBootVersion());
            }
            javaVersions.add(item);
        }
        Map<Long, List<String>> javaRefs = new LinkedHashMap<>();
        for (CfgBootJavaEntity row : snapshot.bootJava()) {
            javaRefs.computeIfAbsent(row.getBootPk(), k -> new ArrayList<>()).add(row.getJavaVersionId());
        }
        Map<Long, List<String>> starterRefs = new LinkedHashMap<>();
        for (CfgBootStarterRefEntity row : sorted(snapshot.bootStarterRefs(), CfgBootStarterRefEntity::getSort)) {
            starterRefs.computeIfAbsent(row.getBootPk(), k -> new ArrayList<>()).add(row.getStarterId());
        }
        List<Map<String, Object>> bootVersions = new ArrayList<>();
        for (CfgBootVersionEntity row : sorted(snapshot.bootVersions(), CfgBootVersionEntity::getSort)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getVersionId());
            item.put("label", row.getLabel());
            item.put("official", !Boolean.FALSE.equals(row.getOfficial()));
            item.put("supportedJavaVersions", javaRefs.getOrDefault(row.getId(), List.of()));
            item.put("starters", starterRefs.getOrDefault(row.getId(), List.of()));
            if (row.getFpGroup() != null && row.getFpArtifact() != null && row.getFpVersion() != null) {
                Map<String, Object> parent = new LinkedHashMap<>();
                parent.put("groupId", row.getFpGroup());
                parent.put("artifactId", row.getFpArtifact());
                parent.put("version", row.getFpVersion());
                item.put("frameworkParent", parent);
            }
            bootVersions.add(item);
        }
        root.put("javaVersions", javaVersions);
        root.put("bootVersions", bootVersions);
        return YamlTexts.dump(root);
    }

    /**
     * 把「归档布局」写成 ZIP 字节流。
     *
     * @param metadataYaml       metadata.yml 文本（可空 = 不写入该条目）
     * @param starterDocuments   单 starter YAML 文档清单（按 {@code starters/<id>.yml} 写入）
     * @param conflictYaml       conflict-rules.yml 文本（可空）
     * @param templates          模板名（相对 templates/ 路径）→ 模板全文（可空）
     * @return ZIP 字节流
     * @throws BusinessException 序列化失败（4010）
     */
    public byte[] writeArchive(String metadataYaml, Map<String, String> starterDocuments,
                               String conflictYaml, Map<String, String> templates) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
            writeEntry(zip, METADATA_ENTRY, metadataYaml);
            if (starterDocuments != null) {
                List<String> names = new ArrayList<>(starterDocuments.keySet());
                names.sort(Comparator.naturalOrder());
                for (String name : names) {
                    writeEntry(zip, STARTERS_PREFIX + name, starterDocuments.get(name));
                }
            }
            writeEntry(zip, CONFLICT_ENTRY, conflictYaml);
            if (templates != null) {
                List<String> names = new ArrayList<>(templates.keySet());
                names.sort(Comparator.naturalOrder());
                for (String name : names) {
                    writeEntry(zip, TEMPLATES_PREFIX + name, templates.get(name));
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.CONFIG_EXPORT_FAILED,
                    "配置导出失败: 归档序列化异常: " + e.getMessage(), e);
        }
        log.debug("配置归档已生成: bytes={}", buffer.size());
        return buffer.toByteArray();
    }

    // ==================== 内部 ====================

    /**
     * 条目名归一与安全校验：拒绝绝对路径、{@code ..} 段、反斜杠伪装、超长名与超深层级。
     *
     * @param raw 原始条目名
     * @return 归一后的相对路径；空名返回空串
     * @throws BusinessException 违规条目（4008）
     */
    private static String normalizeEntryName(String raw) {
        if (raw == null) {
            throw archiveInvalid("归档存在无名条目");
        }
        // 统一分隔符：Windows 打包工具可能用 "\\"；统一后再做穿越判定，避免绕过
        String name = raw.replace('\\', '/').trim();
        while (name.startsWith("./")) {
            name = name.substring(2);
        }
        if (name.isEmpty() || "/".equals(name)) {
            return "";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw archiveInvalid("归档条目名过长: " + name.substring(0, 64) + "...");
        }
        if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")) {
            throw archiveInvalid("归档条目名不得为绝对路径: " + name);
        }
        String[] segments = name.split("/", -1);
        if (segments.length > MAX_DEPTH) {
            throw archiveInvalid("归档目录层级超限（最多 " + MAX_DEPTH + " 层）: " + name);
        }
        for (String segment : segments) {
            if ("..".equals(segment)) {
                throw archiveInvalid("归档条目名不得包含路径穿越段 '..': " + name);
            }
        }
        return name;
    }

    /**
     * 读取单个条目（带单文件大小上限校验）。
     *
     * @param zip  ZIP 流
     * @param name 条目名（错误明细用）
     * @return 条目字节
     * @throws IOException       读取失败
     * @throws BusinessException 单文件超限（4008）
     */
    private static byte[] readEntry(ZipInputStream zip, String name) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = zip.read(chunk)) > 0) {
            if (buffer.size() + read > MAX_FILE_BYTES) {
                throw archiveInvalid("归档单文件超限（最多 " + MAX_FILE_BYTES + " 字节）: " + name);
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /**
     * 严格 UTF-8 解码（非法字节序列直接拒绝，不静默替换）。
     *
     * @param bytes 条目字节
     * @param name  条目名（错误明细用）
     * @return 解码文本
     * @throws BusinessException 非法 UTF-8（4008）
     */
    private static String decodeUtf8(byte[] bytes, String name) {
        var decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
        try {
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            throw archiveInvalid("归档条目不是合法 UTF-8 文本: " + name);
        }
    }

    /**
     * 写入单个条目（空文本跳过）。
     *
     * @param zip     ZIP 输出流
     * @param name    条目名
     * @param content 文本内容（可空）
     * @throws IOException 写入失败
     */
    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        if (content == null) {
            return;
        }
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    /**
     * 按条目名排序并过滤。
     *
     * @param entries 条目集合
     * @param filter  过滤条件
     * @return 命中的条目名（自然序）
     */
    private static List<String> sortedKeys(Map<String, String> entries,
                                          java.util.function.Predicate<String> filter) {
        Set<String> keys = new LinkedHashSet<>();
        for (String key : entries.keySet()) {
            if (filter.test(key)) {
                keys.add(key);
            }
        }
        List<String> sorted = new ArrayList<>(keys);
        sorted.sort(Comparator.naturalOrder());
        return sorted;
    }

    /**
     * 结构行按 {@code sort} 定序。
     *
     * @param rows   待排序行
     * @param sortOf 排序键提取
     * @param <T>    行类型
     * @return 定序后的新列表
     */
    private static <T> List<T> sorted(List<T> rows, java.util.function.Function<T, Integer> sortOf) {
        List<T> copy = new ArrayList<>(rows);
        copy.sort(Comparator.comparingInt(row -> {
            Integer sort = sortOf.apply(row);
            return sort == null ? Integer.MAX_VALUE : sort;
        }));
        return copy;
    }

    /**
     * 构造归档非法异常（4008）。
     *
     * @param message 明细
     * @return 业务异常
     */
    private static BusinessException archiveInvalid(String message) {
        return new BusinessException(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID, message);
    }

    /**
     * 便捷写出到输出流（流式下载场景：归档体量小，先全量构造再写出，避免半途失败留下残包）。
     *
     * @param out              目标输出流
     * @param archive          ZIP 字节
     * @throws IOException     写出失败
     */
    public void writeTo(OutputStream out, byte[] archive) throws IOException {
        out.write(archive);
        out.flush();
    }
}
