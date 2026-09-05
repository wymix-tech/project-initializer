import type { StarterConfigForm } from '@/types/starter-config'

/**
 * starter 配置 YAML 渲染器（纯函数）。
 *
 * 产出与 {@code catalog/starters/*.yml} 同构的单文档 YAML，可追加进 starter 草稿
 * （草稿为多文档，文档间以 {@code ---} 分隔）。字段顺序与现有样例文件保持一致，
 * 便于人工复核；空联动渲染为 {@code linkages: {}}（与官方 web-starter.yml 一致）。
 *
 * 标量渲染策略对齐现有样例文件：坐标与描述尽量不加引号（如
 * {@code groupId: org.springframework.boot}、{@code description: Spring MVC + 内嵌 Tomcat}），
 * 仅在会被 YAML 解析为非字符串（纯数字 / true/false）或含行内冒号+空格等危险字符时加引号；
 * version 一律加引号（与样例 {@code version: "1.2.0"} 一致，避免纯数字版本被解析为数字）。
 *
 * 设计为纯函数：无副作用、可单测覆盖边界（多依赖、占位符、空联动），
 * 供向导预览与提交两处复用，避免渲染逻辑漂移。
 */

/** 单行缩进（两个空格，与项目样例 yml 一致）。 */
const IND = '  '
/** 多文档分隔符，向草稿追加单文档时用。 */
export const DOC_SEPARATOR = '\n---\n'

/** 占位符白名单（与后端 PlaceholderPolicy 一致）。 */
const ALLOWED_PLACEHOLDERS = new Set([
  'basePackage',
  'packagePath',
  'artifact',
  'group',
  'bootVersion',
  'javaVersion',
])

const PLACEHOLDER_RE = /\{([^{}]+)\}/g

/** 会触发 YAML 特殊解析、需加引号的「危险前缀字符」。
 *
 * 含流式集合指示符（{}[]）、标签/别名（!&*）、引用（"'`）、映射指示（:>）、
 * 序列指示（-）、注释（#）与逗号（,）等，以这些字符开头的值须加引号，
 * 否则会被 SnakeYAML 解析为非字符串或聚合结构。 */
