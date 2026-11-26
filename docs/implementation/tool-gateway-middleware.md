# ToolGateway 补充设计 · Tool Middleware、审批与异步恢复

工具选定之后，ToolGateway 还要决定本次调用是否可以受理、能否立即执行，以及出了问题怎样恢复。配置路由、固定 SOP、Planner，或通过 Function Calling 接进来的 ToolNode，都可以提交同一种工具请求，并复用这套治理。

## 上篇 · 原理与设计思路

### 1. 为什么把治理做成 Tool Middleware

以「查询会议室」和「提交交通报销」为例。两项业务的参数、审批要求和外部副作用不同，但进入执行层时都需要有调用身份、工具契约、权限检查和审计。如果把这些判断散在每个 HTTP、Java 或 MCP 工具里，接入新业务就要重复实现一遍，还容易出现某个入口漏掉审批或脱敏。

**Middleware 负责拦截、编排和决定何时继续，领域服务负责计算决策。** RBAC 层向权限服务问「可不可以调用」，ABAC 层向策略引擎问「这组参数和主体是否满足条件」，Secret 层向凭证服务申请下游凭证，Adapter 只负责按协议执行。调整规则时，可以改有版本的策略，执行结构仍可复用。

### 2. 从一次调用推导出治理顺序

| 设计阶段 | 要解决的问题 | 这一步可以给出的结果 |
| --- | --- | --- |
| 建立可信上下文 | 这个人是谁、属于哪个租户、代表哪个 Agent | 构造可信 Caller，或拒绝访问 |
| 解析工具与授权 | 应该用哪个固定版本，此身份能否执行 | 得到受控绑定，或拒绝执行 |
| 判断是否是同一次操作 | 客户端只是重发，还是提交了新参数 | 返回已有执行、返回冲突，或继续受理 |
| 对新执行做准入 | 参数是否合法、数据范围是否允许、额度是否足够、是否要等人批准 | 继续、拒绝或挂起。需要继续时先持久化执行记录 |
| 派发到受控端点 | 用哪个协议、凭证和时间预算执行 | 适配器调用，可能立即完成，也可能取得外部任务 ID |
| 结算与回传 | 实际调用结果是什么，哪些字段可以返回 | 校验输出、脱敏、记录状态及审计 |

教学清单里的「ABAC → Schema → 配额 → 审批 → 幂等」适合逐项说明职责。落地时需要把**已有执行回放**与**新执行创建**分开。如果重发也扣额度、创建审批，客户端超时重试就会制造额外业务记录。常见设计是先认证和授权，再走幂等查找，只有新请求才扣额度和创建审批。对调度或写入场景，**先提交执行记录，再做外部调用**；外部调用结束后用另一个短事务结算。

### 3. 用接口表达中间件的控制权

```java
public interface ToolMiddleware {
    ToolExecutionResult invoke(
            ToolExecutionContext context,
            ToolInvocationChain chain);
}

public interface ToolInvocationChain {
    ToolExecutionResult proceed(ToolExecutionContext context);
}
```

`ToolExecutionContext` 可承载已认证的 Caller、工具名及版本、参数、幂等键和追踪 ID，受理后再关联执行 ID。授权通过时调用 `proceed(context)`；权限拒绝、幂等回放和待审批可直接返回；超时和熔断可以包住适配器调用；输出校验和脱敏可以处理下一环返回的结果。凭证明文留在派发层的受控调用参数里。

**短路只结束这次处理。** 返回 `WAITING_APPROVAL` 后，HTTP 请求可以结束。审批完成通过原执行 ID 恢复任务，重新进入派发逻辑。跨分钟或跨进程的等待由数据库状态表达。

### 4. 权限、凭证和审计各管哪件事

- **入口认证**确认身份和 API scope。工具执行授权还需要具体的工具、版本和参数，因此适用于 HTTP 入口、Java 内部派发和后台恢复。上层请求可以带业务参数，租户、用户、角色及 Agent 身份来自可信上下文。
- **RBAC** 提供基础授权，例如谁可以发现、执行或审批报销。**ABAC** 根据调用上下文做细化，例如「用户和被查询员工都属于研发部」，或者「订单金额在额度内且用户是销售经理」。部门、归属和金额应由经验证的身份或可信业务来源提供。复杂表达式交由统一 Policy Engine。
- **Secret Provider** 按工具的凭证引用解析实际凭证，例如 `hr-api-key` 映射到 Vault 的管理路径。限制引用可用于哪些租户与端点；有职责要求时再绑定 Agent 范围。凭证仅供 Adapter 调用。模型、接口响应、日志和审计只接触允许的元数据。轮换和吊销通过凭证来源管理。
- **韧性**为调用提供超时预算、受限重试和熔断。可能产生外部写入的操作超时后，要根据下游是否支持幂等、查询或对账选择下一步。
- **审计**把发布、授权、预占、审批、派发结果按执行与追踪 ID 串起来。**指标**用于统计拒绝、重试、延迟与审批积压。审计留存及访问权限、原始载荷的脱敏和保留期需要分别定义。

