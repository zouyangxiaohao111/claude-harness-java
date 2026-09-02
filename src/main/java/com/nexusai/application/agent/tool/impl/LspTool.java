package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.lsp.LspManager;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ReadPermissionChecker;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.Tool;
import com.nexusai.application.agent.tool.ToolNameConstants;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LSP 工具 · 对齐 CC LSPTool.ts:127-860 (buildTool({name: LSP_TOOL_NAME, isLsp: true, ...})).
 *
 * <p>L1 语义: LLM 调 LSP 操作 (goToDefinition/findReferences/hover/documentSymbol 等).
 * isEnabled 委托给 {@link LspManager#isLspConnected()} (CC manager.ts:99).
 * 未连接时拒绝执行, 返回明确错误.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1 API Contract</b>: operation 枚举对齐 CC LSPTool.ts:62-72 (9 种)</li>
 *   <li><b>A2 Golden Trace</b>: call(filePath) → getServerForFile → client.sendRequest 序列</li>
 *   <li><b>A3 State Machine</b>: 未 connected → 早返; 未 init → 抛 IllegalStateException</li>
 *   <li><b>A4 Tool Sequence</b>: validateInput → checkPermissions → call</li>
 *   <li><b>A5 Business Result</b>: result 字段 = 服务端响应 JSON 字符串</li>
 * </ul>
 *
 * <p>L3 (Java idiom): POJO, sealed AgentToolResult, Map.of 不可变. 由 {@code ToolRegistrationConfig}
 * 显式 @Bean 注册 (与 SkillToolImpl 同款模式, 避免 @Component 自动注册与 @Bean 冲突).
 */
public class LspTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(LspTool.class);

    /** 对齐 CC {@code LSPTool.ts:53 MAX_LSP_FILE_SIZE_BYTES = 10_000_000}（10MB 上限）。 */
    private static final long MAX_LSP_FILE_SIZE_BYTES = 10_000_000L;

    private final LspManager lspManager;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * [S06 接线 · X11] 读权限检查器 · 对齐 CC {@code checkReadPermissionForTool}
     * （filesystem.ts:1030-1193）。{@code @Autowired(required=false)} + setter 模式与
     * GlobTool/GrepTool 一致：本工具经 ToolRegistrationConfig @Bean 注册，构造器保持
     * 单参（{@code lspManager}）不动，测试经 setter 装配。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker;

    /** 测试/装配用 setter · 与 GlobTool 同模式（构造器保留旧 API）。 */
    public void setPermissionChecker(
            com.nexusai.application.agent.permission.ReadPermissionChecker permissionChecker) {
        this.permissionChecker = permissionChecker;
    }

    public LspTool(LspManager lspManager) {
        this.lspManager = lspManager;
    }

    @Override
    public String name() {
        // CC 原名: LSP_TOOL_NAME (Open-ClaudeCode/src/tools/LSPTool/prompt.ts:1) = 'LSP'
        // 旧实现返回小写 'lsp' 偏离 CC 大写；现引用 ToolNameConstants 常量保证单点权威。
        if (log.isDebugEnabled()) {
            log.debug("LspTool.name(): 返回 CC 工具名 LSP_TOOL_NAME='LSP'（对齐 Open-ClaudeCode/src/tools/LSPTool/prompt.ts:1）");
        }
        return ToolNameConstants.LSP_TOOL_NAME;
    }

    @Override
    public String description() {
        return "Interact with Language Server Protocol (LSP) servers for code intelligence "
            + "(definitions, references, hover, symbols). Requires LSP server connected.";
    }

    @Override
    public JsonNode inputSchema() {
        // 完整 schema 对齐 CC LSPTool.ts:62-72 (9 种 operation 枚举)
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("operation", Map.of(
            "type", "string",
            "enum", java.util.List.of(
                "goToDefinition", "findReferences", "hover",
                "documentSymbol", "workspaceSymbol", "goToImplementation",
                "prepareCallHierarchy", "incomingCalls", "outgoingCalls"
            ),
            "description", "LSP operation to perform"));
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "The absolute or relative path to the file"));
        properties.put("line", Map.of(
            "type", "integer",
            "minimum", 1,
            "description", "1-based line number (as shown in editors)"));
        properties.put("character", Map.of(
            "type", "integer",
            "minimum", 1,
            "description", "1-based character offset (as shown in editors)"));
        schema.put("properties", properties);
        // CC LSPTool.ts:62-86 必填 operation/filePath/line/character 四参
        schema.put("required", java.util.List.of("operation", "filePath", "line", "character"));
        return mapper.valueToTree(schema);
    }

    /**
     * 路径扩展点 · CC original: {@code getPath({filePath}) → expandPath(filePath)}
     * （{@code LSPTool.ts:152}）。
     *
     * <p>权限管线（CC {@code filesystem.ts:1035-1041}）用本方法取本次 LSP 操作的路径做权限检查。
     * Java 端直接返回 {@code filePath} 字段（不做 expandPath，Java 无 cwd 展开概念；CC
     * expandPath 语义差异登记）。
     *
     * @param input 工具输入（含 {@code filePath}）
     * @return 本次 LSP 操作的路径；缺失返回 null
     */
    @Override
    public String getPath(JsonNode input) {
        return input == null ? null : input.path("filePath").asText(null);
    }

    @Override
    public boolean isLsp() {
        return true;  // CC LSPTool.ts:131 isLsp: true
    }

    @Override
    public boolean isEnabled() {
        // A3 状态机: 无 server ready → 工具不可用
        return lspManager.isLspConnected();
    }

    /**
     * 是否延迟加载（§2.18 shouldDefer）· 对齐 CC {@code LSPTool.ts:136}
     * {@code shouldDefer: true}（静态字面量 true，非 input 函数）。
     *
     * <p>CC 语义：LSP 工具不占首轮 schema，经 ToolSearch 检索到 {@code searchHint}
     * 后才加载（defer_loading）。Java 端直接 {@code return true} 逐字对齐；不引入
     * input 分支逻辑（CC 端是静态 true 而非函数）。
     *
     * <p>生效路径：{@code ToolSearchService.isDeferredTool} 第 7 条默认规则
     * {@code return tool.shouldDefer(input)} → 由 false 转 true，LSP 工具进入
     * defer 加载通道（经 ToolSearch 检索过滤 + SchemaNotSentHint gate3）。
     *
     * @param input 工具输入（CC 端静态 true 不消费 input，此处仅满足接口签名）
     * @return 恒 true = 延迟加载（对齐 CC LSPTool.ts:136 shouldDefer: true）
     */
    @Override
    public boolean shouldDefer(JsonNode input) {
        return true;
    }

    @Override
    public AgentToolResult execute(ToolUseBlock call) {
        // A5 Business Result: 必须返回非 null result, 错误也不抛 (CC call 不抛)
        JsonNode input = call.input();
        String operation = input.path("operation").asText("");
        String filePath = input.path("filePath").asText("");
        int line = input.has("line") ? input.path("line").asInt(0) : 0;
        int character = input.has("character") ? input.path("character").asInt(0) : 0;

        if (operation.isEmpty() || filePath.isEmpty()) {
            return ToolResult.error(call.id(), "operation and filePath are required");
        }
        if (line < 1) {
            return ToolResult.error(call.id(),
                "line must be >= 1 (1-based, as in editor); got " + line);
        }
        if (character < 1) {
            return ToolResult.error(call.id(),
                "character must be >= 1 (1-based, as in editor); got " + character);
        }
        if (!isEnabled()) {
            // A3: 未连接 → 显式错误, 不假装执行。
            // 前缀 "Error: " 使 LlmAgentLoop.isToolErrorData 识别为错误（is_error=true），
            // 对齐全仓错误语义（LspToolPermissionTest.execute_notConnected_errors 断言）。
            return ToolResult.error(call.id(), "Error: LSP not connected: no language server ready");
        }
        var serverOpt = lspManager.getServerForFile(filePath);
        if (serverOpt.isEmpty()) {
            return ToolResult.error(call.id(),
                "No LSP server registered for file extension: " + filePath);
        }

        // A4 Tool Sequence: validateInput → checkPermissions → call(此处省略前两步, 对齐 CC 默认 passthrough)
        // [G13②] getMethodAndParams 对齐 CC LSPTool.ts:427-513：workspaceSymbol → workspace/symbol
        //   {query:''}；incoming/outgoing → textDocument/prepareCallHierarchy；其余 →
        //   textDocument+position。uri 经 pathToFileURL 等价（percent-encode）。
        String method;
        Map<String, Object> params;
        try {
            MethodAndParams mp = getMethodAndParams(operation, filePath, line, character);
            method = mp.method();
            params = mp.params();
        } catch (IllegalArgumentException e) {
            return ToolResult.error(call.id(), "Unknown operation: " + operation);
        }

        try {
            // didOpen + 10MB 上限 · 对齐 CC LSPTool.ts:259-278：大多数 LSP server 要求
            // textDocument/didOpen 后才能执行操作；文件未 open 时读取 + openFile（didOpen）。
            // Q-6 归属裁决：LspManager 已暴露 isFileOpen/openFile（LSPServerManager.ts:270-310），
            // didOpen 补齐归属本工具（file-tools 域），随 IMP-D3 在本任务落地。
            if (!lspManager.isFileOpen(filePath)) {
                long fileSize;
                try {
                    fileSize = Files.size(Path.of(filePath));
                } catch (java.io.IOException e) {
                    // 文件读/stat 失败（ENOENT 等）：fail-loud，不假装执行
                    log.warn("[LspTool] didOpen stat 失败: file={} cause={}", filePath, e.toString());
                    return ToolResult.error(call.id(),
                        "Error: LSP cannot open file " + filePath + ": " + e.getMessage());
                }
                if (fileSize > MAX_LSP_FILE_SIZE_BYTES) {
                    // CC :265-271 超 10MB 返回提示（不 didOpen）。"Error:" 前缀使
                    // isToolErrorData 识别为错误（CC 以 data 返回，Java 以错误结果 fail-loud）。
                    return ToolResult.error(call.id(),
                        "Error: File too large for LSP analysis ("
                            + (long) Math.ceil(fileSize / 1_000_000.0)
                            + "MB exceeds 10MB limit)");
                }
                String fileContent = Files.readString(Path.of(filePath), StandardCharsets.UTF_8);
                lspManager.openFile(filePath, fileContent);
                if (log.isDebugEnabled()) {
                    log.debug("[LspTool] didOpen: file={} bytes={}", filePath, fileContent.length());
                }
            }

            // 两步 call hierarchy 对齐 CC LSPTool.ts:299-321：incomingCalls/outgoingCalls 需先
            // prepareCallHierarchy 拿 CallHierarchyItem，再用该 item 请求实际 calls。
            // 旧实现直接 map 到 callHierarchy/{in,out}goingCalls + textDocument/position（缺 {item}），
            // LSP server 无法解析 → 功能不可用（HIGH R-3）。
            boolean isCallHierarchy = "incomingCalls".equals(operation) || "outgoingCalls".equals(operation);
            if (isCallHierarchy) {
                String prepareMethod = "textDocument/prepareCallHierarchy";
                JsonNode prepareRaw = lspManager.sendRequest(filePath, prepareMethod, params, JsonNode.class);
                if (prepareRaw == null || prepareRaw.isNull() || prepareRaw.size() == 0) {
                    log.warn("[LspTool] call hierarchy prepare 无 item: file={} op={}",
                        filePath, operation);
                    // "Error:" 前缀使 isToolErrorData 识别为错误（LSP 调用失败 → is_error=true）
                    return ToolResult.error(call.id(),
                        "Error: No call hierarchy item found at this position");
                }
                JsonNode item = prepareRaw.isArray() ? prepareRaw.get(0) : prepareRaw;
                // 用 item 构造实际 calls 请求（callHierarchy/incomingCalls 或 outgoingCalls）
                Map<String, Object> chParams = new LinkedHashMap<>();
                chParams.put("item", item);
                params = chParams;
                // 第二步真实传输 · 对齐 CC LSPTool.ts:319-326：callMethod =
                //   incomingCalls→callHierarchy/incomingCalls；outgoingCalls→callHierarchy/outgoingCalls。
                //   注意：此处不能复用 prepare 阶段的 method（=textDocument/prepareCallHierarchy）。
                String callMethod = "incomingCalls".equals(operation)
                    ? "callHierarchy/incomingCalls"
                    : "callHierarchy/outgoingCalls";
                JsonNode raw = lspManager.sendRequest(filePath, callMethod, params, JsonNode.class);
                if (raw == null) {
                    String reason = "[LSP transport returned null] server=" + serverOpt.get().name()
                        + " method=" + method;
                    log.warn("[LspTool] op={} file={} method={} → null response",
                        operation, filePath, method);
                    return ToolResult.error(call.id(), reason);
                }
                // [G13②] 格式化输出（formatters 全族 + resultCount/fileCount · 对齐 CC LSPTool.ts:376-389）
                LspResultFormatter.Formatted formatted = formatResult(operation, raw);
                if (log.isInfoEnabled()) {
                    log.info("[LspTool] op={} file={} method={} → {} bytes formatted={} resultCount={} fileCount={}",
                        operation, filePath, method, raw.toString().length(), formatted.text().length(),
                        formatted.resultCount(), formatted.fileCount());
                }
                return ToolResult.successWithStructuredOutput(call.id(), formatted.text(),
                    outputMap(operation, formatted, filePath));
            }

            // 真实传输链 · 对齐 CC LSPTool.ts:259-281: manager.sendRequest(absolutePath, method, params).
            // LspManager.sendRequest 内部 ensureServerStarted 惰性启动真实子进程 (ProcessLspClient).
            // didOpen 由 EditFileTool/WriteFileTool 写盘链触发 (Java 端无文件读取, 已知差异登记).
            JsonNode raw = lspManager.sendRequest(filePath, method, params, JsonNode.class);
            if (raw == null) {
                // 真实传输: 无 server (sendRequest 返回 null) 或 server 返回空 result, 不假装执行.
                String reason = "[LSP transport returned null] server=" + serverOpt.get().name()
                    + " method=" + method;
                log.warn("[LspTool] op={} file={} method={} → null response",
                    operation, filePath, method);
                return ToolResult.error(call.id(), reason);
            }
            // [G13②] 格式化输出（formatters 全族 + resultCount/fileCount · 对齐 CC LSPTool.ts:376-389）：
            //   CC formatResult → {formatted, resultCount, fileCount} → output {operation, result,
            //   filePath, resultCount, fileCount}。旧实现 raw.toString() 直发 JSON 偏离 CC 已替换。
            LspResultFormatter.Formatted formatted = formatResult(operation, raw);
            if (log.isInfoEnabled()) {
                log.info("[LspTool] op={} file={} method={} → {} bytes formatted={} resultCount={} fileCount={}",
                    operation, filePath, method, raw.toString().length(), formatted.text().length(),
                    formatted.resultCount(), formatted.fileCount());
            }
            return ToolResult.successWithStructuredOutput(call.id(), formatted.text(),
                outputMap(operation, formatted, filePath));
        } catch (Exception e) {
            log.warn("[LspTool] call failed op={} file={}: {}", operation, filePath, e.getMessage());
            return ToolResult.error(call.id(),
                "LSP " + operation + " failed: " + e.getMessage());
        }
    }

    // ──────────────── [S06 接线 · X11] checkPermissions · 读权限检查 ────────────────

    /**
     * [S06 接线 · X11] 读权限检查 · 对齐 CC {@code LSPTool.ts:210-217}：
     * {@code checkPermissions → checkReadPermissionForTool(LSPTool, input, toolPermissionContext)}
     * （filesystem.ts:1030-1193；T06 探查 E-CALL-02）。Java 等价物 =
     * {@link com.nexusai.application.agent.permission.ReadPermissionChecker#check}。
     *
     * <p><b>路径提取</b>：CC 经 {@code tool.getPath(input)} 取路径
     * （LSPTool.ts:152-154 {@code getPath({filePath}) = expandPath(filePath)}）。[G3]
     * Java {@code ReadPermissionChecker.check} 已迁出 extractPath → 直接调用本工具
     * {@link #getPath(JsonNode)}（读取 {@code filePath} 字段），不再需要工具侧字段映射。
     *
     * <p><b>数据流纯净</b>：checker 决策的 updatedInput 还原为原 input——CC
     * checkReadPermissionForTool 决策不携带 updatedInput（displayInput = ctx.input），
     * 避免 path 适配副本泄漏到弹窗展示 / hook updatedInput 全替换。
     *
     * <p><b>fail-loud</b>：permissionChecker 未注入 = 装配 bug → ISE（对齐
     * ReadFileTool:408-416 模式，Pattern #11，不再静默放行）。
     *
     * @param input LLM 给的参数（含 {@code filePath}）
     * @param ctx   工具调用上下文（含 permissionContext；管线调用恒非 null）
     * @return      读权限决策（Allow / Ask / Deny）
     */
    @Override
    public PermissionResult checkPermissions(JsonNode input, ToolUseContext ctx) {
        if (permissionChecker == null) {
            throw new IllegalStateException(
                "permissionChecker 未注入, 无法执行 lsp 读权限检查");
        }
        if (log.isDebugEnabled()) {
            log.debug("[LspTool] checkPermissions 入口: filePath={}",
                input == null ? null : input.path("filePath").asText(null));
        }
        // [G3] checker 经 tool.getPath(input) 读 filePath 字段（LSPTool.ts:152-154）
        PermissionResult result = permissionChecker.check(this, input, ctx);
        if (result instanceof PermissionResult.Allow) {
            if (log.isDebugEnabled()) {
                log.debug("[LspTool] 读权限放行: filePath={}",
                    input == null ? null : input.path("filePath").asText(null));
            }
        } else {
            // 关键分支：Ask/Deny（未放行）→ info
            if (log.isInfoEnabled()) {
                log.info("[LspTool] 读权限未放行: decision={} filePath={}",
                    result.getClass().getSimpleName(),
                    input == null ? null : input.path("filePath").asText(null));
            }
        }
        return restoreUpdatedInput(result, input);
    }

    /**
     * updatedInput 还原为原 input（CC 决策不携带 updatedInput；见
     * {@link #checkPermissions} 数据流纯净说明）。
     */
    private static PermissionResult restoreUpdatedInput(
            PermissionResult result, JsonNode originalInput) {
        if (originalInput == null) {
            return result;
        }
        if (result instanceof PermissionResult.Allow allow) {
            return new PermissionResult.Allow(
                originalInput, allow.reason(), allow.toolUseID(),
                allow.userModified(), allow.acceptFeedback(), allow.contentBlocks());
        }
        if (result instanceof PermissionResult.Ask ask) {
            return new PermissionResult.Ask(
                ask.message(), ask.reason(), ask.suggestions(),
                ask.blockedPath(), originalInput, ask.metadata(),
                ask.isBashSecurityCheckForMisparsing(),
                ask.pendingClassifierCheck(), ask.contentBlocks());
        }
        return result;
    }

    // ──────────────── 内部辅助 ────────────────

    /** method + params 二元组 · CC getMethodAndParams 返回值。 */
    private record MethodAndParams(String method, Map<String, Object> params) {}

    /**
     * [G13②] operation → method + params · 对齐 CC {@code LSPTool.ts:427-513 getMethodAndParams}：
     * <ul>
     *   <li>workspaceSymbol → {@code workspace/symbol} 仅 {@code {query: ''}}（空 query 返回全部符号，
     *       CC :471-477）—— 旧实现恒发 textDocument+position 导致 workspaceSymbol 无 query 参数</li>
     *   <li>findReferences → 附加 {@code context: {includeDeclaration: true}}（CC :447-455）</li>
     *   <li>incomingCalls/outgoingCalls → {@code textDocument/prepareCallHierarchy}（两步取 item，
     *       CC :494-511）</li>
     *   <li>其余 → {@code textDocument/... + position}（CC 0-based：line-1/character-1）</li>
     * </ul>
     * uri 经 {@link #pathToFileUri}（pathToFileURL 等价，percent-encode）—— 旧实现
     * {@code "file://" + path} 不做百分号编码，路径含空格/中文时 LSP server 无法解析。
     */
    private MethodAndParams getMethodAndParams(String operation, String filePath, int line, int character) {
        String uri = pathToFileUri(filePath);
        // LSP 是 0-indexed, 输入是 1-indexed（CC LSPTool.ts:81-83/432-435 描述：line-1/character-1）
        int zeroLine = line - 1;
        int zeroCharacter = character - 1;
        Map<String, Object> textDoc = new LinkedHashMap<>();
        textDoc.put("uri", uri);
        switch (operation) {
            case "goToDefinition":
                return new MethodAndParams("textDocument/definition", textDocPosition(textDoc, zeroLine, zeroCharacter));
            case "findReferences": {
                Map<String, Object> params = textDocPosition(textDoc, zeroLine, zeroCharacter);
                params.put("context", Map.of("includeDeclaration", true));
                return new MethodAndParams("textDocument/references", params);
            }
            case "hover":
                return new MethodAndParams("textDocument/hover", textDocPosition(textDoc, zeroLine, zeroCharacter));
            case "documentSymbol":
                return new MethodAndParams("textDocument/documentSymbol", Map.of("textDocument", textDoc));
            case "workspaceSymbol":
                // CC :471-477 — workspace/symbol 仅 query 参数（空 query 返回全部符号）
                return new MethodAndParams("workspace/symbol", Map.of("query", ""));
            case "goToImplementation":
                return new MethodAndParams("textDocument/implementation", textDocPosition(textDoc, zeroLine, zeroCharacter));
            case "prepareCallHierarchy":
                return new MethodAndParams("textDocument/prepareCallHierarchy", textDocPosition(textDoc, zeroLine, zeroCharacter));
            case "incomingCalls":
            case "outgoingCalls":
                // 两步 call hierarchy：先用 prepareCallHierarchy 拿 item，再发 calls 请求（CC :494-511）
                return new MethodAndParams("textDocument/prepareCallHierarchy", textDocPosition(textDoc, zeroLine, zeroCharacter));
            default:
                throw new IllegalArgumentException("Unknown operation: " + operation);
        }
    }

    /** textDocument + position（0-based）params 构造。 */
    private static Map<String, Object> textDocPosition(Map<String, Object> textDoc, int line, int character) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("textDocument", textDoc);
        params.put("position", Map.of("line", line, "character", character));
        return params;
    }

    /**
     * file path → file:// URI（percent-encode）· 对齐 CC {@code pathToFileURL(absolutePath).href}
     * （LSPTool.ts:431）。Java {@code Path.toUri()} 产出 file:///C:/...（Windows）与
     * pathToFileURL.href 同构（含百分号编码）。
     */
    private static String pathToFileUri(String path) {
        return java.nio.file.Path.of(path).toAbsolutePath().normalize().toUri().toString();
    }

    /** [G13②] 格式化 + 计数 · 委托 {@link LspResultFormatter#format}（CC LSPTool.ts:636-829 formatResult）。 */
    private LspResultFormatter.Formatted formatResult(String operation, JsonNode raw) {
        java.nio.file.Path cwd = java.nio.file.Path.of("").toAbsolutePath().normalize();
        return LspResultFormatter.format(operation, raw, cwd);
    }

    /** [G13②] 结构化输出 · 对齐 CC LSPTool.ts outputSchema（:89-121）5 字段。 */
    private Map<String, Object> outputMap(String operation, LspResultFormatter.Formatted formatted, String filePath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation", operation);
        out.put("result", formatted.text());
        out.put("filePath", filePath);
        out.put("resultCount", formatted.resultCount());
        out.put("fileCount", formatted.fileCount());
        return out;
    }

    /**
     * [G13②] tool_result 块 · 读 structuredOutput "result"（formatted 文本）渲染
     * （CC LSPTool.ts:415-421 mapToolResultToToolResultBlockParam：content = output.result）。
     */
    @Override
    public com.nexusai.application.agent.tool.ToolResultBlockParam mapToToolResultBlockParam(
            AgentToolResult<?> result, String toolUseId, boolean isError) {
        if (isError) {
            return Tool.super.mapToToolResultBlockParam(result, toolUseId, isError);
        }
        if (!(result instanceof ToolResult<?> tr)) {
            return null;
        }
        Map<String, Object> so = ToolResult.presentationMeta(tr);
        String content = so.get("result") instanceof String s ? s : "";
        if (log.isDebugEnabled()) {
            log.debug("[LspTool].mapToToolResultBlockParam: id={} contentLen={}（CC LSPTool.ts:415-421）",
                toolUseId, content.length());
        }
        return new com.nexusai.application.agent.tool.ToolResultBlockParam(toolUseId, "tool_result", content, isError);
    }
}