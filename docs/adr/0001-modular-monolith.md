# 采用 Maven 多模块的模块化单体

状态：已接受。OfferPilot 后端按 identity、resume、job、career、interview、agent 和 platform 划分 Maven 模块，但仅 `offerpilot-bootstrap` 含 Spring Boot 启动入口并部署为一个业务后端进程。当前 Agent 运行、SSE、审批和数据一致性跨越多个业务域；在领域接口、数据所有权与独立扩缩容需求稳定前，不将这些模块拆成独立微服务。`jobs-mcp` 保持独立进程，因为它具有独立的调用协议与发布节奏。
