import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { BootVersion, ConflictRule, JavaVersion, ProjectMeta, Starter } from '@/types/api'

/** 触发的冲突条目（M3.5）：规则 ID + 提示文案 + 涉及 starter。 */
export interface TriggeredConflict {
  ruleId: string
  message: string
  starters: string[]
}

/** 互斥提示（M3.8）：某 starter 的互斥对象与文案，供 UI 标记与二选一切换。 */
export interface MutexInfo {
  /** 与之互斥的 starter ID 清单（仅保留已登记条目） */
  partners: string[]
  /** 冲突提示文案 */
  message: string
}

/**
 * 计算「强制依赖」集合（M3.8）：已选 starter 的**单元素 requires 分组**（无 OR）的依赖。
 *
 * <p>OR 分组（多元素）不在此列——它仍由用户显式选择，仅作提示。未登记的依赖忽略。
 */
function computeMandatoryDeps(registry: Record<string, Starter>, selected: Set<string>): Set<string> {
  const out = new Set<string>()
  for (const id of selected) {
    for (const group of registry[id]?.requires ?? []) {
      if (group.length === 1 && registry[group[0]]) out.add(group[0])
    }
  }
  return out
}

/**
 * 计算互斥标记（M3.8）：某规则只要涉及「强制依赖」或已触发（≥2 个成员被选），
 * 其全部已登记成员都标记为互斥，供前端高亮与二选一切换。
 */
