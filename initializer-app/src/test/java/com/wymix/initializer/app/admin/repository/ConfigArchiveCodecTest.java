package com.wymix.initializer.app.admin.repository;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConfigArchiveCodec} 单元测试（结构化改造 T4）：归档布局提取、条目名安全归一、
 * 单文件与总量上限、严格 UTF-8、归档写出/读回往返。
 */
class ConfigArchiveCodecTest {

    private final ConfigArchiveCodec codec = new ConfigArchiveCodec();

    // ==================== 读取与提取 ====================

    @Test
    void readArchive_extractsDocumentsByLayout() {
        Map<String, String> entries = codec.readArchive(new ByteArrayInputStream(zip(new LinkedHashMap<>(
                Map.of("metadata.yml", "javaVersions: []\nbootVersions: []\n",
                        "starters/a", "id: a\n",
                        "starters/b", "id: b\n",
                        "conflict-rules.yml", "rules: []\n",
                        "templates/pom.xml.ftl", "<project/>",
                        "templates/starters/x/App.java.ftl", "class App {}")))));

        assertThat(codec.metadataOf(entries)).contains("javaVersions");
        assertThat(codec.conflictRulesOf(entries)).contains("rules");
        // starter 文档按条目名自然序拼接为多文档
        assertThat(codec.startersOf(entries)).isEqualTo("id: a\n\n---\nid: b\n");
        // 模板名 = 相对 templates/ 的路径
        assertThat(codec.templatesOf(entries)).contains("pom.xml.ftl").contains("starters/x/App.java.ftl");
    }

    @Test
    void readArchive_missingSections_returnsNulls() {
        Map<String, String> entries = codec.readArchive(new ByteArrayInputStream(
                zip(new LinkedHashMap<>(Map.of("metadata.yml", "javaVersions: []\n")))));
        assertThat(codec.startersOf(entries)).isNull();
        assertThat(codec.templatesOf(entries)).isNull();
        assertThat(codec.conflictRulesOf(entries)).isNull();
    }

    @Test
    void readArchive_normalizesDotSlashAndIgnoresDirectoryEntries() {
        Map<String, String> raw = new LinkedHashMap<>();
        raw.put("./metadata.yml", "javaVersions: []\n");
        Map<String, String> entries = codec.readArchive(new ByteArrayInputStream(zipWithDirectory(raw, "docs/")));
        assertThat(entries).containsOnlyKeys("metadata.yml");
    }

