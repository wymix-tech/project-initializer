/**
 * 后端接口契约类型定义，与 initializer-app 各 Controller DTO 一一对应。
 */

/** Java 版本清单项：GET /api/metadata/java-versions */
export interface JavaVersion {
  id: string
  label: string
  deprecated: boolean
  recommendedBootVersion: string | null
}

/** Boot 版本清单项：GET /api/metadata/boot-versions?java= */
export interface BootVersion {
  id: string
  label: string
  official: boolean
}

/** starter 清单项：GET /api/metadata/starters?boot=&q=&category= */
export interface Starter {
  id: string
  label: string
  /** 维护方：official 官方 / internal 内部 */
  maintainer: 'official' | 'internal'
  /** 分类（M3.4）：web / data / middleware / tool；清单未登记时为 null */
  category: string | null
  /** 描述（M3.4）：清单未登记时为 null */
  description: string | null
}

/** 元信息校验结果：POST /api/validate */
export interface ValidationResult {
  valid: boolean
  errors: Record<string, string> | null
}

/** 包名推导响应：GET /api/derive-package */
export interface DerivePackageResponse {
  packageName: string
}

/** 构建工具：maven（默认）/ gradle */
export type BuildTool = 'maven' | 'gradle'

/** 生成请求体：POST /api/generate */
export interface GenerateRequest {
  group: string
  artifact: string
  packageName: string
  javaVersion: string
  bootVersion: string
  starters: string[]
  /** 构建工具（M2.4）：缺省由后端回退 maven */
  buildTool?: BuildTool
}

/** 后端统一错误体：ErrorResponse{code,message,timestamp} */
export interface ErrorResponse {
  code: number
  message: string
  timestamp: number
}

/** 四步表单的元信息表单数据 */
export interface ProjectMeta {
  group: string
  artifact: string
  packageName: string
  packageTouched: boolean
  /** 构建工具（M2.4）：默认 maven */
  buildTool: BuildTool
}
