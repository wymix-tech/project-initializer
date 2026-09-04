package com.wymix.initializer.app.admin.refresh;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 快照持有器（M3.1）：{@link AtomicReference} 持有当前生效快照，发布成功后原子切换。
 *
 * <p>读路径无锁：生成/元数据接口随时读取当前引用；刷新失败不触碰引用，
 * 旧快照继续服务（热刷新不中断生成）。
 *
 * <p>线程安全性：AtomicReference 原子语义 + 不可变快照，线程安全。
 */
@Component
public class CatalogSnapshotHolder {

    private final AtomicReference<CatalogSnapshot> snapshotRef = new AtomicReference<>();

    /**
     * @return 当前生效快照；应用启动后尚未发布过时为 {@code null}
     */
    public CatalogSnapshot current() {
        return snapshotRef.get();
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
