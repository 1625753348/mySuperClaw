# workflow-skill 企业 SOP

当前实现包含 [SkillCompiler](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/compiler/SkillCompiler.java)、[SkillRegistry](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/SkillRegistry.java) 和 [PostgresSkillRegistry](../../modules/infrastructure/src/main/java/com/example/enterpriseagent/infrastructure/workflow/PostgresSkillRegistry.java)。发布入口已用 GitHub 复盘 Skill 验证。会话入口绑定和通用固定 DAG 执行仍待接入。

## 编译与发布

启用 `storage,gateway` 并应用 [007 迁移](../../database/migrations/007-workflow-skill-publication.sql)。发布管理员需要 JWT scope `skills.admin`。该阶段复用同一个发布请求提交并审核，记录操作者。

| 请求 | 功能 |
| --- | --- |
| `POST /v1/admin/workflow-skills/{key}/versions/{version}/publish` | 提交 `{ "expectedRevision": 0, "markdown": "完整 SKILL.md 原文" }`。首次发布修订号传 `0`，后续使用当前修订号。 |
| `GET /v1/admin/workflow-skills/{key}` | 获取当前版本的 ID、修订号、编译摘要、固定 DAG 和工具版本 ID。传 `?version=1` 可读历史版本。 |
| `GET /v1/admin/workflow-skills/resolve?command=%2Fgithub-retro` | 按当前租户的精确命令找到已发布版本，用来验证注册表触发器。 |

编译器解析 Markdown 中唯一的 JSON 围栏，拒绝未注册处理器、环、未知依赖、裸工具版本及未声明的输入引用。发布时还会核对同租户的网关工具处于启用状态、指定版本处于发布状态、端点可用，且参数和证据输出与节点契约匹配。本版支持 GitHub 复盘的两类取证输出及两个本地处理器契约。

发布事务保留 Markdown、编译摘要和每个节点的工具版本 ID，切换当前版本、更新触发器并写入 `WorkflowSkillPublished` Outbox。内容相同的同版本请求直接返回原版本。发布下一版后，旧版仍可按版本号查询。Outbox 已留档，消费与缓存同步将在接入执行时实现。

联调脚本使用临时工具绑定验证完整发布链路。它覆盖缺少权限、跨租户、缺少工具、循环依赖、无效引用、同版重试、并发修订冲突的拒绝条件，以及历史版本查询。 [联调脚本](../../scripts/runtime-smoke.mjs) · [GitHub 复盘 Skill](../../skills/github-project-retrospective/SKILL.md)

## 设计中的实现关系

1. Compiler 读取 `SKILL.md` 里的声明式清单，核对参数、固定 DAG 和工具版本，生成用于发布的定义。
2. Registry 保存版本、触发别名和节点到具体工具版本的绑定。L0 可按明确命令或 Skill Key 找到已发布定义。
3. TaskInput 固定所用 Skill 版本。准入成功后，[运行时](agent-runtime.md) 把该定义作为固定 DAG 执行。具体工具调用由 [网关](tool-gateway.md) 完成。

例如 `/refund SO001` 先选定 SOP 并等待退款原因，后续补参沿用已绑定版本。固定图的规模和等待时长由运行时处理。[SOP Skill 设计及清单示例](../../design/skills.md)
