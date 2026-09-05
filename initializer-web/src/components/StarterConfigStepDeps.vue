<script setup lang="ts">
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'

/**
 * 第 2 步：依赖坐标清单（GAV）。
 *
 * 支持多条依赖（一个 starter 可引入多个坐标）。groupId/artifactId 必填；
 * version 可空——官方 starter 由 Boot BOM 管理，内部 starter 显式声明版本。
 */
const wizard = useStarterConfigWizardStore()
</script>

<template>
  <div>
    <h3>② 依赖坐标</h3>
    <p class="scw-hint">
      一个 starter 可声明多条依赖。官方 starter 的 version 留空（由 Boot BOM 管理）；
      内部 starter 需显式声明 version。
    </p>

    <p v-if="wizard.stepErrors.dependencies" class="field-error">{{ wizard.stepErrors.dependencies }}</p>

    <div v-for="(dep, i) in wizard.form.dependencies" :key="dep.rowId" class="dep-row">
      <span class="dep-idx">#{{ i + 1 }}</span>
      <input
        v-model="dep.groupId"
        type="text"
        placeholder="groupId，如 org.springframework.boot"
        class="dep-input"
      />
      <input
        v-model="dep.artifactId"
        type="text"
        placeholder="artifactId，如 spring-boot-starter-web"
        class="dep-input"
      />
      <input
        v-model="dep.version"
        type="text"
        placeholder="version（可空）"
        class="dep-input dep-version"
      />
      <button
        type="button"
        class="btn-icon"
        :disabled="wizard.form.dependencies.length <= 1"
        :title="wizard.form.dependencies.length <= 1 ? '至少保留一条依赖' : '删除此行'"
        @click="wizard.removeDependency(dep.rowId)"
      >
        ✕
      </button>
    </div>

    <button type="button" class="btn" @click="wizard.addDependency()">+ 增加依赖</button>
  </div>
</template>

<style scoped>
h3 { font-size: 15px; margin-bottom: 8px; }
.scw-hint { color: #57606a; font-size: 13px; margin-bottom: 12px; }
.dep-row {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px;
}
.dep-idx { color: #57606a; font-size: 13px; width: 28px; flex-shrink: 0; }
.dep-input {
  flex: 1; padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px;
}
.dep-version { max-width: 140px; flex: 0 0 140px; }
.btn-icon {
  border: 1px solid #d0d7de; background: #f6f8fa; border-radius: 6px;
  width: 32px; height: 36px; cursor: pointer; color: #cf222e; flex-shrink: 0;
}
.btn-icon:disabled { opacity: 0.4; cursor: not-allowed; color: #8c959f; }
.btn { padding: 8px 16px; border-radius: 6px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 14px; }
.field-error { color: #cf222e; font-size: 12px; margin-bottom: 8px; }
</style>
