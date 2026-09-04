package com.wymix.initializer.app.admin.refresh;

import com.wymix.initializer.api.error.BusinessException;
import com.wymix.initializer.api.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 快照持有器（M3.1/M3.2）：{@link AtomicReference} 持有当前生效快照，发布成功后原子切换。
 *
 * <p>读路径无锁：生成/元数据接口随时读取当前引用；刷新失败不触碰引用，
 * 旧快照继续服务（热刷新不中断生成）。启动期由装配层 {@code seed} 写入
 * classpath 内置清单快照作为兜底，保证读路径自启动起始终可用。
 *
 * <p>线程安全性：AtomicReference 原子语义 + 不可变快照，线程安全。
 */
@Component
public class CatalogSnapshotHolder {

    private final AtomicReference<CatalogSnapshot> snapshotRef = new AtomicReference<>();

    /**
     * @return 当前生效快照；应用启动后尚未播种时为 {@code null}
     */
    public CatalogSnapshot current() {
        return snapshotRef.get();
    }

    /**
     * M3.2：读取当前生效快照，未播种时抛系统错误（读路径契约：启动后始终有快照）。
     *
     * @return 当前生效快照
     * @throws BusinessException 快照未初始化（错误码 {@link ErrorCode#INTERNAL_ERROR}）
     */
    public CatalogSnapshot requireCurrent() {
        CatalogSnapshot snapshot = snapshotRef.get();
        if (snapshot == null) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "配置快照未初始化");
        }
        return snapshot;
    }

    /**
     * 启动期播种：写入 classpath 内置清单快照（兜底语义，仅装配层调用一次）。
     *
     * @param snapshot 初始快照
     */
    public void seed(CatalogSnapshot snapshot) {
        snapshotRef.set(snapshot);
    }

    /**
     * 原子切换快照（仅发布成功路径调用）。
     *
     * @param snapshot 新快照
     */
    public void swap(CatalogSnapshot snapshot) {
        snapshotRef.set(snapshot);
    }
}
