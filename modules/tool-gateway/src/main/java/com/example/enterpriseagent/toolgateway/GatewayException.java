package com.example.enterpriseagent.toolgateway;

/** 网关对调用方返回稳定错误码，底层网络错误不出现在响应中。 */
public final class GatewayException extends RuntimeException {

    public enum Code { INVALID, FORBIDDEN, NOT_FOUND, CONFLICT, POLICY_REQUIRED, UPSTREAM }

    private final Code code;

    public GatewayException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
