import type { AdminConfigKind } from '@/types/api'

/**
 * 管理端各配置草稿的示例模板与字段释义（降低 YAML 手写门槛）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>示例内容与后端解析器同构，逐字段带中文注释，可直接保存并发布
 *       （各 kind 示例的 starter ID 互相自洽，避免悬空引用被发布期拒绝）</li>
 *   <li>字段释义与示例同源维护，避免文档漂移；纯常量导出，无副作用</li>
 * </ul>
 *
 * <p>各 kind 结构依据：
 * metadata → {@code catalog/metadata.yml}；starter → {@code catalog/starters/*.yml}（多文档）；
 * conflict-rule → {@code rules} 节；template → 多文档 {@code {name, content}}。
 */

/** 单个字段的用途释义。 */
export interface ConfigFieldDoc {
  /** 字段路径（数组以 [] 标注，映射键以 &lt;key&gt; 标注） */
  path: string
  /** 是否必填 */
  required: boolean
  /** 该字段的用途说明 */
  purpose: string
}

/** 一个配置种类的示例 + 字段释义 + 校验注意事项。 */
export interface AdminConfigTemplate {
  /** 该配置种类的一句话定位 */
  summary: string
  /** 带逐字段注释的示例 YAML（可直接发布） */
  sample: string
  /** 字段用途释义表 */
  fields: ConfigFieldDoc[]
  /** 发布期校验注意事项 */
  notes: string[]
}

