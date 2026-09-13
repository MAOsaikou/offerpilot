# 前端开发指南

本指南仅适用于 `frontend/`。

## 边界

依赖方向保持为 `app → widgets → features → shared`。Feature 组合 shared 能力，不相互导入。服务端契约放在 `shared/api` 和 `shared/types`；浏览器本地工作区模型与服务端 DTO 分开。服务端驱动卡片统一通过 `widgets/CardRenderer` 渲染。

变更影响结构、状态归属、导航、用户体验、响应式行为或视觉系统时，更新 `docs/07-前端设计规范.md`。变更影响 HTTP、SSE、Agent 事件或卡片契约时，更新 `docs/05-接口文档.md`。

## 变更流程

1. 检查目标功能及最近的测试；新增抽象前先复用现有 shared 基础能力。
2. 实现变更涉及的全部用户可见状态，包括适用的加载、空数据、错误、离线和窄屏状态。
3. 先运行范围最小的相关检查；共享类型、构建配置、路由或跨功能组合变化时再扩大验证范围。
4. 检查差异中的依赖逆流、重复服务端类型、不可访问交互，以及生产路径意外启用 Mock 的问题。

## 验证

在 `frontend/` 目录中，以 `package.json` scripts 为命令事实来源：

```powershell
pnpm run test --run <test-file>
pnpm run lint
pnpm run typecheck
pnpm run test --run
pnpm run build
```

局部行为先运行定向测试。源码变更运行 lint 和类型检查；共享契约、路由、配置或生产装配变化时，再增加完整测试与构建。UI 工作只有在相关视口下检查受影响页面后才算完成。
