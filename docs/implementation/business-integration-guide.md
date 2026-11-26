# 企业业务系统接入指南

mySuperClaw 将业务系统接入拆成三项可独立评审的配置，即 **Tool、Intent Definition 和执行路由**。其中参数 Schema 是 Intent Definition 的业务契约，工具自身还有独立的输入、输出 Schema。已支持的意图与适配器可以通过配置复用，新的自然语言业务动作需要补齐当前目录中的定义与识别逻辑。

## 1. 准备一个可验收的业务动作

先选择一项具体能力，例如「查询待办审批」。从业务系统收集接口地址、方法、认证方式、输入输出定义、数据权限、脱敏样例和常见失败。确认谁能发现和执行、参数从哪里来、怎样认定成功。写入接口同时明确审批、幂等和结果查询方式。

已有 OpenAPI 文档可以提供方法与 Schema，OA 接入脚本就是一个范例。已经通过 MCP 暴露的能力可以先从受管 MCP 服务发现，再选择要登记和发布的工具。[OA 接入实录](oa-business-system-integration.md) · [ToolGateway](tool-gateway.md)

## 2. 注册 Tool 及其契约

| 要填写的内容 | OA 示例 |
| --- | --- |
| 工具名、描述、分类和风险 | `oa_approval_list`，查询审批列表，OA，只读 |
| 协议与部署端点别名 | HTTP GET，绑定 OA 的 `/api/approvals` |
| 输入 Schema | 可选 `status`，只允许契约列出的状态枚举 |
| 输出 Schema | 已裁剪的审批单列表，可包含编号、标题、状态和阶段 |
| 权限与凭证引用 | 明确授予 `DISCOVER`、`EXECUTE`；OA Bearer 由网关解析 |
| 发布版本 | 发布为 `1`，路由固定引用该版本 |

管理台可以登记工具、配置权限并发布版本。工具调用参数只承载业务字段，租户和操作者由认证上下文提供，下游地址和凭证由服务器配置提供。

## 3. 明确 Intent Definition 和参数 Schema

意图描述用户想做的事。对审批列表，意图为 `APPROVAL.LIST@1`，「查询待办审批」提供槽位 `approvalStatus=pending`。工具的参数名为 `status`，两者在路由中映射。

| 业务规则 | 需要定义的内容 |
| --- | --- |
| 定义与适用范围 | 领域、动作、定义版本、用户常见表达、近似动作、所需权限或角色 |
| 必填与可选项 | 字段名称、类型、枚举、范围、组合校验和缺参追问 |
| 参数来源 | 当前输入、对上一轮追问的回复、认证上下文、受信任的默认值或上游工具结果 |
| 多轮关联 | 用户明确回复原任务时继续同一个 Task，多个任务都可能接收同一个补参时先消歧 |

已在目录中的意图可以直接做租户路由配置。当前「预定义规则」可为支持的低风险 `DIRECT` 意图附加约束、角色和追问。全新通用意图在 `IntentCatalog` 补定义，按需在 `SlotExtractor` 或识别层补解析。固定 SOP 可以在 `SKILL.md` 声明输入契约和节点依赖。[定义与参数来源](sop-dag-definition-rules.md) · [预定义规则](operations.md#预定义规则)

## 4. 绑定租户和执行路由

下面配置可用于已经登记了 `APPROVAL.LIST@1` 和 `oa_approval_list@1` 的环境。替换租户为会话认证时使用的真实租户。

```yaml
enterprise:
  intent:
    routing:
      bindings:
        - tenant: company-a
          intent: APPROVAL.LIST
          definition-version: 1
          mode: DIRECT
          tool-name: oa_approval_list
          tool-version: "1"
          parameters:
            "[status]": "slot.approvalStatus?"
```

结尾的 `?` 表示可选槽位。用户没有给出状态时，决策层省略该工具参数。由 `ValidatedIntentIR` 驱动路由，配置绑定的版本与 IR 定义版本相符才允许派发。固定流程选择版本化 `WORKFLOW`，开放目标通过已装配的 Planner 策略生成并校验计划。[决策与路由配置](decision.md) · [Skill 发布](workflow-skill.md) · [Planner](general-planner.md)

## 5. 验收并上线

1. 用会话凭证查询工具目录，确认仅授予的工具可发现。用没有执行权限的主体发起调用，验证网关拒绝。
2. 准备完整输入、缺参、错误格式、明确回复补参等用例。查看候选、槽位、Intent IR、路由和工具回执。
3. 写操作验证审批前、审批拒绝、超时、重放及结果确认。跨系统流程验证上游数据如何传给后续节点。
4. 保留工具、意图、Skill 和路由版本，以及测试样例、发布记录和回滚方式。用同一方法接入下一个业务系统。
