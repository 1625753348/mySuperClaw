# workflow-skill 企业 SOP

开放标准的自然语言技能通过 [Agent Skills 宿主](agent-skills.md) 激活和运行。本页讲解 JSON 清单编译后的固定 DAG。

当前实现包含 [SkillCompiler](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/compiler/SkillCompiler.java)、[SkillRegistry](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/SkillRegistry.java) 和 [PostgresSkillRegistry](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/workflow/PostgresSkillRegistry.java)。GitHub 复盘与订单退款可从会话识别或命令进入固定 DAG。

## 编译与发布

启用 `storage,gateway` 并应用 [007 迁移](../../database/migrations/007-workflow-skill-publication.sql)。发布管理员需要 JWT scope `skills.admin`。该阶段复用同一个发布请求提交并审核，记录操作者。

| 请求 | 功能 |
| --- | --- |
| `GET /v1/admin/workflow-skills?after=&limit=25` | 分页列出本租户当前启用的 Skill 发布版本和修订号。使用响应的 `nextCursor` 请求下一页。 |
| `POST /v1/admin/workflow-skills/{key}/versions/{version}/publish` | 提交 `{ "expectedRevision": 0, "markdown": "完整 SKILL.md 原文" }`。首次发布修订号传 `0`，后续使用当前修订号。 |
| `GET /v1/admin/workflow-skills/{key}` | 获取当前版本的 ID、修订号、编译摘要、固定 DAG 和工具版本 ID。传 `?version=1` 可读历史版本。 |
| `GET /v1/admin/workflow-skills/resolve?command=%2Fgithub-retro` | 按当前租户的精确命令找到已发布版本，用来验证注册表触发器。 |

编译器解析 Markdown 中唯一的 JSON 围栏，拒绝未注册处理器、环、未知依赖、裸工具版本及未声明的输入引用。发布时还会核对同租户的网关工具处于启用状态、指定版本处于发布状态、端点可用，且参数和输出与节点契约匹配。本版支持复盘证据、Markdown、退款资格校验及业务回执。

发布事务保留 Markdown、编译摘要和每个节点的工具版本 ID，切换当前版本、更新触发器并写入 `WorkflowSkillPublished` Outbox。内容相同的同版本请求直接返回原版本。发布下一版后，旧版仍可按版本号查询。Worker 消费发布事件后按版本 UUID 预热编译 DAG。API 查询版本或命令时以数据库为准，随后读取缓存或回源。[EventBus 与缓存保留](event-bus.md)

管理台「Skill 发布」可用 `skills.admin` JWT 查看已发布列表、按版本查询编译后的 DAG，以及粘贴或导入 `SKILL.md` 后发布。首次发布使用预期修订号 `0`。更新时点击「读取当前版本」，页面会带出当前修订号和下一个版本建议。发布前需同步修改 Markdown 清单里的版本。并发更新冲突后重新读当前版本，再核对内容和版本号。

联调脚本使用临时工具绑定验证完整发布链路。它覆盖缺少权限、跨租户、缺少工具、循环依赖、无效引用、同版重试、并发修订冲突的拒绝条件，以及历史版本查询。 [联调脚本](../../scripts/runtime-smoke.mjs) · [GitHub 复盘 Skill](../../skills/github-project-retrospective/SKILL.md)

## 从会话执行

启动 Profile `storage,gateway,intent,runtime`，再启动独立的 `worker`。已有库应用 [008 迁移](../../database/migrations/008-fixed-workflow-dispatch.sql)。JWT 需要 `intents.submit intents.read tools.invoke runs.submit runs.read`。退款申请另需 `refunds.submit`。配置 `ENTERPRISE_GATEWAY_JOB_KEY`，审批继续使用网关的审批权限与接口。

CRM 仓库绑定由部署提供，例如以下配置允许 `operations` 角色识别该项目。网关工具还须获得自己的仓库授权。

```yaml
enterprise:
  intent:
    retrospective:
      repositories:
        - tenant: company-a
          repository: owner/crm-rewrite
          ref: main
          aliases: [CRM系统重构]
          roles: [operations]
```

显式命令通过发布表查询版本，自然语言使用当前两类流程的确定性识别。候选回执和右侧 `WorkflowSkill` 显示版本 ID。补参沿用同一 Task 和 Skill 版本。复盘项目别名出现歧义时追问，确认后将仓库和时间窗作为可信上下文，与业务参数分开。复盘综合处理器按工具提供的影响字段排序，验证证据覆盖标记并输出引用，未接入模型综合。

运行时将固定 DAG 复制为 `runtime.plan` 和 `runtime.step`，读取该 Run 绑定的任务输入版本并逐步推进。工具提交按编译时固定的 UUID 复核工具版本。节点通过现有租约、检查点、网关审批与 SSE 状态恢复。会话回执在 `executions` 显示状态及结果地址，产物页可查看计划依赖和网关调用。

退款使用 [订单退款 Skill](../../skills/order-refund/SKILL.md)。输入 `/refund SO001` 后补充退款原因。资格不通过时终止，资格通过后高风险工具等待审批。审批通过后同一 Run 继续执行，并返回有明确下游状态的回执。联调只绑定模拟下游。

## 供 Planner 组合的受审批写入

将清单的 `admissionPolicy` 设为 `workflow.approved-write`，`triggers` 设为空数组。每个节点声明固定的 ToolGateway 版本，输入来自清单声明的参数，出口使用 `GenericResult`。发布时，注册表验证至少有一个 HIGH 或 CRITICAL 工具配置了生效的 `APPROVAL` 策略，其余工具可用 LOW 等级。审批工具、版本和承诺的参数 Schema 随 DAG 固定。

调用该 Skill 的租户需要配置确切的版本、允许绑定的输入字段及 `allow-write: true`。请求主体必须具备 `skills.execute-write`，每次真正写入前会再次由 ToolGateway 检查当前工具权限和审批策略。配置写入的计划只允许一次迭代，避免验收失败后重规划再次提交业务写入。审批由另外具备审批权限的主体按原有接口处理。 [多 SOP 编排示例](general-planner.md#组合多个-sop)

## 模块关系

1. Compiler 读取 `SKILL.md` 里的声明式清单，核对参数、固定 DAG 和工具版本，生成用于发布的定义。
2. Registry 保存版本、触发别名和节点到具体工具版本的绑定。L0 可按明确命令或 Skill Key 找到已发布定义。
3. TaskInput 固定所用 Skill 版本。准入成功后，[运行时](agent-runtime.md) 把该定义作为固定 DAG 执行。具体工具调用由 [网关](tool-gateway.md) 完成。

例如 `/refund SO001` 先选定 SOP 并等待退款原因，后续补参沿用已绑定版本。固定图的规模和等待时长由运行时处理。[SOP Skill 设计及清单示例](../../design/skills.md)
