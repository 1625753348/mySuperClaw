package com.example.enterpriseagent.toolgateway.discovery;

import com.example.enterpriseagent.toolgateway.GatewayCaller;
import com.example.enterpriseagent.toolgateway.GatewayException;
import com.example.enterpriseagent.toolgateway.adapter.ToolAdapterFactory;
import com.example.enterpriseagent.toolgateway.execution.ToolSecrets;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository;
import com.example.enterpriseagent.toolgateway.registry.GatewayRepository.Candidate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** PostgreSQL 授权快照、BM25、可选向量召回、RRF 和确定性精排。 */
public final class ToolDiscovery {
    private static final System.Logger LOG = System.getLogger(ToolDiscovery.class.getName());
    private static final int PAGE_SIZE = 250;
    private static final Pattern WORDS = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+");

    private final GatewayRepository store;
    private final ToolSemanticSearch semantic;
    private final ToolAdapterFactory adapters;
    private final ToolSecrets secrets;
    private final ObjectMapper json;

    public ToolDiscovery(GatewayRepository store, ToolSemanticSearch semantic,
                         ToolAdapterFactory adapters, ToolSecrets secrets, ObjectMapper json) {
        this.store = store;
        this.semantic = semantic;
        this.adapters = adapters;
        this.secrets = secrets;
        this.json = json;
    }

    public List<Hit> search(GatewayCaller caller, String query, int limit) {
        if (query == null || query.length() > 120 || limit < 1 || limit > 50) {
            throw new GatewayException(GatewayException.Code.INVALID, "查询长度或返回数量无效");
        }
        List<Candidate> candidates = authorized(caller);
        if (query.isBlank()) {
            return candidates.stream().limit(limit).map(tool -> hit(tool, 0, 0, null)).toList();
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Scored> documents = candidates.stream().map(tool -> new Scored(tool, terms(text(tool)))).toList();
        Set<String> queryTerms = terms(query).keySet();
        scoreLexical(documents, queryTerms);
        mergeRanks(documents, caller.tenantId(), query);
        String exactName = normalize(query).strip();
        documents.forEach(document -> {
            if (exactName.equals(normalize(document.tool.name()))) {
                document.fused += 1.0 / 61;
            }
            if (!queryTerms.isEmpty() && document.lexical > 0) {
                long covered = queryTerms.stream().filter(document.frequency::containsKey).count();
                document.fused += 0.002 * covered / queryTerms.size();
            }
        });
        return documents.stream().filter(document -> document.fused > 0)
                .sorted(Comparator.comparingDouble((Scored item) -> item.fused).reversed()
                        .thenComparing(Comparator.comparingDouble((Scored item) -> item.lexical).reversed())
                        .thenComparing(item -> item.tool.name()))
                .limit(limit)
                .map(item -> hit(item.tool, item.fused, item.lexical, item.semantic))
                .toList();
    }

    private List<Candidate> authorized(GatewayCaller caller) {
        String roles = json.valueToTree(caller.roles()).toString();
        List<Candidate> allowed = new ArrayList<>();
        String cursor = "";
        List<Candidate> page;
        do {
            page = store.discover(new GatewayRepository.Search(caller.tenantId(), caller.principalId(),
                    caller.agentId(), roles, cursor, PAGE_SIZE));
            if (!page.isEmpty()) {
                cursor = page.getLast().name();
                page.stream().filter(tool -> adapters.configured(caller.tenantId(), tool.protocol(), tool.targetRef())
                                && secrets.permits(caller.tenantId(), tool.protocol(), tool.targetRef(), tool.credentialRef()))
                        .forEach(allowed::add);
            }
        } while (page.size() == PAGE_SIZE);
        return allowed;
    }

    private static void scoreLexical(List<Scored> documents, Set<String> queryTerms) {
        double average = Math.max(1, documents.stream().mapToInt(item -> item.length).average().orElse(1));
        for (String term : queryTerms) {
            long frequency = documents.stream().filter(doc -> doc.frequency.containsKey(term)).count();
            double idf = Math.log(1 + (documents.size() - frequency + 0.5) / (frequency + 0.5));
            for (Scored doc : documents) {
                int count = doc.frequency.getOrDefault(term, 0);
                if (count != 0) {
                    doc.lexical += idf * (count * 2.2) / (count + 1.2 * (0.25 + 0.75 * doc.length / average));
                }
            }
        }
    }

    private void mergeRanks(List<Scored> documents, String tenant, String query) {
        List<Scored> lexical = documents.stream().filter(item -> item.lexical > 0)
                .sorted(Comparator.comparingDouble((Scored item) -> item.lexical).reversed()
                        .thenComparing(item -> item.tool.name())).toList();
        for (int i = 0; i < lexical.size(); i++) {
            lexical.get(i).fused += 1.0 / (61 + i);
        }
        if (!semantic.enabled()) {
            return;
        }
        Map<UUID, Scored> visible = documents.stream().collect(Collectors.toMap(item -> item.tool.versionId(), item -> item));
        int rank = 0;
        try {
            for (var match : semantic.search(tenant, query, 100)) {
                Scored item = visible.get(match.versionId());
                if (item != null && item.semantic == null && Double.isFinite(match.score())) {
                    item.semantic = match.score();
                    item.fused += 1.0 / (61 + rank++);
                }
            }
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "工具向量召回暂不可用，使用 BM25");
        }
    }

