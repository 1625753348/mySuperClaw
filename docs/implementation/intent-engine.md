# Intent Engine

`storage,intent` 启动独立的会话识别 API。它使用 PostgreSQL 保存会话状态，JWT 提供租户、用户、Agent 和权限。组合 `storage,gateway,intent,runtime` 会同时暴露 Gateway 和 Run 已有接口。识别输出包括候选关系、槽位、追问、业务路由建议与可校验的 Intent IR。

## 调用

| 请求 | 权限和结果 |
| --- | --- |
| `POST /v1/conversations` | `intents.submit`，返回 `conversationId` |
| `POST /v1/conversations/{id}/turns` | `intents.submit` 与 `Idempotency-Key`，提交 `text` 或 `skillKey`，返回逐候选结果 |
| `GET /v1/conversations/{id}/turns/{turnId}` | `intents.read`，读取该会话的持久化回执 |

用 `{"text":"帮我订会议室"}` 创建补参任务。下一轮可提交 `{"text":"明天下午三点，八人","replyToMessageId":"上一轮 assistantMessageId"}`。回执保留同一个 `taskId`，返回新参数和 `READY`。一轮涉及两个待办时可用 `targetTaskId` 指定目标。时区由 `ENTERPRISE_INTENT_ZONE` 设置，默认 `Asia/Shanghai`。

## 识别与持久化

1. `IntentEngine` 做 Unicode 归一化和显式命令检查。`IntentCatalog` 保存中文关键词、混合英文、槽位、权限、风险和路由定义。候选按规则和关键词匹配分出识别策略。启用模型时，由 LangChain4j 在允许的候选里辅助消歧，实际得分档与策略保存在 `routing_assessment`。
2. `ContextResolver` 从当前会话查找焦点任务与可用待补参任务。用当前槽位覆盖旧值，多个可用目标时要求明确目标。跨候选的业务依赖以 `dependsOn` 表达，缺少前置结果就进入 `WAITING_DEPENDENCY`。
3. `SlotExtractor` 校验时间、人数、金额、编号及附件引用。高风险动作通过签名 JWT 的业务权限校验。高危确认绑定动作、参数和待确认任务，限时使用一次。`READY` 时返回 `IntentIR`，包括定义版本、参数和来源。
4. `PostgresIntentStore` 读取最新快照，在事务外识别。提交时检查会话修订号和幂等键，一并保存 Turn、消息、候选评分、输入版本、槽位追问与确认。会话窗口保留最近八轮的意图信号，更早的信号合并为摘要。任务关联受时间和轮数限制。`清空上下文` 会清理工作状态并关闭旧的补参和确认。

日程、审批、合同等条目目前作为识别目录和路由建议。真实工具执行、附件内容、业务对象权限、字段级脱敏、撤回和补偿需要相应的执行绑定与权威业务回执。`CHAT` 和 `KNOWLEDGE` 返回对话路由。意图服务没有业务数据写入接口。

## 启动与单独验收

已有库应用 `database/migrations/005-intent-conversations.sql`。空白测试库使用 `database/schema.sql`。配置数据库连接和网关 JWT 的 issuer、audience、JWK 地址后运行以下命令。

```powershell
java -jar apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar --spring.profiles.active=storage,intent
```

启用模型时设置 `ENTERPRISE_INTENT_MODEL_ENABLED=true` 和 `ENTERPRISE_INTENT_MODEL_API_KEY`，或者复用 `DEEPSEEK_API_KEY`。模型端点和型号可通过 `ENTERPRISE_INTENT_MODEL_BASE_URL` 与 `ENTERPRISE_INTENT_MODEL` 配置。默认使用规则和关键词。工作簿验收使用该默认设置。

```powershell
node scripts/intent-smoke.mjs apps/agent-app/target/agent-app-0.1.0-SNAPSHOT.jar $workbook target/intent-evaluation.md
```

`$workbook` 指向原始 XLSX。脚本通过 Python 标准库读取工作簿，也可读取导出的工作簿 JSON。`INTENT_PYTHON` 可指定解释器。省略工作簿时仅运行独立 API 验收。设置 `INTENT_SMOKE_MODEL=true` 可使用本地确定性模型响应验证 LangChain4j 适配和候选约束。[独立测试脚本](../../scripts/intent-smoke.mjs)
