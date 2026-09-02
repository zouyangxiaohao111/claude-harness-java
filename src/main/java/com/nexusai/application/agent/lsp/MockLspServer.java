package com.nexusai.application.agent.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 真实 LSP server 测试替身 (stdio 子进程模式).
 *
 * <p>运行方式: {@code java -cp <classpath> com.nexusai.application.agent.lsp.MockLspServer},
 * 通过 stdin/stdout 与 ProcessLspClient 通信, 帧格式 Content-Length + body.
 * 在单测中可作为 ProcessBuilder.command() 的可执行命令启动.
 */
public class MockLspServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        new MockLspServer().run();
    }

    private void run() throws IOException {
        AtomicBoolean running = new AtomicBoolean(true);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        OutputStream out = System.out;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> running.set(false)));
        while (running.get()) {
            String contentLengthLine = in.readLine();
            if (contentLengthLine == null) break;
            if (!contentLengthLine.toLowerCase().startsWith("content-length:")) continue;
            int len = Integer.parseInt(contentLengthLine.split(":")[1].trim());
            in.readLine(); // 空行
            char[] buf = new char[len];
            int read = 0;
            while (read < len) {
                int n = in.read(buf, read, len - read);
                if (n < 0) break;
                read += n;
            }
            String json = new String(buf, 0, read);
            JsonNode node;
            try {
                node = MAPPER.readTree(json);
            } catch (Exception e) {
                continue;
            }
            String method = node.path("method").asText("");
            long id = node.path("id").asLong(0);
            switch (method) {
                case "initialize" -> sendResponse(out, id, buildInitializeResult());
                case "textDocument/hover" -> sendResponse(out, id, buildHoverResult("mock hover content"));
                case "textDocument/definition" -> sendResponse(out, id, buildDefinitionResult());
                default -> {
                    // ignore
                }
            }
        }
    }

    private static ObjectNode buildInitializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode caps = MAPPER.createObjectNode();
        caps.put("definitionProvider", true);
        caps.put("hoverProvider", true);
        caps.put("referencesProvider", true);
        caps.put("documentSymbolProvider", true);
        result.set("capabilities", caps);
        ObjectNode serverInfo = MAPPER.createObjectNode();
        serverInfo.put("name", "mock-lsp-server");
        serverInfo.put("version", "1.0.0-test");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private static ObjectNode buildHoverResult(String content) {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode contents = MAPPER.createObjectNode();
        contents.put("kind", "markdown");
        contents.put("value", content);
        result.set("contents", contents);
        return result;
    }

    private static ObjectNode buildDefinitionResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ObjectNode location = MAPPER.createObjectNode();
        location.put("uri", "file:///mock/definition.ts");
        ObjectNode range = MAPPER.createObjectNode();
        ObjectNode start = MAPPER.createObjectNode();
        start.put("line", 0);
        start.put("character", 0);
        ObjectNode end = MAPPER.createObjectNode();
        end.put("line", 0);
        end.put("character", 10);
        range.set("start", start);
        range.set("end", end);
        location.set("range", range);
        result.set("location", location);
        return result;
    }

    private static void sendResponse(OutputStream out, long id, Object result) throws IOException {
        Map<String, Object> msg = Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "result", result
        );
        byte[] body = MAPPER.writeValueAsBytes(msg);
        String header = "Content-Length: " + body.length + "\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }
}