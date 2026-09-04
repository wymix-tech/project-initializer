package com.wymix.initializer.core.starter;

/**
 * application.yml 片段合并冲突明细：完整 key path + 两侧值。
 *
 * <p>冲突显式失败原则：合并器不静默覆盖，冲突清单随 3004 异常输出。
 *
 * <p>线程安全性：不可变对象，线程安全。
 */
public record YmlConflict(
        /** 冲突 key 的完整路径，如 {@code spring.redis.host} */
        String path,
        /** 先合并方的值 */
        String existingValue,
        /** 后合并方的值 */
        String incomingValue,
        /** 后合并方来源标识（如片段 classpath 路径） */
        String incomingSource) {
}
