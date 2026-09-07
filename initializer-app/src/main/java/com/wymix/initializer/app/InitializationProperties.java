package com.wymix.initializer.app;

/**
 * 应用级配置键常量。
 *
 * <p>{@code --demo} 内置清单开关：{@link InitializerApplication#main(String[])}
 * 解析命令行参数后以 system property 下发，因此 jar 启动场景下该值必定显式存在，
 * 且优先级高于 application.yml；未经过 main 的场景（单元测试 / {@code @SpringBootTest}）
 * 则回退到 application.yml 的默认值。
 *
 * <p>线程安全性：仅含常量，线程安全。
 */
public final class InitializationProperties {

    /** 内置清单开关的 property 键。 */
    public static final String DEMO_ENABLED_PROPERTY = "initializer.demo.enabled";

    /** 内置清单开关的默认值（未配置且未经 main 下发时）。 */
    public static final boolean DEMO_ENABLED_DEFAULT = true;

    private InitializationProperties() {
        throw new AssertionError("常量类不可实例化");
    }
}