    public boolean indexPublished(String tenant, UUID toolId) {
        Candidate published = store.indexableTool(tenant, toolId);
        return published == null || index(tenant, published);
    }

    public Reindex reindex(String tenant, String afterName, int limit) {
        if (!semantic.enabled()) {
            throw new GatewayException(GatewayException.Code.CONFLICT, "工具向量检索未启用");
        }
        if (afterName == null || afterName.length() > 64 || limit < 1 || limit > 100) {
            throw new GatewayException(GatewayException.Code.INVALID, "索引游标或批量大小无效");
        }
        List<Candidate> page = store.indexable(tenant, afterName, limit);
        List<String> failed = new ArrayList<>();
        for (Candidate tool : page) {
            if (!index(tenant, tool)) {
                failed.add(tool.name());
            }
        }
        return new Reindex(page.size() - failed.size(), failed, page.size() < limit ? null : page.getLast().name());
    }

    private boolean index(String tenant, Candidate tool) {
        try {
            semantic.index(tenant, tool.versionId(), text(tool));
            return true;
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "工具版本的向量索引未更新");
            return false;
        }
    }

    private String text(Candidate tool) {
        StringBuilder text = new StringBuilder(tool.name()).append(' ').append(tool.name()).append(' ')
                .append(tool.description());
        if (tool.category() != null) {
            text.append(' ').append(tool.category());
        }
        try {
            var properties = json.readTree(tool.inputSchema()).path("properties");
            properties.fields().forEachRemaining(field -> text.append(' ').append(field.getKey())
                    .append(' ').append(field.getValue().path("description").asText()));
        } catch (JsonProcessingException ex) {
            LOG.log(System.Logger.Level.WARNING, "工具契约无法生成参数摘要");
        }
        return text.substring(0, Math.min(text.length(), 3000));
    }

    private static Map<String, Integer> terms(String text) {
        Map<String, Integer> frequency = new HashMap<>();
        var matcher = WORDS.matcher(normalize(text));
        while (matcher.find()) {
            String part = matcher.group();
            int[] points = part.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN && points.length > 1) {
                for (int i = 0; i < points.length - 1; i++) {
                    frequency.merge(new String(points, i, 2), 1, Integer::sum);
                }
            } else {
                frequency.merge(part, 1, Integer::sum);
            }
        }
        return frequency;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static Hit hit(Candidate item, double score, double bm25, Double dense) {
        return new Hit(item.name(), item.description(), item.versionName(), item.protocol(), score, bm25, dense,
                item.category());
    }

    private static final class Scored {
        final Candidate tool;
        final Map<String, Integer> frequency;
        final int length;
        double lexical;
        double fused;
        Double semantic;

        Scored(Candidate tool, Map<String, Integer> frequency) {
            this.tool = tool;
            this.frequency = frequency;
            length = frequency.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    public record Hit(String name, String description, String versionName, String protocol, double score,
                      double bm25Score, Double semanticScore, String category) {}
    public record Reindex(int indexed, List<String> failed, String nextAfter) {}
}
