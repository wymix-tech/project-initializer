import { defineStore } from 'pinia'
import { api, ApiError } from '@/api/client'
import type { BootVersion, JavaVersion, ProjectMeta, Starter } from '@/types/api'

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
    selectedJava: '' as string,
    selectedBoot: '' as string,
    selectedStarters: new Set<string>() as Set<string>,
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

    /** 选择 Boot 版本：联动拉取 starter 清单（携带当前搜索条件）。 */
    async selectBoot(bootId: string): Promise<void> {
      this.selectedBoot = bootId
      this.selectedStarters = new Set()
      this.starterKeyword = ''
      this.starterCategory = ''
      this.loading = true
      try {
        this.starters = await api.listStarters(bootId)
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
      } finally {
        this.loading = false
      }
    },

    /** 切换 starter 选中态。 */
    toggleStarter(starterId: string): void {
      if (this.selectedStarters.has(starterId)) {
        this.selectedStarters.delete(starterId)
      } else {
        this.selectedStarters.add(starterId)
      }
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
