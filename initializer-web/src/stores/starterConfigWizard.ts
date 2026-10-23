import { defineStore } from 'pinia'
import { adminStructuredApi, ApiError } from '@/api/client'
import type { DraftSaveResult, StarterDto } from '@/types/admin'
import {
  emptyStarterConfigForm,
  makeAbstractClassRow,
  makeDependencyRow,
  makeTemplateRow,
  makeYmlFragmentRow,
  type StarterConfigForm,
} from '@/types/starter-config'

/** 向导步骤：1 基本信息 → 2 依赖 → 3 联动 → 4 预览与提交 */
export const CFG_STEP_BASIC = 1
export const CFG_STEP_DEPS = 2
export const CFG_STEP_LINKAGE = 3
export const CFG_STEP_PREVIEW = 4
export const CFG_TOTAL_STEPS = 4

/** 步骤标签（与 StepIndicator 复用风格）。 */
export const CFG_STEP_LABELS = ['基本信息', '依赖坐标', '联动规则', '预览与提交']

/** 提交动作状态。 */
export type WizardActionState = 'idle' | 'saving' | 'publishing' | 'done' | 'error'

/**
 * starter 配置向导状态（T6.4）：分步表单 → **结构化提交**。
 *
 * <p>T6 之前向导把表单渲染成 starter yml 文本再 `PUT /api/admin/config/starter` 追加文档；
 * T6 起改为直接提交结构化 DTO（`PUT /api/admin/starters` 整体清单），
 * 不再拼 YAML、不再依赖多文档合并语义。这样：
 * <ul>
 *   <li>`hidden` / `required` 等列语义不会再在「YAML 渲染 → 反解析」往返中丢失；</li>
 *   <li>向导产出的条目与「清单表」编辑的是同一批 `cfg_starter` 行（`source = MANUAL`）；</li>
 *   <li>追加语义由「读清单 → 合并一条 → 整体提交」实现，读取走结构化接口。</li>
 * </ul>
 *
 * <p>提交后仍需到发布台发布，草稿才真正热生效（与清单表一致）。
 */
