import { describe, expect, it } from 'vitest'
import { buildStarterYaml, findUnknownPlaceholders, DOC_SEPARATOR } from '@/starter-yaml-builder'
import { emptyStarterConfigForm, type StarterConfigForm } from '@/types/starter-config'

/**
 * starter 配置 YAML 渲染器单测：覆盖空联动、全量联动、多依赖、占位符白名单等边界，
 * 确保产出与后端 YamlStarterParser 契约一致。
 */
describe('buildStarterYaml', () => {
  it('官方 starter 无联动渲染 linkages: {}', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      category: 'web',
      maintainer: 'official',
      description: 'Spring MVC + 内嵌 Tomcat',
      dependencies: [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('id: web')
    expect(yml).toContain('category: web')
    expect(yml).toContain('maintainer: official')
    expect(yml).toContain('description: Spring MVC + 内嵌 Tomcat')
    expect(yml).toContain('- groupId: org.springframework.boot')
    expect(yml).toContain('  artifactId: spring-boot-starter-web')
    // 官方 starter 无 version（走 Boot BOM）
    expect(yml).not.toContain('version:')
    expect(yml).toContain('linkages: {}')
  })

  it('描述为空时缺省取 id', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo-starter',
      description: '',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo-starter', version: '' }],
    }
    expect(buildStarterYaml(form)).toContain('description: demo-starter')
  })

  it('内部 starter 多依赖且显式 version', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'xxx-redis-spring-boot-starter',
      category: 'middleware',
      maintainer: 'internal',
      description: '内部 Redis 封装',
      dependencies: [
        { rowId: 'r1', groupId: 'com.company', artifactId: 'xxx-redis-spring-boot-starter', version: '1.2.0' },
        { rowId: 'r2', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-data-redis', version: '' },
      ],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('- groupId: com.company')
    expect(yml).toContain('  version: "1.2.0"')
    expect(yml).toContain('- groupId: org.springframework.boot')
    // 第二条无 version
    const lines = yml.split('\n')
    const secondDepIdx = lines.findIndex((l) => l.includes('- groupId: org.springframework.boot'))
    expect(lines[secondDepIdx + 1]).toContain('artifactId: spring-boot-starter-data-redis')
    // 下一条不应紧跟 version
    expect(lines[secondDepIdx + 2]).not.toContain('version')
  })

  it('全量联动渲染 config-package / templates / fragments / abstract-classes', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'xxx-redis-spring-boot-starter',
      category: 'middleware',
      maintainer: 'internal',
      description: '内部 Redis 封装',
      dependencies: [{ rowId: 'r1', groupId: 'com.company', artifactId: 'xxx-redis-spring-boot-starter', version: '1.2.0' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 'starters/xxx-redis/RedisConfig.java.ftl', target: 'src/main/java/{packagePath}/config/RedisConfig.java' }],
      ymlFragments: [{ rowId: 'r1', source: 'starters/xxx-redis/application-redis.yml', mergeStrategy: 'append' }],
      abstractClasses: [{ rowId: 'r1', fqcn: 'com.company.redis.RedisSupport', alias: 'redisSupport' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('config-package: "{basePackage}.config"')
    expect(yml).toContain('templates:')
    expect(yml).toContain('- source: starters/xxx-redis/RedisConfig.java.ftl')
    expect(yml).toContain('  target: "src/main/java/{packagePath}/config/RedisConfig.java"')
    expect(yml).toContain('application-yml-fragments:')
    expect(yml).toContain('- source: starters/xxx-redis/application-redis.yml')
    expect(yml).toContain('  merge-strategy: append')
    expect(yml).toContain('abstract-classes:')
    expect(yml).toContain('- fqcn: com.company.redis.RedisSupport')
    expect(yml).toContain('  alias: redisSupport')
    // 非空 linkages 不应出现 {}
    expect(yml).not.toMatch(/linkages: \{\}/)
  })

  it('override 合并策略正确渲染', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo', version: '' }],
      ymlFragments: [{ rowId: 'r1', source: 'a.yml', mergeStrategy: 'override' }],
    }
    expect(buildStarterYaml(form)).toContain('merge-strategy: override')
  })

  it('含空格的描述不加引号（普通多词标量，与样例一致）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      description: 'a "quoted" desc',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('description: a "quoted" desc')
  })

  it('纯数字版本号加引号防 YAML 解析为数字', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'com.example', artifactId: 'demo', version: '2024' }],
    }
    const yml = buildStarterYaml(form)
    expect(yml).toContain('version: "2024"')
  })

  it('产出可追加为多文档草稿（含 DOC_SEPARATOR）', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'web',
      dependencies: [{ rowId: 'r1', groupId: 'org.springframework.boot', artifactId: 'spring-boot-starter-web', version: '' }],
    }
    const draft = 'id: old\n'
    const merged = `${draft.trimEnd()}${DOC_SEPARATOR}${buildStarterYaml(form)}`
    expect(merged).toContain('---')
    expect(merged.indexOf('id: old')).toBeLessThan(merged.indexOf('---'))
    expect(merged.indexOf('---')).toBeLessThan(merged.indexOf('id: web'))
  })
})

describe('findUnknownPlaceholders', () => {
  it('白名单占位符不报错', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 's.ftl', target: '{packagePath}/x.java' }],
    }
    expect(findUnknownPlaceholders(form)).toEqual([])
  })

  it('越界占位符被识别', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{basePackage}.config',
      templates: [{ rowId: 'r1', source: 's.ftl', target: '{unknwnPath}/x.java' }],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown).toContain('{unknwnPath}')
  })

  it('多字段越界占位符去重', () => {
    const form: StarterConfigForm = {
      ...emptyStarterConfigForm(),
      id: 'demo',
      dependencies: [{ rowId: 'r1', groupId: 'g', artifactId: 'a', version: '' }],
      configPackage: '{foo}',
      templates: [{ rowId: 'r1', source: '{foo}', target: '{foo}' }],
    }
    const unknown = findUnknownPlaceholders(form)
    expect(unknown.filter((u) => u === '{foo}').length).toBe(1)
  })

  it('无联动表单无占位符', () => {
    const form = emptyStarterConfigForm()
    form.id = 'web'
    expect(findUnknownPlaceholders(form)).toEqual([])
  })
})
