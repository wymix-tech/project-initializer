<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()

/**
 * 选中 Java 版本并联动加载兼容的 Boot 版本（单页布局，不再分步跳转）。
 *
 * @param id Java 版本 id
 */
function pick(id: string): void {
  wizard.selectJava(id)
}
</script>

<template>
  <section class="config-section">
    <h2 class="section-title">Java 版本</h2>
    <div class="option-row">
      <button
        v-for="jv in wizard.javaVersions"
        :key="jv.id"
        type="button"
        class="option"
        :class="{ selected: wizard.selectedJava === jv.id }"
        :disabled="jv.deprecated"
        @click="pick(jv.id)"
      >
        <span class="option-dot" aria-hidden="true"></span>
        <span class="option-label">{{ jv.label }}</span>
        <span v-if="jv.deprecated" class="option-badge">已废弃</span>
        <span v-else-if="jv.recommendedBootVersion" class="option-hint">
          推荐 Boot {{ jv.recommendedBootVersion }}
        </span>
      </button>
    </div>
    <p v-if="wizard.javaVersions.length === 0 && !wizard.loading" class="empty">暂无可用版本</p>
  </section>
</template>

<style scoped>
.section-title { font-size: 15px; font-weight: 700; margin-bottom: 10px; }
.option-row { display: flex; flex-wrap: wrap; gap: 4px 20px; }
.option {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 0;
  border: none;
  background: none;
  font-size: 14px;
  color: #1b1f23;
  cursor: pointer;
}
.option-dot {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: 1px solid #9a9a9a;
  position: relative;
  flex-shrink: 0;
}
.option.selected .option-dot { border-color: #6db33f; }
.option.selected .option-dot::after {
  content: '';
  position: absolute;
  inset: 3px;
  border-radius: 50%;
  background: #6db33f;
}
.option.selected .option-label { color: #4e8a2c; font-weight: 600; }
.option:disabled { opacity: 0.5; cursor: not-allowed; }
.option-badge { color: #9a6700; font-size: 12px; }
.option-hint { color: #6a737d; font-size: 12px; }
.empty { color: #6a737d; font-size: 14px; }
</style>
