<script setup lang="ts">
import { useWizardStore } from '@/stores/wizard'

const wizard = useWizardStore()
</script>

<template>
  <div>
    <h2>选择依赖（可多选）</h2>
    <ul class="starter-list">
      <li v-for="st in wizard.starters" :key="st.id">
        <label class="starter" :class="{ checked: wizard.selectedStarters.has(st.id) }">
          <input
            type="checkbox"
            :checked="wizard.selectedStarters.has(st.id)"
            @change="wizard.toggleStarter(st.id)"
          />
          <span>{{ st.label }}</span>
          <span v-if="st.maintainer === 'internal'" class="maintainer-tag">内部</span>
          <code class="starter-id">{{ st.id }}</code>
        </label>
      </li>
    </ul>
  </div>
</template>

<style scoped>
h2 { font-size: 16px; margin-bottom: 12px; }
.starter-list { list-style: none; padding: 0; display: grid; gap: 8px; }
.starter {
  display: flex; align-items: center; gap: 8px; padding: 10px 14px;
  border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; cursor: pointer;
}
.starter.checked { border-color: #0969da; background: #ddf4ff; }
.starter-id { margin-left: auto; color: #57606a; font-size: 12px; }
.maintainer-tag {
  font-size: 11px; color: #8250df; border: 1px solid #d2a8ff;
  border-radius: 10px; padding: 0 6px; line-height: 16px;
}
</style>
