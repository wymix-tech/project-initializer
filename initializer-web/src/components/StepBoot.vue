<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()

/**
 * 选中 Boot 版本并联动加载 starter 清单（单页布局，不再分步跳转）。
 *
 * @param id Boot 版本 id
 */
function pick(id: string): void {
  wizard.selectBoot(id)
}
</script>

<template>
  <section class="config-section">
    <h2 class="section-title">
      Boot 版本
      <span v-if="wizard.selectedJava" class="section-sub">已选 Java {{ wizard.selectedJava }}</span>
    </h2>
    <div class="option-row">
      <button
        v-for="bv in wizard.bootVersions"
        :key="bv.id"
        type="button"
        class="option"
        :aria-pressed="wizard.selectedBoot === bv.id"
        :class="{ selected: wizard.selectedBoot === bv.id }"
        @click="pick(bv.id)"
      >
        <span class="option-dot" aria-hidden="true"></span>
        <span class="option-label">{{ bv.label }}</span>
        <span v-if="bv.official" class="option-badge">官方</span>
      </button>
    </div>
    <p v-if="wizard.bootVersions.length === 0 && !wizard.loading" class="empty">
      该 Java 版本暂无兼容的 Boot 版本
    </p>
  </section>
</template>

<style scoped>
.section-title { font-size: 13px; font-weight: 600; margin-bottom: 12px; }
.section-sub { display: inline-block; font-size: 11px; font-weight: 400; color: var(--muted); margin-left: 8px; }
.option-row { display: flex; flex-wrap: wrap; gap: 8px; }
.option { display: inline-flex; flex-wrap: wrap; align-items: center; gap: 7px; padding: 10px 12px; border: 1px solid var(--line); border-radius: 8px; background: white; font-size: 13px; color: #354a3f; cursor: pointer; text-align: left; min-height: 42px; }
.option:hover:not(:disabled) { border-color: #71aa8e; background: #f7faf8; }
.option.selected { background: var(--accent-soft); border-color: #318662; color: var(--accent); }
.option-dot { width: 14px; height: 14px; border-radius: 50%; border: 1px solid #a4b5aa; position: relative; flex-shrink: 0; }
.option.selected .option-dot { border-color: var(--accent); }
.option.selected .option-dot::after { content: ''; position: absolute; inset: 3px; border-radius: 50%; background: var(--accent); }
.option.selected .option-label { font-weight: 600; }
.option:disabled { opacity: .5; cursor: not-allowed; }
.option-badge { font-size: 10px; padding: 1px 5px; background: #e5efe7; color: #476c52; border-radius: 4px; }
.option-hint { color: var(--muted); font-size: 11px; }
.empty { color: var(--muted); font-size: 13px; line-height: 1.7; padding: 8px 0; }
</style>
