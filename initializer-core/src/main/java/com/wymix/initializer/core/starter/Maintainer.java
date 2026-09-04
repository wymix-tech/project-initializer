package com.wymix.initializer.core.starter;

/**
 * starter 维护方：官方 starter 与公司内部 starter 统一入清单（M2.1 要求）。
 *
 * <p>线程安全性：枚举实例天然线程安全。
 */
public enum Maintainer {
    /** Spring 官方 starter */
    OFFICIAL,
    /** 公司内部 starter */
    INTERNAL
}
