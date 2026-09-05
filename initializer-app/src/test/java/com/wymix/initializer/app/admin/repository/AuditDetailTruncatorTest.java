package com.wymix.initializer.app.admin.repository;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AuditDetailTruncator} 单元测试：null/边界/超长截断与省略标记。
 */
class AuditDetailTruncatorTest {

    @Test
    void nullDetail_returnsNull() {
        assertThat(AuditDetailTruncator.truncate(null)).isNull();
    }

    @Test
    void detailAtLimit_returnsUnchanged() {
        String detail = "x".repeat(AuditDetailTruncator.MAX_LENGTH);
        assertThat(AuditDetailTruncator.truncate(detail)).isSameAs(detail);
    }

    @Test
    void oversizedDetail_truncatedWithMarker_andWithinLimit() {
        String detail = "长".repeat(2000);
        String result = AuditDetailTruncator.truncate(detail);
        assertThat(result).hasSize(AuditDetailTruncator.MAX_LENGTH);
        assertThat(result).endsWith("…(已截断)");
        assertThat(result).startsWith("长长长");
    }
}