export const useStarterConfigWizardStore = defineStore('starterConfigWizard', {
  state: () => ({
    step: CFG_STEP_BASIC as number,
    form: emptyStarterConfigForm() as StarterConfigForm,
    /** 已登记的 starter ID 集合（结构化读取，用于查重；不含同步通道条目） */
    existingIds: [] as string[],
    existingEntries: [] as StarterDto[],
    /** 现有清单草稿乐观锁版本（PUT 携带 If-Match） */
    draftVersion: null as number | null,
    /** 已登录 admin token */
    token: '' as string,
    actionState: 'idle' as WizardActionState,
    message: '' as string,
    messageType: 'info' as 'info' | 'error',
    loading: false,
  }),

  getters: {
    /**
     * 表单即将提交的结构化条目（预览区展示，与提交体同源）。
     *
     * 空值归一化为 null / 空数组，与 `CatalogDtos.StarterDto` 的可空字段口径一致。
     */
    previewDto(state): StarterDto {
      return toStarterDto(state.form, state.existingIds.length)
    },

    /** 表单中出现的越界占位符（即时提示，避免发布期被拒）。 */
    unknownPlaceholders(state): string[] {
      return findUnknownPlaceholders(state.form)
    },

    /** requires 分组的多行文本（一行一组，组内逗号分隔）。 */
    requiresText(state): string {
      return state.form.requires.map((group) => group.join(', ')).join('\n')
    },

    /** 当前表单是否已声明任何联动规则（用于预览展示与步骤引导）。 */
    hasLinkages(state): boolean {
      const f = state.form
      return (
        f.configPackage.trim() !== '' ||
        f.templates.length > 0 ||
        f.ymlFragments.length > 0 ||
        f.abstractClasses.length > 0
      )
    },

    /** 当前步骤字段校验错误（逐步聚焦，避免一次性吓退用户）。 */
    stepErrors(state): Record<string, string> {
      const errors: Record<string, string> = {}
      const f = state.form
      if (state.step >= CFG_STEP_BASIC) {
        if (f.id.trim() === '') errors.id = 'starter id 不能为空'
        else if (!/^[a-z][a-z0-9-]*$/.test(f.id.trim())) {
          errors.id = 'id 须小写字母开头，仅小写字母/数字/中划线'
        }
        const scope = new Set(f.bootVersions)
        if (state.existingEntries.some((s) => s.id === f.id.trim() &&
            s.bootVersions.length > 0 && scope.size > 0 &&
            s.bootVersions.some((b) => scope.has(b)) && !sameScope(s.bootVersions, f.bootVersions))) {
          errors.id = '该 ID 的 Boot 版本范围部分重叠，请到清单中明确拆分或修改范围'
        }
        if (f.description.trim() === '') errors.description = '描述建议填写（展示与文档说明）'
      }
      if (state.step >= CFG_STEP_DEPS) {
        if (f.dependencies.length === 0) {
          errors.dependencies = '至少声明一个依赖坐标'
        } else {
          for (const dep of f.dependencies) {
            if (dep.groupId.trim() === '' || dep.artifactId.trim() === '') {
              errors.dependencies = '每条依赖的 groupId / artifactId 不能为空'
              break
            }
          }
        }
      }
      if (state.step >= CFG_STEP_LINKAGE) {
        for (const t of f.templates) {
          if (t.source.trim() === '' || t.target.trim() === '') {
            errors.templates = '模板联动行 source / target 均需填写'
            break
          }
        }
        for (const fr of f.ymlFragments) {
          if (fr.source.trim() === '') {
            errors.ymlFragments = 'yml 片段 source 不能为空'
            break
          }
        }
        for (const a of f.abstractClasses) {
          if (a.fqcn.trim() === '' || a.alias.trim() === '') {
            errors.abstractClasses = '抽象类 fqcn / alias 均需填写'
            break
          }
        }
      }
      return errors
    },

    /** 是否可进入下一步（当前步骤无字段错误）。 */
    canNext(state): boolean {
      const errs = this.stepErrors
      const step = state.step
      if (step === CFG_STEP_BASIC) return !('id' in errs)
      if (step === CFG_STEP_DEPS) return !('dependencies' in errs)
      if (step === CFG_STEP_LINKAGE) {
        return !('templates' in errs) && !('ymlFragments' in errs) && !('abstractClasses' in errs)
      }
      // 预览步：占位符必须合法才能提交
      return this.unknownPlaceholders.length === 0
    },
  },

  actions: {
    /** 设置 admin token（由 AdminPanel 登录后注入，复用同一会话鉴权）。 */
    setToken(t: string): void {
      this.token = t
    },

    /** 重置表单为空白（用于「再配一个 starter」）。 */
    reset(): void {
      this.step = CFG_STEP_BASIC
      this.form = emptyStarterConfigForm()
      this.actionState = 'idle'
      this.message = ''
      this.messageType = 'info'
    },

    /**
     * 拉取当前 starter 清单（结构化），用于 id 查重与乐观锁版本。
     *
     * 失败时（草稿不存在 / token 无效）不阻断向导，按「首次保存」语义走 null 版本。
     */
    async loadExistingDraft(): Promise<void> {
      if (this.token === '') return
      try {
        const view = await adminStructuredApi.getStarters(this.token)
        this.existingEntries = view.starters
        this.existingIds = view.starters.map((s) => s.id)
        const version = await adminStructuredApi.readDraftVersion(this.token, 'starter')
        this.draftVersion = version
      } catch {
        this.existingIds = []
        this.draftVersion = null
      }
    },

    /** 增加一条依赖坐标行。 */
    addDependency(): void {
      this.form.dependencies.push(makeDependencyRow())
    },

    /** 删除指定依赖行（保留至少一条，避免空清单）。 */
    removeDependency(rowId: string): void {
      if (this.form.dependencies.length <= 1) return
      this.form.dependencies = this.form.dependencies.filter((d) => d.rowId !== rowId)
    },

    addTemplate(): void {
      this.form.templates.push(makeTemplateRow())
    },

    removeTemplate(rowId: string): void {
      this.form.templates = this.form.templates.filter((t) => t.rowId !== rowId)
    },

    addYmlFragment(): void {
      this.form.ymlFragments.push(makeYmlFragmentRow())
    },

    removeYmlFragment(rowId: string): void {
      this.form.ymlFragments = this.form.ymlFragments.filter((f) => f.rowId !== rowId)
    },

    addAbstractClass(): void {
      this.form.abstractClasses.push(makeAbstractClassRow())
    },

    removeAbstractClass(rowId: string): void {
      this.form.abstractClasses = this.form.abstractClasses.filter((a) => a.rowId !== rowId)
    },

    /**
     * 解析并写入 requires 分组（一行一组，组内逗号 / 空白分隔）。
     *
     * @param text 多行文本
     */
    setRequires(text: string): void {
      this.form.requires = text
        .split('\n')
        .map((line) => [...new Set(line.split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== ''))])
        .filter((group) => group.length > 0)
    },

    /**
     * 解析并写入关联 Boot 版本清单（逗号 / 空白分隔）。
     *
     * @param text 文本
     */
    setBootVersions(text: string): void {
      this.form.bootVersions = [
        ...new Set(text.split(/[\s,，]+/).map((s) => s.trim()).filter((s) => s !== '')),
      ]
    },

    /** 下一步（当前步骤校验通过才前进）。 */
    next(): void {
      if (this.canNext && this.step < CFG_TOTAL_STEPS) this.step += 1
    },

    /** 上一步。 */
    prev(): void {
      if (this.step > CFG_STEP_BASIC) this.step -= 1
    },

    /** 跳转到指定步骤（仅允许前进到已校验通过或后退）。 */
    goto(step: number): void {
      if (step < CFG_STEP_BASIC || step > CFG_TOTAL_STEPS) return
      this.step = step
    },

    showText(type: 'info' | 'error', text: string): void {
      this.messageType = type
      this.message = text
    },

    /**
     * 结构化提交：把本表单作为一条 starter 追加进清单草稿并保存（带乐观锁）。
     *
     * <p>合并策略：读取当前清单 → 按 id 覆盖同名或追加 → 整体提交
     * （`PUT /api/admin/starters` 是「提交该模块完整结构」语义，天然无孤儿行）。
     *
     * @returns 是否保存成功（失败时 actionState=error，message 含明细）
     */
    async saveToDraft(): Promise<boolean> {
      if (this.token === '') {
        this.showText('error', '请先在管理后台登录后再使用向导')
        return false
      }
      if (Object.keys(this.stepErrors).length > 0) {
        this.showText('error', '表单存在校验错误，请先修正')
        return false
      }
      if (this.unknownPlaceholders.length > 0) {
        this.showText(
          'error',
          `占位符越界：${this.unknownPlaceholders.join('、')}（仅允许 {basePackage} {packagePath} {artifact} {group} {bootVersion} {javaVersion}）`,
        )
        return false
      }
      this.actionState = 'saving'
      try {
        const merged = await this.mergeIntoCatalog()
        const result: DraftSaveResult = await adminStructuredApi.putStarters(
          this.token,
          { starters: merged },
          this.draftVersion,
        )
        this.draftVersion = result.version
        this.existingEntries = merged
        this.existingIds = merged.map((s) => s.id)
        this.actionState = 'done'
        this.showText('info', `已写入 starter 清单草稿（${result.summary}），未发布`)
        return true
      } catch (e) {
        this.actionState = 'error'
        this.showText('error', e instanceof ApiError ? e.detail : e instanceof Error ? e.message : '草稿保存失败')
        return false
      }
    },

    /**
     * 读取当前手工清单并把本表单合并进去。
     *
     * @returns 合并后的完整清单（同名覆盖，其余保留）
     */
    async mergeIntoCatalog(): Promise<StarterDto[]> {
      const view = await adminStructuredApi.getStarters(this.token)
      const entry = toStarterDto(this.form, view.starters.length)
      const overlap = view.starters.find((s) => s.id === entry.id &&
        s.bootVersions.length > 0 && entry.bootVersions.length > 0 &&
        s.bootVersions.some((b) => entry.bootVersions.includes(b)) &&
        !sameScope(s.bootVersions, entry.bootVersions))
      if (overlap) {
        throw new Error('同 ID 的 Boot 版本范围部分重叠，请先在清单中明确拆分范围')
      }
      const previous = view.starters.find((s) => s.id === entry.id && sameScope(s.bootVersions, entry.bootVersions))
      if (previous) { entry.sort = previous.sort }
      const others = view.starters.filter((s) => s.id !== entry.id || !sameScope(s.bootVersions, entry.bootVersions))
      return [...others, entry]
    },

    /**
     * 发布：先结构化保存草稿（如有改动）→ 触发全量校验 + 快照切换。
     *
     * 复用后端 publish 接口，发布校验失败返回 4002 + 明细，草稿保留可继续编辑。
     *
     * @returns 是否发布成功
     */
    async publish(): Promise<boolean> {
      const saved = await this.saveToDraft()
      if (!saved) return false
      this.actionState = 'publishing'
      try {
        const { adminApi } = await import('@/api/client')
        const result = await adminApi.publish(this.token)
        this.actionState = 'done'
        this.showText(
          'info',
          `发布 #${result.releaseId}：starter 清单 ${result.starterCount} 项 / 模板 ${result.templateOverrideCount} / 冲突规则 ${result.conflictRuleCount} 条`,
        )
        return true
      } catch (e) {
        this.actionState = 'error'
        this.showText('error', e instanceof ApiError ? e.detail : '发布失败')
        return false
      }
    },
  },
})

