<script setup lang="ts">
import { watch } from 'vue'
import { useStarterConfigWizardStore, CFG_STEP_BASIC, CFG_STEP_DEPS, CFG_STEP_LINKAGE, CFG_STEP_PREVIEW } from '@/stores/starterConfigWizard'
import StarterConfigStepBasic from '@/components/StarterConfigStepBasic.vue'
import StarterConfigStepDeps from '@/components/StarterConfigStepDeps.vue'
import StarterConfigStepLinkage from '@/components/StarterConfigStepLinkage.vue'
import StarterConfigStepPreview from '@/components/StarterConfigStepPreview.vue'

/**
 * starter 配置引导向导（傻瓜化接入入口）。
 *
 * 把「手写 starter yml」拆成 4 步表单，字段级即时校验 + 占位符白名单前置提示，
 * 最后一步实时预览生成的 yml → 写草稿（乐观锁）→ 发布。后端零改动，复用管理端 API。
 *
 * 需要 AdminPanel 登录后注入 token（同会话鉴权）。
 */
const wizard = useStarterConfigWizardStore()

const props = withDefaults(defineProps<{ token: string; operator?: string }>(), { operator: 'admin' })

watch(() => props.token, (token) => {
  wizard.setToken(token)
  void wizard.loadExistingDraft()
}, { immediate: true })
watch(() => props.operator, (operator) => { wizard.operator = operator }, { immediate: true })

const emit = defineEmits<{ (event: 'saved'): void }>()

/** 保存成功后通知父清单重新读取，避免切回清单表时仍显示旧内容。 */
async function saveDraft(): Promise<void> {
  if (await wizard.saveToDraft()) { emit('saved') }
}

/** 保存并发布成功后同步父清单。 */
async function publish(): Promise<void> {
  if (await wizard.publish()) { emit('saved') }
}
</script>

<template>
  <section class="scw">
    <ol class="scw-steps">
      <li
        v-for="(label, i) in ['基本信息', '依赖坐标', '联动规则', '预览与发布']"
        :key="label"
        class="scw-step"
        :class="{
          active: i + 1 === wizard.step,
          done: i + 1 < wizard.step,
        }"
      >
        <span class="scw-step-idx">{{ i + 1 < wizard.step ? '✓' : i + 1 }}</span>
        <span class="scw-step-label">{{ label }}</span>
      </li>
    </ol>

    <div class="scw-body">
      <StarterConfigStepBasic v-if="wizard.step === CFG_STEP_BASIC" />
      <StarterConfigStepDeps v-else-if="wizard.step === CFG_STEP_DEPS" />
      <StarterConfigStepLinkage v-else-if="wizard.step === CFG_STEP_LINKAGE" />
      <StarterConfigStepPreview v-else />
    </div>

    <p v-if="wizard.unknownPlaceholders.length > 0" class="scw-warn" role="alert">
      ⚠ 占位符越界：{{ wizard.unknownPlaceholders.join('、') }}（仅允许 {basePackage} {packagePath} {artifact} {group} {bootVersion} {javaVersion}）
    </p>

    <p v-if="wizard.message" class="scw-msg" :class="`scw-msg-${wizard.messageType}`" role="status">
      {{ wizard.message }}
    </p>

    <div class="scw-actions">
      <button v-if="wizard.step > CFG_STEP_BASIC" type="button" class="btn" @click="wizard.prev()">
        上一步
      </button>
      <button
        v-if="wizard.step < CFG_STEP_PREVIEW"
        type="button"
        class="btn btn-primary"
        :disabled="!wizard.canNext"
        @click="wizard.next()"
      >
        下一步
      </button>
      <template v-else>
        <button
          type="button"
          class="btn"
          :disabled="wizard.actionState === 'saving' || wizard.actionState === 'publishing'"
          @click="saveDraft"
        >
          {{ wizard.actionState === 'saving' ? '保存中…' : '写入草稿' }}
        </button>
        <button
          type="button"
          class="btn btn-primary"
          :disabled="
            Object.keys(wizard.stepErrors).length > 0 ||
            wizard.unknownPlaceholders.length > 0 ||
            wizard.actionState === 'saving' ||
            wizard.actionState === 'publishing'
          "
          @click="publish"
        >
          {{ wizard.actionState === 'publishing' ? '发布中…' : '保存并发布' }}
        </button>
      </template>
    </div>
  </section>
</template>

<style scoped>
.scw { background: #fff; border: 1px solid #d0d7de; border-radius: 14px; padding: 24px; min-width: 0; }
.scw-steps { display: flex; background: #f4f7f5; border-radius: 10px; padding: 12px; list-style: none; gap: 8px; flex-wrap: wrap; }
.scw-step { display: flex; flex: 1 1 120px; min-height: 34px; align-items: center; gap: 6px; color: #57606a; font-size: 13px; }
.scw-step.active { color: var(--accent); font-weight: 600; }
.scw-step.done { color: #1a7f37; }
.scw-step-idx {
  width: 22px; height: 22px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid currentColor; font-size: 12px;
}
.scw-body { margin: 16px 0; min-height: 200px; }
.scw-warn { color: #9a6700; background: #fff8c5; border: 1px solid #d4a72c; border-radius: 6px; padding: 8px 12px; font-size: 13px; margin-bottom: 12px; }
.scw-msg { padding: 8px 12px; border-radius: 6px; font-size: 14px; margin-bottom: 12px; }
.scw-msg-info { background: #ddf4ff; color: #0550ae; }
.scw-msg-error { background: #ffebe9; color: #cf222e; }
.scw-actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 8px 16px; border-radius: 6px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 14px; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: var(--accent); border-color: var(--accent); color: #fff; }
</style>
