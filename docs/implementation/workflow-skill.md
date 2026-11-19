# workflow-skill 企业 SOP

当前 Maven 模块包含 [registry](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/registry/package-info.java) 和 [compiler](../../modules/workflow-skill/src/main/java/com/example/enterpriseagent/skill/compiler/package-info.java) 的包说明。Skill 编译器、发布服务和触发器查询待实现。数据库已有 `runtime.workflow_skill` 系列四张表。

## 设计中的实现关系

1. Compiler 读取 `SKILL.md` 里的声明式清单，核对参数、固定 DAG 和工具版本，生成用于发布的定义。
2. Registry 保存版本、触发别名和节点到具体工具版本的绑定。L0 可按明确命令或 Skill Key 找到已发布定义。
3. TaskInput 固定所用 Skill 版本。准入成功后，[运行时](agent-runtime.md) 把该定义作为固定 DAG 执行。具体工具调用由 [网关](tool-gateway.md) 完成。

例如 `/refund SO001` 先选定 SOP 并等待退款原因，后续补参沿用已绑定版本。固定图的规模和等待时长由运行时处理。[SOP Skill 设计及清单示例](../../design/skills.md)
