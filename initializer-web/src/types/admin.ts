/**
 * 管理端结构化与归档契约类型（T6）：与 initializer-app 的
 * `admin.dto.CatalogDtos` / `admin.dto.ConfigArchiveDtos` 一一对应。
 *
 * <p>与 {@code types/api.ts} 的边界：`api.ts` 承载「生成向导 + YAML 契约」的对外类型，
 * 本文件只承载 T3 结构化契约（`/api/admin/{metadata,starters,conflict-rules,templates}`）
 * 与 T4 归档契约（`/api/admin/config/{export,import}`）。两套契约读写同一批
 * `release_id = 0` 草稿行，但**类型互不混用**。
 *
 * <p>草稿宽松 / 发布严格：结构化保存只做字段级与结构级校验；跨模块引用与图合法性
 * 统一在发布期收敛为 4002 明细，因此本文件中的可空字段（如 `recommendedBootVersion`）
 * 允许悬空值，前端不做本地强校验、只做必填提示。
 */

// ==================== 元数据 ====================

/** Java 版本项：`CatalogDtos.JavaVersionDto`。 */
export interface JavaVersionDto {
  /** Java 版本 ID（如 "17"），同 release 内唯一 */
  id: string
  /** 展示名（后端空值按 `Java <id>` 兜底） */
  label: string | null
  /** 是否已废弃 */
  deprecated: boolean | null
  /** 推荐 Boot 版本 ID（可空；悬空值发布期才拒） */
  recommendedBootVersion: string | null
  /** 展示顺序（可空，缺省按提交顺序） */
  sort: number | null
}

/** 框架父 POM 三字段（缺一不可，整体可空）。 */
export interface FrameworkParentDto {
  groupId: string | null
  artifactId: string | null
  version: string | null
}

/** Boot 版本项：`CatalogDtos.BootVersionDto`。 */
export interface BootVersionDto {
  /** Boot 版本 ID（如 "3.2.5"），同 release 内唯一 */
  id: string
  /** 展示名（后端空值按 `Spring Boot <id>` 兜底） */
  label: string | null
  /** 是否官方发布版本 */
  official: boolean | null
  /** 兼容的 Java 版本 ID 清单 */
  supportedJavaVersions: string[]
  /** 可选 starter 白名单（空 = 不设白名单） */
  starters: string[]
  /** 框架父 POM（null = 回退官方父 POM） */
  frameworkParent: FrameworkParentDto | null
  /** 展示顺序 */
  sort: number | null
}

/** 元数据模块视图：`GET /api/admin/metadata`。 */
export interface MetadataView {
  javaVersions: JavaVersionDto[]
  bootVersions: BootVersionDto[]
}

/** 元数据整体提交请求：`PUT /api/admin/metadata`。 */
export interface MetadataUpsertRequest {
  javaVersions: JavaVersionDto[]
  bootVersions: BootVersionDto[]
  operator: string
}

// ==================== starter 清单 ====================

/** 依赖坐标（GAV；`version` 可空 = 走 Boot BOM）。 */
export interface DependencyDto {
  groupId: string | null
  artifactId: string | null
  version: string | null
}

/** 模板联动项（source → target）。 */
export interface TemplateLinkageDto {
  source: string | null
  target: string | null
}

/** application.yml 片段联动项（mergeStrategy = append / override）。 */
export interface YmlFragmentDto {
  source: string | null
  mergeStrategy: string | null
}

/** 抽象类联动项。 */
export interface AbstractClassDto {
  fqcn: string | null
  alias: string | null
}

/** starter 联动规则（三类联动）。 */
export interface LinkageDto {
  templates: TemplateLinkageDto[]
  ymlFragments: YmlFragmentDto[]
  abstractClasses: AbstractClassDto[]
}

/**
 * starter 项（读写同构）：`CatalogDtos.StarterDto`。
 *
 * `requires` 为 AND-of-OR 分组：单元素分组 = 强制依赖（前端自动勾选并锁定），
 * 多元素分组 = OR（仅提示）。`hidden` 直落 `cfg_starter.hidden`（PR #40 语义），
 * 表单必须提供开关，否则该字段会在保存时被静默丢弃。
 */
