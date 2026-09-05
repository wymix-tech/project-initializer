<script setup lang="ts">
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'
import type { StarterCategory, StarterMaintainer } from '@/types/starter-config'

/**
 * 第 1 步：starter 基本信息（id / 分类 / 维护方 / 描述）。
 *
 * id 即时校验命名规范与草稿查重（与后端 StarterCatalog 装载期口径一致），
 * 命名非法或与草稿现有 id 重复时阻止前进。
 */
const wizard = useStarterConfigWizardStore()

const categories: { value: StarterCategory; label: string }[] = [
  { value: 'web', label: 'Web' },
  { value: 'data', label: '数据' },
  { value: 'middleware', label: '中间件' },
  { value: 'tool', label: '工具' },
]

const maintainers: { value: StarterMaintainer; label: string }[] = [
  { value: 'official', label: '官方' },
  { value: 'internal', label: '内部' },
]
</script>

<template>
  <div>
    <h3>① 基本信息</h3>
    <p class="scw-hint">填写 starter 的唯一标识与分类。id 建议与依赖 artifactId 保持一致，便于追溯。</p>

    <div class="field">
      <label for="scw-id">Starter ID</label>
      <input
        id="scw-id"
        v-model="wizard.form.id"
        type="text"
        placeholder="如 web、xxx-redis-spring-boot-starter"
      />
      <p v-if="wizard.stepErrors.id" class="field-error">{{ wizard.stepErrors.id }}</p>
    </div>

    <div class="field-row">
      <div class="field">
        <label for="scw-category">分类</label>
        <select id="scw-category" v-model="wizard.form.category">
          <option v-for="c in categories" :key="c.value" :value="c.value">{{ c.label }}</option>
        </select>
      </div>
      <div class="field">
        <label for="scw-maintainer">维护方</label>
        <select id="scw-maintainer" v-model="wizard.form.maintainer">
          <option v-for="m in maintainers" :key="m.value" :value="m.value">{{ m.label }}</option>
        </select>
      </div>
    </div>

    <div class="field">
      <label for="scw-desc">描述</label>
      <input
        id="scw-desc"
        v-model="wizard.form.description"
        type="text"
        placeholder="如 Spring MVC + 内嵌 Tomcat"
      />
      <p v-if="wizard.stepErrors.description" class="field-error">{{ wizard.stepErrors.description }}</p>
    </div>
  </div>
</template>

<style scoped>
h3 { font-size: 15px; margin-bottom: 8px; }
.scw-hint { color: #57606a; font-size: 13px; margin-bottom: 12px; }
.field-row { display: flex; gap: 12px; }
.field-row .field { flex: 1; }
.field { margin-bottom: 14px; display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 13px; color: #57606a; }
.field input,
.field select {
  padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; background: #fff;
}
.field-error { color: #cf222e; font-size: 12px; }
</style>
