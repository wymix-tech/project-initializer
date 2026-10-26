<script setup lang="ts">
import { ref } from 'vue'
import WizardForm from '@/components/WizardForm.vue'
import ToastHost from '@/components/ToastHost.vue'
import AdminPanel from '@/components/AdminPanel.vue'

/** 视图切换：生成向导 / 管理后台（M3 评审遗留项 2）。 */
const view = ref<'wizard' | 'admin'>('wizard')
</script>

<template>
  <a class="skip-link" href="#main-content">跳至主要内容</a>
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
          <div><span class="brand-name">项目初始化向导</span><span class="brand-caption">PROJECT INITIALIZER</span></div>
        </div>
        <nav class="app-nav" aria-label="主导航">
          <button
            type="button"
            class="nav-btn"
            :class="{ active: view === 'wizard' }"
            :aria-current="view === 'wizard' ? 'page' : undefined"
            @click="view = 'wizard'"
          >
            项目生成
          </button>
          <button
            type="button"
            class="nav-btn"
            :class="{ active: view === 'admin' }"
            :aria-current="view === 'admin' ? 'page' : undefined"
            @click="view = 'admin'"
          >
            管理后台
          </button>
        </nav>
      </div>
    </header>
    <main id="main-content" class="app-main" tabindex="-1">
      <WizardForm v-if="view === 'wizard'" />
      <AdminPanel v-else />
    </main>
    <ToastHost />
  </div>
</template>

<style>
:root {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #21342e; background: #f4f7f5; font-synthesis: none;
  --accent: #167653; --accent-soft: #edf7f1; --line: #dee7e1; --muted: #65776e;
}
* { box-sizing: border-box; margin: 0; }
html { scroll-padding-bottom: 160px; }
body { min-width: 320px; min-height: 100vh; }
button, input, select, textarea { font: inherit; }
button, a, input, select, textarea { -webkit-tap-highlight-color: transparent; }
button { touch-action: manipulation; }
button:not(:disabled), a, input, select, textarea { transition: background-color .16s, border-color .16s, box-shadow .16s; }
:focus-visible { outline: 3px solid #3a9a77; outline-offset: 3px; }
input:focus-visible, select:focus-visible, textarea:focus-visible { outline: 2px solid #3a9a77; outline-offset: 2px; }
input[type=checkbox], input[type=radio] { accent-color: var(--accent); width: 16px; height: 16px; }
a { color: var(--accent); text-underline-offset: 3px; }
.skip-link { position: absolute; width: 1px; height: 1px; padding: 0; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
.skip-link:focus { position: fixed; top: 12px; left: 16px; width: auto; height: auto; padding: 12px; clip-path: none; background: white; border-radius: 8px; z-index: 1100; }
.app { min-height: 100vh; display: flex; flex-direction: column; }
.app-header { background: #fff; border-bottom: 1px solid var(--line); }
.header-inner { max-width: 1440px; width: 100%; margin: 0 auto; padding: 0 40px; min-height: 80px; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.brand { display: flex; align-items: center; gap: 12px; }
.brand-mark { display: block; width: 42px; height: 42px; }
.brand-name { display: block; font-size: 17px; font-weight: 700; letter-spacing: .3px; }
.brand-caption { display: block; margin-top: 4px; font-size: 10px; letter-spacing: 1.8px; color: var(--muted); }
.app-nav { display: flex; gap: 4px; background: #f1f5f2; padding: 5px; border-radius: 12px; }
.nav-btn { padding: 10px 18px; border: 1px solid transparent; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font-size: 14px; }
.nav-btn:hover { color: var(--accent); background: #e6eee8; }
.nav-btn.active { background: white; color: var(--accent); font-weight: 600; border-color: var(--line); box-shadow: 0 2px 4px #193c2310; }
.app-main { width: 100%; max-width: 1440px; margin: 0 auto; flex: 1; padding: 36px 40px; }
@media (max-width: 600px) {
  .header-inner { padding: 14px 20px; flex-wrap: wrap; }
  .app-nav { width: 100%; }
  .nav-btn { flex: 1; }
  .app-main { padding: 24px 16px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { transition: none !important; animation: none !important; scroll-behavior: auto !important; } }
</style>
