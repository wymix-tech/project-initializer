# project-initializer

Java 项目脚手架生成平台（配置驱动型，对标 Spring Initializr）。

## 模块

| 模块 | 说明 |
|---|---|
| initializer-api | 对外 DTO、枚举、错误码（纯 POJO） |
| initializer-core | 核心域：校验、生成引擎、联动合并（无 Spring 依赖） |
| initializer-app | Spring Boot 启动模块：Controller、配置加载、装配 |
| initializer-web | 前端工程（Vue3，M1.5 落地） |

## 质量门禁（CI 强制）

- UT 成功率 100%，行/分支覆盖率 ≥ 80%（JaCoCo）
- PIT 突变测试杀死率 ≥ 70%（pitest-maven）
- 注释一致性评审（公共 API 必须有 Javadoc，注释与实现语义一致）

## 本地构建

```bash
mvn verify            # 含 JaCoCo 覆盖率门禁
mvn org.pitest:pitest-maven:mutationCoverage -pl initializer-core,initializer-app   # 突变测试
```

Ref: #4（里程碑 M1）、#3（技术方案）

## CI 门禁详情（.cnb.yml）

| 门禁 | 工具 | 卡点 |
|---|---|---|
| 门禁1：单元测试 | JUnit5 + JaCoCo | 成功率 100%，行/分支覆盖 ≥ 80% |
| 门禁2：突变测试 | PIT (pitest-maven) | 杀死率 ≥ 70% |
| 门禁3：注释一致性 | PR 评审项 | 公共 API 强制 Javadoc，语义偏离一票否决 |

门禁事件覆盖：`main` push、`main` pull_request、`feat/*` push。
