package com.example.enterpriseagent.toolgateway;

import java.util.List;

/** 从可信认证构造，工具参数和模型输出不能设置这些字段。 */
public record GatewayCaller(String tenantId, String principalId, String agentId, List<String> roles) {

    public GatewayCaller {
        if (missing(tenantId) || missing(principalId) || missing(agentId)) {
            throw new GatewayException(GatewayException.Code.FORBIDDEN, "凭证缺少网关所需的身份信息");
        }
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    private static boolean missing(String value) {
        return value == null || value.isBlank();
    }
}