/** 元数据配置（与 catalog/metadata.yml 同构）：版本清单 + 兼容矩阵 + starter 元信息。 */
const METADATA_TEMPLATE: AdminConfigTemplate = {
  summary: '定义可选的 Java 版本、Spring Boot 版本、两者兼容矩阵，以及 starter 的展示名与依赖坐标。',
  sample: `# ===== 元数据配置（与 catalog/metadata.yml 同构）=====
# 首发快照必需项：本配置为空时发布会被拒绝。

# javaVersions：前端「Java 版本」下拉框的数据源
javaVersions:
  - id: "17"                        # 必填。版本标识（前端回传值），需与下方 supportedJavaVersions 元素一致
    label: "Java 17"                # 必填。下拉框展示文案
    deprecated: false               # 可选。是否已废弃，true 时前端打废弃标记但仍可选
    recommendedBootVersion: "3.2.5" # 可选。选中该 Java 版本后默认联动的 Boot 版本 ID
  - id: "21"
    label: "Java 21"
    deprecated: false
    recommendedBootVersion: "3.2.5"
  - id: "8"
    label: "Java 8"
    deprecated: false
    recommendedBootVersion: "2.7.18"

# bootVersions：可选的 Spring Boot 版本清单
bootVersions:
  - id: "3.2.5"                     # 必填。版本标识，写入生成项目的 pom.xml / build.gradle
    label: "Spring Boot 3.2.5"      # 必填。下拉框展示文案
    official: true                  # 可选。是否官方发布版本，公司内部定制版填 false
    supportedJavaVersions: ["17", "21"] # 必填。兼容的 Java 版本 ID 列表，版本联动过滤的唯一依据
    frameworkParent:                # 可选。该版本生成项目挂接的框架父 POM（不同 Boot 版本可不同）
      groupId: com.yourcompany      # 必填（声明时）。Maven groupId
      artifactId: your-framework-parent # 必填（声明时）。Maven artifactId
      version: "1.0.0-SNAPSHOT"     # 必填（声明时）。支持 \${revision} 等 Maven 属性占位写法
    starters:                       # 必填。该 Boot 版本可选的 starter ID 列表，须在下方 starters 节登记
      - web
      - data-jpa
      - actuator
      - xxx-redis-spring-boot-starter
  - id: "2.7.18"
    label: "Spring Boot 2.7.18"
    official: true
    supportedJavaVersions: ["8"]
    starters:
      - web
      - data-jpa
      - actuator
      - xxx-redis-spring-boot-starter

# 说明：本配置不再维护 starters 节——展示名、依赖坐标、是否必选（required）、
# 依赖联动（requires）全部在「starter 清单」（kind = starter）里配置，发布时自动联动合并。
`,
  fields: [
    { path: 'javaVersions[].id', required: true, purpose: 'Java 版本标识，前端下拉框回传值，需与 bootVersions.supportedJavaVersions 元素对齐' },
    { path: 'javaVersions[].label', required: true, purpose: '下拉框展示文案，如「Java 17」' },
    { path: 'javaVersions[].deprecated', required: false, purpose: '是否已废弃，true 时前端打废弃标记但仍允许选择' },
    { path: 'javaVersions[].recommendedBootVersion', required: false, purpose: '选中该 Java 版本后默认联动的 Boot 版本 ID' },
    { path: 'bootVersions[].id', required: true, purpose: 'Boot 版本标识，写入生成项目的 pom.xml / build.gradle' },
    { path: 'bootVersions[].label', required: true, purpose: '下拉框展示文案，如「Spring Boot 3.2.5」' },
    { path: 'bootVersions[].official', required: false, purpose: '是否官方发布版本，公司内部定制版填 false' },
    { path: 'bootVersions[].supportedJavaVersions', required: true, purpose: '兼容的 Java 版本 ID 列表，是「选 Java 后过滤 Boot」的唯一依据' },
    { path: 'bootVersions[].frameworkParent', required: false, purpose: '该版本生成项目挂接的框架父 POM（写入 pom.xml 的 <parent>），不同 Boot 版本可不同；也可在顶层声明一次作为全局默认，条目内未声明时继承' },
    { path: 'bootVersions[].frameworkParent.groupId', required: false, purpose: '父 POM groupId；声明 frameworkParent 时必填' },
    { path: 'bootVersions[].frameworkParent.artifactId', required: false, purpose: '父 POM artifactId；声明 frameworkParent 时必填' },
    { path: 'bootVersions[].frameworkParent.version', required: false, purpose: '父 POM version，支持 ${revision} 等 Maven 属性占位写法；声明 frameworkParent 时必填' },
    { path: 'bootVersions[].starters', required: true, purpose: '该 Boot 版本可选的 starter ID 列表，每项必须在 starters 节登记，否则发布期悬空引用拒绝' },
    { path: 'bootVersions[].starters', required: false, purpose: '【保留】该 Boot 版本可引用的 starter ID 白名单；省略或为空即该版本对全部 starter 清单条目可选；列出的 ID 必须在 starter 清单中存在，否则不可见' },
  ],
  notes: [
    'starter 的展示名、依赖坐标、是否必选（required）、依赖联动（requires）全部在「starter 清单」（kind = starter）里维护，本配置不再有 starters 节，发布时自动联动合并。',
    'bootVersions.starters 保留：用于声明该 Boot 版本可引用哪些 starter；引用的 ID 必须有对应的 starter 清单条目，否则该 starter 不可见。',
    'frameworkParent 建议声明在每个 Boot 版本条目内（不同版本可挂不同父 POM）；groupId/artifactId/version 三项任缺其一即装载失败，整节省略时回退官方 spring-boot-starter-parent。',
    '版本 ID 与 starter ID 不得重复，重复即装载失败。',
    '本配置为首发快照必需项，空白会导致发布被拒绝。',
  ],
}

