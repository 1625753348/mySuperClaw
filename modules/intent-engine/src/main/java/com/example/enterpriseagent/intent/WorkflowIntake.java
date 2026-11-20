package com.example.enterpriseagent.intent;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.example.enterpriseagent.intent.IntentModel.*;

/** 将复盘请求和补参解释为一个已发布的 Skill 任务。仓库绑定由可信端口提供。 */
public final class WorkflowIntake {
    public static final String INTENT = "PROJECT.RETROSPECTIVE";
    public static final String KEY = "github.project-retrospective";
    private static final String COMMAND = "/github-retro";
    private static final Pattern QUOTED_PROJECT = Pattern.compile("[‘“'「\"]([^’”'」\"]{1,160})[’”'」\"]");
    private static final Pattern REPO = Pattern.compile("(?i)(?<![A-Za-z0-9_.-])[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}(?![A-Za-z0-9_.-])");
    private static final Pattern DAYS = Pattern.compile("(?:最近|近|过去)\\s*(\\d+)\\s*天");
    private static final Pattern REQUEST = Pattern.compile("复盘|踩坑|最大的坑");
    private final Catalog catalog;

    public WorkflowIntake(Catalog catalog) {
        this.catalog = catalog;
    }

    public Optional<Result> resolve(Actor actor, Request request, String normalized, Task anchored, Task focus, long now, long revision) {
        var refund = RefundIntake.resolve(catalog, actor, request, normalized, anchored, focus, now, revision);
        if (refund.isPresent()) return refund;
        String text = request.text() == null ? "" : java.text.Normalizer.normalize(request.text(), java.text.Normalizer.Form.NFKC).strip();
        boolean command = normalized.equals(COMMAND) || normalized.startsWith(COMMAND + " ");
        boolean explicit = KEY.equals(request.skillKey()) || command;
        boolean natural = REQUEST.matcher(normalized).find()
                && (normalized.contains("项目") || normalized.contains("git") || normalized.contains("仓库"));
        String repoInput = first(REPO, text, 0);
        Task target = matches(anchored, KEY) ? anchored : null;
        if (target == null && request.targetTaskId() == null && !natural && !explicit && matches(focus, KEY)
                && "NEEDS_CLARIFICATION".equals(focus.status())
                && (text.length() <= 160 && (repoInput != null || catalog.repository(actor, text).isPresent()))) {
            target = focus;
        }
        if (request.skillKey() != null && !KEY.equals(request.skillKey()) || !explicit && !natural && target == null) {
            return Optional.empty();
        }
        if (anchored != null && target == null) return Optional.empty();
        var definition = catalog.find(actor, KEY, target == null ? null : target.workflowSkill(), command ? COMMAND : null);
        if (definition.isEmpty()) {
            if (!explicit && target == null) return Optional.empty();
            var assessment = assessment(explicit);
            var outcome = new Outcome(INTENT, target == null ? "NEW_TASK" : "CONTINUATION", target == null ? null : target.id(),
                    "UNSUPPORTED", "NONE", KEY, Map.of(), List.of(), List.of(),
                    "当前没有可用的复盘 Skill 版本。", "SKILL_VERSION_UNAVAILABLE", List.of(), assessment, null);
            return Optional.of(new Result(null, outcome));
        }
        return Optional.of(capture(actor, request, text, normalized, explicit, command, repoInput, target, definition.get(), now, revision));
    }

