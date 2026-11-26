# 事务台 OA 接入智能助手

本机 OA Swagger 页面是 `http://127.0.0.1:3000/swagger-ui/`，机器可读契约是同一服务的 `/openapi.json`。该契约为 OpenAPI 3.0.3，使用 Bearer 认证，金额字段 `amountCents` 的单位是人民币分。

## 1. 盘点接口与权限

先读契约中的 HTTP 方法、路径、角色、输入参数和响应，再用真实测试账号验证。OA 的员工账号可查询本人报销、审批列表和会议室。使用该账号访问采购订单会返回 403。

| OA 模块 | 本阶段接入 | 后续接入所需条件 |
| --- | --- | --- |
| 登录与身份 | 启动前取得员工令牌，用 `GET /api/auth/me` 核对角色 | 共享部署需要把每个助手会话主体映射到其 OA 主体，并处理过期和撤权 |
| 报销 | `GET /api/reimbursements`，审批通过后 `POST /api/reimbursements` | 详情需要固定路径参数；审核、撤回和付款需要独立写工具及审批策略 |
| 审批 | `GET /api/approvals` | 审批发起、审核、撤回需要核对节点处理权限、状态前置条件与结果查询 |
| 会议预约 | `GET /api/meeting-rooms`，`POST /api/meetings` 创建草稿 | 正式预约需要明确日期、时间、会议室和确认动作 |
| 采购、发货、退货 | 已核对 Swagger 契约和采购查询权限 | 由 OA 负责人提供适用角色，按采购单号绑定读取与履约流程 |
| 员工、合同、考勤 | 已盘点 Swagger 接口 | 按 OA 数据范围配置权限；考勤 CSV 单独走文件产物交付 |

## 2. 配置网关 HTTP GET 目标

本阶段三个路径都使用 GET，报销与审批支持可选的 `status` 查询参数。

[HttpToolAdapter](../../modules/tool-gateway/src/main/java/com/example/enterpriseagent/toolgateway/adapter/HttpToolAdapter.java) 新增 `enterprise.gateway.http-read-targets`。每个别名绑定固定 URI，并声明允许的查询参数和列表返回字段。网关把经 Schema 校验的参数编码到查询串，按凭证引用注入 OA Bearer，执行 GET，再裁剪列表字段并校验输出契约。原有 POST 目标沿用原部署配置。

## 3. 绑定两个系统的身份

本机测试把 OA 员工令牌授权给当前助手租户的已配置 HTTP 目标。发布时单独给指定的助手用户授予工具 `DISCOVER` 和 `EXECUTE`，会话还需 `tools.invoke`。管理 JWT 用于发布和审批，会话 JWT 用于使用工具，OA 员工令牌由网关调用 OA 时使用。

从 OA 登录获取令牌后，在本机启动器进程设置 `PROGRAM001_OA_EMPLOYEE_TOKEN`，调用 [接入脚本](../../scripts/oa-console-integration.mjs) 的 `prepareOaConsole(springApplicationJson, tenant)`，再把返回对象序列化为应用进程的 `SPRING_APPLICATION_JSON`。函数先校验 Swagger 和 OA 员工身份，并写入 GET 目标、凭证作用域及意图绑定。令牌过期后从 OA 重新取得令牌并重启该本机配置。生产多人接入需要逐用户身份映射和独立凭证生命周期。

## 4. 注册、发布并验证召回

8081 本机控制台生成自己的管理和会话凭证后运行下列命令。脚本从 OA Swagger 读取查询参数及列表字段的契约，创建工具版本 1、发布、配置权限，再用会话凭证验证工具可被召回。

```powershell
node scripts/oa-console-integration.mjs "<本机控制台凭证目录>" local-console-session
```