function computeMutex(
  registry: Record<string, Starter>,
  rules: ConflictRule[],
  selected: Set<string>,
  mandatory: Set<string>,
): Record<string, MutexInfo[]> {
  const info: Record<string, MutexInfo[]> = {}
  for (const rule of rules) {
    const known = rule.starters.filter((id) => registry[id])
    if (known.length < 2) continue
    const involvesMandatory = known.some((id) => mandatory.has(id))
    const hits = known.filter((id) => selected.has(id)).length
    const triggered = rule.type === 'pair' ? hits === 2 : hits >= 2
    if (!involvesMandatory && !triggered) continue
    for (const id of known) {
      const partners = known.filter((p) => p !== id)
      ;(info[id] ??= []).push({ partners, message: rule.message })
    }
  }
  return info
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
    /** 有效选中集：用户显式选择 + 必选项 + 强制依赖（由 {@link syncSelection} 派生维护） */
    selectedStarters: new Set<string>() as Set<string>,
    /**
     * M3.8：用户显式选择的 starter（意图源）。强制依赖由 {@link syncSelection} 基于此闭包派生，
     * 父组件取消后未被其他已选组件依赖的自动项随之移除（随父组件联动）。
     */
    manualStarters: new Set<string>() as Set<string>,
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
     * M3.8：不可取消的 starter —— 框架必选项 + 强制依赖（单元素分组）。
     *
     * 互斥成员不锁定：当强制依赖与已选组件直接互斥时，双方都需可切换以表达二选一意图。
     * OR 分组（多元素）依赖不自动勾选，也不锁定。
     */
    lockedStarters(state): Set<string> {
      const locked = new Set<string>()
      for (const st of Object.values(state.starterRegistry)) {
        if (st.required) locked.add(st.id)
      }
      const mandatory = computeMandatoryDeps(state.starterRegistry, state.selectedStarters)
      const mutex = computeMutex(state.starterRegistry, state.conflictRules, state.selectedStarters, mandatory)
      for (const id of mandatory) {
        if (!mutex[id]) locked.add(id)
      }
      return locked
    },

    /** M3.8：由已选组件的单元素分组派生出的强制依赖集合（自动选中，不可取消）。 */
    mandatoryDeps(state): Set<string> {
      return computeMandatoryDeps(state.starterRegistry, state.selectedStarters)
    },

    /**
     * M3.8：当前生效的互斥标记映射（starterId → 互斥对象 + 文案），供 UI 高亮与二选一切换。
     */
    mutexInfo(state): Record<string, MutexInfo[]> {
      const mandatory = computeMandatoryDeps(state.starterRegistry, state.selectedStarters)
      return computeMutex(state.starterRegistry, state.conflictRules, state.selectedStarters, mandatory)
    },

    /** M3.8：参与互斥的 starter ID 集合（{@link mutexInfo} 的键集）。 */
    mutexIds(state): Set<string> {
      const mandatory = computeMandatoryDeps(state.starterRegistry, state.selectedStarters)
      const mutex = computeMutex(state.starterRegistry, state.conflictRules, state.selectedStarters, mandatory)
      return new Set(Object.keys(mutex))
    },

    /**
     * M3.8：依赖选择列表实际展示项。
     *
     * <p>hidden 条目默认隐藏；若被已选中组件选中或其 requires 引用则强制展示（需求3）。
     * 被揭示但不在当前检索结果中的 hidden 条目从注册表补入，保证依赖可见。
     */
    displayStarters(state): Starter[] {
      const selected = state.selectedStarters
      const revealed = (id: string): boolean => {
        if (selected.has(id)) return true
        for (const sid of selected) {
          if (state.starterRegistry[sid]?.requires.some((group) => group.includes(id))) return true
        }
        return false
      }
      const visible = state.starters.filter((st) => !st.hidden || revealed(st.id))
      const seen = new Set(visible.map((st) => st.id))
      for (const st of Object.values(state.starterRegistry)) {
        if (st.hidden && revealed(st.id) && !seen.has(st.id)) {
          visible.push(st)
          seen.add(st.id)
        }
      }
      return visible
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

    /**
     * M3.9：当前待用户「二选一」的 OR 候选依赖。
     *
     * <p>已选组件的多元素 requires 分组若尚未被满足，其全部已登记成员即候选项——
     * 列表内以淡黄底 + 红色 * 提示，替代行内依赖文案（M3.9 起列表不再渲染 `需 xxx`）。
     * 单元素分组（强制依赖）由 {@link syncSelection} 自动选中，不在此列。
     */
    pendingOrOptions(state): Set<string> {
      const selected = state.selectedStarters
      const out = new Set<string>()
      for (const id of selected) {
        for (const group of state.starterRegistry[id]?.requires ?? []) {
          if (group.length < 2) continue
          if (group.some((dep) => selected.has(dep))) continue
          for (const dep of group) {
            if (state.starterRegistry[dep]) out.add(dep)
          }
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
      this.manualStarters = new Set()
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
      this.manualStarters = new Set()
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
      this.syncSelection()
    },

    /** M3.6：登记 starter 到全量注册表（搜索过滤不清空，依赖计算始终可用）。 */
    registerStarters(list: Starter[]): void {
      for (const st of list) this.starterRegistry[st.id] = st
    },

    /**
     * M3.8：按用户意图（{@link manualStarters}）重算有效选中集。
     *
     * <p>规则：
     * <ol>
     *   <li>以用户显式选择为基，叠加必选 starter</li>
     *   <li>迭代补齐选中组件的**单元素 requires 分组**（强制依赖，支持传递依赖）</li>
     *   <li>若候选依赖与当前已在集合内的组件互斥，则跳过（保证一对互斥只自动选中一个）</li>
     * </ol>
     * 迭代收敛后写回 {@link selectedStarters}；父组件取消后未被重新派生的自动项即被移除。
     */
    syncSelection(): void {
      const next = new Set<string>(this.manualStarters)
      for (const st of Object.values(this.starterRegistry)) {
        if (st.required) next.add(st.id)
      }
      // 依赖是否与当前集合内某组件直接互斥（同规则内出现即不可同选）
      const conflictsWithSelected = (dep: string, set: Set<string>): boolean =>
        this.conflictRules.some(
          (rule) => rule.starters.includes(dep) && rule.starters.some((m) => m !== dep && set.has(m)),
        )
      let changed = true
      let guard = 0
      while (changed && guard < 1000) {
        changed = false
        guard += 1
        for (const id of [...next]) {
          for (const group of this.starterRegistry[id]?.requires ?? []) {
            if (group.length !== 1) continue
            const dep = group[0]
            if (next.has(dep) || !this.starterRegistry[dep]) continue
            if (conflictsWithSelected(dep, next)) continue
            next.add(dep)
            changed = true
          }
        }
      }
      this.selectedStarters = next
    },

    /** M3.8：切换 starter 选中态（含强制依赖自动联动与互斥二选一）。 */
    toggleStarter(starterId: string): void {
      // 必选项与强制依赖不可取消
      if (this.lockedStarters.has(starterId)) return
      if (this.selectedStarters.has(starterId)) {
        // 强制依赖按单选/自动语义处理：点击已选中项不取消（避免出现无有效态）；切换请点互斥的另一方
        if (this.mandatoryDeps.has(starterId)) return
        this.manualStarters.delete(starterId)
        this.syncSelection()
        return
      }
      // 选中：与互斥对象二选一（移除其手动选择，交由重算决定是否仍作为其他组件的强制依赖出现）
      this.manualStarters.add(starterId)
      for (const info of this.mutexInfo[starterId] ?? []) {
        for (const partner of info.partners) this.manualStarters.delete(partner)
      }
      this.syncSelection()
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
