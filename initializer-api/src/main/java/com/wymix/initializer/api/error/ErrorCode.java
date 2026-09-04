package com.wymix.initializer.api.error;

/**
 * 平台统一错误码枚举。
 *
 * <p>所有业务错误码统一收口于此枚举，禁止在业务代码中使用魔法字符串。
 * 错误码分段约定：
 * <ul>
 *   <li>10xx —— 参数校验类</li>
 *   <li>20xx —— 元数据/兼容性类</li>
 *   <li>30xx —— 生成引擎类</li>
 *   <li>40xx —— 管理配置类（M3.1）</li>
 *   <li>90xx —— 系统内部错误</li>
 * </ul>
 *
 * <p>线程安全性：枚举实例天然线程安全。
 */
public enum ErrorCode {

    /** 元信息参数非法（Group/Artifact/Package 等不满足命名规范） */
    INVALID_METADATA(1001, "元信息校验失败"),

    /** 版本组合不兼容（如 Java 8 搭配 Boot 3.x） */
    INCOMPATIBLE_VERSION(2001, "版本组合不兼容"),

    /** 请求的版本或元数据不存在 */
    METADATA_NOT_FOUND(2002, "请求的元数据不存在"),

    /** 请求的 starter 不在所选 Boot 版本清单内（M2.0 设计对齐：与 2002 元数据缺失区分） */
    UNKNOWN_STARTER(2003, "请求的 starter 不存在"),

    /** 生成过程中模板渲染失败 */
    TEMPLATE_RENDER_FAILED(3001, "模板渲染失败"),

    /** 模板产物路径冲突（多个 starter 写同一 target 路径） */
    TEMPLATE_TARGET_CONFLICT(3002, "模板产物路径冲突"),

    /** zip 打包失败 */
    ZIP_PACK_FAILED(3003, "产物打包失败"),

    /** application.yml 片段合并冲突（同 key 不同值，多 starter 同时选中场景） */
    YML_MERGE_CONFLICT(3004, "application.yml 片段合并冲突"),

    /** 构建工具取值非法（仅允许 maven / gradle） */
    INVALID_BUILD_TOOL(1004, "构建工具取值非法"),

    /** starter 清单配置非法（加载/校验失败，启动即失败） */
    STARTER_CATALOG_INVALID(9002, "starter 清单配置非法"),

    /** 管理端草稿不存在（kind 非法或未初始化） */
    CONFIG_DRAFT_NOT_FOUND(4001, "配置草稿不存在"),

    /** 发布前校验失败（明细随异常文案输出，草稿保留可继续编辑） */
    CONFIG_VALIDATION_FAILED(4002, "配置发布校验失败"),

    /** 草稿乐观锁冲突：草稿已被他人修改，需刷新后重试 */
    CONFIG_CONCURRENT_MODIFY(4003, "配置草稿已被他人修改，请刷新后重试"),

    /** 未授权访问管理接口（404/4004 语义） */
    FORBIDDEN(4004, "未授权访问"),

    /** 配置热刷新失败（保留旧快照，明细随异常文案输出） */
    CONFIG_REFRESH_FAILED(4006, "配置热刷新失败"),

    /** 系统内部未知错误 */
    INTERNAL_ERROR(9001, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    /** @return 数字错误码，对外 API 唯一标识 */
    public int getCode() {
        return code;
    }

    /** @return 默认错误描述，可被 BusinessException 覆盖为更具体的上下文信息 */
    public String getMessage() {
        return message;
    }
}