| 测试用语 | 意图 | 工具与上游请求 |
| --- | --- | --- |
| 查我的报销单 | `EXPENSE.LIST` | `oa_reimbursement_list@1` → `GET /api/reimbursements` |
| 查询待办审批 | `APPROVAL.LIST` | `oa_approval_list@1` → `GET /api/approvals?status=pending` |
| 查询会议室 | `MEETING.ROOM_LIST` | `oa_meeting_room_list@1` → `GET /api/meeting-rooms` |

## 5. 看一轮对话实际怎样执行

1. Intent Engine 将「查询待办审批」识别为 `APPROVAL.LIST`，从「待办」抽取 `approvalStatus=pending`，校验后写入 Intent IR。
2. Decision Service 根据当前租户的 `DIRECT` 绑定选择 `oa_approval_list@1`。`slot.approvalStatus?` 表示源槽位可选，未提供时省略上游查询条件。
3. ToolGateway 复核执行权限，使用 OA 员工凭证发请求。OA 按员工自身权限返回结果。
4. 网关校验裁剪后的列表、保存执行记录；会话 API 回传 `DIRECT` 执行状态和数据，可在调试页面查看产物。空列表也是成功结果。

例如报销结果保留单据编号、标题、类别、金额、发生日期和状态。审批结果保留编号、标题、类型、状态和当前阶段。会议室结果保留编号、名称、楼层和容量。

本机联调实际读到 3 条报销、0 条待办审批和 4 间会议室。脚本还验证了已付款筛选、请求 Schema 拒绝非法状态，以及持有管理 JWT 的主体在缺少该工具 `EXECUTE` 授权时收到 403。意图路由回归覆盖既有的查询分类、混合轮次与多轮补参。

## 6. 接入会澄清的创建操作

1. **登记契约。** 接入脚本从 OA 的 OpenAPI 读取 POST 请求与回执 Schema，为两个工具建立独立版本。会话需要 `oa.create`，网关仍复核工具执行授权。
2. **定义槽位。** 会议草稿要求标题和 1～30 人。报销要求标题、类别、金额、费用日期及费用说明。归一化后由 `SlotExtractor` 识别参数，`ContextResolver` 按回复关系定位原任务，`SlotMerger` 合并已填参数。任一项缺失或无效，回执保留 `NEEDS_CLARIFICATION`，网关执行列表为空。
3. **绑定发布。** 参数完整后生成 Validated Intent IR，`DIRECT` 绑定映射为 OA 请求字段。会议使用 `oa_meeting_draft_create@1`，报销使用 `oa_reimbursement_submit@1`。HTTP 的 `http-write-targets` 在服务端固定 `draft=true`，或把网关执行 UUID 放进 OA 的 `requestKey`。用户输入契约不含这两个服务端字段。
4. **按风险执行。** 会议草稿不占用会议室，OA 返回草稿编号和过期时间。报销金额由确定性代码将元转成分，补参完成后网关停在 `WAITING_APPROVAL`。有审批权限的管理主体在会话页批准后，同一个执行继续提交，返回 OA 报销编号和 `pending` 状态。这个状态表示正等待 OA 自身审核。
5. **核对完成状态。** 会话重复请求复用客户端幂等键。报销使用稳定的上游幂等键；会议接口没有同等契约，超时应先查 OA 中是否已有草稿。联调参数 `--verify-creates` 会创建唯一标记的测试记录，核对回执后取消草稿、撤回报销。默认脚本测试首轮追问，只读 OA 数据。

会议多轮示例依次发送「创建会议草稿」→「标题：项目周会」→「8人」。报销示例依次发送「提交OA报销」→「标题：客户拜访交通费」→「类别：交通，金额：12.34元，费用日期：今天，说明：打车去客户办公室」。建议用「回复此任务」补参，尤其是正在并行填写多个任务时。

本机联调已确认两种创建在缺参时都不会派发，引用上一轮追问可沿用原 Task，重复发送同一幂等键复用原轮次。报销的 `12.34` 元在 IR 中折算为 `1234` 分，批准前 OA 中没有单据，批准后只查到一条匹配记录。测试草稿和报销分别由 OA 的取消、撤回接口清理。
