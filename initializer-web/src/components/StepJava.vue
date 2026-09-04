<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()

/**
 * 选中 Java 版本并进入下一步。
 *
 * @param id Java 版本 id
 */
function pick(id: string): void {
  wizard.selectJava(id).then(() => wizard.next())
}
</script>

<template>
  <div>
    <h2>选择 Java 版本</h2>
    <ul class="option-list">
      <li v-for="jv in wizard.javaVersions" :key="jv.id">
        <button
          type="button"
          class="option"
          :class="{ selected: wizard.selectedJava === jv.id }"
          :disabled="jv.deprecated"
          @click="pick(jv.id)"
        >
          <span class="option-label">{{ jv.label }}</span>
          <span v-if="jv.deprecated" class="option-badge">已废弃</span>
          <span v-else-if="jv.recommendedBootVersion" class="option-hint">
            推荐 Boot {{ jv.recommendedBootVersion }}
          </span>
        </button>
      </li>
    </ul>
    <p v-if="wizard.javaVersions.length === 0 && !wizard.loading" class="empty">暂无可用版本</p>
  </div>
</template>

<style scoped>
h2 { font-size: 16px; margin-bottom: 12px; }
.option-list { list-style: none; padding: 0; display: grid; gap: 8px; }
.option {
  width: 100%; text-align: left; padding: 10px 14px; font-size: 14px;
  border: 1px solid #d0d7de; border-radius: 6px; background: #fff; cursor: pointer;
  display: flex; align-items: center; gap: 8px;
}
.option.selected { border-color: #0969da; background: #ddf4ff; }
.option:disabled { opacity: 0.5; cursor: not-allowed; }
.option-badge { color: #9a6700; font-size: 12px; }
.option-hint { color: #57606a; font-size: 12px; }
.empty { color: #57606a; font-size: 14px; }
</style>
