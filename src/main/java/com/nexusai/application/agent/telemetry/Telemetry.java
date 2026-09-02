package com.nexusai.application.agent.telemetry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolDecisionInfo;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.logs.LoggerProvider;
import io.opentelemetry.api.logs.Severity;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.exporter.logging.SystemOutLogRecordExporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Telemetry 总模块 · 对齐 CC utils/telemetry/instrumentation.ts (825 行) + bigqueryExporter/perfettoTracing/sessionTracing 等 9 文件.
 *
 * <p><b>[R32-b12 D-2 P0 修复]</b> 扩 OpenTelemetry SDK 接入 (路径 C: OTel API + Logging Exporter).
 *
 * <h2>R32-b12 之前状态</h2>
 * <p>FIX-H1b 简化版: 仅 52 行 in-memory 计数器 + SLF4J trace 日志. 注释明确说
 * "LIMIT: 真实 telemetry 需 OTel SDK; 当前是内存计数 + logging".
 *
 * <h2>R32-b12 之后状态</h2>
 * <p>在保留向后兼容 in-memory 计数器基础上, 加 OpenTelemetry SDK (Logging Exporter):
 * <ul>
 *   <li>{@link #openTelemetry} —— OTel SDK 实例 (OpenTelemetrySdk)</li>
 *   <li>{@link #eventLogger} —— CC 真源 {@code getEventLogger().emit()} 等价</li>
 *   <li>{@link #logOTelEvent(String, Map)} —— 对齐 CC
 *       Open-ClaudeCode/src/utils/telemetry/events.ts:21-75</li>
 *   <li>{@link #recordEvent(String, Map)} —— CC logEvent() 等价 (1P/Statsig 适配层)</li>
 *   <li>{@link #extractToolInputForTelemetry(JsonNode)} —— 对齐 CC metadata.ts:291-303</li>
 *   <li>{@link #isToolDetailsLoggingEnabled()} —— 对齐 CC metadata.ts:86-88 (OTEL_LOG_TOOL_DETAILS)</li>
 *   <li>{@link #incrementCodeEditCounter(String, String, String, String)} —— 对齐 CC
 *       toolExecution.ts:970-976 (isCodeEditingTool + Counter)</li>
 * </ul>
 *
 * <h2>路径 C 决策（CLAUDE.md 规则 12 · Fail loud）</h2>
 * <p>使用 OTel API + OTel SDK + Logging Exporter (无 OTLP collector):
 * <ul>
 *   <li>严格对齐 CC bootstrapTelemetry 接口 (CC 用 LoggingSpanExporter/ConsoleLogRecordExporter 等价)</li>
 *   <li>本地 stdout/logback 输出 (生产环境可平滑迁移到 OTLP Honeycomb/BigQuery)</li>
 *   <li>0 collector 依赖 (避免 R32+ 引入 Prometheus/OTel-collector 运维负担)</li>
 * </ul>
 *
 * <h2>向后兼容 (CLAUDE.md 规则 3 · 外科手术式)</h2>
 * <ul>
 *   <li>{@link #recordEvent(String)} / {@link #recordEvent(String, Map)} —— 保留 (原 52 行 API)</li>
 *   <li>{@link #getCounter(String)} / {@link #totalEvents()} / {@link #flush()} —— 保留</li>
 *   <li>原 in-memory 计数器继续累积 (作为 OTel 之外的本地 fallback)</li>
 * </ul>
 *
 * @see ToolTelemetryProperties
 * @see OpenTelemetrySdk
 * @see EventLogger
 * @since R32-b12
 */
@Component
public class Telemetry {

    private static final Logger log = LoggerFactory.getLogger(Telemetry.class);

    /**
     * CC 真源常量 · 对齐 Open-ClaudeCode/src/utils/telemetry/events.ts:36-38.
     * event.name 属性 key (OTel semantic convention).
     */
    private static final AttributeKey<String> EVENT_NAME = AttributeKey.stringKey("event.name");
    private static final AttributeKey<String> EVENT_TIMESTAMP = AttributeKey.stringKey("event.timestamp");
    private static final AttributeKey<Long> EVENT_SEQUENCE = AttributeKey.longKey("event.sequence");
    private static final AttributeKey<String> PROMPT_ID = AttributeKey.stringKey("prompt.id");
    private static final AttributeKey<String> WORKSPACE_HOST_PATHS = AttributeKey.stringKey("workspace.host_paths");
    private static final AttributeKey<String> TOOL_NAME = AttributeKey.stringKey("tool_name");

    /**
     * OTel SDK 实例 · 对齐 CC {@code getOtelInstance()} 单例 (instrumentation.ts:86).
     * null 时 (Spring 未注入 properties 或 disabled) 全部 OTel 调用降级为 noop.
     */
    private volatile OpenTelemetry openTelemetry;
    private volatile io.opentelemetry.api.logs.Logger eventLogger;
    private volatile LoggerProvider loggerProvider;

    /**
     * [R32-b12 D-2] 事件序列号单调递增 · 对齐 CC
     * Open-ClaudeCode/src/utils/telemetry/events.ts:36 ({@code eventSequence++}).
     * 模块级 static 状态; Java 端用实例 AtomicLong 持有 (Spring 容器内单例).
     */
    private final AtomicLong eventSequence = new AtomicLong(0);

    /**
     * [R32-b12 D-9 P1] code-edit counter · 对齐 CC
     * Open-ClaudeCode/src/services/tools/toolExecution.ts:970-976 (Counter<ToolAttributes>).
     * key=decisionSource; value=AtomicLong. counter 仅在 Edit/Write/MultiEdit 工具调用时 +1.
     */
    private final Map<String, AtomicLong> codeEditCounters = new ConcurrentHashMap<>();

    /**
     * [R32-b12 D-2 P0] 配置注入 · @Autowired(required=false) 容错无 bean 场景 (单测可手动注入).
     * 字段名匹配 application.yml {@code nexusai.telemetry.otel.*}.
     */
    private final ToolTelemetryProperties properties;

    /**
     * 原向后兼容 in-memory 计数器 (R32-b12 之前 52 行实现保留).
     */
    private final AtomicLong totalEvents = new AtomicLong(0);
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    public Telemetry() {
        this(new ToolTelemetryProperties());
    }

    @Autowired
    public Telemetry(ToolTelemetryProperties properties) {
        this.properties = properties != null ? properties : new ToolTelemetryProperties();
    }

    /**
     * [R32-b12 D-2] Spring 启动后初始化 OTel SDK (Logging Exporter).
     *
     * <p>WHY @PostConstruct: OTel SDK 需要 Resource + LoggerProvider 在第一次 logOTelEvent
     * 之前就绪; properties 注入后立即构建, 避免 lazy init 在 hot path 上支付开销.
     *
     * <p>Spring 关闭时 {@link #shutdown()} 由 @PreDestroy 调用.
     */
    @PostConstruct
    public void init() {
        if (!properties.isEnabled()) {
            log.info("Telemetry OTel SDK 关闭 (nexusai.telemetry.otel.enabled=false)");
            return;
        }
        try {
            Resource resource = Resource.getDefault().toBuilder()
                .put("service.name", properties.getServiceName())
                .build();
            // 路径 C: SystemOut LogRecordExporter (无 collector, 输出到 stdout/logback).
            // 注意: io.opentelemetry.exporter.logging 内部 LoggingLogRecordExporter 是 private
            // (via SPI provider); 公开 API 是 SystemOutLogRecordExporter.
            LogRecordExporter loggingExporter = SystemOutLogRecordExporter.create();
            LogRecordProcessor processor = SimpleLogRecordProcessor.create(loggingExporter);
            SdkLoggerProvider sdkLoggerProvider = SdkLoggerProvider.builder()
                .setResource(resource)
                .addLogRecordProcessor(processor)
                .build();
            this.loggerProvider = sdkLoggerProvider;
            this.openTelemetry = OpenTelemetrySdk.builder()
                .setLoggerProvider(sdkLoggerProvider)
                .build();
            this.eventLogger = sdkLoggerProvider
                .get("nexusai.tool.telemetry");
            log.info("Telemetry OTel SDK 初始化完成: serviceName={} logToolDetails={}",
                properties.getServiceName(), properties.isLogToolDetails());
        } catch (Throwable th) {
            // Fail loud 但不挂掉 Spring 启动 (CLAUDE.md 规则 12)
            log.error("Telemetry OTel SDK 初始化失败, 降级为 in-memory 模式: err={}", th.toString(), th);
            this.openTelemetry = OpenTelemetry.noop();
            this.eventLogger = null;
            this.loggerProvider = null;
        }
    }

    /**
     * [R32-b12 D-2] Spring 关闭时 flush + shutdown SDK.
     */
    @PreDestroy
    public void shutdown() {
        if (loggerProvider instanceof SdkLoggerProvider sdkProvider) {
            try {
                sdkProvider.shutdown();
                log.info("Telemetry OTel SDK shutdown 完成");
            } catch (Throwable th) {
                log.warn("Telemetry OTel SDK shutdown 失败: {}", th.toString());
            }
        }
    }

    // ─────────────────── 4 工具函数 (D-6/D-7/D-8/D-9/D-10) ───────────────────

    /**
     * [R32-b12 D-6] OTel 事件发射 · 对齐 CC Open-ClaudeCode/src/utils/telemetry/events.ts:21-75.
     *
     * <p>WHY: CC 真源 {@code logOTelEvent} 把 metadata 写入 OTel Attributes 后调
     * {@code eventLogger.emit({body, attributes})}. Java 端用 OTel API 的
     * {@code EventLogger.builder(body).add(...).emit()} 等价.
     *
     * <p>OTel 不可用时（init 失败/disabled）降级为 SLF4J trace 日志 (保持向后兼容).
     *
     * @param eventName 事件名（映射到 OTel event.name + body=`claude_code.${eventName}`）
     * @param metadata  属性 metadata (key → value); null 值跳过 (对齐 CC for-loop)
     */
    public void logOTelEvent(String eventName, Map<String, ?> metadata) {
        if (eventName == null || eventName.isBlank()) {
            return;
        }
        long sequence = eventSequence.incrementAndGet();
        if (eventLogger == null) {
            // OTel SDK 未初始化 → SLF4J fallback (测试/降级场景)
            if (log.isTraceEnabled()) {
                log.trace("OTel 降级（SDK 未初始化）：事件={} 序号={} 属性={}",
                    eventName, sequence, metadata);
            }
            return;
        }
        try {
            AttributesBuilder builder = Attributes.builder()
                .put(EVENT_NAME, eventName)
                .put(EVENT_TIMESTAMP, java.time.Instant.now().toString())
                .put(EVENT_SEQUENCE, sequence);
            if (metadata != null) {
                for (Map.Entry<String, ?> entry : metadata.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (key == null || value == null) {
                        continue;
                    }
                    builder = putAttribute(builder, key, value);
                }
            }
            // workspace.host_paths 是 multi-value, OTel API 仅支持 string attribute,
            // 简化版: 用 "|" 分隔写入单字符串 (CC 是 array, Java 用 string 简化)
            String hostPaths = properties.getWorkspaceHostPaths();
            if (hostPaths != null && !hostPaths.isBlank()) {
                builder.put(WORKSPACE_HOST_PATHS, hostPaths);
            }
            eventLogger.logRecordBuilder()
                .setBody("claude_code." + eventName)
                .setSeverity(Severity.INFO)
                .setAllAttributes(builder.build())
                .emit();
            if (log.isDebugEnabled()) {
                log.debug("OTel 事件已发送：名称={} 序号={}", eventName, sequence);
            }
        } catch (Throwable th) {
            log.warn("OTel event emit 失败: name={} err={}", eventName, th.toString());
        }
    }

    /**
     * [R32-b12 D-6] logOTelEvent 重载 (无 metadata) · 对齐 CC events.ts:22 默认参数.
     */
    public void logOTelEvent(String eventName) {
        logOTelEvent(eventName, Map.of());
    }

    /**
     * [R32-b12 D-8 P1] code-edit counter +1 · 对齐 CC toolExecution.ts:970-976.
     *
     * <p>WHY: CC 在 headless 模式时 permission path 不写 tool_decision logEvent，
     * 所以在主路径用 code-edit counter 补足. Java 端简化: 用 ConcurrentHashMap + AtomicLong 模拟
     * OTel Counter (R33+ 可替换为正式 OTel Counter).
     *
     * <p>调用方（{@code LlmAgentLoop.applyPermissionFilter}）在 isCodeEditingTool(tool.name())
     * 通过时调用本方法.
     *
     * @param toolName  工具名（Edit / Write / MultiEdit / NotebookEdit）
     * @param decision  {@code "accept"} / {@code "reject"}
     * @param source    decision source (decisionReasonToOTelSource 输出)
     * @param decisionType  decision type (CC: 同 decision 字段, 保留双字段语义)
     */
    public void incrementCodeEditCounter(String toolName, String decision,
                                          String source, String decisionType) {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        // key = toolName + decision, 区分 accept/reject 计数
        String key = toolName + "|" + (decision == null ? "" : decision);
        codeEditCounters.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        if (log.isDebugEnabled()) {
            log.debug("代码编辑计数器 +1：工具={} 决策={} 来源={} 类型={}",
                toolName, decision, source, decisionType);
        }
    }

    /**
     * [R32-b12 D-7] isToolDetailsLoggingEnabled 开关 · 对齐 CC metadata.ts:86-88.
     *
     * <p>CC 真源: {@code return process.env.OTEL_LOG_TOOL_DETAILS === 'true'}.
     * Java 端从 {@link ToolTelemetryProperties} 读取 {@code nexusai.telemetry.otel.log-tool-details}.
     *
     * <p>WHY: tool parameters 可能含敏感内容（bash command / file_path / MCP server name），
     * 应 opt-in 而非 opt-out. 默认 false.
     *
     * @return true 当 OTEL_LOG_TOOL_DETAILS 开启
     */
    public boolean isToolDetailsLoggingEnabled() {
        return properties != null && properties.isLogToolDetails();
    }

    /**
     * [R32-b12 D-10 P1] tool_result_size_bytes · 计算 ToolResult.content() 字节大小.
     *
     * <p>对齐 CC toolExecution.ts:1297-1301 mappedToolResultBlock.content.length.
     *
     * @param content ToolResult content (可为 null)
     * @return UTF-8 字节大小 (content=null → 0)
     */
    public long toolResultSizeBytes(String content) {
        if (content == null) {
            return 0L;
        }
        return content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * [R32-b12 D-6] 提取工具参数供 OTel log · 对齐 CC metadata.ts:291-303 extractToolInputForTelemetry.
     *
     * <p>WHY: 工具 input JsonNode 可能很大（含整文件内容 / 大 bash command），需要深度截断 +
     * 字符串截断 + 数组截断 + JSON 字符上限. 输出 JSON 字符串供 OTel attribute 写入.
     *
     * <p>CC 真源:
     * <ul>
     *   <li>TOOL_INPUT_MAX_DEPTH = 2</li>
     *   <li>TOOL_INPUT_STRING_TRUNCATE_AT = 500</li>
     *   <li>TOOL_INPUT_STRING_TRUNCATE_TO = 200</li>
     *   <li>TOOL_INPUT_MAX_COLLECTION_ITEMS = 20</li>
     *   <li>TOOL_INPUT_MAX_JSON_CHARS = 5000</li>
     * </ul>
     *
     * @param input 工具输入 JsonNode (可为 null)
     * @return JSON 字符串 (null → null); 当 isToolDetailsLoggingEnabled()==false 返回 null (对齐 CC)
     */
    public String extractToolInputForTelemetry(JsonNode input) {
        if (!isToolDetailsLoggingEnabled()) {
            return null;
        }
        if (input == null || input.isNull()) {
            return null;
        }
        JsonNode truncated = ToolInputTruncator.truncate(input);
        try {
            String json = new ObjectMapper().writeValueAsString(truncated);
            if (json.length() > ToolInputTruncator.MAX_JSON_CHARS) {
                json = json.substring(0, ToolInputTruncator.MAX_JSON_CHARS) + "…[truncated]";
            }
            return json;
        } catch (Exception e) {
            log.warn("extractToolInputForTelemetry 序列化失败: err={}", e.toString());
            return null;
        }
    }

    /**
     * [R32-b12 D-4] 写入 tool decision 到 ctx map · 供后续 tool_result OTel event 使用.
     *
     * <p>对齐 CC toolExecution.ts:953-955 / 1170-1171 / 1741-1743
     * ({@code toolUseContext.toolDecisions.set(toolUseID, {source, decision})}).
     *
     * <p>调用方（LlmAgentLoop.applyPermissionFilter）在 Allow/Deny 分支调本方法，
     * 然后 StreamingToolExecutor 工具完成后读 ctx.toolDecisions() 注入 telemetry.
     *
     * @param decisions map (key=toolUseId, value=ToolDecisionInfo)
     * @param toolUseId tool call id
     * @param info decisionInfo (允许 null 时跳过)
     */
    public static void recordToolDecision(Map<String, ToolDecisionInfo> decisions,
                                           String toolUseId, ToolDecisionInfo info) {
        if (decisions == null || toolUseId == null || info == null) {
            return;
        }
        decisions.put(toolUseId, info);
    }

    /**
     * 静态 helper: 把任意 Object 值塞入 Attributes builder.
     * 简化版: 仅支持 String / Long / Boolean / Double. 复杂对象 → toString().
     */
    private static AttributesBuilder putAttribute(AttributesBuilder builder, String key, Object value) {
        if (value instanceof String s) {
            return builder.put(AttributeKey.stringKey(key), s);
        } else if (value instanceof Long l) {
            return builder.put(AttributeKey.longKey(key), l);
        } else if (value instanceof Integer i) {
            return builder.put(AttributeKey.longKey(key), i.longValue());
        } else if (value instanceof Boolean b) {
            return builder.put(AttributeKey.booleanKey(key), b);
        } else if (value instanceof Double d) {
            return builder.put(AttributeKey.doubleKey(key), d);
        } else {
            return builder.put(AttributeKey.stringKey(key), String.valueOf(value));
        }
    }

    // ─────────────────── 原 52 行 API 保留（向后兼容）───────────────────

    public void recordEvent(String name, Map<String, Object> attributes) {
        totalEvents.incrementAndGet();
        counters.computeIfAbsent(name, k -> new AtomicLong(0)).incrementAndGet();
        if (log.isTraceEnabled()) {
            log.trace("遥测事件：{} 属性={}", name, attributes);
        }
    }

    public void recordEvent(String name) {
        recordEvent(name, Map.of());
    }

    public long getCounter(String name) {
        AtomicLong counter = counters.get(name);
        return counter == null ? 0 : counter.get();
    }

    public long totalEvents() {
        return totalEvents.get();
    }

    public void flush() {
        log.debug("遥测刷新：共 {} 个事件", totalEvents.get());
    }

    public OpenTelemetry getOpenTelemetry() {
        return openTelemetry;
    }

    public io.opentelemetry.api.logs.Logger getEventLogger() {
        return eventLogger;
    }

    /**
     * 复制 metadata map 为 Map<String, Object> 用于 recordEvent (向后兼容 in-memory counter).
     * 避免 Map<String, ?> 与 Map<String, Object> 类型冲突.
     */
    private static Map<String, Object> copyMetadataForRecord(Map<String, ?> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new java.util.HashMap<>(metadata.size());
        for (Map.Entry<String, ?> entry : metadata.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    public enum MetricType { COUNTER, HISTOGRAM, GAUGE }
}