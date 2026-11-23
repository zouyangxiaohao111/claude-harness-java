package com.nexusai.application.agent.acp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 内存假 ACP server（单测用）· 一对管道流 + 一个读循环，不启真进程。
 *
 * <p>三种用法：
 * <ul>
 *   <li>{@link #on(String, Function)} 对 client 请求回固定帧；</li>
 *   <li>{@link #requestClient} 主动向 client 发请求（权限），回调拿到 client 的响应；</li>
 *   <li>{@link #notifyClient} 主动发通知（session/update）。</li>
 * </ul>
 * 所有帧处理都在同一读线程，写帧加锁 ⇒ 不会交叉。
 */
final class FakeAcpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PipedOutputStream clientToServerOut = new PipedOutputStream();
    private final PipedInputStream clientToServerIn;
    private final PipedOutputStream serverToClientOut = new PipedOutputStream();
    private final PipedInputStream clientIn;

    private final Map<String, Function<JsonNode, List<JsonNode>>> handlers = new ConcurrentHashMap<>();
    private final Map<Long, Consumer<JsonNode>> clientResponders = new ConcurrentHashMap<>();
    private final List<JsonNode> received = Collections.synchronizedList(new ArrayList<>());

    private Thread thread;
    private volatile boolean closed;

    FakeAcpServer() throws IOException {
        clientToServerIn = new PipedInputStream(clientToServerOut, 1 << 16);
        clientIn = new PipedInputStream(serverToClientOut, 1 << 16);
    }

    AcpTransport clientTransport() {
        return new AcpTransport(clientIn, clientToServerOut);
    }

    FakeAcpServer on(String method, Function<JsonNode, List<JsonNode>> handler) {
        handlers.put(method, handler);
        return this;
    }

    FakeAcpServer start() {
        thread = new Thread(this::loop, "fake-acp-server");
        thread.setDaemon(true);
        thread.start();
        return this;
    }

    List<JsonNode> received() {
        synchronized (received) {
            return new ArrayList<>(received);
        }
    }

    /** 主动向 client 发请求；{@code onResult} 在读线程上被调用。 */
    void requestClient(long id, String method, Object params, Consumer<JsonNode> onResult) {
        clientResponders.put(id, onResult);
        send(frame(id, method, params));
    }

    /** 主动向 client 发通知。 */
    void notifyClient(String method, Object params) {
        send(frame(null, method, params));
    }

    /** 回一个 result（{@code id} 为 null 时是通知）。 */
    void respond(Long id, Object result) {
        send(response(id, result));
    }

    /** 回一个 error。 */
    void respondError(Long id, int code, String message) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        send(response);
    }

    static ObjectNode frame(Long id, String method, Object params) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        if (id != null) {
            frame.put("id", id);
        }
        frame.put("method", method);
        if (params != null) {
            frame.set("params", JSON.valueToTree(params));
        }
        return frame;
    }

    static ObjectNode response(Long id, Object result) {
        ObjectNode frame = JSON.createObjectNode();
        frame.put("jsonrpc", "2.0");
        frame.put("id", id);
        frame.set("result", result == null ? JSON.nullNode() : JSON.valueToTree(result));
        return frame;
    }

    /** 帧写入口 · 测试可直接写原始字符串（坏帧/粘帧用例）。 */
    synchronized void writeRaw(String text) {
        try {
            serverToClientOut.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            serverToClientOut.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void send(JsonNode frame) {
        try {
            writeRaw(JSON.writeValueAsString(frame));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void loop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(clientToServerIn, StandardCharsets.UTF_8))) {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                JsonNode frame;
                try {
                    frame = JSON.readTree(line);
                } catch (Exception e) {
                    continue;
                }
                received.add(frame);
                dispatch(frame);
            }
        } catch (IOException ignore) {
            // 关闭
        }
    }

    private void dispatch(JsonNode frame) {
        JsonNode method = frame.get("method");
        JsonNode id = frame.get("id");
        if (method != null && method.isTextual()) {
            Function<JsonNode, List<JsonNode>> handler = handlers.get(method.asText());
            if (handler != null) {
                for (JsonNode out : handler.apply(frame)) {
                    send(out);
                }
            }
            return;
        }
        if (id != null && id.isIntegralNumber()) {
            Consumer<JsonNode> responder = clientResponders.remove(id.asLong());
            if (responder != null) {
                responder.accept(frame);
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            clientToServerOut.close();
        } catch (IOException ignore) {
            // ignore
        }
        try {
            serverToClientOut.close();
        } catch (IOException ignore) {
            // ignore
        }
    }
}