/** starter 清单（与 catalog/starters/*.yml 同构，多文档）：依赖坐标 + 选中后的联动生成规则。 */
const STARTER_TEMPLATE: AdminConfigTemplate = {
  summary: 'starter 的权威数据源：依赖坐标（含版本）、展示名、分类、维护方与「选中后自动生成」的联动规则；多个 starter 用 --- 分隔成多文档。发布后自动合并进元数据，无需在 metadata 里重复登记。',
  sample: `# ===== starter 清单（与 catalog/starters/*.yml 同构）=====
# 多个 starter 以 --- 分隔为多个 YAML 文档；下方示例含「无联动」与「全联动」两种典型形态。

# ---- 文档 1：官方 starter（无联动规则，仅依赖坐标）----
id: web                             # 必填。starter 唯一 ID，与 metadata 的 bootVersions[].starters 引用对齐
category: web                       # 必填。分类，用于前端分组浏览：web / data / middleware / tool
maintainer: official                # 必填。维护方：official（Spring 官方）/ internal（公司内部）
description: Spring MVC + 内嵌 Tomcat # 可选。一句话说明；同时作为前端展示名（推荐填写）
required: false                     # 可选。true = 必选：前端默认勾选且禁止取消，生成时后端兜底补齐
requires: []                        # 可选。依赖联动（AND-of-OR-groups）：
                                    #       requires: [b, c]      → 需要 b 和 c（扁平写法，历史语义）
                                    #       requires: [[b, c]]    → 需要 b 或 c（b、c 互斥时只选其一即可）
                                    #       requires: [[b, c], d] → 需要 (b 或 c) 且 d
                                    #       前端不自动勾选也不锁定这些项，仅提示「需要 …」，未满足时生成期拒绝
bootVersions: []                    # 可选。本条目关联的 Boot 版本（值取 metadata 的 bootVersions[].id）；
                                    #       不写/为空 = 对全部 Boot 版本可用；同一 Boot 版本内 ID 不允许重复，
                                    #       不同 Boot 版本允许重复（同一 id 可在不同版本声明不同坐标/版本）
dependencies:                       # 必填。选中后写入构建脚本的依赖坐标清单
  - groupId: org.springframework.boot # 必填。Maven groupId
    artifactId: spring-boot-starter-web # 必填。Maven artifactId
    # version: "3.2.5"             # 可选。显式版本；不填则交由 Spring Boot BOM 统一管理版本
linkages: {}                        # 必填（可为空映射）。无联动规则时写 {}

---
# ---- 文档 2：内部 starter（选中即联动生成配置类 + yml 片段）----
id: xxx-redis-spring-boot-starter
category: middleware
maintainer: internal
description: 内部 Redis 封装 starter（含集成抽象类 RedisSupport）
dependencies:
  - groupId: com.company
    artifactId: xxx-redis-spring-boot-starter
    version: "1.2.0"                # 可选。内部 starter 不在 Boot BOM 内，通常需显式声明版本
linkages:                           # 联动规则：选中该 starter 后自动生成的内容
  config-package: "{basePackage}.config" # 可选。生成配置类的目标包名，支持占位符
  templates:                        # 可选。按模板生成代码文件
    - source: "starters/xxx-redis/RedisConfig.java.ftl" # 必填。classpath 下模板路径（相对 templates/）
      target: "src/main/java/{packagePath}/config/RedisConfig.java" # 必填。生成到目标项目的文件路径，支持占位符
  application-yml-fragments:        # 可选。向生成项目的 application.yml 合并配置片段
    - source: "starters/xxx-redis/application-redis.yml" # 必填。classpath 下 yml 片段路径
      merge-strategy: append        # 必填。数组合并策略：append（追加去重，默认）/ override（整体替换）
  abstract-classes:                 # 可选。starter 提供的集成抽象类，生成配置类时自动 import 并引用
    - fqcn: "com.company.redis.RedisSupport" # 必填。抽象类全限定名
      alias: redisSupport           # 必填。在模板中的引用别名
`,
  fields: [
    { path: 'id', required: true, purpose: 'starter 全局唯一 ID，与 metadata 的 bootVersions[].starters 引用对齐' },
    { path: 'category', required: true, purpose: '分类标识，前端按此分组浏览；约定取值：web / data / middleware / tool' },
    { path: 'maintainer', required: true, purpose: '维护方：official（Spring 官方）/ internal（公司内部）' },
    { path: 'description', required: false, purpose: '一句话说明；同时作为前端展示名与检索关键字' },
    { path: 'required', required: false, purpose: '是否必选（原 metadata starters 节能力，已迁移到清单）：true 时前端默认勾选且禁止取消，生成时后端兜底补齐' },
    { path: 'requires', required: false, purpose: '依赖联动（AND-of-OR-groups）：分组内任一被选即满足该分组，全部分组满足才算依赖满足。扁平写法 [b, c] 表示需要 b 和 c；嵌套写法 [[b, c]] 表示需要 b 或 c（适合 b、c 互斥场景）。引用的 ID 必须在本清单登记，不得自引用；仅单选项分组参与成环判定（多选项分组可换分支打破环）' },
    { path: 'bootVersions', required: false, purpose: 'Boot 版本关联：值取 metadata 的 bootVersions[].id；不写即对全部版本可用。同一 Boot 版本内 ID 不允许重复（重复发布即拒），不同 Boot 版本允许重复（同一 id 可在不同版本声明不同坐标/版本/联动）' },
    { path: 'dependencies[].groupId', required: true, purpose: 'Maven groupId，选中后写入生成项目构建脚本' },
    { path: 'dependencies[].artifactId', required: true, purpose: 'Maven artifactId，选中后写入生成项目构建脚本' },
    { path: 'dependencies[].version', required: false, purpose: '显式版本号；缺省交由 Spring Boot BOM 统一管理（内部 starter 通常必填）' },
    { path: 'linkages', required: true, purpose: '联动规则集合，无规则时写 {}；决定「选中该 starter 后额外生成什么」' },
    { path: 'linkages.config-package', required: false, purpose: '生成配置类的目标包名，支持 {basePackage} 等占位符' },
    { path: 'linkages.templates[].source', required: false, purpose: 'classpath 模板路径（相对 templates/ 目录），作为代码生成来源' },
    { path: 'linkages.templates[].target', required: false, purpose: '生成到目标项目中的文件路径，支持 {packagePath} 等占位符' },
    { path: 'linkages.application-yml-fragments[].source', required: false, purpose: 'classpath 下 application.yml 片段路径，合并进生成项目的配置文件' },
    { path: 'linkages.application-yml-fragments[].merge-strategy', required: false, purpose: '数组合并策略：append（追加去重，默认）/ override（整体替换）' },
    { path: 'linkages.abstract-classes[].fqcn', required: false, purpose: 'starter 提供的集成抽象类全限定名，生成配置类时自动 import' },
    { path: 'linkages.abstract-classes[].alias', required: false, purpose: '集成抽象类在模板中的引用别名' },
  ],
  notes: [
    '每个 starter 一个 YAML 文档，文档之间以行首 --- 分隔。',
    '占位符仅允许 {basePackage} {packagePath} {artifact} {group} {bootVersion} {javaVersion}，越界占位符在装载期被拒绝。',
    '含占位符的值务必加引号（如 "{basePackage}.config"），否则会被 YAML 解析为流式映射。',
    'starter ID 重复即装载失败；制品库同步通道同名条目会被手工配置覆盖。',
  ],
}

