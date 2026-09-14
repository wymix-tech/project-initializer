package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link YamlStarterParser} 分支补测（T5 覆盖率补齐）：
 * 重点覆盖 {@code requires} 三种写法与全部非法形态、{@code bootVersions} 四种形态、
 * 以及 maintainer / 描述等可选字段的回退分支。
 *
 * <p>补测动机：core 分支覆盖率门禁 0.80 在 `yaml2Db` 基线既未达标（0.72），
 * T5 顺手把本类（原本 45/90）与 sync 包（原本 0）补齐，避免门禁继续恶化。
 */
class YamlStarterParserBranchesTest {

    private static StarterCatalogEntry parse(String yaml) {
        return YamlStarterParser.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static BusinessException parseFailure(String yaml) {
        return (BusinessException) org.assertj.core.api.Assertions
                .catchThrowable(() -> parse(yaml));
    }

    private static String base(String extra) {
        return """
                id: branch-starter
                category: tool
                maintainer: internal
                description: 分支用例
                dependencies:
                  - groupId: com.company
                    artifactId: branch
                linkages: {}
                """ + extra;
    }

    // ==================== requires：三种合法写法 ====================

    @Test
    void requires_singleString_becomesSingleGroup() {
        StarterCatalogEntry entry = parse(base("requires: \"other-starter\"\n"));
        assertThat(entry.requires()).containsExactly(java.util.List.of("other-starter"));
    }

    @Test
    void requires_flatStringList_becomesAndGroups() {
        StarterCatalogEntry entry = parse(base("requires: [\"a-starter\", \"b-starter\"]\n"));
        assertThat(entry.requires()).containsExactly(
                java.util.List.of("a-starter"), java.util.List.of("b-starter"));
    }

    @Test
    void requires_nestedList_becomesOrGroups() {
        StarterCatalogEntry entry = parse(base("requires:\n  - [\"a-starter\", \"b-starter\"]\n  - [\"c-starter\"]\n"));
        assertThat(entry.requires()).containsExactly(
                java.util.List.of("a-starter", "b-starter"), java.util.List.of("c-starter"));
    }

    @Test
    void requires_emptyList_yieldsNoGroups() {
        StarterCatalogEntry entry = parse(base("requires: []\n"));
        assertThat(entry.requires()).isEmpty();
    }

    @Test
    void requires_absent_yieldsNoGroups() {
        assertThat(parse(base("")).requires()).isEmpty();
    }

    @Test
    void requires_flatEntriesAreTrimmed() {
        StarterCatalogEntry entry = parse(base("requires: [\"  a-starter  \"]\n"));
        assertThat(entry.requires()).containsExactly(java.util.List.of("a-starter"));
    }

    // ==================== requires：全部非法形态 ====================

    @Test
    void requires_blankSingleString_throws9002() {
        assertThat(parseFailure(base("requires: \"   \"\n")).getErrorCode())
                .isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
    }

    @Test
    void requires_nonListNonString_throws9002() {
        assertThat(parseFailure(base("requires: 123\n")).getErrorCode())
                .isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
    }

    @Test
    void requires_mixedStringAndList_throws9002WithMixedHint() {
        assertThat(parseFailure(base("requires:\n  - \"a-starter\"\n  - [\"b-starter\"]\n")).getMessage())
                .contains("不支持混合");
    }

    @Test
    void requires_flatBlankEntry_throws9002() {
        assertThat(parseFailure(base("requires: [\"\"]\n")).getErrorCode())
                .isEqualTo(ErrorCode.STARTER_CATALOG_INVALID);
    }

    @Test
    void requires_emptyOrGroup_throws9002() {
        assertThat(parseFailure(base("requires:\n  - []\n")).getMessage())
                .contains("OR 组不能为空列表");
    }

    @Test
    void requires_nonStringInsideOrGroup_throws9002() {
        assertThat(parseFailure(base("requires:\n  - [123]\n")).getMessage())
                .contains("OR 组内必须全部为字符串");
    }

    @Test
    void requires_blankInsideOrGroup_throws9002() {
        assertThat(parseFailure(base("requires:\n  - [\"  \"]\n")).getMessage())
                .contains("OR 组内含空字符串");
    }

    // ==================== bootVersions：四种形态 ====================

    @Test
    void bootVersions_absent_yieldsEmpty() {
        assertThat(parse(base("")).bootVersions()).isEmpty();
    }

    @Test
    void bootVersions_singleString_isAccepted() {
        assertThat(parse(base("bootVersion: \"3.2.5\"\n")).bootVersions()).containsExactly("3.2.5");
    }

    @Test
    void bootVersions_blankString_yieldsEmpty() {
        assertThat(parse(base("bootVersions: \"  \"\n")).bootVersions()).isEmpty();
    }

    @Test
    void bootVersions_list_isTrimmedAndBlankFiltered() {
        assertThat(parse(base("bootVersions: [\" 3.2.5 \", \"  \", \"2.7.18\"]\n")).bootVersions())
                .containsExactly("3.2.5", "2.7.18");
    }

    @Test
    void bootVersions_illegalType_throws9002() {
        assertThat(parseFailure(base("bootVersions: 32\n")).getMessage())
                .contains("必须为列表或字符串");
    }

    // ==================== maintainer / 描述 ====================

    @Test
    void maintainer_officialCaseInsensitiveWithDash_parses() {
        String yaml = """
                id: m-starter
                category: web
                maintainer: OFFICIAL
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """;
        assertThat(parse(yaml).maintainer()).isEqualTo(Maintainer.OFFICIAL);
    }

    @Test
    void maintainer_illegalValue_throws9002() {
        String yaml = """
                id: m-starter
                category: web
                maintainer: vendor
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """;
        assertThat(parseFailure(yaml).getMessage()).contains("maintainer 非法");
    }

    @Test
    void blankDescription_becomesEmpty() {
        // description 缺省/空白 → 空字符串（展示名由 MetadataCatalog 装载期与 id 联动填充）
        String yaml = """
                id: no-desc-starter
                category: web
                maintainer: internal
                description: "  "
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """;
        assertThat(parse(yaml).description()).isEmpty();
    }

    @Test
    void absentDescription_becomesEmpty() {
        String yaml = """
                id: no-desc-starter
                category: web
                maintainer: internal
                dependencies:
                  - groupId: g
                    artifactId: a
                linkages: {}
                """;
        assertThat(parse(yaml).description()).isEmpty();
    }
}
