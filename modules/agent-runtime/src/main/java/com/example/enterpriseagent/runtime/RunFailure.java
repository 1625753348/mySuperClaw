package com.example.enterpriseagent.runtime;

public final class RunFailure extends RuntimeException {
    public enum Code { INVALID, NOT_FOUND, CONFLICT, FORBIDDEN, BUSY }
    private final Code code;
    public RunFailure(Code code, String message) {
        super(message);
        this.code = code;
    }
    public Code code() { return code; }
}