    private Result capture(Actor actor, Request request, String text, String normalized, boolean explicit,
                           boolean command, String repoInput, Task target, Definition definition, long now, long revision) {
        Map<String, Object> supplied = request.arguments() == null ? Map.of() : request.arguments();
        var slots = new LinkedHashMap<String, Object>();
        var sources = new LinkedHashMap<String, String>();
        var invalid = new java.util.ArrayList<String>();
        var missing = new java.util.ArrayList<String>();
        String project = supplied.containsKey("project") && supplied.get("project") instanceof String s ? s.strip() : null;
        if (!supplied.containsKey("project")) {
            project = first(QUOTED_PROJECT, text, 1);
            if (project == null && command && text.length() > COMMAND.length()) project = text.substring(COMMAND.length()).strip();
            if (project == null && repoInput != null) project = repoInput;
            if (project == null && target != null && !text.isBlank() && catalog.repository(actor, text).isPresent()) project = text;
        }
        if (supplied.containsKey("project") && (project == null || project.isBlank()) || project != null && project.length() > 160) {
            invalid.add("project");
            project = null;
        }
        boolean currentProject = project != null && !project.isBlank();
        String priorProject = prior(target, "project");
        if (currentProject && repoInput != null && repoInput.equalsIgnoreCase(project) && priorProject != null) {
            project = priorProject;
            currentProject = false;
        } else if (!currentProject && !invalid.contains("project")) {
            project = priorProject;
        }
        if (project == null) missing.add("project");
        else put(slots, sources, "project", project, currentProject ? "CURRENT_TURN" : "PRIOR_TASK");

        Integer days;
        String span = first(DAYS, normalized, 1);
        if (supplied.containsKey("days") || span != null || normalized.contains("最近一个月") || normalized.contains("近一个月")) {
            if (supplied.containsKey("days")) days = wholeDays(supplied.get("days"));
            else if (span == null) days = 30;
            else days = span.length() <= 3 ? Integer.valueOf(span) : null;
            if (days == null || days < 1 || days > 90) invalid.add("days");
            else put(slots, sources, "days", days, "CURRENT_TURN");
        } else {
            days = target != null && target.slots().get("days") instanceof Number n ? n.intValue() : definition.defaultDays();
            if (days == null) missing.add("days");
            else put(slots, sources, "days", days, target != null && target.slots().containsKey("days") ? "PRIOR_TASK" : "SCHEMA_DEFAULT");
        }
        if (!invalid.isEmpty() && invalid.contains("days")) days = null;

        String to = prior(target, "to");
        if (to == null) to = Instant.ofEpochMilli(now).toString();
        put(slots, sources, "to", to, "TRUSTED_CONTEXT");
        String from = days == null ? null : Instant.parse(to).minus(days, ChronoUnit.DAYS).toString();
        if (from != null) put(slots, sources, "from", from, "TRUSTED_CONTEXT");
        String repositoryQuery = repoInput != null && !supplied.containsKey("project") ? repoInput : project;
        if (repoInput == null && !currentProject && repositoryQuery != null && prior(target, "repository") != null) {
            repositoryQuery = prior(target, "repository");
        }
        Repository repo = repositoryQuery == null ? null : catalog.repository(actor, repositoryQuery).orElse(null);
        if (project != null && repo == null) missing.add("repository");
        if (repo != null && project != null) {
            put(slots, sources, "repository", repo.name(), "VERIFIED_LOOKUP");
            put(slots, sources, "ref", repo.ref(), "VERIFIED_LOOKUP");
        }
        Map<String, Object> arguments = project != null && days != null ? Map.of("project", project, "days", days) : Map.of();
        if (missing.isEmpty() && invalid.isEmpty() && !definition.valid().test(arguments)) invalid.add("project");
        boolean ready = missing.isEmpty() && invalid.isEmpty();
        var selected = definition.skill();
        UUID id = target == null ? UUID.randomUUID() : target.id();
        var assessment = assessment(explicit);
        String state = ready ? "READY" : "NEEDS_CLARIFICATION";
        var ir = ready ? new ValidatedIntentIR("PROJECT", "RETROSPECTIVE", selected.definitionVersion(), id, arguments,
                sources, null, "NORMAL", ValidatedIntentIR.ResponseMode.PROGRESS_AND_RESULT, selected,
                new WorkflowContext(repo.name(), repo.ref(), from, to)) : null;
        String question = ready ? null : invalid.contains("days") || missing.contains("days") ? "请提供 1 到 90 天的复盘范围。"
                : "请提供已授权的项目名称，或明确 GitHub 仓库 owner/repo。";
        String reason = ready ? "WORKFLOW_INPUT_VALIDATED" : invalid.isEmpty() ? "WORKFLOW_INPUT_REQUIRED" : "WORKFLOW_INPUT_INVALID";
        var task = new Task(id, INTENT, Map.copyOf(slots), state, revision, now, assessment, null,
                selected.definitionVersion(), selected);
        var outcome = new Outcome(INTENT, target == null ? "NEW_TASK" : "CONTINUATION", id, state, "WORKFLOW", KEY,
                task.slots(), List.copyOf(missing), List.copyOf(invalid), question, reason, List.of(), assessment,
                ir, selected, sources);
        return new Result(task, outcome);
    }

    public static boolean isWorkflow(Task task) { return task != null && task.workflowSkill() != null; }
    static boolean matches(Task task, String key) { return isWorkflow(task) && key.equals(task.workflowSkill().key()); }

    private static String prior(Task task, String key) {
        return task != null && task.slots().get(key) instanceof String value ? value : null;
    }

    private static Integer wholeDays(Object raw) {
        if (!(raw instanceof Number number)) return null;
        try {
            return new java.math.BigDecimal(number.toString()).intValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            return null;
        }
    }

    private static String first(Pattern pattern, String text, int group) {
        var match = pattern.matcher(text);
        return match.find() ? match.group(group) : null;
    }

    private static void put(Map<String, Object> values, Map<String, String> sources, String key, Object value, String source) {
        values.put(key, value);
        sources.put(key, source);
    }

    static Assessment assessment(boolean explicit) {
        return new Assessment(explicit ? .22 : .38, explicit ? 1 : .93, explicit ? "RULE" : "RETRIEVAL_SMALL_MODEL",
                explicit ? "RULE" : "RETRIEVAL_SMALL_MODEL", false, null);
    }

    public interface Catalog {
        Optional<Definition> find(Actor actor, String key, WorkflowSkill pinned, String command);
        Optional<Repository> repository(Actor actor, String alias);
    }
    public record Definition(WorkflowSkill skill, Integer defaultDays, Predicate<Map<String, Object>> valid) {}
    public record Repository(String name, String ref) {}
    public record Result(Task task, Outcome outcome) {}
}
