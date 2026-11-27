package com.example.enterpriseagent.infrastructure.skills;

import com.example.enterpriseagent.skill.agent.AgentSkills.Actor;
import com.example.enterpriseagent.skill.agent.AgentSkills.Summary;
import com.example.enterpriseagent.toolgateway.registry.ToolContracts;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 从部署审核的目录加载不可变版本；模型只按授权逐层看到相应内容。 */
public final class AgentSkillCatalog {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern RISK = Pattern.compile(
            "(?i)\\.(?:ssh|aws)[/\\\\]|\\bid_rsa\\b|\\bid_ed25519\\b"
                    + "|ignore.{0,30}(?:previous|system).{0,15}instructions"
                    + "|(?:忽略|绕过).{0,12}(?:系统指令|工具权限|审批)"
                    + "|(?:泄露|外传|输出).{0,8}(?:私钥|访问令牌|环境变量)");
    private final List<Skill> skills;

    public AgentSkillCatalog(Path configuredRoot, List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            skills = List.of();
            return;
        }
        if (configuredRoot == null || entries.size() > 64) throw new IllegalArgumentException("Agent Skill 目录配置无效");
        try {
            Path root = configuredRoot.toRealPath();
            skills = entries.stream().map(entry -> load(root, entry)).toList();
            if (skills.stream().map(skill -> skill.summary().name()).distinct().count() != skills.size()) {
                throw new IllegalArgumentException("Agent Skill 名称重复");
            }
        } catch (IOException invalid) {
            throw new UncheckedIOException("无法加载 Agent Skill 目录", invalid);
        }
    }

    public List<Skill> visible(Actor actor) {
        return skills.stream().filter(skill -> skill.access().tenants().contains(actor.tenant())
                && (skill.access().users().contains(actor.user()) || actor.roles() != null
                    && actor.roles().stream().anyMatch(skill.access().roles()::contains))).toList();
    }

    private static Skill load(Path root, Entry entry) {
        try {
            if (!validName(entry.name()) || entry.tenants().isEmpty() || entry.users().isEmpty() && entry.roles().isEmpty()
                    || entry.tools().size() > 12 || entry.resources().size() > 12) {
                throw new IllegalArgumentException("缺少有效名称或授权范围");
            }
            if (entry.tools().stream().map(Tool::name).distinct().count() != entry.tools().size()) {
                throw new IllegalArgumentException("工具绑定重复");
            }
            Path directory = root.resolve(entry.name());
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("技能必须是根目录下的普通目录");
            }
            String raw = read(directory, "SKILL.md", 32_768).replace("\r\n", "\n");
            int frontEnd = raw.indexOf("\n---\n", 4);
            if (!raw.startsWith("---\n") || frontEnd < 0) throw new IllegalArgumentException("缺少 YAML frontmatter");
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(0);
            options.setCodePointLimit(32_768);
            if (!(new Yaml(new SafeConstructor(options)).load(raw.substring(4, frontEnd)) instanceof Map<?, ?> front)
                    || !entry.name().equals(front.get("name"))
                    || !(front.get("description") instanceof String description)
                    || description.isBlank() || description.length() > 1024) {
                throw new IllegalArgumentException("无效的 Agent Skills name/description");
            }
            String instructions = raw.substring(frontEnd + 5).strip();
            if (instructions.isBlank()) throw new IllegalArgumentException("缺少技能指令正文");
            scan(raw);
            var resources = new LinkedHashMap<String, String>();
            var digest = new StringBuilder(ToolContracts.digest(raw));
            for (String path : entry.resources().stream().sorted().toList()) {
                if (!path.matches("(?:references|assets)/(?:[a-zA-Z0-9_-]+/)*[a-zA-Z0-9_.-]+\\.(?:md|txt|json|csv)")) {
                    throw new IllegalArgumentException("资源必须是白名单中的文本文件");
                }
                String value = read(directory, path, 12_288);
                scan(value);
                resources.put(path, value);
                digest.append('\n').append(path).append(':').append(ToolContracts.digest(value));
            }
            String hash = ToolContracts.digest(digest.toString());
            String version = front.get("metadata") instanceof Map<?, ?> metadata && metadata.get("version") instanceof String declared
                    && declared.matches("[a-zA-Z0-9_.-]{1,48}") ? declared : hash.substring(0, 12);
            return new Skill(new Summary(entry.name(), description, version), hash, instructions, Map.copyOf(resources), entry);
        } catch (IOException invalid) {
            throw new UncheckedIOException("无法读取发布的 Agent Skill " + entry.name(), invalid);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Agent Skill 校验失败 " + entry.name() + "，请检查元数据、资源和内容审查", invalid);
        }
    }

    private static String read(Path directory, String relative, int limit) throws IOException {
        Path file = directory.resolve(relative);
        if (!file.normalize().startsWith(directory) || !file.toRealPath().startsWith(directory)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("技能资源越过发布目录");
        }
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IllegalArgumentException("技能文件超过大小限制");
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        }
    }

    private static boolean validName(String value) {
        return value != null && value.length() <= 64 && NAME.matcher(value).matches();
    }

    private static void scan(String value) {
        String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC).replaceAll("\\p{Cf}", "");
        if (RISK.matcher(normalized).find()) throw new IllegalArgumentException("技能触发内容审查");
    }

    public record Entry(String name, List<String> tenants, List<String> users, List<String> roles,
                        List<Tool> tools, List<String> resources) {
        public Entry {
            tenants = tenants == null ? List.of() : List.copyOf(tenants);
            users = users == null ? List.of() : List.copyOf(users);
            roles = roles == null ? List.of() : List.copyOf(roles);
            tools = tools == null ? List.of() : List.copyOf(tools);
            resources = resources == null ? List.of() : List.copyOf(resources);
        }
    }

    public record Tool(String name, String version) {
        public Tool {
            if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")
                    || version == null || !version.matches("[A-Za-z0-9._-]{1,48}")) {
                throw new IllegalArgumentException("Agent Skill 工具必须绑定明确名称和版本");
            }
        }
    }

    public record Skill(Summary summary, String digest, String instructions, Map<String, String> resources, Entry access) {}
}
