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
     * 单页布局下生成按钮可用条件：版本已选 + 元信息完整且校验通过。
     */
    canGenerate(state): boolean {
      return (
        state.selectedJava !== '' &&
        state.selectedBoot !== '' &&
        state.meta.group !== '' &&
        state.meta.artifact !== '' &&
        state.meta.packageName !== '' &&
        Object.keys(state.validationErrors).length === 0
      )
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
     * M3.7：不可取消的 starter —— **仅框架必选项**。
     *
     * requires 引入的依赖不再锁定：A 需要 (B 或 C) 时用户可能只想选 C，
     * 也可能 B 与 C 互斥，锁定会让用户无法表达真实意图。
     */
    lockedStarters(state): Set<string> {
      const locked = new Set<string>()
      for (const st of Object.values(state.starterRegistry)) {
        if (st.required) locked.add(st.id)
      }
      return locked
    },

    /**
     * M3.7：已选 starter 中依赖分组未被满足的条目（本地即时提示，生成期后端兜底校验）。
     *
     * requires 为 AND-of-OR-groups：分组内任一被选即满足该分组，全部分组满足才算满足。
     */
    unsatisfiedRequires(state): { id: string; group: string[] }[] {
      const selected = state.selectedStarters
      const out: { id: string; group: string[] }[] = []
      for (const id of selected) {
        for (const group of state.starterRegistry[id]?.requires ?? []) {
          if (!group.some((dep) => selected.has(dep))) out.push({ id, group })
        }
      }
      return out
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

    /**
     * M3.7：切换 starter 选中态。
     *
     * 不再自动带上/释放 requires 依赖：requires 只作为「需要 B 或 C」的提示与生成期校验依据，
     * 依赖是否勾选完全由用户决定（A 需要 B 或 C 时只选一个即可；B 与 C 互斥时也不会被强制同选）。
     *
     * 仅框架必选项（required=true）禁止取消。
     */
    toggleStarter(starterId: string): void {
      if (this.selectedStarters.has(starterId)) {
        if (this.starterRegistry[starterId]?.required) return
        this.selectedStarters.delete(starterId)
        return
      }
      this.selectedStarters.add(starterId)
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