const DANGER_PREFIX = /^[!&*{}\[\]|>"'`%@#,\-]/

/**
 * 判断值是否可安全地不加引号输出为 YAML 标量字符串。
 *
 * 判定准则（保守）：
 * - 纯数字 / true / false / null 会被解析为非字符串 → 需加引号
 * - 含行内「: 」（冒号+空格）会被解析为映射 → 需加引号
 * - 危险前缀字符（!&* 等）会被解析为标签/别名等 → 需加引号
 * - 其余（含点号、中划线、空格、中文、+ 等）视为合法的普通/多词标量，不加引号
 *
 * @param value 待判定值
 */
function needsQuote(value: string): boolean {
  if (value === '') return true
  if (/^-?\d+$/.test(value)) return true // 纯整数
  if (value === 'true' || value === 'false' || value === 'null' || value === 'True' || value === 'False' || value === 'Null' || value === 'TRUE' || value === 'FALSE' || value === 'NULL') {
    return true
  }
  if (DANGER_PREFIX.test(value)) return true
  if (/[{}]/.test(value)) return true // 占位符 {basePackage} 等含花括号 → 加引号（与样例一致，避免流式集合歧义）
  if (/:\s/.test(value)) return true // 行内冒号+空格 → 映射歧义
  if (value.startsWith('- ')) return true // 序列指示符
  if (value.includes('# ') || value.trim().startsWith('#')) return true
  return false
}

/**
 * 将值渲染为 YAML 标量字符串：尽量不加引号（与样例文件一致），
 * 仅在会被解析为非字符串或含危险字符时加双引号。
 *
 * @param value 原始值
 * @returns yml 标量字面量
 */
function scalar(value: string): string {
  const v = value ?? ''
  return needsQuote(v) ? quoteYamlString(v) : v
}

/**
 * 将值渲染为「恒定字符串」标量：一律加引号。
 *
 * 供 version 字段使用——版本号可能是纯数字（如 {@code 2024}）或多段数字串
 * （{@code 1.2.0}），一律加引号保证解析为字符串，与样例 {@code version: "1.2.0"} 一致。
 *
 * @param value 原始值
 */
function scalarString(value: string): string {
  return quoteYamlString(value ?? '')
}

/** 双引号包裹并转义内部双引号与反斜杠。 */
function quoteYamlString(value: string): string {
  const escaped = value.replace(/\\/g, '\\\\').replace(/"/g, '\\"')
  return `"${escaped}"`
}

/**
 * 渲染完整 starter yml 文本（单文档，不含文档分隔符）。
 *
 * @param form 表单状态
 * @returns starter yml 全文
 */
export function buildStarterYaml(form: StarterConfigForm): string {
  const lines: string[] = []
  lines.push(`id: ${scalar(form.id)}`)
  lines.push(`category: ${scalar(form.category)}`)
  lines.push(`maintainer: ${scalar(form.maintainer)}`)
  lines.push(`description: ${scalar(form.description || form.id)}`)
  lines.push('dependencies:')
  for (const dep of form.dependencies) {
    lines.push(`${IND}- groupId: ${scalar(dep.groupId)}`)
    lines.push(`${IND}  artifactId: ${scalar(dep.artifactId)}`)
    if (dep.version.trim() !== '') {
      lines.push(`${IND}  version: ${scalarString(dep.version)}`)
    }
  }
  lines.push(`linkages:${renderLinkages(form)}`)
  return lines.join('\n') + '\n'
}

/**
 * 渲染 linkages 节：无任何联动规则时输出 {@code {}}（单行），
 * 否则输出多行映射。保持与 web-starter.yml（空）和 xxx-redis（全量）样例一致。
 */
function renderLinkages(form: StarterConfigForm): string {
  const hasConfigPackage = form.configPackage.trim() !== ''
  const hasTemplates = form.templates.length > 0
  const hasFragments = form.ymlFragments.length > 0
  const hasAbstract = form.abstractClasses.length > 0
  if (!hasConfigPackage && !hasTemplates && !hasFragments && !hasAbstract) {
    return ' {}'
  }
  const lines: string[] = []
  if (hasConfigPackage) {
    lines.push(`${IND}config-package: ${scalar(form.configPackage)}`)
  }
  if (hasTemplates) {
    lines.push(`${IND}templates:`)
    for (const t of form.templates) {
      lines.push(`${IND}- source: ${scalar(t.source)}`)
      lines.push(`${IND}  target: ${scalar(t.target)}`)
    }
  }
  if (hasFragments) {
    lines.push(`${IND}application-yml-fragments:`)
    for (const f of form.ymlFragments) {
      lines.push(`${IND}- source: ${scalar(f.source)}`)
      lines.push(`${IND}  merge-strategy: ${scalar(f.mergeStrategy)}`)
    }
  }
  if (hasAbstract) {
    lines.push(`${IND}abstract-classes:`)
    for (const a of form.abstractClasses) {
      lines.push(`${IND}- fqcn: ${scalar(a.fqcn)}`)
      lines.push(`${IND}  alias: ${scalar(a.alias)}`)
    }
  }
  // 返回换行+缩进块，使 `linkages:` 后直接换行（与样例文件格式一致，无尾随空格）
  return '\n' + lines.join('\n')
}

/**
 * 检测表单中出现的越界占位符（与后端 PlaceholderPolicy 白名单一致）。
 *
 * 用于向导前端即时提示，避免发布期才被后端 9002 拒绝。
 *
 * @param form 表单状态
 * @returns 越界占位符（保持出现顺序去重）；全部合法时为空
 */
export function findUnknownPlaceholders(form: StarterConfigForm): string[] {
  const unknown = new Set<string>()
  const scan = (text: string): void => {
    if (!text) return
    let m: RegExpExecArray | null
    const re = new RegExp(PLACEHOLDER_RE.source, 'g')
    while ((m = re.exec(text)) !== null) {
      const name = m[1]
      if (!ALLOWED_PLACEHOLDERS.has(name)) unknown.add(`{${name}}`)
    }
  }
  scan(form.configPackage)
  for (const t of form.templates) scan(t.target)
  // source 路径不支持占位符（仅 target 支持），仍扫描以兜底提示
  for (const t of form.templates) scan(t.source)
  for (const f of form.ymlFragments) scan(f.source)
  for (const a of form.abstractClasses) {
    scan(a.fqcn)
    scan(a.alias)
  }
  return [...unknown]
}
