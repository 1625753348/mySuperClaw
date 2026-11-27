# 开放标准 Agent Skills

开放标准技能以 `SKILL.md` 的元数据、自然语言指令和配套资源指导模型。项目的 Workflow Skill 通过 JSON 清单编译固定 DAG，两套入口可以同时启用。 [Agent Skills 规范](https://agentskills.io/specification) · [固定 Workflow Skill](workflow-skill.md)

## 从发现到执行

1. **部署。** 运维登记审核后的目录、租户、主体、工具版本和资源白名单。宿主校验 YAML frontmatter、目录名和路径边界，对登记的文本执行基础静态扫描，将本次发布内容固定在内存。
2. **发现。** 按租户和用户或角色过滤。模型首轮只收到可见技能的名称、描述和版本，可以通过原生 Function Calling 请求 `activate_skill`。用户也可以显式指定技能，宿主复核相同的权限。
3. **激活。** 返回正文、可用资源路径和工具名。资源在部署时完成校验，模型只有调用 `read_skill_resource` 后才看到具体内容。宿主只读取白名单内 `references/` 和 `assets/` 的文本。
4. **执行。** 宿主收到 `get_tool_schema` 后查询网关的固定版本契约，收到 `execute_tool` 后检查部署白名单，再将名称、固定版本和参数交给 ToolGateway。网关负责独立校验权限、ABAC、Schema、审批和审计。
5. **结算。** 宿主对模型往返次数及消息大小设限。工具成功时可以继续生成回复。等待审批、异步结果或外部结果未知时先返回可追踪的执行 ID，后台继续由网关处理。当前原生 Skill 模型对话会在挂起点结束，之后可查工具状态；跨审批自动恢复模型对话需要新的持久化控制流。

技能包的指令与资源都只提供给模型作为任务数据。需要运行脚本时，可以先把脚本封装为审核过的网关工具，由具体适配实现提供输入契约、隔离和工具授权。标准技能宿主提供白名单的文本读取和网关工具代理。工具版本、身份、凭证和地址由服务端决定。

## 部署与调用

已有库应用 [024 迁移](../../database/migrations/024-agent-skills.sql)，空库使用最新 [建表脚本](../../database/schema.sql)。HTTP 服务在 `storage,gateway` 上追加 `agent-skills` Profile。模型密钥通过 `ENTERPRISE_AGENT_SKILLS_API_KEY` 注入，也可使用 `DEEPSEEK_API_KEY`。端点和型号可通过 `ENTERPRISE_AGENT_SKILLS_BASE_URL`、`ENTERPRISE_AGENT_SKILLS_MODEL` 配置。

下面的部署配置对应 [会议室示例技能](../../agent-skills/oa-meeting-rooms/SKILL.md)：

```yaml
enterprise:
  agent-skills:
    root: /opt/mysuperclaw/agent-skills
    entries:
      - name: oa-meeting-rooms
        tenants: [company-a]
        users: [alice]
        roles: [office-assistant]
        tools:
          - name: oa_meeting_room_list
            version: "1"
        resources:
          - references/format.md
```

租户必须命中，用户或角色至少命中其一。调用者 JWT 需要 `agent.skills.use` 和 `tools.invoke`。网关管理入口须发布工具并授予 `DISCOVER` 和 `EXECUTE`。技能 frontmatter 即使声明 `allowed-tools`，权限仍按部署绑定与网关授权计算。

| API | 用途 |
| --- | --- |
| `GET /v1/agent-skills` | 查询当前主体可见的元数据 |
| `POST /v1/agent-skills/runs` | 带 `Idempotency-Key` 提交 `message`，可选 `skill`、`conversationId` |
| `GET /v1/agent-skills/runs/{id}` | 读取本人运行回执或对应工具状态 |

请求 `{"message":"找能容纳十人的会议室"}` 会让模型选技能。也可传 `"skill":"oa-meeting-rooms"`。当传入会话 ID，宿主校验租户、用户和 Agent 的归属后用于审计关联。本入口只处理本次请求消息。

同一主体用同键同内容重发会得到原运行，内容不同返回 `409`。同一次运行内，相同工具版本和相同参数使用稳定网关键。如果网关调用挂起，宿主返回 HTTP 202；审批完成后接口可以查到 `TOOL_SUCCEEDED`，表示底层调用已完成。如果宿主长时间未结算，可查到 `INTERRUPTED`，相同幂等键仍复用原记录。

## 审计与验证

`agent_skill.invocation` 保存可信身份、可选会话、请求摘要、运行回执和最近的执行 ID。`agent_skill.load_event` 记录激活和资源读取，包含技能版本、内容摘要与资源路径。业务调用沿用现有网关审计，可按执行 ID 或操作键前缀关联。静态扫描用于发现常见敏感路径与越权指令，实际权限继续由宿主和网关执行。

在独立测试库运行 `node scripts/agent-skills-smoke.mjs <JAR>`。脚本使用本地模型与业务夹具验证渐进式加载、Skill 可见性、网关执行授权和请求幂等。模型接口使用 [LangChain4j 原生 Tool Calling](https://docs.langchain4j.dev/tutorials/tools/)。
