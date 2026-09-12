<script setup lang="ts">
import { useStarterConfigWizardStore } from '@/stores/starterConfigWizard'
import type { FragmentMergeStrategy } from '@/types/starter-config'

/**
 * 第 3 步：联动规则（全部可选——官方 starter 通常无需联动）。
 *
 * 四类联动：config-package（配置类包名）、templates（模板文件）、
 * application-yml-fragments（配置片段）、abstract-classes（集成抽象类）。
 * 占位符须在白名单内，越界会在预览步与保存时拦截。
 */
const wizard = useStarterConfigWizardStore()

const strategies: { value: FragmentMergeStrategy; label: string }[] = [
  { value: 'append', label: 'append（追加去重）' },
  { value: 'override', label: 'override（整体替换）' },
]
</script>

<template>
  <div>
    <h3>③ 联动规则<span class="scw-opt">（可选）</span></h3>
    <p class="scw-hint">
      选中本 starter 后自动生成的配置代码与 yml 片段。官方 starter 一般留空（直接下一步）；
      内部 starter 填写后会生成 RedisConfig 配置类、application.yml 片段等。
    </p>
    <p class="scw-hint">
      占位符仅允许 <code>{basePackage}</code> <code>{packagePath}</code>
      <code>{artifact}</code> <code>{group}</code> <code>{bootVersion}</code> <code>{javaVersion}</code>。
    </p>

    <!-- requires 依赖联动分组 -->
    <div class="block">
      <div class="block-head">
        <span>依赖联动 requires（一行一组，组内逗号分隔）</span>
      </div>
      <p class="scw-hint">
        单元素行 = 强制依赖（选中本 starter 时自动勾选并锁定）；
        多元素行 = OR 分组（仅提示，不自动勾选）。分组内任一被选即满足该分组，全部分组满足才算依赖满足。
      </p>
      <textarea
        :value="wizard.requiresText"
        class="requires-input"
        rows="2"
        placeholder="b, c&#10;d"
        @input="wizard.setRequires(($event.target as HTMLTextAreaElement).value)"
      ></textarea>
    </div>

    <!-- config-package -->
    <div class="field">
      <label for="scw-cp">配置类包名（config-package）</label>
      <input
        id="scw-cp"
        v-model="wizard.form.configPackage"
        type="text"
        placeholder="如 {basePackage}.config"
      />
    </div>

    <!-- templates -->
    <div class="block">
      <div class="block-head">
        <span>模板文件（templates）</span>
        <button type="button" class="btn-sm" @click="wizard.addTemplate()">+ 增加模板</button>
      </div>
      <p v-if="wizard.stepErrors.templates" class="field-error">{{ wizard.stepErrors.templates }}</p>
      <div v-for="t in wizard.form.templates" :key="t.rowId" class="pair-row">
        <input v-model="t.source" type="text" placeholder="source，如 starters/xxx/RedisConfig.java.ftl" class="pair-input" />
        <input v-model="t.target" type="text" placeholder="target，如 src/main/java/{packagePath}/config/RedisConfig.java" class="pair-input" />
        <button type="button" class="btn-icon" @click="wizard.removeTemplate(t.rowId)">✕</button>
      </div>
    </div>

    <!-- yml fragments -->
    <div class="block">
      <div class="block-head">
        <span>application.yml 片段</span>
        <button type="button" class="btn-sm" @click="wizard.addYmlFragment()">+ 增加片段</button>
      </div>
      <p v-if="wizard.stepErrors.ymlFragments" class="field-error">{{ wizard.stepErrors.ymlFragments }}</p>
      <div v-for="f in wizard.form.ymlFragments" :key="f.rowId" class="pair-row">
        <input v-model="f.source" type="text" placeholder="source，如 starters/xxx/application-redis.yml" class="pair-input" />
        <select v-model="f.mergeStrategy" class="pair-select">
          <option v-for="s in strategies" :key="s.value" :value="s.value">{{ s.label }}</option>
        </select>
        <button type="button" class="btn-icon" @click="wizard.removeYmlFragment(f.rowId)">✕</button>
      </div>
    </div>

    <!-- abstract classes -->
    <div class="block">
      <div class="block-head">
        <span>集成抽象类（abstract-classes）</span>
        <button type="button" class="btn-sm" @click="wizard.addAbstractClass()">+ 增加抽象类</button>
      </div>
      <p v-if="wizard.stepErrors.abstractClasses" class="field-error">{{ wizard.stepErrors.abstractClasses }}</p>
      <div v-for="a in wizard.form.abstractClasses" :key="a.rowId" class="pair-row">
        <input v-model="a.fqcn" type="text" placeholder="fqcn，如 com.company.redis.RedisSupport" class="pair-input" />
        <input v-model="a.alias" type="text" placeholder="alias，如 redisSupport" class="pair-input" />
        <button type="button" class="btn-icon" @click="wizard.removeAbstractClass(a.rowId)">✕</button>
      </div>
    </div>

    <p v-if="!wizard.hasLinkages" class="scw-empty-tip">
      当前未声明任何联动规则，将生成 <code>linkages: {}</code>（与官方 starter 一致），可直接下一步预览。
    </p>
  </div>
</template>

<style scoped>
h3 { font-size: 15px; margin-bottom: 8px; }
.scw-opt { color: #57606a; font-size: 13px; font-weight: normal; margin-left: 6px; }
.scw-hint { color: #57606a; font-size: 13px; margin-bottom: 8px; }
.scw-hint code { background: #f6f8fa; padding: 0 4px; border-radius: 4px; font-size: 12px; }
.field { margin-bottom: 16px; display: flex; flex-direction: column; gap: 4px; }
.field label { font-size: 13px; color: #57606a; }
.field input { padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; }
.requires-input {
  width: 100%; padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px;
  font-size: 14px; font-family: inherit; resize: vertical;
}
.block { margin-bottom: 16px; }
.block-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; font-size: 13px; color: #24292f; }
.pair-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.pair-input { flex: 1; padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; }
.pair-select { padding: 8px 10px; border: 1px solid #d0d7de; border-radius: 6px; font-size: 14px; background: #fff; max-width: 180px; }
.btn-sm { padding: 4px 10px; border-radius: 6px; border: 1px solid #d0d7de; background: #f6f8fa; cursor: pointer; font-size: 12px; }
.btn-icon {
  border: 1px solid #d0d7de; background: #f6f8fa; border-radius: 6px;
  width: 32px; height: 36px; cursor: pointer; color: #cf222e; flex-shrink: 0;
}
.field-error { color: #cf222e; font-size: 12px; margin-bottom: 6px; }
.scw-empty-tip { color: #1a7f37; font-size: 13px; background: #dafbe1; border: 1px solid #aceebb; border-radius: 6px; padding: 8px 12px; }
.scw-empty-tip code { background: rgba(0,0,0,0.06); padding: 0 4px; border-radius: 4px; }
</style>