```java
public interface SecretProvider {
    Credential getCredential(String secretRef, ToolContext context);
}
```

### 5. 审批、异步作业和 Checkpoint 怎样协作

对同一笔报销，审批记录回答「谁批准了什么」，工具执行记录回答「实际是否派发、返回了什么」，Checkpoint 回答「工作流从哪里继续」。

1. 网关根据管理员登记的风险和策略判断是否要审批。低风险查询通常在权限满足后执行，中风险按数据范围和工具策略治理。高风险写入和关键操作可以要求人工审批，极高风险还可以由业务制度要求二次确认或禁止 Agent 自动发起。
2. 需要审批时，事务内保存执行和审批记录，绑定工具版本、参数摘要、申请人、审批策略和有效期，再返回挂起状态。
3. 审批人作出决定，批准的执行进入后台队列。Worker 领取后要重新核对工具仍可用、申请人仍有执行权限、版本和参数摘要保持一致、审批策略仍匹配。拒绝、过期和复核失败分别记录。
4. 需要等待外部系统时，保留该执行和外部任务标识，可通过轮询或受控回调取得结果。
5. 如果工具是 DAG 的节点，Checkpoint 保存恢复位置和已提交产物引用，Step 持有执行 ID。工作流恢复时沿这个 ID 查询原执行，成功结果通过产物引用交给后续节点。

审批状态可以用 `PENDING → APPROVED / REJECTED / EXPIRED` 描述。执行状态则从等待审批继续到排队、恢复校验、派发和结算。只有执行结果需要回填时，工作流才推进依赖节点。用这种方式，即使模型上下文被压缩，工具状态和证据仍可从持久化链路找回。

## 下篇 · 对照 mySuperClaw 当前代码

### 1. 实际执行顺序

当前工程由 `ToolExecutionService` 显式协调调用步骤，由 `ToolJobService` 处理后台恢复。上篇的 `ToolMiddleware`、`ToolInvocationChain` 和 `SecretProvider` 是接口设计示意。下面按实际编排、状态及数据表对应。

```text
接入边界
  Spring Security 验证 JWT / API scope → Controller 构造 GatewayCaller

受理阶段 · 短事务
  解析工具和版本、检查 EXECUTE → 查找同一幂等请求
    ├─ 已受理：返回原执行，或在键与参数/版本冲突时返回 409
    └─ 新请求：评估 ABAC、审批策略和工具输入 Schema，核对端点
        → 扣减配额并预占执行 ID
        → 保存待执行参数和审批记录（如需异步/审批）
        → 提交事务

派发阶段 · 事务外
  立即执行或由后台领取 → 恢复时重新校验 → 获取凭证
  → 超时 / 条件重试 / 熔断 → 协议 Adapter → 下游

结算阶段 · 短事务
  校验结果契约 → 脱敏 → 更新执行状态 → 写审计及适用的状态事件
```

