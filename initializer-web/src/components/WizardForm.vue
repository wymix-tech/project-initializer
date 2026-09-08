<script setup lang="ts">
import { onMounted } from 'vue'
import { useWizardStore } from '@/stores/wizard'
import StepJava from '@/components/StepJava.vue'
import StepBoot from '@/components/StepBoot.vue'
import StepStarters from '@/components/StepStarters.vue'
import StepMeta from '@/components/StepMeta.vue'

const wizard = useWizardStore()

onMounted(() => {
  wizard.loadJavaVersions()
})
</script>

<template>
  <section class="wizard">
    <div class="wizard-grid" :class="{ loading: wizard.loading }">
      <div class="wizard-left">
        <StepJava />
        <StepBoot />
        <StepMeta />
      </div>
      <aside class="wizard-right">
        <div class="deps-head">
          <h2 class="section-title">依赖</h2>
          <span class="deps-count">已选 {{ wizard.selectedStarters.size }}</span>
        </div>
        <StepStarters />
      </aside>
    </div>
    <footer v-if="wizard.errorMessage" class="wizard-error" role="alert">
      {{ wizard.errorMessage }}
    </footer>
    <div class="wizard-actions">
      <button
        type="button"
        class="btn-generate"
        :disabled="!wizard.canGenerate || wizard.submitState === 'generating'"
        @click="wizard.submit()"
      >
        {{ wizard.submitState === 'generating' ? '生成中…' : '生成并下载' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.wizard { display: flex; flex-direction: column; }
.wizard-right :deep(.deps-panel) {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.wizard-right :deep(.starter-list) {
  flex: 1;
  min-height: 0;
  max-height: none;
}
.wizard-grid {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  gap: 40px;
  flex: 1;
  min-height: 0;
}
.wizard-right {
  border-left: 1px solid #e6e6e6;
  padding-left: 40px;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.wizard-left :deep(.config-section) { margin-bottom: 36px; }
.wizard-grid.loading { opacity: 0.6; pointer-events: none; }
.wizard-right {
  border-left: 1px solid #e6e6e6;
  padding-left: 40px;
}
.deps-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 4px;
}
.deps-count {
  font-size: 12px;
  color: #6a737d;
  border: 1px solid #d0d7de;
  border-radius: 10px;
  padding: 1px 8px;
}
@media (max-width: 880px) {
  .wizard-grid { grid-template-columns: 1fr; }
  .wizard-right { border-left: none; padding-left: 0; }
}
.wizard-error {
  color: #cf222e;
  font-size: 14px;
  margin-top: 24px;
  padding: 10px 14px;
  border: 1px solid #ffc0c0;
  background: #fff5f5;
  border-radius: 4px;
}
.wizard-actions {
  margin-top: 24px;
  padding: 14px 16px;
  background: #f1f1f1;
  border: 1px solid #e3e3e3;
  border-radius: 4px;
  display: flex;
  justify-content: center;
}
.btn-generate {
  padding: 10px 32px;
  font-size: 14px;
  font-weight: 700;
  border: 2px solid #1b1f23;
  border-radius: 2px;
  background: #fff;
  color: #1b1f23;
  cursor: pointer;
  letter-spacing: 1px;
}
.btn-generate:hover:not(:disabled) {
  background: #6db33f;
  border-color: #6db33f;
  color: #fff;
}
.btn-generate:disabled { opacity: 0.5; cursor: not-allowed; }
</style>
