# 企业级智能助手设计评审

本次评审覆盖 Intent Engine、Agent Runtime、ToolGateway 和 EventBus 长任务。技术栈采用 Spring Boot、LangChain4j、LangGraph4j。收到设计确认后进入业务代码阶段。

| 文件 | 评审内容 |
| --- | --- |
| [学习导览](walkthrough.md) | 用一个完整请求理解系统，按产物和状态串起各组件 |
| [系统架构](architecture.md) | L0～L4 按需能力、逐层产物、上下文解析、执行路由、组件选型与交付顺序 |
| [识别路由与模型调用](routing.md) | 四档复杂度策略、滞回区间、匹配置信度与极高风险处理 |
| [L4 Planner 与复杂任务编排](planner.md) | 规划输入、计划契约、DAG 校验和调度、失败后的计划修订 |
| [企业 SOP Skill](skills.md) | SKILL.md 声明式清单、版本发布、L0 触发、固定 DAG 与 MCP 工具映射 |
| [EventBus 长任务](eventbus.md) | 持久化调度、审批、外部任务、重试、恢复、取消和 SSE |
| [记忆与持久化](memory.md) | 六类记忆、Store/Saver、缓存、读写时点、滑动窗口与断点恢复 |
| [接口与数据模型](contracts.md) | API、关键对象、表职责、状态与事务不变量 |
| [数据库交付目录](../database/README.md) | ER 图、建表 SQL、字段与约束字典 |

## 请确认的设计决策

| 决策 | 建议值 |
| --- | --- |
| 首期部署 | Java 21、Spring Boot 3.5 系列、Maven。单仓模块化，API 和 Worker 同构建产物、独立进程部署 |
| 长任务 | PostgreSQL 后台队列、DAG 并行与恢复、Ralph 有界迭代。Redis Streams 作为首期 EventBus，SSE 从 PostgreSQL RunEventLog 补发 |
| 状态存储 | PostgreSQL 保存权威状态，状态提交同时追加事件。事件可供审计、回放和通知，首期采用状态表恢复执行 |
| 记忆 | 首期会话临时状态、对话历史与摘要、工作记忆。档案、历史案例和领域知识有库表草案，按业务授权逐步启用 |
| 模型与图编排 | LangChain4j 负责模型调用，LangGraph4j 运行图节点。Run Manager 负责领取、租约、调度与业务状态。框架具体版本在编码阶段通过 Maven 依赖与恢复用例确认 |
| Planner 调用边界 | 闲聊、简单查询和固定业务流程分别走 Chat LLM、DIRECT、WORKFLOW。复杂目标和动态探索走 PLAN_AND_EXECUTE。持续 Agent Loop 作为扩展能力 |
| 企业 SOP Skill | 业务与技术人员共建 SKILL.md，校验发布后按固定 DAG 运行。明确触发走 L0，语义触发按需识别。实际工具权限由服务端复核 |
| 识别路由 | 四档复杂度以 0.30、0.50、0.70 为边界，先选择识别策略。滞回只复用可比较的识别路径，极高风险由权限表和状态机接管 |
| 首期工具 | HTTP、关键字发现、权限和 Schema 校验。用报表异步生成验证恢复，用沙箱业务环境或仿真退款验证审批与幂等 |
| 业务与环境参数 | 首期按单会话所有者、租户隔离设计。模型服务、业务认证、审批人来源、部署规模和数据保留期待业务环境定稿 |

设计输入包括《ToolGateway 企业级完整设计方案》《Intent Engine 与 Plan-and-Execute 模式设计说明》、Context Assembler 与 Context Resolver 补充说明，以及附图《企业级智能助手 · 整体架构（含逐层中间产物）》。评审稿也纳入后续对话中的六类记忆、L0～L4 分层和调用边界、六类请求的执行模式，以及四档复杂度和滞回规则。原始附件保留为设计来源。
