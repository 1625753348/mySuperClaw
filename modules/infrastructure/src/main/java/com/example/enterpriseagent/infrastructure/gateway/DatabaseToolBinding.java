package com.example.enterpriseagent.infrastructure.gateway;

import javax.sql.DataSource;
import java.util.Set;

/** 应用启动时注册查询模板及专用只读数据源。调用参数只能绑定给模板中的命名参数。 */
public record DatabaseToolBinding(String name, DataSource source, String sql, Set<String> allowedTenants, int maxRows) {
    public DatabaseToolBinding {
        if (name == null || name.isBlank() || source == null || sql == null || !sql.stripLeading().regionMatches(true, 0, "SELECT ", 0, 7)
                || sql.contains(";") || allowedTenants == null || allowedTenants.isEmpty() || maxRows < 1 || maxRows > 500) {
            throw new IllegalArgumentException("数据库工具需要固定 SELECT、允许租户和最多 500 行");
        }
        allowedTenants = Set.copyOf(allowedTenants);
    }
}
