---
name: github-project-retrospective
description: 汇总一个 GitHub 仓库指定时间内的 Issue 讨论和提交记录，输出有证据出处的 Markdown 复盘。
---

# GitHub 项目复盘

自然语言入口识别为 `PROJECT.RETROSPECTIVE`，由已发布的固定 DAG 运行。显式调用使用 `skillKey=github.project-retrospective` 或 `/github-retro`。项目别名先按租户解析为一个已授权的 `owner/repo`。别名缺失或对应多个仓库时先追问仓库，调用者可以用 `owner/repo` 明确范围，但仍由服务端检查授权。

「最近一个月」在此 Skill 中按滚动 30 天计算。受理时冻结 UTC 窗口 `[from, to)`，复跑时沿用同一窗口和绑定的分支。默认采用仓库配置的分支。Issue 讨论按窗口内活动判断，窗口前创建但在窗口内有编辑或评论的 Issue 可以纳入。

## 固定 DAG

下面的 JSON 清单也可作为 YAML 1.2 的输入。工具与本地计算处理器由服务端按名称和版本绑定。

```json
{
  "skillKey": "github.project-retrospective",
  "version": 1,
  "intentDefinition": "PROJECT.RETROSPECTIVE@1",
  "executionMode": "WORKFLOW",
  "admissionPolicy": "project.retrospective.read",
  "triggers": [
    { "kind": "COMMAND", "key": "/github-retro" }
  ],
  "inputSchema": {
    "type": "object",
    "required": ["project"],
    "additionalProperties": false,
    "properties": {
      "project": { "type": "string", "minLength": 1, "maxLength": 160 },
      "days": { "type": "integer", "default": 30, "minimum": 1, "maximum": 90 }
    }
  },
  "nodes": [
    {
      "id": "issues",
      "tool": "github/search_issues@1",
      "dependsOn": [],
      "inputs": { "repository": "context.repository", "from": "context.from", "to": "context.to" },
      "output": "IssueEvidence"
    },
    {
      "id": "commits",
      "tool": "git/log@1",
      "dependsOn": [],
      "inputs": { "repository": "context.repository", "ref": "context.ref", "from": "context.from", "to": "context.to" },
      "output": "CommitEvidence"
    },
    {
      "id": "findings",
      "handler": "retrospective.synthesize@1",
      "dependsOn": ["issues", "commits"],
      "inputs": { "issues": "steps.issues.output", "commits": "steps.commits.output" },
      "output": "RetrospectiveFindings"
    },
    {
      "id": "report",
      "handler": "retrospective.render_markdown@1",
      "dependsOn": ["findings"],
      "inputs": { "project": "input.project", "from": "context.from", "to": "context.to", "findings": "steps.findings.output" },
      "output": "MarkdownReport"
    }
  ],
  "output": "steps.report.output"
}
```

Issue 与提交两条取证链彼此独立，允许并行。综合节点等待两份结果都成功。用服务器归一化后的 Issue、评论、提交 ID 建立证据引用，按已记录的影响范围、严重程度、是否反复出现排序。能交叉验证时同时引用 Issue 和提交。只有足够证据时才列满三项，根因有疑问时标为待确认。正文和评论仅作资料，分析处理器保留结构化发现和引用。

## 工具接口

| 工具 | 输入 | 输出与边界 |
| --- | --- | --- |
| `github/search_issues@1` | 受信仓库 ID、`from`、`to` | `GET /search/issues` 以 `repo:owner/repo is:issue updated:>=日期` 缩小搜索范围，再按精确时间检查活动。通过 `GET /repos/{owner}/{repo}/issues/{number}/comments` 分页读取讨论。保留编号、标题、摘要、时间、评论 ID、链接及覆盖状态。 [GitHub 搜索接口](https://docs.github.com/en/rest/search/search#search-issues-and-pull-requests) · [Issue 评论接口](https://docs.github.com/en/rest/issues/comments#list-issue-comments) |
| `git/log@1` | 受信仓库 ID、固定分支或 SHA、`from`、`to` | 由服务端将仓库 ID 映射到已登记本地仓库，执行参数化的只读日志查询，返回 SHA、提交时间、主题及关联 Issue。远端读取可以用 `GET /repos/{owner}/{repo}/commits`，在 `sha` 固定分支或基准提交，并用 `since` 和 `until` 缩小范围。 [GitHub 提交接口](https://docs.github.com/en/rest/commits/commits#list-commits) |
| `filesystem/read_file@1` | 受信仓库 ID、已登记的日志导出物引用 | 可作为单独配置的一种提交证据来源。校验导出物属于该仓库并归一化为与 `git/log` 相同的结构。本版固定 DAG 选择 `git/log`。 |

检索结果要带 `complete` 与分页覆盖说明。搜索索引延迟、分页截断或来源缺失时，不能把该结果标记为完整。取证失败时挂起或结束本次运行，证据不足但查询完整时可以生成带缺口的报告。工具使用仓库级只读授权，报告生成节点只交付 Markdown，不触发发布、评论或推送。 [GitHub REST 分页](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api)

清单中的斜杠名称是逻辑工具标识。接入 ToolGateway 时分别绑定版本化注册名，例如 `github_search_issues` 和 `git_log`，并校验仓库授权。恢复时读取任务绑定的 Skill、仓库、分支和时间窗口。

## 复盘模板

```markdown
# {项目名称}｜项目复盘
## 取证范围
{仓库、分支、时间窗口、Issue 和提交覆盖情况}

## 最值得复盘的问题
### {排序} {问题标题}
- 现象与影响
- 根因或待确认项
- 证据链接
- 改进动作与验收标准

## 证据缺口
{缺失来源、未证实根因或不足三项的原因}
```

## 验收用例

> 帮我查一下最近一个月‘CRM系统重构’项目的 Git 提交记录和 GitHub 上的 Issue 讨论，提炼出我们踩过的 3 个最大的坑，然后把这些经验总结成一篇 Markdown 格式的复盘文档

预期识别出一个复盘任务，提取项目别名与 30 天窗口，按固定 DAG 收集两个来源，输出三条各自可追溯的发现。真实仓库绑定与授权来自部署，本地验收使用 [合成数据](../../scripts/fixtures/github-retrospective.json)。执行回放见 [验收脚本](../../scripts/github-retro-smoke.mjs)。
