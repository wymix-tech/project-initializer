<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { toasts, dismiss } = useToast()
</script>

<template>
  <div class="toast-host" role="status" aria-live="polite">
    <div
      v-for="toast in toasts"
      :key="toast.id"
      class="toast"
      :class="`toast-${toast.type}`"
      @click="dismiss(toast.id)"
    >
      {{ toast.text }}
    </div>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  top: 16px;
  right: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 1000;
}
.toast {
  padding: 10px 16px;
  border-radius: 6px;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
  max-width: 360px;
}
.toast-info { background: #0969da; }
.toast-error { background: #cf222e; }
</style>
