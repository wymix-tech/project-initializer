/**
 * starter 配置向导表单模型：与 catalog/starters/*.yml 字段一一对应。
 *
 * 该类型仅描述管理端向导的表单状态，生成器后端契约由 YamlStarterParser 校验，
 * 故字段命名保持与 starter yml 一致（如 config-package / application-yml-fragments）。
 */

/** starter 分类（与后端清单 category 枚举一致，前端默认选项）。 */
export type StarterCategory = 'web' | 'data' | 'middleware' | 'tool'

/** 维护方：official 官方 / internal 内部。 */
export type StarterMaintainer = 'official' | 'internal'

/** yml 片段合并策略：append 追加去重 / override 整体替换。 */
export type FragmentMergeStrategy = 'append' | 'override'

/** 依赖坐标行（GAV，version 可空——官方 starter 由 Boot BOM 管理）。 */
export interface DependencyRow {
  /** 数据行 id（仅前端追踪，不参与 YAML 渲染） */
  rowId: string
  groupId: string
  artifactId: string
  /** 版本：内部 starter 显式声明；官方 starter 留空 */
  version: string
}

/** 模板联动行：source（classpath 模板路径）→ target（生成项目目标路径）。 */
export interface TemplateLinkageRow {
  rowId: string
  source: string
  target: string
}

/** application.yml 片段联动行。 */
export interface YmlFragmentRow {
  rowId: string
  source: string
  mergeStrategy: FragmentMergeStrategy
}

/** 集成抽象类联动行。 */
export interface AbstractClassRow {
  rowId: string
  fqcn: string
  alias: string
}

/**
 * starter 配置向导完整表单状态。
 *
 * 设计为可序列化纯数据，YAML 渲染由 {@link buildStarterYaml} 纯函数完成，
 * 便于单测覆盖边界（空联动、多依赖、占位符校验等）。
 */
export interface StarterConfigForm {
  /** starter ID，全局唯一（生成项目引用键） */
  id: string
  /** 分类 */
  category: StarterCategory
  /** 维护方 */
  maintainer: StarterMaintainer
  /** 展示描述（可空，渲染时缺省取 id） */
  description: string
  /** 是否必选（M3.6）：true 时前端默认勾选且禁止取消，生成时后端兜底补齐 */
  required: boolean
  /** 是否在前端依赖选择列表中默认隐藏（M3.8，PR #40 语义）：直落 cfg_starter.hidden */
  hidden: boolean
  /** 依赖联动分组（AND-of-OR）：单元素组 = 强制依赖，多元素组 = OR 提示 */
  requires: string[][]
  /** 关联 Boot 版本 ID 清单（空 = 全部版本可用） */
  bootVersions: string[]
  /** 依赖坐标清单（至少一条） */
  dependencies: DependencyRow[]
  /** 联动：配置类目标包名（支持 {basePackage} 等占位符，可空） */
  configPackage: string
  /** 联动：模板清单 */
  templates: TemplateLinkageRow[]
  /** 联动：application.yml 片段清单 */
  ymlFragments: YmlFragmentRow[]
  /** 联动：集成抽象类清单 */
  abstractClasses: AbstractClassRow[]
}

/** 新建空白表单（含一条空依赖行，降低多选门槛）。 */
export function emptyStarterConfigForm(): StarterConfigForm {
  return {
    id: '',
    category: 'web',
    maintainer: 'official',
    description: '',
    required: false,
    hidden: false,
    requires: [],
    bootVersions: [],
    dependencies: [makeDependencyRow()],
    configPackage: '',
    templates: [],
    ymlFragments: [],
    abstractClasses: [],
  }
}

/** 生成带前端追踪 id 的依赖行。 */
export function makeDependencyRow(): DependencyRow {
  return { rowId: rid(), groupId: '', artifactId: '', version: '' }
}

/** 生成带前端追踪 id 的模板行。 */
export function makeTemplateRow(): TemplateLinkageRow {
  return { rowId: rid(), source: '', target: '' }
}

/** 生成带前端追踪 id 的 yml 片段行。 */
export function makeYmlFragmentRow(): YmlFragmentRow {
  return { rowId: rid(), source: '', mergeStrategy: 'append' }
}

/** 生成带前端追踪 id 的抽象类行。 */
export function makeAbstractClassRow(): AbstractClassRow {
  return { rowId: rid(), fqcn: '', alias: '' }
}

let seq = 0

/** 生成前端行追踪 id（不参与 YAML 渲染，仅 v-for key 稳定）。 */
function rid(): string {
  seq += 1
  return `r${Date.now().toString(36)}${seq}`
}