设计清单可以把幂等放在审批后面讲，便于逐项介绍职责。当前实现特意**先复核 `EXECUTE`，再读原幂等执行；只有新请求才进入配额扣减和创建审批**。这样同一个请求超时重发时，不会多扣一次配额或多生成一条审批。预占和执行结算各自用短事务，下游请求在事务之外，等待审批时 HTTP 请求可以先返回。`ToolRegistry.endpoint()` 在受理时验证端点和凭证可用性，派发时再取得实际使用的凭证。[受理、派发与结算源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolExecutionService.java#L161) · [两次短事务](tool-gateway.md#两次短事务之间执行工具)

### 2. 治理职责与基础设施映射

| 类别 | 治理环节 | 当前仓库怎样实现 | 可演进方案 |
| --- | --- | --- | --- |
| Security | Authentication | Spring Security 验证签名、发行方、受众、有效期和 API scope；Controller 从认证信息构造 `GatewayCaller` | 多入口统一可信身份映射 |
| Security | RBAC | `ToolRegistry.requirePermission` 查询 PostgreSQL 授权表，支持用户、Agent、角色和租户，匹配的 DENY 优先 | 将工具授权抽成独立 Permission Service |
| Security | ABAC | `ToolPolicyService` 读取版本化 JSON 策略，检查角色、Agent、参数值或归属指针 | 按策略复杂度评估 OPA、Casbin 或 Cedar |
| Validation | Schema | `ToolContracts` 校验指定版本的输入输出 JSON Schema | 增加契约上线前测试和兼容性审查 |
| Governance | Rate Limit / Quota | PostgreSQL `gateway.tool_usage_window` 原子更新分钟窗口和日配额，按主体或租户配置 | 流量规模需要时引入 Redis + Lua 快速限流，再保留必要的持久配额账本 |
| Governance | Approval | `ToolJobService` 与持久化审批状态机，绑定执行 ID、策略版本、参数摘要及到期时间 | 多级审批、组织审批人选择 |
| Reliability | Idempotency | PostgreSQL 唯一约束与持久化执行状态，按规范化参数及工具版本计算指纹 | 热点结果可加缓存，恢复仍以持久状态为准 |
| Credential | Secret | `ToolSecrets` 根据 `credentialRef` 解析部署凭证或通过 `VaultSecretReader` 读取 Vault KV v2 | 扩展云 Secret Manager、轮换和供应方访问审计 |
| Reliability | Timeout / Retry / Circuit Breaker | Adapter 自身超时，`ToolResilience` 管理重试预算，`RedisToolCircuit` 跨实例共享熔断状态 | 有统一韧性平台时可评估 Resilience4j 等实现 |
| Output | Result Validation / Redaction | 输出先经版本契约校验，再按配置移除敏感字段 | 对输出字段做分类与更细粒度授权 |
| Observability | Audit / Metrics | 关键管理动作及执行状态写 PostgreSQL 审计，适用的后台状态变更经 Outbox 和 Redis Streams 传播 | 统一的 Micrometer/Prometheus 指标；确有异构审计订阅需求时再接 Kafka 等通道 |

例如网关持久化的审计动作包含工具发布、授权修改、执行预占、人工审批和执行结算。它们通过工具 ID、执行 ID 或追踪 ID 关联。需要覆盖所有认证拒绝与调用失败时，应另设计入口层安全日志和保留策略。审计记录按证据需求保存元数据，工具正文遵循独立的脱敏、加密与保留约定。[持久化表](tool-gateway.md#持久化表怎么对应) · [审计与载荷生命周期](memory.md#长任务压缩后工具调用记录在哪里)

### 3. RBAC、ABAC 和入口认证的代码边界

当前实现支持以下一类 ABAC JSON 规则：

```json
{
  "roles": ["operations"],
  "values": {"/region": ["CN"]},
  "ownerPointer": "/userId"
}
```

这要求调用者具备相应角色，参数中的地区属于允许值，并且 `/userId` 和认证主体匹配。你给的「用户部门与目标员工部门均为研发部」以及「订单金额不超过 10000 且用户是销售经理」是进一步的业务策略例子。部门归属与订单金额应来自可信身份或业务系统，当前规则集适合角色、Agent、值集合和归属判断。复杂比较可用独立 Policy Engine 或可信业务校验节点承担，避免把表达式分散到各个中间件里。[策略校验源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolPolicyService.java#L141)

Spring Security 在 HTTP 入口决定请求是否具备访问 API 的身份和 scope。Tool Middleware 在工具入口用具体版本、参数和业务上下文执行最终授权。对于通过 Java 服务进入的会话派发、Runtime 节点和后台续跑，可信 Caller 也要随调用传入同一个网关执行入口。MCP 是网关下游协议，选择 MCP Adapter 同样要先通过工具级授权。[入口认证](../../modules/access/src/main/java/com/example/enterpriseagent/access/http/ToolGatewaySecurity.java) · [授权对象](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/registry/ToolRegistry.java#L292)

### 4. 凭证引用怎样解析

工具端点记录凭证引用。例如可以登记 `credential_ref=hr-api-key`，再在部署配置中把这个别名映射到 Vault 路径 `secret/hr/api-key`。当前引用名和 Vault 路径各自校验，使用 `allowed-tenants` 与 `allowed-targets` 限定范围。

当前由 `ToolSecrets.resolve(tenant, protocol, target, ref)` 与 Vault 适配共同实现。支持的托管凭证类型是 Bearer，适用于 HTTP、MCP、gRPC 和 Async 等支持路径。轮换时可保持工具引用稳定，由凭证来源更新实际值。[ToolSecrets 源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolSecrets.java) · [Vault 接入](tool-gateway.md#vault-怎样提供凭证)

### 5. 风险分级如何进入实际准入

| 等级 | 典型动作 | 执行约束 |
| --- | --- | --- |
| LOW | 查询公开或低敏信息 | 按授权与契约执行，可选择配置审批 |
| MEDIUM | 查询受限业务数据、创建低风险草稿 | 根据数据范围和工具策略执行 |
| HIGH | 提交报销、创建或修改关键业务数据 | 当前网关要求存在有效审批策略，批准后重新校验才派发 |
| CRITICAL | 删除生产资源、触发生产发布 | 当前网关同样要求审批策略。额外的二次确认、独立发布审批或禁止 Agent 自动发起可由业务准入策略明确 |

风险由管理员配置的工具元数据和生效策略共同决定。当高风险工具未配置审批策略时，网关拒绝受理。低风险工具需要审批也可以单独配置相同机制。[审批准入检查](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolExecutionService.java#L222)

### 6. 审批和执行状态如何落库

```text
审批单状态
  PENDING → APPROVED / REJECTED / EXPIRED

工具执行状态
  WAITING_APPROVAL → QUEUED → Worker 领取，重新校验后派发
      │                         ├─ 校验失败 → BLOCKED
      │                         ├─ 异步下游 → WAITING_EXTERNAL
      │                         └─ 调用结算 → SUCCEEDED / FAILED / UNKNOWN
      ├─ REJECTED
      └─ EXPIRED
```

上篇提到的重新校验对应 `ToolJobService.prepare()`。当前执行表把领取中的执行标记为 `RUNNING`，重新校验逻辑在 Worker 内执行。审批决定和执行转为 `QUEUED` 在同一事务更新，同时写审计及执行状态事件。后台领取已有租约，结算时校验 Worker 与租约代次，并行的迟到结算不能覆盖已推进的状态。

审批行绑定执行 ID、工具版本、请求者、操作摘要、批准策略版本和到期时间，保留决策人及决策状态。审批者需获得 `APPROVE`，申请人不能批准自己的执行。Worker 从加密作业恢复可信身份和角色快照，重新检查当前工具状态、执行授权、固定版本、端点、参数摘要、Schema、ABAC 和批准策略版本。企业 IdP 中角色变化的即时同步仍需要部署接入相应的撤权或主体查询机制。[审批裁决与续跑源码](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/execution/ToolJobService.java)

### 7. 从 OA 报销追到长任务 Checkpoint

**单工具报销。** 用户补齐标题、类别、金额、日期、说明后，会话按 `DIRECT` 提交 `oa_reimbursement_submit@1`。网关保存 `WAITING_APPROVAL` 后返回，OA 此时不会收到写请求。批准后，后台沿原执行 ID 调用 OA，该 ID 还会作为 OA 的 `requestKey`。OA 返回 `pending` 表示报销单进入 OA 自身的审核，网关审批已经完成。

**DAG 中的审批节点。** Runtime 在 Tool Step 提交时将 `run_id`、`step_id` 与网关执行绑定；节点保存 `tool_execution_id` 和稳定操作键。审批等待期间保留节点状态，释放 Worker。网关结算事件可加快唤醒，后台轮询作为恢复路径。Worker 续跑时按原执行 ID 查状态，已经成功的节点通过产物引用向后传递结果。外部调用结果未知时，Run 可转入待对账，保持原执行关联。

| 记录 | 管什么 | 关联方式 |
| --- | --- | --- |
| `gateway.tool_approval` | 审批人、决策、策略版本、有效期 | `execution_id` |
| `gateway.tool_execution`、`gateway.tool_job` | 执行身份、版本、状态、稳定操作及加密恢复载荷 | 网关 `execution_id`，DAG 调用还绑定 `run_id + step_id` |
| `runtime.step` | 节点进度、工具执行和结果引用 | `run_id + step_id`，节点持有 `tool_execution_id` |
| `runtime.checkpoint`、`runtime.checkpoint_artifact` | 快照版本、恢复位置及节点产物绑定 | 按 Run、图线程 `graph_thread_key`、格式版本和序号读取 |

运行时用 Postgres Checkpoint Saver 持久化适用于执行恢复的结构化状态，LangGraph4j 负责相应的图节点执行。Checkpoint 中的产物引用负责找回节点证据，审批表负责证明状态和裁决，两者通过 Run、Step 与网关执行关联衔接。这样可以在模型上下文裁剪、分页读取或进程重启后找回已完成结果，无需重新提交已成功的工具节点。[OA 接入](oa-business-system-integration.md#6-接入会澄清的创建操作) · [长任务恢复](agent-runtime.md#一次运行怎么推进) · [Checkpoint 与产物](runtime-artifact-storage.md)

### 8. 联调时看哪几个结果

1. 用只能发现工具的身份搜索成功，再尝试执行，观察执行权限拒绝发生在 Adapter 之前。
2. 用同一个幂等键重发同版本同参数请求，核对执行 ID 不变；换参数后检查是否返回 `409`。
3. 提交 OA 报销后观察 `WAITING_APPROVAL`，用申请人凭证尝试审批应被拒绝。审批员批准后，核对同一个执行 ID 和一份下游业务结果。
4. 长任务等待审批或外部回执时重启 Worker，查 Step、Checkpoint 和网关执行 ID，核对哪些节点已完成、哪些节点被继续，以及审计能否还原关键状态变化。

回看 [ToolGateway 学习问答](tool-gateway-learning-notes.md) 可以从 A/B 与配置路由的角度理解谁在选工具；沿 [事件投递与消费](event-bus.md) 可以继续查看挂起后怎样唤醒。
