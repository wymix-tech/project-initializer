# 浏览器端到端回归

测试真实前端产物、Spring Boot HTTP 接口与隔离 H2 数据库，不连接生产数据。

## 执行

需要 JDK 17+、Maven、Node.js 20.19+/22.12+、Python 3（仅用于 ZIP 产物断言），以及 Playwright Chromium。

```bash
# 仓库根目录：先构建当前后端；前端由 E2E 命令重建
mvn -B -DskipTests -Dskip.web=true package
cd initializer-web
npm ci
npx playwright install chromium
npm run test:e2e
```

已安装 Chrome/Chromium/headless-shell 时可设置 `E2E_CHROMIUM_PATH` 指向其可执行文件。也可设置 `E2E_BACKEND_JAR`、`E2E_API_PORT`（默认 18080）、`E2E_WEB_PORT`（默认 18173）、`E2E_ADMIN_TOKEN`（仅用于隔离测试服务）。

测试运行器自动启动后端与静态资源/API 代理，等待已提交的演示发布记录出现后捕获初始归档；每个用例恢复这份归档并重新发布。归档由服务进程保存，测试 worker 重启也不会重新捕获被修改的草稿。后端使用内存数据库，终止运行后清除；不触碰仓库的运行数据库。12 个场景在两个视口串行执行，共 24 项：1440×1000、1024×768。

## 场景与证据

- Maven/Gradle 浏览器生成下载，解压验证构建脚本、README 和 YAML。
- 发布备注、当前操作人写入真实历史。
- 缺核心模板时发布失败，旧版继续生成。
- 导出、导入差异、预览无副作用、并发版本变化拒绝、重新预览应用。
- ZIP 路径穿越拒绝并保留草稿。
- 同 ID 多 Boot 条目经真实新增向导保存，保留其他版本与当前操作人。
- 延迟真实接口响应验证包名竞态与手工编辑优先。
- OR 依赖阻止提交，满足后可下载，YAML 项目变量替换与运行变量保留。
- 企业框架 Gradle 限制在 UI 和 API 一致。
- 不支持的生成请求方法返回 405 和 Allow 头。
- 目录内 YAML 模板通过真实 HTTP 读取、保存和删除。
- 目标视口下生成页、管理页横向溢出检查。

`reports/e2e/index.html` 为 HTML 报告，`reports/e2e/results.json` 为机器可读结果；失败截图、页面 HTML、trace、成功示例截图和下载 ZIP 在 `test-results/e2e/`，后端日志在 `test-results/e2e-backend.log`，代理 HTTP 状态在 `test-results/e2e-http.log`。这些运行产物已排除出 Git。

本套件验证浏览器到生成产物，不表示已对生成项目执行 Maven/Gradle 依赖解析或启动基础设施服务。
