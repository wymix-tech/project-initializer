<script setup lang="ts">
defineProps<{ current: number }>()

const stepLabels = ['Java 版本', 'Boot 版本', '依赖', '元信息']
</script>

<template>
  <ol class="steps">
    <li
      v-for="(label, index) in stepLabels"
      :key="label"
      class="step"
      :class="{ active: index + 1 === current, done: index + 1 < current }"
    >
      <span class="step-index">{{ index + 1 < current ? '✓' : index + 1 }}</span>
      <span class="step-label">{{ label }}</span>
    </li>
  </ol>
</template>

<style scoped>
.steps { display: flex; list-style: none; padding: 0; gap: 8px; }
.step { display: flex; align-items: center; gap: 6px; color: #57606a; font-size: 13px; }
.step.active { color: #0969da; font-weight: 600; }
.step.done { color: #1a7f37; }
.step-index {
  width: 22px; height: 22px; border-radius: 50%;
  display: inline-flex; align-items: center; justify-content: center;
  border: 1px solid currentColor; font-size: 12px;
}
</style>
