package com.wymix.initializer.core.starter;

import com.wymix.initializer.api.error.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YmlFragmentRendererTest {

    private Map<String, Object> render(String text, Map<String, Object> model) {
        return YmlFragmentRenderer.render(text, "fragment.yaml",
                YmlFragmentLinkage.MergeStrategy.APPEND, model).parsed();
    }

    @Test
    void replacementMustNotChangeYamlStructureTypesOrInterpretInsertedPlaceholders() {
        String value = "a: b\nnext: \"quoted\" # comment \\ $1 ${group}";
        Map<String, Object> tree = render("""
                plain: ${artifact}
                single: '${artifact}'
                double: "${artifact}"
                list: [${artifact}, "${artifact}"]
                nested:
                  value: ${artifact}
                flag: true
                count: 42
                empty: null
                numeric: ${javaVersion}
                "${artifact}": value
                """, Map.of("artifact", value, "group", "must-not-replace", "javaVersion", "17"));
        assertThat(tree).containsEntry("plain", value).containsEntry("single", value)
                .containsEntry("double", value).containsEntry("flag", true).containsEntry("count", 42)
                .containsEntry("empty", null).containsEntry("numeric", "17").containsEntry(value, "value");
        assertThat(tree.get("list")).isEqualTo(List.of(value, value));
        assertThat(tree.get("nested")).isEqualTo(Map.of("value", value));
    }

    @Test
    void runtimeAndEscapedParametersRemainLiteralInAllQuotingStyles() {
        Map<String, Object> tree = render("""
                plain: \\${artifact}
                single: '\\${artifact}'
                double: "\\${artifact}"
                runtime: ${HOST:localhost}
                unknown: ${HOST}
                collection: ${starters}
                nested: "${HOST:${artifact}}"
                """, Map.of("artifact", "demo", "starters", List.of("web")));
        assertThat(tree).containsEntry("plain", "${artifact}").containsEntry("single", "${artifact}")
                .containsEntry("double", "${artifact}").containsEntry("runtime", "${HOST:localhost}")
                .containsEntry("unknown", "${HOST}").containsEntry("collection", "${starters}")
                .containsEntry("nested", "${HOST:demo}");
    }

    @Test
    void duplicateResolvedKeysFailExplicitly() {
        assertThatThrownBy(() -> render("${artifact}: first\ndemo: second\n", Map.of("artifact", "demo")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("参数替换后键冲突");
    }

    @Test
    void emptyYamlIsAllowedAndMalformedYamlStillFails() {
        assertThat(render("", Map.of())).isNull();
        assertThatThrownBy(() -> render("broken: [", Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("片段解析失败");
    }
}
