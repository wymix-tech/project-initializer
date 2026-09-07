import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { BootVersion, ConflictRule, JavaVersion, ProjectMeta, Starter } from '@/types/api'

/** 触发的冲突条目（M3.5）：规则 ID + 提示文案 + 涉及 starter。 */
export interface TriggeredConflict {
  ruleId: string
  message: string
  starters: string[]
}

/** 表单步骤：1 Java 版本 → 2 Boot 版本 → 3 依赖 → 4 元信息 */
export const STEP_JAVA = 1
export const STEP_BOOT = 2
export const STEP_STARTERS = 3
export const STEP_META = 4
export const TOTAL_STEPS = 4

/** 提交状态 */
export type SubmitState = 'idle' | 'generating' | 'success' | 'error'

/**
 * 四步表单向导状态：负责版本联动加载、starter 多选、元信息与提交。
 */
export const useWizardStore = defineStore('wizard', {
  state: () => ({
    step: STEP_JAVA as number,
    javaVersions: [] as JavaVersion[],
    bootVersions: [] as BootVersion[],
    starters: [] as Starter[],
    /** M3.4：starter 搜索关键字与分类过滤条件 */
    starterKeyword: '' as string,
    starterCategory: '' as string,
    /** M3.5：当前生效冲突规则集（选择依赖时本地即时计算提示） */
    conflictRules: [] as ConflictRule[],
    selectedJava: '' as string,
    selectedBoot: '' as string,
    selectedStarters: new Set<string>() as Set<string>,
    /** M3.6：用户显式勾选的 starter（自动引入的依赖不在此集合，取消时据此判断可释放） */
    explicitStarters: new Set<string>() as Set<string>,
    /**
     * M3.6：starter 注册表（累积加载过的全部 starter，不受搜索过滤影响）。
     * 依赖联动计算必须基于全量，否则被搜索过滤掉的依赖无法解析。
     */
    starterRegistry: {} as Record<string, Starter>,
    meta: {
      group: '',
      artifact: '',
      packageName: '',
      packageTouched: false,
      buildTool: 'maven',
    } as ProjectMeta,
    validationErrors: {} as Record<string, string>,
    submitState: 'idle' as SubmitState,
    errorMessage: '' as string,
    loading: false,
  }),

  getters: {
    /** 是否可进入下一步。 */
    canNext(state): boolean {
      switch (state.step) {
        case STEP_JAVA:
          return state.selectedJava !== ''
        case STEP_BOOT:
          return state.selectedBoot !== ''
        case STEP_STARTERS:
          return true
        case STEP_META:
          return (
            Object.keys(state.validationErrors).length === 0 &&
            state.meta.group !== '' &&
            state.meta.artifact !== '' &&
            state.meta.packageName !== ''
          )
        default:
          return false
      }
    },

    /**
     * M3.5：已选 starter 组合触发的冲突清单（本地即时计算，软提示）。
     */
    triggeredConflicts(state): TriggeredConflict[] {
      const selected = state.selectedStarters
      return state.conflictRules
        .filter((rule) => {
          const hits = rule.starters.filter((id) => selected.has(id)).length
          return rule.type === 'pair' ? hits === 2 : hits >= 2
        })
        .map((rule) => ({ ruleId: rule.id, message: rule.message, starters: rule.starters }))
    },

    /**
     * M3.6：starter → 依赖它的已选 starter id 列表（用于「由 X 引入」提示与锁定判断）。
     */
    importedBy(state): Record<string, string[]> {
      const map: Record<string, string[]> = {}
      for (const id of state.selectedStarters) {
        for (const dep of state.starterRegistry[id]?.requires ?? []) {
          ;(map[dep] ??= []).push(id)
        }
      }
      return map
    },

    /** M3.6：不可取消的 starter（必选项 + 被其他已选项依赖引入的项）。 */
    lockedStarters(state): Set<string> {
      const locked = new Set<string>()
      for (const st of Object.values(state.starterRegistry)) {
        if (st.required) locked.add(st.id)
      }
      const by: Record<string, string[]> = this.importedBy
      for (const [id, importers] of Object.entries(by)) {
        if (importers.some((i) => state.selectedStarters.has(i))) locked.add(id)
      }
      return locked
    },

    /** 生成请求体。 */
    generateRequest(state) {
      return {
        group: state.meta.group,
        artifact: state.meta.artifact,
        packageName: state.meta.packageName,
        javaVersion: state.selectedJava,
        bootVersion: state.selectedBoot,
        starters: [...state.selectedStarters],
        buildTool: state.meta.buildTool,
      }
    },
  },

  actions: {
    /** 拉取 Java 版本清单（进入第一步时调用）。 */
    async loadJavaVersions(): Promise<void> {
      this.loading = true
      try {
        this.javaVersions = await api.listJavaVersions()
      } finally {
        this.loading = false
      }
    },

    /** 选择 Java 版本：联动拉取兼容 Boot 版本，默认选中推荐版本。 */
    async selectJava(javaId: string): Promise<void> {
      this.selectedJava = javaId
      this.selectedBoot = ''
      this.selectedStarters = new Set()
      this.loading = true
      try {
        this.bootVersions = await api.listBootVersions(javaId)
        const recommended = this.javaVersions.find((j) => j.id === javaId)
        const recommendedId = recommended?.recommendedBootVersion
        if (recommendedId && this.bootVersions.some((b) => b.id === recommendedId)) {
          await this.selectBoot(recommendedId)
        }
      } finally {
        this.loading = false
      }
    },

    /** 选择 Boot 版本：联动拉取 starter 清单（携带当前搜索条件）与冲突规则（M3.5）。 */
    async selectBoot(bootId: string): Promise<void> {
      this.selectedBoot = bootId
      this.selectedStarters = new Set()
      this.explicitStarters = new Set()
      this.starterRegistry = {}
      this.starterKeyword = ''
      this.starterCategory = ''
      this.loading = true
      try {
        const [starters, conflictRules] = await Promise.all([
          api.listStarters(bootId),
          api.listConflictRules().catch(() => [] as ConflictRule[]),
        ])
        this.starters = starters
        this.conflictRules = conflictRules
        this.registerStarters(starters)
        this.selectRequiredStarters()
      } finally {
        this.loading = false
      }
    },

    /** M3.4：更新搜索条件并按条件重新拉取 starter 清单。 */
    async setSearch(keyword: string, category: string): Promise<void> {
      this.starterKeyword = keyword
      this.starterCategory = category
      if (this.selectedBoot === '') return
      this.loading = true
      try {
        this.starters = await api.listStarters(
          this.selectedBoot,
          keyword || undefined,
          category || undefined,
        )
        // 检索只换展示项，注册表累积（依赖联动需基于全量）
        this.registerStarters(this.starters)
        this.selectRequiredStarters()
      } finally {
        this.loading = false
      }
    },

    /** M3.6：把清单中 required=true 的 starter 默认勾上（必选项不允许取消）。 */
    selectRequiredStarters(): void {
      for (const st of this.starters) {
        if (st.required) this.selectedStarters.add(st.id)
      }
    },

    /** M3.6：登记 starter 到全量注册表（搜索过滤不清空，依赖计算始终可用）。 */
    registerStarters(list: Starter[]): void {
      for (const st of list) this.starterRegistry[st.id] = st
    },

    /** M3.6：计算 requires 传递闭包（基于全量注册表）。 */
    closureOf(ids: string[]): Set<string> {
      const result = new Set<string>()
      const queue = [...ids]
      while (queue.length > 0) {
        const id = queue.shift() as string
        if (result.has(id)) continue
        result.add(id)
        for (const dep of this.starterRegistry[id]?.requires ?? []) queue.push(dep)
      }
      return result
    },

    /** M3.6：释放不再被任何已选项依赖的自动引入项。 */
    pruneAutoStarters(): void {
      let changed = true
      while (changed) {
        changed = false
        for (const id of [...this.selectedStarters]) {
          if (this.explicitStarters.has(id)) continue
          if (this.starterRegistry[id]?.required) continue
          const stillNeeded = [...this.selectedStarters].some(
            (sel) => sel !== id && (this.starterRegistry[sel]?.requires ?? []).includes(id),
          )
          if (!stillNeeded) {
            this.selectedStarters.delete(id)
            changed = true
          }
        }
      }
    },

    /**
     * M3.6：切换 starter 选中态（含依赖联动）。
     * - 选中：自动带上其 requires 传递闭包
     * - 取消：必选项与被其他已选项依赖的项禁止取消；取消后释放不再需要的自动引入项
     */
    toggleStarter(starterId: string): void {
      if (this.selectedStarters.has(starterId)) {
        const importers = (this.importedBy[starterId] ?? []).filter((id) =>
          this.selectedStarters.has(id),
        )
        if (this.starterRegistry[starterId]?.required || importers.length > 0) return
        this.selectedStarters.delete(starterId)
        this.explicitStarters.delete(starterId)
        this.pruneAutoStarters()
        return
      }
      for (const id of this.closureOf([starterId])) this.selectedStarters.add(id)
      this.explicitStarters.add(starterId)
    },

    /** 更新元信息字段；group/artifact 变化且用户未手改包名时自动推导。 */
    async updateMeta(field: Exclude<keyof ProjectMeta, 'packageTouched'> | 'packageTouched', value: string): Promise<void> {
      if (field === 'packageTouched') {
        this.meta.packageTouched = value === 'true'
        return
      }
      ;(this.meta as Record<string, unknown>)[field] = value
      if ((field === 'group' || field === 'artifact') && !this.meta.packageTouched) {
        if (this.meta.group !== '' && this.meta.artifact !== '') {
          try {
            const res = await api.derivePackage(this.meta.group, this.meta.artifact)
            this.meta.packageName = res.packageName
          } catch (e) {
            if (e instanceof ApiError) this.validationErrors.packageName = e.detail
          }
        } else {
          this.meta.packageName = ''
        }
      }
    },

    /** 元信息即时校验（防抖由组件层负责）。 */
    async validateMeta(): Promise<void> {
      if (this.meta.group === '' && this.meta.artifact === '' && this.meta.packageName === '') {
        this.validationErrors = {}
        return
      }
      const result = await api.validate(this.meta.group, this.meta.artifact, this.meta.packageName)
      this.validationErrors = result.valid ? {} : (result.errors ?? {})
    },

    /** 下一步。 */
    next(): void {
      if (this.canNext && this.step < TOTAL_STEPS) this.step += 1
    },

    /** 上一步。 */
    prev(): void {
      if (this.step > STEP_JAVA) this.step -= 1
    },

    /**
     * 提交生成请求并触发 zip 下载；失败记录后端明细供 toast 展示。
     *
     * @returns 是否成功
     */
    async submit(): Promise<boolean> {
      this.submitState = 'generating'
      this.errorMessage = ''
      try {
        await api.generateProject(this.generateRequest)
        this.submitState = 'success'
        return true
      } catch (e) {
        this.submitState = 'error'
        this.errorMessage =
          e instanceof ApiError ? `生成失败：[${e.code}] ${e.detail}` : '生成失败：网络异常，请稍后重试'
        return false
      }
    },
  },
})
