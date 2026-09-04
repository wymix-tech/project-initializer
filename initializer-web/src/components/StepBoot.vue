<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()

/**
 * 选中 Boot 版本并进入下一步。
 *
 * @param id Boot 版本 id
 */
function pick(id: string): void {
  wizard.selectBoot(id).then(() => wizard.next())
}
</script>

<template>
  <div>
    <h2>选择 Spring Boot 版本（Java {{ wizard.selectedJava }}）</h2>
    <ul class="option-list">
      <li v-for="bv in wizard.bootVersions" :key="bv.id">
        <button
          type="button"
          class="option"
          :class="{ selected: wizard.selectedBoot === bv.id }"
          @click="pick(bv.id)"
        >
          <span class="option-label">{{ bv.label }}</span>
          <span v-if="bv.official" class="option-badge">官方</span>
        </button>
      </li>
    </ul>
    <p v-if="wizard.bootVersions.length === 0 && !wizard.loading" class="empty">
      该 Java 版本暂无兼容的 Boot 版本
    </p>
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
.option-badge { color: #1a7f37; font-size: 12px; border: 1px solid #1a7f37; border-radius: 4px; padding: 0 4px; }
.empty { color: #57606a; font-size: 14px; }
</style>
