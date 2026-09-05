package com.wymix.initializer.core.conflict;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * {@link ConflictRule} 单元测试（M3.5）：结构校验与触发判定。
 */
class ConflictRuleTest {

    @Test
    void pairRule_bothSelected_triggers() {
        ConflictRule rule = new ConflictRule("r1", ConflictRule.Type.PAIR,
                List.of("a", "b"), "两者互斥");
        assertThat(rule.isTriggeredBy(Set.of("a", "b"))).isTrue();
    }

    @Test
    void pairRule_singleSelected_doesNotTrigger() {
        ConflictRule rule = new ConflictRule("r1", ConflictRule.Type.PAIR,
                List.of("a", "b"), "两者互斥");
        assertThat(rule.isTriggeredBy(Set.of("a"))).isFalse();
        assertThat(rule.isTriggeredBy(Set.of("b", "c"))).isFalse();
    }

    @Test
    void pairRule_extraSelections_stillTriggers() {
        ConflictRule rule = new ConflictRule("r1", ConflictRule.Type.PAIR,
                List.of("a", "b"), "两者互斥");
        assertThat(rule.isTriggeredBy(Set.of("a", "b", "c"))).isTrue();
    }

    @Test
    void groupRule_twoSelected_triggers() {
        ConflictRule rule = new ConflictRule("r2", ConflictRule.Type.GROUP,
                List.of("a", "b", "c"), "组内互斥");
        assertThat(rule.isTriggeredBy(Set.of("a", "c"))).isTrue();
    }

    @Test
    void groupRule_allSelected_triggers() {
        ConflictRule rule = new ConflictRule("r2", ConflictRule.Type.GROUP,
                List.of("a", "b", "c"), "组内互斥");
        assertThat(rule.isTriggeredBy(Set.of("a", "b", "c"))).isTrue();
    }

    @Test
    void groupRule_singleSelected_doesNotTrigger() {
        ConflictRule rule = new ConflictRule("r2", ConflictRule.Type.GROUP,
                List.of("a", "b", "c"), "组内互斥");
        assertThat(rule.isTriggeredBy(Set.of("b"))).isFalse();
        assertThat(rule.isTriggeredBy(Set.of("x", "y"))).isFalse();
    }

    @Test
    void pairRule_wrongStartersCount_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", ConflictRule.Type.PAIR, List.of("a"), "m"))
                .withMessageContaining("pair");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", ConflictRule.Type.PAIR, List.of("a", "b", "c"), "m"));
    }

    @Test
    void groupRule_singleStarter_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", ConflictRule.Type.GROUP, List.of("a"), "m"))
                .withMessageContaining("group");
    }

    @Test
    void missingFields_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule(" ", ConflictRule.Type.PAIR, List.of("a", "b"), "m"))
                .withMessageContaining("id");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", null, List.of("a", "b"), "m"))
                .withMessageContaining("type");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", ConflictRule.Type.PAIR, List.of(), "m"))
                .withMessageContaining("starters");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ConflictRule("r", ConflictRule.Type.PAIR, List.of("a", "b"), " "))
                .withMessageContaining("message");
    }

    @Test
    void typeParse_caseInsensitive() {
        assertThat(ConflictRule.Type.parse("pair")).isEqualTo(ConflictRule.Type.PAIR);
        assertThat(ConflictRule.Type.parse("GROUP")).isEqualTo(ConflictRule.Type.GROUP);
    }

    @Test
    void startersList_immutable() {
        ConflictRule rule = new ConflictRule("r", ConflictRule.Type.PAIR, List.of("a", "b"), "m");
        assertThat(rule.starters()).containsExactly("a", "b");
    }
}
