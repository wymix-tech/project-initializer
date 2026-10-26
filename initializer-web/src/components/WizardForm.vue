<script setup lang="ts">
import { onMounted } from 'vue'
import { useWizardStore } from '@/stores/wizard'
import StepJava from '@/components/StepJava.vue'
import StepBoot from '@/components/StepBoot.vue'
import StepStarters from '@/components/StepStarters.vue'
import StepMeta from '@/components/StepMeta.vue'

const wizard = useWizardStore()

onMounted(() => {
  wizard.loadJavaVersions()
})
</script>

<template>
  <section class="wizard">
    <header class="page-intro">
      <div>
        <p class="eyebrow">开始构建 · START BUILDING</p>
        <h1>让好项目，从这里开始。</h1>
        <p class="intro-copy">选择技术栈，按需搭配依赖，一键生成可运行的项目骨架。</p>
      </div>
      <span class="intro-badge">Java 生态 · 开箱即用</span>
    </header>
    <div v-if="wizard.loading" class="loading-status" role="status">正在加载兼容版本与依赖…</div>
    <div class="wizard-grid" :aria-busy="wizard.loading">
      <div class="wizard-left">
        <div class="config-card">
          <div class="card-heading">
            <span class="step-number">01</span>
            <div><h2>运行环境</h2><p>选择 Java 与兼容的 Spring Boot 版本</p></div>
          </div>
          <StepJava />
          <StepBoot />
        </div>
        <div class="config-card">
          <div class="card-heading">
            <span class="step-number">02</span>
            <div><h2>项目配置</h2><p>定义项目坐标与构建方式</p></div>
          </div>
          <StepMeta />
        </div>
      </div>
      <aside id="starter-selection" class="wizard-right">
        <div class="deps-head">
          <div class="card-heading">
            <span class="step-number">03</span>
            <div><h2>添加依赖</h2><p>组合项目需要的能力</p></div>
          </div>
          <span class="deps-count">已选 {{ wizard.selectedStarters.size }}</span>
        </div>
        <StepStarters />
      </aside>
    </div>
    <footer v-if="wizard.errorMessage" class="wizard-error" role="alert">
      {{ wizard.errorMessage }}
    </footer>
    <ul v-if="wizard.generationBlockers.length" class="wizard-error" aria-live="polite">
      <li v-for="(reason, index) in wizard.generationBlockers" :key="index">
        <a :href="reason.target">{{ reason.message }}</a>
      </li>
    </ul>
    <div class="wizard-actions">
      <div class="generation-summary">
        <strong>{{ wizard.meta.artifact || '未命名项目' }}<span class="archive-tag">.zip</span></strong>
        <p>Java {{ wizard.selectedJava || '待选择' }} · Boot {{ wizard.selectedBoot || '待选择' }} · {{ wizard.meta.buildTool === 'maven' ? 'Maven' : 'Gradle' }} · {{ wizard.selectedStarters.size }} 项依赖</p>
      </div>
      <button
        type="button"
        class="btn-generate"
        :disabled="!wizard.canGenerate || wizard.submitState === 'generating'"
        @click="wizard.submit()"
      >
        {{ wizard.submitState === 'generating' ? '生成中…' : '生成并下载' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.wizard { display: flex; flex-direction: column; gap: 24px; }
.page-intro { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 4px 0 8px; }
.eyebrow { font-size: 11px; color: var(--accent); letter-spacing: 1.6px; font-weight: 700; margin-bottom: 12px; }
h1 { font-size: clamp(24px, 3vw, 32px); letter-spacing: -.8px; line-height: 1.4; }
.intro-copy { color: var(--muted); font-size: 14px; line-height: 1.7; margin-top: 10px; }
.intro-badge { padding: 8px 12px; background: #e6f0e9; color: var(--accent); border-radius: 24px; font-size: 12px; white-space: nowrap; }
.wizard-grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.15fr); gap: 24px; align-items: start; }
.wizard-left { display: grid; gap: 24px; min-width: 0; }
.config-card, .wizard-right { background: white; border: 1px solid var(--line); border-radius: 16px; padding: 26px; box-shadow: 0 4px 18px #23473304; min-width: 0; }
.card-heading { display: flex; gap: 12px; align-items: center; margin-bottom: 24px; }
.card-heading h2 { font-size: 16px; font-weight: 650; }
.card-heading p { color: var(--muted); font-size: 12px; margin-top: 5px; line-height: 1.6; }
.step-number { display: grid; place-items: center; width: 36px; height: 36px; flex-shrink: 0; border-radius: 10px; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 700; }
.config-card :deep(.config-section + .config-section) { margin-top: 24px; }
.deps-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.deps-head .card-heading { margin-bottom: 12px; }
.deps-count { font-size: 12px; color: var(--accent); background: var(--accent-soft); border-radius: 20px; padding: 5px 10px; white-space: nowrap; }
.wizard-error { color: #a92532; font-size: 13px; padding: 14px 20px; border: 1px solid #f1cbd0; background: #fff4f4; border-radius: 10px; line-height: 1.8; overflow-wrap: anywhere; }
ul.wizard-error { padding-left: 32px; }
.wizard-error a { color: inherit; }
.loading-status { padding: 12px 16px; background: var(--accent-soft); color: var(--accent); border-radius: 10px; font-size: 13px; }
.wizard-actions { position: sticky; bottom: 16px; z-index: 5; padding: 18px 24px; background: #fff; border: 1px solid var(--line); border-radius: 14px; box-shadow: 0 8px 32px #244b3010; display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.generation-summary { min-width: 0; }
.generation-summary strong { display: block; overflow-wrap: anywhere; font-size: 15px; }
.generation-summary p { color: var(--muted); font-size: 12px; line-height: 1.8; margin-top: 5px; overflow-wrap: anywhere; }
.archive-tag { color: var(--muted); font-weight: 400; }
.btn-generate { padding: 13px 28px; min-height: 46px; font-size: 14px; font-weight: 600; border: 1px solid var(--accent); border-radius: 9px; background: var(--accent); color: white; cursor: pointer; flex-shrink: 0; }
.btn-generate:hover:not(:disabled) { background: #105c40; box-shadow: 0 4px 10px #16765324; }
.btn-generate:disabled { background: #e1e8e3; border-color: #d4ded7; color: #65776e; cursor: not-allowed; }
@media (max-width: 900px) { .wizard-grid { grid-template-columns: 1fr; } .intro-badge { display: none; } }
@media (max-width: 600px) { .config-card, .wizard-right { padding: 20px; } .wizard-actions { position: static; flex-direction: column; align-items: stretch; bottom: 8px; padding: 16px; } .wizard { gap: 18px; } }
</style>
