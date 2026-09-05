package com.wymix.initializer.core.conflict;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link ConflictCatalog} 单元测试（M3.5）：规则目录、ID 重复校验与触发求值。
 */
class ConflictCatalogTest {

    @Test
    void emptyCatalog_noConflicts() {
        assertThat(ConflictCatalog.empty().size()).isZero();
        assertThat(ConflictCatalog.empty().all()).isEmpty();
        assertThat(ConflictCatalog.empty().evaluate(Set.of("a", "b"))).isEmpty();
    }

    @Test
    void evaluate_returnsOnlyTriggered() {
        ConflictCatalog catalog = new ConflictCatalog(List.of(
                new ConflictRule("r1", ConflictRule.Type.PAIR, List.of("a", "b"), "A 与 B 互斥"),
                new ConflictRule("r2", ConflictRule.Type.PAIR, List.of("c", "d"), "C 与 D 互斥")));
        var conflicts = catalog.evaluate(Set.of("a", "b"));
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.get(0).ruleId()).isEqualTo("r1");
        assertThat(conflicts.get(0).message()).isEqualTo("A 与 B 互斥");
        assertThat(conflicts.get(0).starters()).containsExactly("a", "b");
    }

    @Test
    void evaluate_multipleTriggers() {
        ConflictCatalog catalog = new ConflictCatalog(List.of(
                new ConflictRule("r1", ConflictRule.Type.PAIR, List.of("a", "b"), "A 与 B 互斥"),
                new ConflictRule("r2", ConflictRule.Type.PAIR, List.of("b", "c"), "B 与 C 互斥")));
        assertThat(catalog.evaluate(Set.of("a", "b", "c")))
                .extracting(ConflictCatalog.TriggeredConflict::ruleId)
                .containsExactlyInAnyOrder("r1", "r2");
    }

    @Test
    void duplicateRuleId_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictCatalog(List.of(
                        new ConflictRule("r1", ConflictRule.Type.PAIR, List.of("a", "b"), "m1"),
                        new ConflictRule("r1", ConflictRule.Type.PAIR, List.of("c", "d"), "m2"))))
                .withMessageContaining("ID 重复");
    }
}
