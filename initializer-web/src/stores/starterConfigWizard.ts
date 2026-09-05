import { defineStore } from 'pinia'
import { adminApi, ApiError } from '@/api/client'
import {
  buildStarterYaml,
  DOC_SEPARATOR,
  findUnknownPlaceholders,
} from '@/starter-yaml-builder'
import {
  emptyStarterConfigForm,
  makeAbstractClassRow,
  makeDependencyRow,
  makeTemplateRow,
  makeYmlFragmentRow,
  type StarterConfigForm,
} from '@/types/starter-config'

/** 向导步骤：1 基本信息 → 2 依赖 → 3 联动 → 4 预览与发布 */
export const CFG_STEP_BASIC = 1
export const CFG_STEP_DEPS = 2
export const CFG_STEP_LINKAGE = 3
export const CFG_STEP_PREVIEW = 4
export const CFG_TOTAL_STEPS = 4

/** 步骤标签（与 StepIndicator 复用风格）。 */
export const CFG_STEP_LABELS = ['基本信息', '依赖坐标', '联动规则', '预览与发布']

/** 提交动作状态。 */
export type WizardActionState = 'idle' | 'saving' | 'publishing' | 'done' | 'error'

/**
 * starter 配置向导状态：分步表单 + YAML 预览 + 草稿追加发布。
 *
 * 与管理端 {@link AdminPanel} 的 YAML 编辑模式互补：向导生成单文档 starter yml，
 * 追加进现有 starter 草稿（草稿为多文档），再走 {@code PUT /api/admin/config/starter}
 * （带乐观锁 If-Match）+ {@code POST /api/admin/config/publish}。后端零改动。
 *
 * 复用 AdminPanel 的 token 登录态（通过 adminApi 携带 X-Admin-Token）。
 */
export const useStarterConfigWizardStore = defineStore('starterConfigWizard', {
  state: () => ({
    step: CFG_STEP_BASIC as number,
    form: emptyStarterConfigForm() as StarterConfigForm,
    /** 现有 starter 草稿全文（GET 拉取，用于追加判断重复 id） */
    existingDraft: '' as string,
    /** 现有草稿乐观锁版本（PUT 携带 If-Match） */
    draftVersion: null as number | null,
    /** 已登录 admin token */
    token: '' as string,
    actionState: 'idle' as WizardActionState,
    message: '' as string,
    messageType: 'info' as 'info' | 'error',
    loading: false,
  }),

  getters: {
    /** 实时渲染的 starter yml 预览（纯函数渲染，无副作用）。 */
    previewYaml(state): string {
      return buildStarterYaml(state.form)
    },

    /** 表单中出现的越界占位符（即时提示，避免发布期被拒）。 */
    unknownPlaceholders(state): string[] {
      return findUnknownPlaceholders(state.form)
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
        } else {
          // 草稿为多文档（--- 分隔），id 行可能位于文档首行或文档分隔符后
          const idLine = new RegExp(`(?:^|\\n)id: ${f.id.trim()}\\s*(?:\\n|$)`)
          if (idLine.test(state.existingDraft)) {
            errors.id = `starter id「${f.id.trim()}」已存在于草稿中，请改名或先删除旧条目`
          }
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
      // 仅校验当前步骤相关的字段键
      if (step === CFG_STEP_BASIC) return !('id' in errs)
      if (step === CFG_STEP_DEPS) return !('dependencies' in errs)
      if (step === CFG_STEP_LINKAGE) {
        return !('templates' in errs) && !('ymlFragments' in errs) && !('abstractClasses' in errs)
      }
      // 预览步：占位符必须合法才能发布
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
     * 拉取当前 starter 草稿（多文档全文 + 乐观锁版本），用于追加前查重。
     *
     * 失败时：草稿不存在属正常（首发场景），仅置空 existingDraft，不报错。
     */
    async loadExistingDraft(): Promise<void> {
      if (this.token === '') return
      try {
        const view = await adminApi.getDraft('starter', this.token)
        this.existingDraft = view.contentYaml ?? ''
        this.draftVersion = view.version
      } catch {
        // 草稿不存在或网络异常：置空不阻断向导，追加时按 null 版本走首次保存
        this.existingDraft = ''
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
     * 追加本表单生成的 starter yml 到草稿并保存（带乐观锁）。
     *
     * 合并策略：草稿为空 → 直接作为首发全文；非空 → 以 {@link DOC_SEPARATOR}
     * 拼接为新文档（草稿本身就是多文档，追加一个文档天然合法）。
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
        const newDoc = buildStarterYaml(this.form)
        const merged = this.existingDraft.trim() === ''
          ? newDoc
          : `${this.existingDraft.trimEnd()}${DOC_SEPARATOR}${newDoc}`
        await adminApi.putDraft('starter', this.token, merged, this.draftVersion)
        await this.loadExistingDraft()
        this.actionState = 'done'
        this.showText('info', '已写入 starter 草稿（未发布）')
        return true
      } catch (e) {
        this.actionState = 'error'
        this.showText('error', e instanceof ApiError ? e.detail : '草稿保存失败')
        return false
      }
    },

    /**
     * 发布：保存草稿（如有改动）→ 触发全量校验 + 快照切换。
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
