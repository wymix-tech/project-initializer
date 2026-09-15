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
      <div class="header-inner">
        <div class="brand">
          <!-- 品牌 Logo：一体式 PI 字标——P 竖笔向下转折成基座，基座右端升起 I -->
          <svg
            class="brand-mark"
            width="22"
            height="22"
            viewBox="0 0 64 64"
            role="img"
            aria-hidden="true"
          >
            <defs>
              <linearGradient id="piBrandGrad" x1="8" y1="6" x2="56" y2="58" gradientUnits="userSpaceOnUse">
                <stop offset="0" stop-color="#6db33f" />
                <stop offset="1" stop-color="#12a37f" />
              </linearGradient>
            </defs>
            <rect
              x="5"
              y="5"
              width="54"
              height="54"
              rx="17"
              fill="none"
              stroke="url(#piBrandGrad)"
              stroke-width="3.2"
            />
            <path
              d="M19 19V39q0 6 6 6h14q6 0 6-6V19M19 19h6a7 7 0 0 1 0 14H19"
              fill="none"
              stroke="url(#piBrandGrad)"
              stroke-width="7"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
          <span class="brand-name">项目初始化向导</span>
        </div>
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
      </div>
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
  font-family: -apple-system, 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  color: #1b1f23;
  background: #fff;
}
* { box-sizing: border-box; margin: 0; }
body { min-height: 100vh; background: #fff; }
.app {
  max-width: 100%;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}
.app-header {
  background: #1b1f23;
}
.header-inner {
  max-width: inherit;
  margin: 0 auto;
  padding: 0 16px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.brand { display: flex; align-items: center; gap: 10px; color: #fff; }
.brand-mark { display: block; border-radius: 5px; }
.brand-name { font-size: 16px; font-weight: 600; letter-spacing: 0.5px; }
.app-nav { display: flex; gap: 4px; }
.nav-btn {
  padding: 8px 14px;
  border: none;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: rgba(255, 255, 255, 0.72);
  cursor: pointer;
  font-size: 14px;
}
.nav-btn:hover { color: #fff; }
.nav-btn.active { color: #6db33f; border-bottom-color: #6db33f; font-weight: 600; }
.app-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  padding: 32px 16px 24px;
}
.app-main > * { flex: 1; }
</style>
