package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 独立的假 ACP server 进程（E2E 用）· 真子进程 + 真 stdio，不依赖模型/密钥。
 *
 * <p>session/prompt 时先推三条 session/update（正文 chunk / tool_call /
 * tool_call_update completed），再回 {stopReason:end_turn}；stdin EOF 即退出。
 *
 * <p>可选 argv：{@code args[0]} = cancel 标记文件路径（{@code "-"} = 不写）；
 * {@code args[1]} = {@code "hold"} ⇒ 不回应 session/prompt（用于测父轮取消）。
 */
public final class AcpFakeServerMain {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        String cancelMarker = args.length > 0 ? args[0] : "-";
        boolean holdPrompt = args.length > 1 && "hold".equals(args[1]);
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode frame;
            try {
                frame = JSON.readTree(line);
            } catch (Exception e) {
                continue;
            }
            JsonNode methodNode = frame.get("method");
            if (methodNode == null || !methodNode.isTextual()) {
                continue;
            }
            JsonNode idNode = frame.get("id");
            Long id = idNode != null && idNode.isIntegralNumber() ? idNode.asLong() : null;
            switch (methodNode.asText()) {
                case "initialize":
                    respond(out, id, Map.of("protocolVersion", 1,
                            "agentInfo", Map.of("name", "fake-acp")));
                    break;
                case "session/new":
                    respond(out, id, Map.of("sessionId", "fake-session-1"));
                    break;
                case "session/prompt":
                    if (holdPrompt) {
                        // 不回应：让 client 的 prompt 保持挂起，供「父轮取消」用例观察
                        break;
                    }
                    notifyUpdate(out, Map.of("sessionUpdate", "agent_message_chunk",
                            "content", Map.of("type", "text", "text", "hello from fake acp")));
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("sessionUpdate", "tool_call");
                    toolCall.put("toolCallId", "t-1");
                    toolCall.put("title", "Run echo");
                    toolCall.put("kind", "execute");
                    toolCall.put("status", "in_progress");
                    toolCall.put("rawInput", Map.of("command", "echo ok"));
                    notifyUpdate(out, toolCall);
                    Map<String, Object> done = new LinkedHashMap<>();
                    done.put("sessionUpdate", "tool_call_update");
                    done.put("toolCallId", "t-1");
                    done.put("status", "completed");
                    done.put("content", List.of(Map.of("type", "content",
                            "content", Map.of("type", "text", "text", "ok"))));
                    notifyUpdate(out, done);
                    respond(out, id, Map.of("stopReason", "end_turn"));
                    break;
                case "session/cancel":
                    if (!"-".equals(cancelMarker)) {
                        try {
                            Files.writeString(Path.of(cancelMarker), "cancelled");
                        } catch (Exception ignore) {
                            // 标记写失败不影响协议行为
                        }
                    }
                    break;
                default:
                    if (id != null) {
                        write(out, error(id, AcpRpcException.METHOD_NOT_FOUND,
                                "unsupported: " + methodNode.asText()));
                    }
            }
        }
    }

    private static void respond(PrintStream out, Long id, Object result) {
        if (id == null) {
            return;
        }
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.set("result", JSON.valueToTree(result));
        write(out, frame);
    }

    private static void notifyUpdate(PrintStream out, Map<String, Object> update) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("method", "session/update");
        frame.set("params", JSON.valueToTree(
                Map.of("sessionId", "fake-session-1", "update", update)));
        write(out, frame);
    }

    private static ObjectNode error(Long id, int code, String message) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        ObjectNode error = frame.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return frame;
    }

    private static void write(PrintStream out, JsonNode frame) {
        try {
            out.println(JSON.writeValueAsString(frame));
        } catch (Exception e) {
            out.println("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialize failed\"}}");
        }
    }
}
