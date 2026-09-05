<script setup lang="ts">
import { ref } from 'vue'
import WizardForm from '@/components/WizardForm.vue'
import ToastHost from '@/components/ToastHost.vue'
import AdminPanel from '@/components/AdminPanel.vue'

/** 视图切换：生成向导 / 管理后台（M3 评审遗留项 2）。 */
const view = ref<'wizard' | 'admin'>('wizard')
</script>

<template>
  <div class="app" :class="{ 'app-wide': view === 'admin' }">
    <header class="app-header">
      <nav class="app-nav">
        <button
          type="button"
          class="nav-btn"
          :class="{ active: view === 'wizard' }"
          @click="view = 'wizard'"
        >
          项目生成
        </button>
        <button
          type="button"
          class="nav-btn"
          :class="{ active: view === 'admin' }"
          @click="view = 'admin'"
        >
          管理后台
        </button>
      </nav>
      <p>官方 Boot 版本 · Maven · 基础生成</p>
    </header>
    <main class="app-main">
      <WizardForm v-if="view === 'wizard'" />
      <AdminPanel v-else />
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
.app {
  /* 向导模式按表单阅读舒适度固定 720；管理后台用 app-wide 撑满更宽视口，避免 YAML 编辑区两侧空旷 */
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 16px;
}
.app.app-wide {
  max-width: min(1280px, calc(100vw - 32px));
}
.app-header h1 { font-size: 24px; margin-bottom: 4px; }
.app-header p { color: #57606a; font-size: 14px; }
.app-main { margin-top: 24px; }
.app-nav { display: flex; gap: 8px; margin-bottom: 8px; }
.nav-btn {
  padding: 6px 14px; border-radius: 6px; border: 1px solid #d0d7de;
  background: #f6f8fa; cursor: pointer; font-size: 14px;
}
.nav-btn.active { background: #0969da; border-color: #0969da; color: #fff; }
</style>