/**
 * 表单 → 结构化 starter 条目（`CatalogDtos.StarterDto` 同构）。
 *
 * 占位符越界检查在调用侧先行；此处只做「空串 → null / 空数组」归一化。
 *
 * @param form 表单状态
 * @param sort 展示顺序（追加至清单末尾）
 * @returns 结构化条目（提交与预览共用，保证「所见即所存」）
 */
export function toStarterDto(form: StarterConfigForm, sort: number): StarterDto {
  const blank = (v: string): string | null => (v.trim() === '' ? null : v)
  return {
    id: form.id.trim(),
    category: blank(form.category),
    maintainer: blank(form.maintainer),
    description: blank(form.description),
    required: form.required,
    hidden: form.hidden,
    configPackage: blank(form.configPackage),
    dependencies: form.dependencies.map((d) => ({
      groupId: blank(d.groupId),
      artifactId: blank(d.artifactId),
      version: blank(d.version),
    })),
    requires: form.requires.map((group) => [...group]),
    bootVersions: [...form.bootVersions],
    linkages: {
      templates: form.templates.map((t) => ({ source: blank(t.source), target: blank(t.target) })),
      ymlFragments: form.ymlFragments.map((f) => ({
        source: blank(f.source),
        mergeStrategy: f.mergeStrategy,
      })),
      abstractClasses: form.abstractClasses.map((a) => ({ fqcn: blank(a.fqcn), alias: blank(a.alias) })),
    },
    sort,
  }
}

/** 占位符白名单（与后端 PlaceholderPolicy 一致）。 */
const ALLOWED_PLACEHOLDERS = new Set([
  'basePackage',
  'packagePath',
  'artifact',
  'group',
  'bootVersion',
  'javaVersion',
])

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
    const re = /\{([^{}]+)\}/g
    let m: RegExpExecArray | null
    while ((m = re.exec(text)) !== null) {
      if (!ALLOWED_PLACEHOLDERS.has(m[1])) unknown.add(`{${m[1]}}`)
    }
  }
  scan(form.configPackage)
  for (const t of form.templates) {
    scan(t.target)
    scan(t.source)
  }
  for (const f of form.ymlFragments) scan(f.source)
  for (const a of form.abstractClasses) {
    scan(a.fqcn)
    scan(a.alias)
  }
  return [...unknown]
}

/** Scope equality is only used for exact replacement; global and scoped entries coexist. */
function sameScope(a: string[], b: string[]): boolean {
  const left = new Set(a)
  const right = new Set(b)
  return left.size === right.size && [...left].every((id) => right.has(id))
}
