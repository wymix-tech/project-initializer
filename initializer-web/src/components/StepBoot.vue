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
.section-title { font-size: 15px; font-weight: 700; margin-bottom: 10px; }
.section-sub { font-size: 12px; font-weight: 400; color: #6a737d; margin-left: 8px; }
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
.option-badge {
  color: #4e8a2c;
  font-size: 11px;
  border: 1px solid #6db33f;
  border-radius: 4px;
  padding: 0 4px;
}
.empty { color: #6a737d; font-size: 14px; }
</style>