    @Test
    void readArchive_rejectsTraversalAbsoluteAndBackslashNames() {
        for (String evil : List.of("../x.yml", "a/../../x.yml", "/abs/x.yml", "C:/x.yml", "starters\\..\\x")) {
            assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(
                    zip(new LinkedHashMap<>(Map.of("metadata.yml", "a: 1\n", evil, "x"))))))
                    .as("违规条目: %s", evil)
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode())
                                    .isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));
        }
    }

    @Test
    void readArchive_rejectsTooDeepOrTooLongNames() {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i <= ConfigArchiveCodec.MAX_DEPTH; i++) {
            deep.append("d").append(i).append('/');
        }
        deep.append("x.ftl");
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(zip(
                new LinkedHashMap<>(Map.of("metadata.yml", "a: 1\n", deep.toString(), "x"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("层级超限"));

        String longName = "templates/" + "x".repeat(ConfigArchiveCodec.MAX_NAME_LENGTH) + ".ftl";
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(zip(
                new LinkedHashMap<>(Map.of("metadata.yml", "a: 1\n", longName, "x"))))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("条目名过长"));
    }

    @Test
    void readArchive_rejectsEmptyArchiveAndBlankName() {
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(new byte[] {})))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));
    }

    @Test
    void readArchive_rejectsOversizedSingleFile() {
        byte[] big = new byte[ConfigArchiveCodec.MAX_FILE_BYTES + 16];
        java.util.Arrays.fill(big, (byte) 'a');
        Map<String, byte[]> raw = new LinkedHashMap<>();
        raw.put("metadata.yml", "a: 1\n".getBytes(StandardCharsets.UTF_8));
        raw.put("templates/big.ftl", big);
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(zipRaw(raw))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("单文件超限"));
    }

    @Test
    void readArchive_rejectsTotalSizeBomb() {
        // 用高压缩比内容逼近总量上限：解压后总量超限即拒绝
        Map<String, byte[]> raw = new LinkedHashMap<>();
        raw.put("metadata.yml", "a: 1\n".getBytes(StandardCharsets.UTF_8));
        int chunk = ConfigArchiveCodec.MAX_FILE_BYTES;
        byte[] compressed = new byte[chunk];
        java.util.Arrays.fill(compressed, (byte) 'a');
        for (int i = 0; i < 20; i++) {
            raw.put("templates/bomb" + i + ".ftl", compressed);
        }
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(zipRaw(raw))))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessage()).contains("解压总量超限"));
    }

    // ==================== 写出与往返 ====================

    @Test
    void writeArchive_thenRead_roundTripsAllSections() {
        byte[] archive = codec.writeArchive("javaVersions: []\n",
                new LinkedHashMap<>(Map.of("b", "id: b\n", "a", "id: a\n")),
                "rules: []\n",
                new LinkedHashMap<>(Map.of("pom.xml.ftl", "<project/>")));

        Map<String, String> entries = codec.readArchive(new ByteArrayInputStream(archive));
        assertThat(entries.keySet()).containsExactlyInAnyOrder("metadata.yml", "starters/a", "starters/b",
                "conflict-rules.yml", "templates/pom.xml.ftl");

        assertThat(codec.metadataOf(entries)).contains("javaVersions");
        assertThat(codec.startersOf(entries)).contains("id: a").contains("id: b");
        assertThat(codec.conflictRulesOf(entries)).contains("rules");
        assertThat(codec.templatesOf(entries)).contains("pom.xml.ftl");
    }

    @Test
    void writeArchive_skipsEmptySections_butReadRejectsEmptyArchive() {
        byte[] archive = codec.writeArchive(null, null, null, null);
        // 空归档写出合法（0 条目），但读取侧拒绝（无任何可导入内容）
        assertThat(archive).isNotEmpty();
        assertThatThrownBy(() -> codec.readArchive(new ByteArrayInputStream(archive)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_IMPORT_ARCHIVE_INVALID));
    }

    @Test
    void renderMetadata_producesJavaAndBootSections() {
        StructuredSnapshot empty = StructuredSnapshot.empty(ConfigRepository.DRAFT_RELEASE_ID);
        assertThat(codec.renderMetadata(empty)).isNull();

        var boot = new com.wymix.initializer.app.admin.entity.CfgBootVersionEntity();
        boot.setId(-1L);
        boot.setVersionId("3.2.5");
        boot.setLabel("Spring Boot 3.2.5");
        boot.setOfficial(true);
        boot.setFpGroup("com.example");
        boot.setFpArtifact("framework-parent");
        boot.setFpVersion("1.0.0");
        var java = new com.wymix.initializer.app.admin.entity.CfgJavaVersionEntity();
        java.setVersionId("17");
        java.setLabel("Java 17");
        java.setRecommendedBootVersion("3.2.5");
        var matrix = new com.wymix.initializer.app.admin.entity.CfgBootJavaEntity();
        matrix.setBootPk(-1L);
        matrix.setJavaVersionId("17");
        var ref = new com.wymix.initializer.app.admin.entity.CfgBootStarterRefEntity();
        ref.setBootPk(-1L);
        ref.setStarterId("web");

        StructuredSnapshot snapshot = new StructuredSnapshot(0L, List.of(java), List.of(boot),
                List.of(matrix), List.of(ref), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        String yaml = codec.renderMetadata(snapshot);
        assertThat(yaml).contains("javaVersions").contains("17").contains("3.2.5")
                .contains("frameworkParent").contains("framework-parent");
    }

    // ==================== 夹具 ====================

    private static byte[] zip(Map<String, String> entries) {
        Map<String, byte[]> raw = new LinkedHashMap<>();
        entries.forEach((k, v) -> raw.put(k, v.getBytes(StandardCharsets.UTF_8)));
        return zipRaw(raw);
    }

    private static byte[] zipWithDirectory(Map<String, String> entries, String dirName) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                zip.putNextEntry(new ZipEntry(dirName));
                zip.closeEntry();
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] zipRaw(Map<String, byte[]> entries) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(buffer, StandardCharsets.UTF_8)) {
                for (var entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue());
                    zip.closeEntry();
                }
            }
            return buffer.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