/** 冲突规则（conflict-rules.yml 同构）：配置化的 starter 互斥约束。 */
const CONFLICT_RULE_TEMPLATE: AdminConfigTemplate = {
  summary: '声明 starter 之间的互斥约束，前端选依赖时即时提示，生成请求也会按此兜底拦截。',
  sample: `# ===== 冲突规则（与 conflict-rules.yml 同构）=====
# 声明哪些 starter 不可同时选中；前端即时提示 + 生成请求兜底拦截。

rules:                              # 必填。规则列表（可为空列表）
  - id: web-vs-webflux              # 必填。规则唯一 ID，同一份配置内不可重复
    type: pair                      # 必填。pair（恰好两个互斥）/ group（两个及以上互斥）
    starters: [web, webflux]        # 必填。涉及的 starter ID；pair 恰好 2 个，group 至少 2 个
    message: Web 与 WebFlux 不可同时选择，二者会注册冲突的 Web 容器 # 必填。冲突提示文案，前端与生成错误明细展示
  - id: cache-manager-exclusive
    type: group                     # group：组内任意两个及以上同时选中即冲突
    starters:
      - data-redis
      - xxx-redis-spring-boot-starter
    message: 缓存管理器实现互斥，仅可保留一种 Redis 封装
`,
  fields: [
    { path: 'rules', required: true, purpose: '冲突规则列表；无规则时可为 null 或空列表' },
    { path: 'rules[].id', required: true, purpose: '规则唯一 ID，同一份配置内不可重复' },
    { path: 'rules[].type', required: true, purpose: '规则类型：pair（两个 starter 互斥）/ group（组内任意两个及以上互斥）' },
    { path: 'rules[].starters', required: true, purpose: '涉及的 starter ID 列表；pair 恰好 2 个，group 至少 2 个' },
    { path: 'rules[].message', required: true, purpose: '冲突提示文案，前端即时展示，同时作为生成请求的兜底错误明细' },
  ],
  notes: [
    'pair 类型的 starters 必须恰好 2 个，group 类型至少 2 个，数量不符发布即拒绝。',
    'starters 引用的 ID 必须在 metadata 的 starters 节或 starter 清单中存在，悬空引用发布即拒绝。',
  ],
}

