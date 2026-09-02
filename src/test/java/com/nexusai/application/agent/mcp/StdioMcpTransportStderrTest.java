package com.nexusai.application.agent.mcp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.nexusai.application.agent.subagent.JsonRpcMcpClient;
import com.nexusai.application.agent.tool.ToolRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [S02 X-5] stdio stderr 64MB cap 消费 + 连接成功后日志 · 对齐 CC client.ts:966-983
 * （stderrOutput 累积 + {@code stderrOutput.length < 64*1024*1024} cap）+ :1081-1083
 * （连接成功后 logMCPError(stderr) + 清空）。
 *
 * <p><b>WHY（规则九）</b>：旧实现 redirectErrorStream(false) 但 getErrorStream() 从不读取
 * ——子进程 stderr 洪泛（>64KB 管道缓冲）→ 子进程写阻塞 → stdout 响应永不送达 →
 * sendRequest 悬挂（X-5 脏代码）。本测试锁定：
 * <ol>
 *   <li>子进程 stderr 洪泛 >64MB：无管道死锁——transport 仍可 sendRequest 并收到响应</li>
 *   <li>cap 生效：drainStderrLog 累积 ≤ 64MB（超出丢弃，CC :973）</li>
 *   <li>连接成功后 stderr 摘要经 McpToolPool 日志（CC :1081-1083 logMCPError 等价）</li>
 * </ol>
 */
@DisplayName("[S02 X-5] stdio stderr 64MB cap 消费 + 连接后日志")
class StdioMcpTransportStderrTest {

    private StdioMcpTransport transport;

    @AfterEach
    void tearDown() {
        if (transport != null) {
            transport.close();
        }
    }

    private static List<String> javaChildArgs(String mode) {
        return List.of(
            "-cp", System.getProperty("java.class.path"),
            StdioTestChildMain.class.getName(),
            mode
        );
    }

    private static McpTransport.TransportConfig stdioConfig(String mode) {
        Path javaBin = javaBin();
        return new McpTransport.TransportConfig(
            javaBin.toString(), javaChildArgs(mode), Map.of(), null, "flood-srv", "stdio");
    }

    private static Path javaBin() {
        return java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java");
    }

    @Test
    @DisplayName("stderr 洪泛 >64MB：无管道死锁（sendRequest 仍响应）+ drainStderrLog cap 生效")
    void stderrFlood_noPipeDeadlock_capEnforced() throws Exception {
        transport = new StdioMcpTransport();
        transport.start(stdioConfig("flood"));
        assertThat(transport.getState()).isEqualTo(McpTransport.State.CONNECTED);

        // 子进程：先洪泛 ~82MB stderr 再回响应 → join 成功即证明无管道死锁（洪泛未阻塞 stdout）
        JsonNode result = transport.sendRequest("tools/call", Map.of("name", "t")).get(60, TimeUnit.SECONDS);
        assertThat(result.path("ok").asBoolean()).as("洪泛 stderr 期间 sendRequest 必须仍可用（无管道死锁）")
            .isTrue();

        // 洪泛已由 stderr reader 消费：cap 生效（≤64MB）+ 确有累积（洪泛远超 cap 阈值）
        String drained = transport.drainStderrLog();
        assertThat(drained).as("drainStderrLog 必须返回累积内容（非空）").isNotBlank();
        assertThat(drained).contains("flood-line-");
        assertThat(drained.length()).as("64MB cap 必须生效（CC client.ts:973 语义：cap 检查在追加前，"
                + "单行可越界一行长度——与 CC 字符串拼接同构）")
            .isLessThanOrEqualTo(64 * 1024 * 1024 + 1024);
        assertThat(drained.length()).as("洪泛量 >64MB → 累积必须触及 cap 区间（证明 cap 被实际行使）")
            .isGreaterThan(50_000_000);

        // 第二次 drain（取+清空语义，CC :1083 释放内存）：清空后为空
        assertThat(transport.drainStderrLog()).as("drain 取+清空（CC :1083 释放内存）").isEmpty();
    }

    @Test
    @DisplayName("连接成功后 stderr 摘要经 McpToolPool 日志（CC :1081-1083 logMCPError 等价）")
    void connectSuccess_logsStderrSummary() throws Exception {
        ch.qos.logback.classic.Logger poolLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(McpToolPool.class);
        Level original = poolLogger.getLevel();
        poolLogger.setLevel(Level.INFO);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        poolLogger.addAppender(appender);
        try {
            McpToolPool pool = new McpToolPool(new McpTransportFactory(),
                new ToolRegistry(), new JsonRpcMcpClient());
            // assembleToolPool 全流：start（spawn java flood 子进程）→ initialize（触发洪泛）
            // → 连接成功后 drainStderrLog 非空 → info 日志（CC :1081-1083）
            pool.assembleToolPool("flood-srv", stdioConfig("flood"));

            boolean logged = appender.list.stream()
                .filter(e -> e.getFormattedMessage() != null
                    && e.getFormattedMessage().contains("server stderr")
                    && e.getFormattedMessage().contains("flood-line"))
                .findFirst().isPresent();
            assertThat(logged).as("连接成功后必须输出 stderr 摘要日志（CC :1081-1083 logMCPError 等价）")
                .isTrue();
            pool.teardown("flood-srv");
        } finally {
            poolLogger.detachAppender(appender);
            poolLogger.setLevel(original);
        }
    }
}
