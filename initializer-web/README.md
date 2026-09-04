# initializer-web

前端工程（Vue 3 + TypeScript + Vite + Pinia），M1.5 四步表单 MVP。

## 功能

- 四步向导：Java 版本 → Boot 版本（联动过滤 + 推荐默认选中）→ starter 多选 → 元信息
- 元信息即时校验（300ms 防抖），Package 由 Group + Artifact 自动推导、允许手改（touched 后停止推导）
- `POST /api/generate` blob 下载 `{artifact}.zip`；失败 toast 展示后端错误码与明细
- 开发模式代理 `/api` 到 `http://localhost:8080`

## 命令

```bash
npm install          # 安装依赖
npm run dev          # 本地开发
npm run build        # 产物构建
npm run typecheck    # vue-tsc 类型检查
npm test             # Vitest 单测
npm run test:coverage  # 单测 + 覆盖率（行/分支/函数/语句 ≥ 80% 门禁）
npm run test:mutation  # Stryker 突变测试（break 阈值 70%）
```

## 质量门禁

- UT 成功率 100%，V8 覆盖率阈值 80%（lines/branches/functions/statements）
- Stryker 突变测试：`thresholds.break = 70`，低于 70% 构建失败
- 类型检查：vue-tsc strict 模式，CI 阶段 `web-typecheck` 卡口