export interface StarterDto {
  /** starter ID，同 release 内唯一 */
  id: string
  /** 分类：web / data / middleware / tool */
  category: string | null
  /** 维护方：official / internal（大小写不敏感） */
  maintainer: string | null
  /** 展示描述（同时作为前端展示名与检索关键字） */
  description: string | null
  /** 是否必选：true 时前端默认勾选且禁止取消 */
  required: boolean | null
  /** 是否在前端依赖选择列表中默认隐藏 */
  hidden: boolean | null
  /** 配置类目标包名（可空） */
  configPackage: string | null
  /** 依赖坐标清单 */
  dependencies: DependencyDto[]
  /** 依赖联动分组（AND-of-OR） */
  requires: string[][]
  /** 关联 Boot 版本 ID 清单（空 = 全部版本可用） */
  bootVersions: string[]
  /** 联动规则 */
  linkages: LinkageDto | null
  /** 展示顺序 */
  sort: number | null
}

/** starter 清单视图：`GET /api/admin/starters`。 */
export interface StarterCatalogView {
  starters: StarterDto[]
}

/** starter 清单整体提交请求：`PUT /api/admin/starters`。 */
export interface StarterCatalogUpsertRequest {
  starters: StarterDto[]
  operator: string
}

// ==================== 冲突规则 ====================

/** 冲突规则项：`CatalogDtos.ConflictRuleDto`。 */
export interface ConflictRuleDto {
  /** 规则唯一 ID */
  id: string
  /** 规则类型：pair（恰好两个互斥）/ group（两个及以上互斥） */
  type: string | null
  /** 涉及 starter ID 清单 */
  starters: string[]
  /** 冲突提示文案 */
  message: string | null
  /** 展示顺序 */
  sort: number | null
}

/** 冲突规则视图：`GET /api/admin/conflict-rules`。 */
export interface ConflictRuleView {
  rules: ConflictRuleDto[]
}

/** 冲突规则整体提交请求：`PUT /api/admin/conflict-rules`。 */
export interface ConflictRuleUpsertRequest {
  rules: ConflictRuleDto[]
  operator: string
}

// ==================== 模板 ====================

/** 模板列表项（**不含 content CLOB**）：`GET /api/admin/templates`。 */
export interface TemplateSummary {
  name: string
  updatedAt: string | null
  sort: number | null
}

/** 模板列表视图。 */
export interface TemplateListView {
  templates: TemplateSummary[]
}

/** 模板详情（含全文）：`GET /api/admin/templates/{name}`。 */
export interface TemplateDetail {
  name: string
  content: string | null
  updatedAt: string | null
  sort: number | null
}

/** 模板集整体提交请求（全量替换）。 */
export interface TemplateCatalogUpsertRequest {
  templates: TemplateDetail[]
  operator: string
}

// ==================== 通用 ====================

/** 结构化草稿保存结果：`CatalogDtos.DraftSaveResult`。 */
export interface DraftSaveResult {
  /** 配置种类（小写中划线形式） */
  kind: string
  /** 实际写入的结构行数 */
  rows: number
  /** 保存后的草稿版本（kind 级乐观锁） */
  version: number | null
  /** 规模摘要（不含模板全文） */
  summary: string
}

/** 导入结果视图：`ConfigArchiveDtos.ImportView`。 */
export interface ImportView {
  /** 写入的结构行合计 */
  rows: number
  /** 各 kind 导入后的版本（kind 小写中划线名 → version） */
  versions: Record<string, number>
  /** 规模摘要 */
  summary: string
}

/** 管理端分区导航键（与表单工作台分区一一对应）。 */
export type AdminSection = 'publish' | 'metadata' | 'starter' | 'conflict-rule' | 'template' | 'transfer'

/** 结构化模块键（有独立草稿与乐观锁版本的四个 kind）。 */
export type AdminModuleKind = 'metadata' | 'starter' | 'conflict-rule' | 'template'

export interface ImportPreview {
  previewId: string
  versions: Record<string, number>
  expiresAt: number
  changes: { path: string; action: 'ADD' | 'UPDATE' | 'DELETE'; before: string | null; after: string | null }[]
}
