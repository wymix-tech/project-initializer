package com.wymix.initializer.core.conflict;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ConflictRuleParser} 单元测试（M3.5）：合法配置解析与各类非法配置拒绝。
 */
class ConflictRuleParserTest {

    private static final String VALID_YAML = """
            rules:
              - id: redis-vs-cache-impl
                type: pair
                starters: [xxx-redis-spring-boot-starter, spring-boot-starter-cache]
                message: 两者均注册缓存管理器，可能引发 Bean 覆盖冲突
              - id: custom-web-family
                type: group
                starters: [xxx-web-a, xxx-web-b, xxx-web-c]
                message: 内部 Web 封装二选一
            """;

    @Test
    void parse_validYaml_buildsCatalog() {
        ConflictCatalog catalog = ConflictRuleParser.parseText(VALID_YAML);
        assertThat(catalog.size()).isEqualTo(2);
        var rule = catalog.all().stream()
                .filter(r -> r.id().equals("redis-vs-cache-impl"))
                .findFirst().orElseThrow();
        assertThat(rule.id()).isEqualTo("redis-vs-cache-impl");
        assertThat(rule.type()).isEqualTo(ConflictRule.Type.PAIR);
        assertThat(rule.starters()).containsExactly(
                "xxx-redis-spring-boot-starter", "spring-boot-starter-cache");
        assertThat(rule.message()).contains("缓存管理器");
    }

    @Test
    void parse_nullOrBlank_emptyCatalog() {
        assertThat(ConflictRuleParser.parseText(null).size()).isZero();
        assertThat(ConflictRuleParser.parseText("  ").size()).isZero();
        assertThat(ConflictRuleParser.parse((java.io.InputStream) null).size()).isZero();
    }

    @Test
    void parse_streamInput_equivalent() {
        var stream = new ByteArrayInputStream(VALID_YAML.getBytes(StandardCharsets.UTF_8));
        assertThat(ConflictRuleParser.parse(stream).size()).isEqualTo(2);
    }

    @Test
    void parse_emptyRulesNode_emptyCatalog() {
        assertThat(ConflictRuleParser.parseText("rules: []\n").size()).isZero();
        assertThat(ConflictRuleParser.parseText("{}\n").size()).isZero();
    }

    @Test
    void parse_badYaml_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules: [unclosed"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void parse_topLevelNotMap_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText("- a\n- b\n"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("rules");
    }

    @Test
    void parse_rulesNotList_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules: 3\n"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void parse_entryNotMap_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules: [foo]\n"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED));
    }

    @Test
    void parse_missingFields_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules:\n  - type: pair\n    starters: [a, b]\n    message: m\n"))
                .hasMessageContaining("id");
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules:\n  - id: r\n    starters: [a, b]\n    message: m\n"))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules:\n  - id: r\n    type: pair\n    message: m\n"))
                .hasMessageContaining("starters");
        assertThatThrownBy(() -> ConflictRuleParser.parseText("rules:\n  - id: r\n    type: pair\n    starters: [a, b]\n"))
                .hasMessageContaining("message");
    }

    @Test
    void parse_badType_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText(
                "rules:\n  - id: r\n    type: mutex\n    starters: [a, b]\n    message: m\n"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("mutex");
    }

    @Test
    void parse_pairWithWrongCount_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText(
                "rules:\n  - id: r\n    type: pair\n    starters: [a]\n    message: m\n"))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("pair");
    }

    @Test
    void parse_duplicateRuleId_throws4002() {
        String duplicated = """
                rules:
                  - id: same-id
                    type: pair
                    starters: [a, b]
                    message: m1
                  - id: same-id
                    type: pair
                    starters: [c, d]
                    message: m2
                """;
        assertThatThrownBy(() -> ConflictRuleParser.parseText(duplicated))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONFIG_VALIDATION_FAILED))
                .hasMessageContaining("ID 重复");
    }

    @Test
    void parse_blankStarterItem_throws4002() {
        assertThatThrownBy(() -> ConflictRuleParser.parseText(
                "rules:\n  - id: r\n    type: pair\n    starters: [a, \" \"]\n    message: m\n"))
                .hasMessageContaining("starters");
    }
}
