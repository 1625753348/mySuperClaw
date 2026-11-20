package com.example.enterpriseagent.infrastructure.intent;

import com.example.enterpriseagent.intent.IntentModel.Actor;
import com.example.enterpriseagent.intent.IntentModel.WorkflowSkill;
import com.example.enterpriseagent.intent.WorkflowIntake;
import com.example.enterpriseagent.skill.SkillRegistry;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** 读取发布后的 Skill 契约。仓库选择仅在租户预先配置的范围内进行。 */
public final class RetrospectiveCatalog implements WorkflowIntake.Catalog {
    private final SkillRegistry skills;
    private final ToolContracts contracts;
    private final ObjectMapper json;
    private final List<Binding> repositories;

    public RetrospectiveCatalog(SkillRegistry skills, ToolContracts contracts, ObjectMapper json, Repositories settings) {
        this.skills = skills;
        this.contracts = contracts;
        this.json = json;
        this.repositories = settings.repositories() == null ? List.of() : List.copyOf(settings.repositories());
    }

    @Override
    public Optional<WorkflowIntake.Definition> find(Actor actor, String key, WorkflowSkill pinned, String command) {
        var subject = new SkillRegistry.Actor(actor.tenant(), actor.user(), actor.agent(), List.copyOf(actor.roles()));
        SkillRegistry.Published published;
        try {
            published = command != null && pinned == null ? skills.resolve(subject, command)
                    : skills.get(subject, key, pinned == null ? null : pinned.version());
        } catch (SkillRegistry.Failure ex) {
            if (ex.status() == 404) return Optional.empty();
            throw ex;
        }
        if (!key.equals(published.skillKey()) || pinned != null && !pinned.id().equals(published.id())
                || !List.of("PUBLISHED", "RETIRED").contains(published.status())) return Optional.empty();
        String definition = published.dag().path("intentDefinition").asText();
        String intent = WorkflowIntake.KEY.equals(key) ? "PROJECT.RETROSPECTIVE" : "ORDER.REFUND";
        if (!definition.matches(java.util.regex.Pattern.quote(intent) + "@[1-9][0-9]{0,8}")) return Optional.empty();
        var schema = published.dag().path("inputSchema");
        var dayDefault = schema.path("properties").path("days").path("default");
        int version = Integer.parseInt(definition.substring(definition.indexOf('@') + 1));
        Integer days = dayDefault.isIntegralNumber() && dayDefault.canConvertToInt()
                && dayDefault.intValue() >= 1 && dayDefault.intValue() <= 90 ? dayDefault.intValue() : null;
        return Optional.of(new WorkflowIntake.Definition(new WorkflowSkill(published.id(), published.skillKey(), published.version(), version),
                days, values -> contracts.matches(schema.toString(), json.valueToTree(values))));
    }

    @Override
    public Optional<WorkflowIntake.Repository> repository(Actor actor, String alias) {
        String normalized = normalize(alias);
        var permitted = repositories.stream().filter(binding -> binding.tenant().equals(actor.tenant()))
                .filter(binding -> binding.roles().stream().anyMatch(actor.roles()::contains))
                .filter(binding -> normalized.equals(normalize(binding.repository()))
                        || binding.aliases().stream().anyMatch(value -> normalized.equals(normalize(value))))
                .map(binding -> new WorkflowIntake.Repository(binding.repository(), binding.ref())).distinct().limit(2).toList();
        return permitted.size() == 1 ? Optional.of(permitted.getFirst()) : Optional.empty();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.strip(), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    @ConfigurationProperties(prefix = "enterprise.intent.retrospective")
    public record Repositories(List<Binding> repositories) {}

    public record Binding(String tenant, String repository, String ref, List<String> aliases, List<String> roles) {
        public Binding {
            if (tenant == null || tenant.isBlank() || repository == null
                    || !repository.matches("[A-Za-z0-9-]{1,39}/[A-Za-z0-9_.-]{1,100}")
                    || repository.endsWith("/.") || repository.endsWith("/..")
                    || ref == null || ref.isBlank() || ref.length() > 128) {
                throw new IllegalArgumentException("复盘仓库绑定的租户、仓库或分支配置无效");
            }
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            roles = roles == null ? List.of() : List.copyOf(roles);
        }
    }
}
