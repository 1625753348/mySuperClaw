package com.example.enterpriseagent.toolgateway.discovery;

import java.util.List;
import java.util.UUID;

/** 向量是可重建的候选索引，发布与授权状态仍由 PostgreSQL 决定。 */
public interface ToolSemanticSearch {
    boolean enabled();

    List<Match> search(String tenant, String query, int limit);

    void index(String tenant, UUID version, String text);

    record Match(UUID versionId, double score) {}
}
