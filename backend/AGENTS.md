# 后端开发指南

本指南仅适用于 `backend/`。后端是 Maven Reactor，由 `offerpilot-bootstrap` 组装为一个 Spring Boot 应用。

## 模块边界

- `offerpilot-bootstrap` 管理唯一的 `@SpringBootApplication`、运行时配置、Flyway 迁移和提示词资源。
- `offerpilot-platform` 管理技术适配器和横切基础设施，不承载业务规则。
- `offerpilot-agent` 管理编排、事件、审批和会话。
- `offerpilot-identity`、`offerpilot-resume`、`offerpilot-job`、`offerpilot-career` 和 `offerpilot-interview` 分别管理各自业务领域。

跨模块调用通过小型应用层接口完成，不直接依赖其他模块的控制器、仓储或持久化实体。新增独立进程或启动类前，先记录相应部署决策。

## 变更流程

1. 确定归属模块和最近的现有测试。
2. 公共行为、持久化或提示词行为变化时，更新根指南指向的对应契约。
3. 实现范围最小且完整的变更。数据库结构演进应新增 Flyway 迁移，已经共享的迁移保持不变。
4. 先运行最接近改动的测试。只有模块装配、运行时配置、共享基础设施或公共契约变化时，才扩大到整个 Reactor。
5. 检查差异中的模块越界、密钥信息、迁移安全和向后兼容性。

## 验证

在 `backend/` 目录中优先使用仓库自带的 Maven Wrapper：

```powershell
.\mvnw.cmd -q -B -pl <module> -am test
.\mvnw.cmd -q -B test
.\mvnw.cmd -q -B -pl offerpilot-bootstrap -am package
```

局部模块变更使用第一条命令。变更跨模块或影响应用装配时，运行 Reactor 测试或 Bootstrap 打包。测试使用一次性夹具且不访问生产环境；直接修复由本次变更引起的失败并重新运行相关检查，无需逐步请求确认。
