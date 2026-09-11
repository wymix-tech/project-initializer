package com.wymix.initializer.app.admin.refresh;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用 MyBatis 语句计数器（性能红线断言）：统计一次装配实际发出的 SQL 条数，
 * 用于验证「子表按 release_id 批量查后在内存分组」而非逐行查库（N+1）。
 *
 * <p>只拦截查询（{@code Executor#query}），以 {@link AtomicInteger} 累计；
 * 测试用例在装配前 {@link #reset()}、装配后读取 {@link #count()}。
 *
 * <p>线程安全性：AtomicInteger 计数，可在并发用例中安全使用。
 */
@Intercepts({
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class,
                        org.apache.ibatis.cache.CacheKey.class, org.apache.ibatis.mapping.BoundSql.class})
})
public class SqlStatementCounter implements Interceptor {

    private final AtomicInteger count = new AtomicInteger();

    /** 归零计数。 */
    public void reset() {
        count.set(0);
    }

    /** @return 累计查询条数 */
    public int count() {
        return count.get();
    }

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        count.incrementAndGet();
        return invocation.proceed();
    }
}
