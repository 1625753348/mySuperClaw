package com.example.enterpriseagent.runtime.execution;

import com.example.enterpriseagent.runtime.RunFailure;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.stream.StreamSupport;

/** 已声明的本地处理器。复盘先使用确定性排序和渲染，证据引用由可信仓库绑定构造。 */
public final class FixedHandlers {
    private FixedHandlers() {}

    public static JsonNode execute(ObjectMapper json, String handler, JsonNode input, String repository) {
        return switch (handler) {
            case "retrospective.synthesize@1" -> synthesize(json, input);
            case "retrospective.render_markdown@1" -> render(json, input, repository);
            case "refund.check_eligibility@1" -> {
                JsonNode eligibility = input.path("eligibility");
                if (!eligibility.path("refundable").isBoolean() || !eligibility.path("refundable").asBoolean()) {
                    throw failure("ORDER_NOT_REFUNDABLE");
                }
                yield eligibility;
            }
            default -> throw failure("WORKFLOW_HANDLER_UNAVAILABLE");
        };
    }

    private static JsonNode synthesize(ObjectMapper json, JsonNode input) {
        JsonNode issues = evidence(input.path("issues"));
        JsonNode commits = evidence(input.path("commits"));
        var result = json.createObjectNode().put("issueCount", issues.size()).put("commitCount", commits.size());
        var findings = result.putArray("items");
        var seen = new HashSet<Long>();
        StreamSupport.stream(issues.spliterator(), false)
                .filter(issue -> issue.path("number").isIntegralNumber() && issue.path("number").asLong() > 0
                        && issue.path("severity").asInt() > 0 && !issue.path("title").asText().isBlank()
                        && !issue.path("impact").asText().isBlank())
                .sorted(Comparator.comparingInt((JsonNode issue) -> issue.path("severity").asInt()).reversed()
                        .thenComparingLong(issue -> issue.path("number").asLong()))
                .filter(issue -> seen.add(issue.path("number").asLong())).limit(3).forEach(issue -> {
                    long number = issue.path("number").asLong();
                    var finding = findings.addObject().put("number", number)
                            .put("commentId", issue.path("commentId").asLong())
                            .put("title", issue.path("title").asText()).put("impact", issue.path("impact").asText())
                            .put("cause", issue.path("cause").asText("待确认")).put("action", issue.path("action").asText("待制定"));
                    var refs = finding.putArray("commits");
                    StreamSupport.stream(commits.spliterator(), false).filter(commit -> number == commit.path("issue").asLong())
                            .map(commit -> commit.path("sha").asText())
                            .filter(sha -> sha.matches("[0-9a-fA-F]{40,64}")).distinct().limit(5).forEach(refs::add);
                });
        return result;
    }

    private static JsonNode evidence(JsonNode value) {
        if (!value.path("complete").isBoolean() || !value.path("complete").asBoolean() || !value.path("items").isArray()) {
            throw failure("EVIDENCE_INCOMPLETE");
        }
        return value.path("items");
    }

    private static JsonNode render(ObjectMapper json, JsonNode input, String repository) {
        if (!repository.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")) throw failure("REPOSITORY_UNVERIFIED");
        JsonNode findings = input.path("findings");
        StringBuilder markdown = new StringBuilder("# ").append(safe(input.path("project").asText())).append("｜项目复盘\n\n")
                .append("## 取证范围\n").append(repository).append(" · ").append(input.path("from").asText())
                .append(" 至 ").append(input.path("to").asText()).append("（终点排除）\n")
                .append("Issue ").append(findings.path("issueCount").asInt()).append(" 条，提交 ")
                .append(findings.path("commitCount").asInt()).append(" 条。\n\n## 最值得复盘的问题\n");
        String base = "https://github.com/" + repository;
        int rank = 0;
        for (JsonNode item : findings.path("items")) {
            long number = item.path("number").asLong(), comment = item.path("commentId").asLong();
            var refs = new ArrayList<String>();
            refs.add("[Issue #" + number + "](" + base + "/issues/" + number
                    + (comment > 0 ? "#issuecomment-" + comment : "") + ")");
            item.path("commits").forEach(sha -> refs.add("[提交 " + sha.asText().substring(0, 7) + "](" + base + "/commit/" + sha.asText() + ")"));
            markdown.append("\n### ").append(++rank).append(". ").append(safe(item.path("title").asText()))
                    .append("\n- 影响 · ").append(safe(item.path("impact").asText()))
                    .append("\n- 根因或待确认项 · ").append(safe(item.path("cause").asText()))
                    .append("\n- 证据 · ").append(String.join("、", refs))
                    .append("\n- 改进与验收 · ").append(safe(item.path("action").asText())).append('\n');
        }
        markdown.append("\n## 证据缺口\n").append(rank < 3 ? "证据支持 " + rank + " 项，其余待补充。" : "以下结论仅覆盖当前时间窗口。").append('\n');
        return json.createObjectNode().put("format", "markdown").put("markdown", markdown.toString());
    }

    private static String safe(String text) {
        return text.replaceAll("\\R", " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("[", "&#91;").replace("]", "&#93;").replace("!", "&#33;").replace("`", "&#96;");
    }

    private static RunFailure failure(String code) { return new RunFailure(RunFailure.Code.INVALID, code); }
}