/** 生成模板（多文档 {name, content}）：覆盖 classpath 内置模板。 */
const TEMPLATE_TEMPLATE: AdminConfigTemplate = {
  summary: '覆盖 classpath 内置生成模板（相对 templates/ 目录），用于定制生成项目的骨架文件内容。',
  sample: `# ===== 生成模板覆盖（多文档，每文档 {name, content}）=====
# name 相对 classpath 的 templates/ 目录，与内置模板同名即覆盖该文件；发布期做 FreeMarker 语法预校验。

name: README.md.ftl                 # 必填。模板名，相对 templates/ 目录的路径，同名覆盖内置模板
content: |                          # 必填。FreeMarker 模板全文（块标量，保留换行）
  # \${artifact}

  由 project-initializer 生成的 Spring Boot 项目骨架。

  - Group: \`\${group}\`
  - Artifact: \`\${artifact}\`
  - Base Package: \`\${basePackage}\`
  - Java: \`\${javaVersion}\`
  - Spring Boot: \`\${bootVersion}\`
  - Starters: <#list starters as s>\`\${s}\`<#if s_has_next>, </#if></#list>

  ## 快速启动

  \`\`\`bash
  mvn spring-boot:run
  \`\`\`
`,
  fields: [
    { path: 'name', required: true, purpose: '模板名，相对 classpath 的 templates/ 目录；与内置模板同名即覆盖该文件' },
    { path: 'content', required: true, purpose: 'FreeMarker 模板全文，发布期做语法预校验，语法非法不允许进入快照' },
  ],
  notes: [
    '每个模板一个 YAML 文档，文档之间以行首 --- 分隔。',
    'name 在同一份配置内不可重复，重复即发布拒绝。',
    'content 建议用 | 块标量书写，避免缩进与转义问题。',
    '模板内可用 \${artifact} \${group} \${basePackage} \${javaVersion} \${bootVersion} 及 starters 等模型变量。',
  ],
}

/** 各配置种类的示例模板（键与后端 ConfigKind 对外小写形式一致）。 */
export const ADMIN_CONFIG_TEMPLATES: Record<AdminConfigKind, AdminConfigTemplate> = {
  metadata: METADATA_TEMPLATE,
  starter: STARTER_TEMPLATE,
  'conflict-rule': CONFLICT_RULE_TEMPLATE,
  template: TEMPLATE_TEMPLATE,
}

/**
 * 取指定配置种类的示例 YAML。
 *
 * @param kind 配置种类
 * @returns 带逐字段注释的示例全文（未登记种类返回空串）
 */
export function sampleOf(kind: AdminConfigKind): string {
  return ADMIN_CONFIG_TEMPLATES[kind]?.sample ?? ''
}
