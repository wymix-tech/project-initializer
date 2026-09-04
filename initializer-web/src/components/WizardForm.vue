<script setup lang="ts">
import { onMounted, watch } from 'vue'
import { useWizardStore } from '@/stores/wizard'
import StepIndicator from '@/components/StepIndicator.vue'
import StepJava from '@/components/StepJava.vue'
import StepBoot from '@/components/StepBoot.vue'
import StepStarters from '@/components/StepStarters.vue'
import StepMeta from '@/components/StepMeta.vue'

const wizard = useWizardStore()

onMounted(() => {
  wizard.loadJavaVersions()
})

watch(
  () => wizard.step,
  (step) => {
    if (step === 4) wizard.validateMeta()
  },
)
</script>

<template>
  <section class="wizard">
    <StepIndicator :current="wizard.step" />
    <div class="wizard-body" :class="{ loading: wizard.loading }">
      <StepJava v-if="wizard.step === 1" />
      <StepBoot v-else-if="wizard.step === 2" />
      <StepStarters v-else-if="wizard.step === 3" />
      <StepMeta v-else />
    </div>
    <footer v-if="wizard.errorMessage" class="wizard-error" role="alert">
      {{ wizard.errorMessage }}
    </footer>
    <div class="wizard-actions">
      <button v-if="wizard.step > 1" type="button" class="btn" @click="wizard.prev()">上一步</button>
      <button
        v-if="wizard.step < 4"
        type="button"
        class="btn btn-primary"
        :disabled="!wizard.canNext"
        @click="wizard.next()"
      >
        下一步
      </button>
      <button
        v-else
        type="button"
        class="btn btn-primary"
        :disabled="!wizard.canNext || wizard.submitState === 'generating'"
        @click="wizard.submit()"
      >
        {{ wizard.submitState === 'generating' ? '生成中…' : '生成并下载' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.wizard { background: #fff; border: 1px solid #d0d7de; border-radius: 8px; padding: 20px; }
.wizard-body { margin: 16px 0; min-height: 200px; }
.wizard-body.loading { opacity: 0.6; pointer-events: none; }
.wizard-error { color: #cf222e; font-size: 14px; margin-bottom: 12px; }
.wizard-actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 8px 16px; border-radius: 6px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 14px; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn-primary { background: #0969da; border-color: #0969da; color: #fff; }
</style>
