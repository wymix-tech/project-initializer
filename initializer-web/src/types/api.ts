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
  /** 基线版本：内部 starter 展示清单声明版本；官方 starter 为 null（走 Boot BOM） */
  version: string | null
  /** 是否必选（M3.6）：true 时默认勾选且禁止取消，生成时后端兜底自动补齐 */
  required: boolean
  /** 依赖联动（M3.6）：选中本 starter 时自动带上的 starter id（后端会求传递闭包） */
  requires: string[]
}

/** 冲突规则清单项：GET /api/metadata/conflict-rules（M3.5） */
export interface ConflictRule {
  /** 规则 ID */
  id: string
  /** 规则类型：pair（id 对互斥）/ group（组内互斥） */
  type: 'pair' | 'group'
  /** 规则涉及的 starter ID */
  starters: string[]
  /** 冲突提示文案 */
  message: string
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

/** 管理端草稿视图：GET /api/admin/config/{kind} */
export interface AdminDraftView {
  kind: string
  contentYaml: string | null
  version: number | null
  updatedAt: string | null
}

/** 管理端发布结果：POST /api/admin/config/publish 与 rollback */
export interface AdminPublishResult {
  releaseId: number
  javaVersionCount: number
  starterCount: number
  templateOverrideCount: number
  conflictRuleCount: number
}

/** 管理端发布历史项：GET /api/admin/releases */
export interface AdminRelease {
  id: number
  releasedBy: string | null
  releasedAt: string | null
  remark: string | null
}

/** 管理端审计项：GET /api/admin/audits */
export interface AdminAudit {
  id: number
  action: string
  targetKind: string | null
  operator: string | null
  detail: string | null
  createdAt: string | null
}

/** 管理端配置草稿种类 */
export type AdminConfigKind = 'metadata' | 'starter' | 'conflict-rule' | 'template'
