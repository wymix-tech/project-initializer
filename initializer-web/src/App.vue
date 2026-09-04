<script setup lang="ts">
import WizardForm from '@/components/WizardForm.vue'
import ToastHost from '@/components/ToastHost.vue'
</script>

<template>
  <div class="app">
    <header class="app-header">
      <h1>Spring Initializer</h1>
      <p>官方 Boot 版本 · Maven · 基础生成</p>
    </header>
    <main class="app-main">
      <WizardForm />
    </main>
    <ToastHost />
  </div>
</template>

<style>
:root {
  font-family: 'Helvetica Neue', Arial, sans-serif;
  color: #213547;
  background: #f6f8fa;
}
* { box-sizing: border-box; margin: 0; }
body { min-height: 100vh; }
.app { max-width: 720px; margin: 0 auto; padding: 24px 16px; }
.app-header h1 { font-size: 24px; margin-bottom: 4px; }
.app-header p { color: #57606a; font-size: 14px; }
.app-main { margin-top: 24px; }
</style>
