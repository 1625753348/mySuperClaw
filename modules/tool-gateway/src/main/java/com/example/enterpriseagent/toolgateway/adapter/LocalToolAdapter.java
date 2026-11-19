package com.example.enterpriseagent.toolgateway.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 只调用显式注册的 LocalTool。超时时以 UNKNOWN 保留可能已经发生的副作用。 */
public final class LocalToolAdapter implements ToolAdapter, AutoCloseable {
    private final ObjectMapper json;
    private final Map<String, LocalTool> tools;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Semaphore capacity = new Semaphore(32);

    public LocalToolAdapter(ObjectMapper json, List<LocalTool> tools) {
        this.json = json;
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(LocalTool::name, Function.identity()));
    }

    @Override public Protocol protocol() { return Protocol.LOCAL; }

    @Override public boolean configured(String targetRef) { return tools.containsKey(targetRef); }

    @Override
    public Outcome invoke(Call call) {
        LocalTool tool = tools.get(call.targetRef());
        if (tool == null) {
            return new Outcome("FAILED", null, "LOCAL_TOOL_NOT_CONFIGURED");
        }
        var future = executor.submit(() -> run(tool, call));
        try {
            return future.get(call.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            return new Outcome("UNKNOWN", null, "LOCAL_TIMEOUT");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Outcome("UNKNOWN", null, "LOCAL_INTERRUPTED");
        } catch (ExecutionException ex) {
            return new Outcome("UNKNOWN", null, "LOCAL_TOOL_ERROR");
        } finally {
            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private Outcome run(LocalTool tool, Call call) throws Exception {
        if (!capacity.tryAcquire()) {
            return new Outcome("FAILED", null, "LOCAL_CAPACITY_EXHAUSTED");
        }
        try {
            String output = json.writeValueAsString(tool.run(json.readTree(call.arguments()), call));
            if (output.getBytes(StandardCharsets.UTF_8).length > 262_144) {
                return new Outcome("FAILED", null, "LOCAL_RESPONSE_TOO_LARGE");
            }
            return new Outcome("SUCCEEDED", output, null);
        } finally {
            capacity.release();
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
