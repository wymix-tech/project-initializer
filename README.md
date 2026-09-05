# project-initializer

Java 项目脚手架生成平台（配置驱动型，对标 [Spring Initializr](https://start.spring.io/)）。

开发者通过表单选择 Java 版本、Spring Boot 版本（官方 / 公司定制版）、依赖模块，填写项目元信息后，一键生成完整可运行的 Maven / Gradle 项目压缩包。版本清单、starter 清单、兼容矩阵、生成模板全部配置文件驱动——**新增 starter / 版本零代码修改**。

## 解决的问题

- **重复搭建脚手架**：表单化配置，秒级生成标准项目骨架
- **框架碎片化**：版本清单 + starter 清单平台统一维护，团队标准收敛
- **重复造轮子**：内部定制 starter 统一接入，选中即自动生成配置代码（核心差异化能力）

## 核心功能

- **版本联动**：选定 Java 版本后仅展示兼容的 Spring Boot 版本（兼容矩阵配置驱动，如 Java 8 → Boot 2.x，Java 17+ → Boot 3.x）
- **依赖模块选择**：官方 starter 与公司内部 starter 统一清单，支持分类浏览
- **starter 联动生成**：选中 starter 后自动写入依赖坐标，并生成对应配置类（`@ConfigurationProperties`）、集成抽象类引用与 `application.yml` 配置片段
- **项目元信息**：Group / Artifact / Package name，命名合法性校验，Package 由 Group + Artifact 自动推导（可修改）
- **构建工具双轨**：Maven / Gradle 任选
- **产物输出**：内存流式打包 zip（`{artifact}.zip`），失败返回明确错误明细，不产出半成品

## 系统架构

```
前端 SPA（表单/下载）
        │
        ▼
initializer-app（REST API + 装配）
        │
        ▼
initializer-core（校验 / 生成引擎 / 联动合并，无 Spring 依赖）
        │
        ▼
classpath: catalog/ 清单与模板 + 制品库同步适配器（M3）
```

## 模块

| 模块 | 说明 |
|---|---|
| initializer-api | 对外 DTO、枚举、错误码（纯 POJO） |
| initializer-core | 核心域：校验、生成引擎、联动合并（无 Spring 依赖，可独立复用） |
| initializer-app | Spring Boot 启动模块：Controller、配置加载、管理后台（M3） |
| initializer-web | 前端工程（Vue 3 + TypeScript + Vite + Pinia） |

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/metadata/java-versions` | Java 版本清单 |
| GET | `/api/metadata/boot-versions?java=17` | 联动过滤后的 Boot 版本 |
| GET | `/api/metadata/starters?boot=3.2.5` | 可用 starter 清单（支持 `q` / `category` 过滤） |
| POST | `/api/generate` | 生成并流式返回 zip（校验失败返回 422 + 明细） |
| GET | `/api/validate?field=group&value=…` | 元信息即时校验 |

## 快速开始

### 环境要求

- JDK 17+、Maven 3.9+
- Node.js 20+（前端开发）

### 后端启动

```bash
mvn -B -DskipTests install
cd initializer-app && mvn spring-boot:run
# 服务监听 http://localhost:8080
```

管理端鉴权 token 通过环境变量 `INITIALIZER_ADMIN_TOKEN` 覆盖（默认 `admin-dev-token`，仅限开发）。

### 前端启动

```bash
cd initializer-web
npm install
npm run dev
# 开发模式代理 /api 到 http://localhost:8080
```

### 生成项目

访问前端页面（或直接调用 API）：

1. 选择 Java 版本（8 / 11 / 17 / 21）
2. 选择 Spring Boot 版本（自动联动过滤）
3. 勾选依赖模块（如 Spring Web、内部 Redis 封装 starter）
4. 填写 Group / Artifact / Package name，选择 Maven 或 Gradle
5. 点击生成，下载 `{artifact}.zip`，解压后 `mvn spring-boot:run`（或 `gradle bootRun`）可直接启动

## 配置驱动（扩展方式）

版本与 starter 清单位于 `initializer-core/src/main/resources/catalog/`：

- `metadata.yml`：Java 版本清单、Boot 版本清单（官方 / 定制）、兼容矩阵、starter 依赖坐标映射
- `starters/*.yml`：单 starter 一个描述文件——依赖坐标 + 联动规则（配置类模板、application.yml 片段、集成抽象类）
- `validation.yml`：Group / Artifact / Package 命名校验规则

**新增 starter = 新增一个 yml + 模板文件，生成器代码零改动。**

管理后台（M3）支持清单/模板在线维护：草稿 → 发布 → 热生效；刷新失败自动回滚旧配置。支持从内部制品库自动同步 starter 清单（`INITIALIZER_SYNC_REMOTE_ENDPOINT`），与手工配置合并（同名以手工为准）。

> 📘 **完整配置教程见 [docs/guide/configuration.md](docs/guide/configuration.md)**：涵盖 metadata.yml 兼容矩阵、starter 联动规则与模板编写、校验规则调整、管理后台草稿-发布-回滚 API 及制品库同步接入。

## 质量门禁（CI 强制）

| 门禁 | 工具 | 卡点 |
|---|---|---|
| 单元测试 | JUnit 5 + JaCoCo | 成功率 100%，行/分支覆盖率 ≥ 80% |
| 突变测试 | PIT（后端）/ Stryker（前端） | 杀死率 ≥ 70% |
| 注释一致性 | PR 评审项 | 公共 API 强制 Javadoc，语义偏离一票否决 |

## 本地构建

```bash
mvn verify                                                          # 含 JaCoCo 覆盖率门禁
mvn org.pitest:pitest-maven:mutationCoverage -pl initializer-core,initializer-app   # 后端突变测试
cd initializer-web && npm run test:coverage                         # 前端单测 + 覆盖率
cd initializer-web && npm run test:mutation                         # 前端突变测试
```

## 文档

- 技术方案与里程碑设计见 `docs/design/` 及仓库 Issue（#1 需求、#2 PRD、#3 技术方案、#4~#6 里程碑 M1~M3）

## License

本项目基于 [MIT License](LICENSE) 开源。
