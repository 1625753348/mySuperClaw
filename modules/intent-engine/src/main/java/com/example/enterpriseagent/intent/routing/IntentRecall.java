package com.example.enterpriseagent.intent.routing;

import java.text.Normalizer;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** 在小型意图目录内用 BM25 召回，并以字符向量余弦分数精排。 */
public final class IntentRecall {
    private static final Pattern WORDS = Pattern.compile("[a-z0-9]+|[\\p{IsHan}]+");
    private final List<Document> documents;
    private final List<String> texts;
    private final Embedder embedder;
    private final Map<String, Integer> frequency = new HashMap<>();
    private final double averageLength;
    private volatile List<float[]> vectors = List.of();
    private volatile long retryAfter;

    public IntentRecall(Map<String, String> documents) {
        this(documents, null);
    }

    public IntentRecall(Map<String, String> documents, Embedder embedder) {
        var ordered = documents.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList();
        this.documents = ordered.stream().map(entry -> document(entry.getKey(), entry.getValue())).toList();
        this.texts = ordered.stream().map(Map.Entry::getValue).toList();
        this.embedder = embedder;
        for (Document doc : this.documents) doc.terms().keySet().forEach(token -> frequency.merge(token, 1, Integer::sum));
        averageLength = this.documents.stream().mapToInt(Document::length).average().orElse(1);
    }

    public List<Match> rank(String text, int limit) {
        var query = document("", text);
        var semantic = semanticScores(text);
        return java.util.stream.IntStream.range(0, documents.size()).mapToObj(index -> {
                    var doc = documents.get(index);
                    double lexical = score(query, doc);
                    return new Match(doc.id(), semantic.isEmpty() ? lexical : 0.55 * lexical + 0.45 * semantic.get(index));
                })
                .filter(match -> match.score() > 0)
                .sorted(Comparator.comparingDouble(Match::score).reversed().thenComparing(Match::id))
                .limit(limit).toList();
    }

    private List<Double> semanticScores(String text) {
        if (embedder == null || retryAfter != 0 && System.nanoTime() - retryAfter < 0) return List.of();
        try {
            List<float[]> indexed = semanticIndex();
            if (indexed.isEmpty()) return List.of();
            var response = embedder.embed(List.of(text));
            if (response.size() != 1) throw new IllegalStateException("向量响应数量无效");
            float[] query = unit(response.getFirst());
            if (query.length != indexed.getFirst().length) throw new IllegalStateException("意图向量维度不一致");
            return indexed.stream().map(vector -> cosine(query, vector)).toList();
        } catch (RuntimeException unavailable) {
            retryAfter = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            return List.of();
        }
    }

    private synchronized List<float[]> semanticIndex() {
        if (vectors.isEmpty() && !texts.isEmpty() && (retryAfter == 0 || System.nanoTime() - retryAfter >= 0)) {
            var prepared = embedder.embed(texts).stream().map(IntentRecall::unit).toList();
            if (prepared.size() != texts.size() || prepared.stream().mapToInt(vector -> vector.length).distinct().count() != 1) {
                throw new IllegalStateException("意图索引向量数量或维度无效");
            }
            vectors = prepared;
        }
        return vectors;
    }

    private static float[] unit(float[] vector) {
        double square = 0;
        for (float value : vector) square += (double) value * value;
        if (!(square > 0) || !Double.isFinite(square)) throw new IllegalStateException("意图向量无效");
        var normalized = vector.clone();
        double length = Math.sqrt(square);
        for (int i = 0; i < normalized.length; i++) normalized[i] /= (float) length;
        return normalized;
    }

    private static double cosine(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) dot += (double) left[i] * right[i];
        return Math.clamp(dot, 0, 1);
    }

    private double score(Document query, Document doc) {
        double bm25 = 0;
        double norm = 0;
        double dot = 0;
        for (var term : query.terms().entrySet()) {
            double weight = idf(term.getKey());
            norm += weight;
            int tf = doc.terms().getOrDefault(term.getKey(), 0);
            if (tf == 0) continue;
            bm25 += weight * (tf * 2.2) / (tf + 1.2 * (0.25 + 0.75 * doc.length() / averageLength));
            dot += term.getValue() * tf;
        }
        double lexical = norm == 0 ? 0 : Math.min(1, bm25 / norm);
        double cosine = dot == 0 ? 0 : dot / Math.sqrt(query.norm() * doc.norm());
        return 0.65 * lexical + 0.35 * cosine;
    }

    private double idf(String token) {
        int seen = frequency.getOrDefault(token, 0);
        return Math.log1p((documents.size() - seen + 0.5) / (seen + 0.5));
    }

    private static Document document(String id, String text) {
        var tokens = new HashMap<String, Integer>();
        var words = WORDS.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT));
        while (words.find()) {
            String word = words.group();
            if (!Character.isIdeographic(word.codePointAt(0))) {
                tokens.merge(word, 1, Integer::sum);
                continue;
            }
            int[] chars = word.codePoints().toArray();
            for (int i = 0; i < chars.length; i++) {
                tokens.merge(new String(chars, i, 1), 1, Integer::sum);
                if (i + 1 < chars.length) tokens.merge(new String(chars, i, 2), 1, Integer::sum);
            }
        }
        int length = tokens.values().stream().mapToInt(Integer::intValue).sum();
        long norm = tokens.values().stream().mapToLong(tf -> (long) tf * tf).sum();
        return new Document(id, tokens, length, norm);
    }

    private record Document(String id, Map<String, Integer> terms, int length, long norm) {}
    public record Match(String id, double score) {}
    @FunctionalInterface public interface Embedder { List<float[]> embed(List<String> texts); }
}
