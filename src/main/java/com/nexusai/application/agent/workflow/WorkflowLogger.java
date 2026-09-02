package com.nexusai.application.agent.workflow;

import java.util.Map;

/**
 * 日志 + telemetry · CC original: {@code Logger}
 * (Open-ClaudeCode/packages/workflow-engine/src/ports.ts:104-112)。
 *
 * <p>WorkflowPorts 8 项依赖抽象之一「日志」。引擎层接口；核心层 logger 实现
 * 委托 {@code logForDebugging}（src/workflow/ports.ts:196-200）。
 */
public interface WorkflowLogger {

    /**
     * 调试日志 · CC original: {@code debug(msg): void} (ports.ts:105)。
     *
     * @param message CC original: {@code msg}
     */
    void debug(String message);

    /**
     * 警告日志（可选）· CC original: {@code warn?(msg): void} (ports.ts:110-111)。
     * 引擎对单个 parallel/pipeline 项失败等用 {@code ?.()} 容忍旧实现缺省；
     * Java 端接口恒有，且支持 SLF4J 风格格式参数（W-1c 引擎以 {@code warn(format, args...)} 调用，
     * 单参数 CC 调用 args 为空仍兼容）。核心实现前缀 "[workflow warn] "，对齐 ports.ts:197。
     *
     * @param message CC original: {@code msg} — 日志格式串（可含 {} 占位）
     * @param args    格式参数（SLF4J 风格；可空）
     */
    void warn(String message, Object... args);

    /**
     * 事件日志 · CC original: {@code event(name, metadata?): void} (ports.ts:106-108)。
     * 核心层实现仅打日志不带上行（ports.ts:198-199 {@code event: name => logForDebugging(...)}）。
     *
     * @param name     CC original: {@code name} — 事件名
     * @param metadata CC original: {@code metadata?} — 事件元数据（可空 Map）
     */
    void event(String name, Map<String, Object> metadata);
}
