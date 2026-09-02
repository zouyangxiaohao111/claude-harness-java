package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.hook.PermissionBehavior;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [Session H3] Hook JSON 输出解析器 · 对齐 CC 真源
 * {@code Open-ClaudeCode/src/utils/hooks.ts:382-737} validateHookJson / parseHookOutput /
 * parseHttpHookOutput / processHookJSONOutput (grep 自验 2026-08-01).
 *
 * <p><b>WHY (H2-GAP-2)</b>: H2 期间 {@link CommandHookExecutor#parseStdoutJson} 只实现 4 字段
 * 最小子集 (continue/decision/stopReason/systemMessage), 15 子类型 hookSpecificOutput
 * (updatedInput / additionalContext / watchPaths / retry / permissionRequestResult /
 * elicitationResponse 等) 全部静默丢失. 本类对齐 CC processHookJSONOutput (hooks.ts:489-737)
 * 完整映射到 {@link GenericHook.HookResult} + awaiting 字段载体 {@link ParsedHookJSONOutput}.
 *
 * <p><b>流程等价 CC</b>:
 * <ol>
 *   <li>{@link #parseHookOutput(String)} (hooks.ts:399-451): trim → 不以 {@code {} 开头 → plainText;
 *       否则 validateHookJson → 成功 json / 失败 plainText+validationError / catch → plainText</li>
 *   <li>{@link #validateHookJson(String)} (hooks.ts:382-397): 手动 deserialize 成
 *       {@link HookJSONOutput} (sync ∪ async union)</li>
 *   <li>{@link #parseHttpHookOutput(String)} (hooks.ts:453-487): 空 body → {}; 非 {@code {} 开头 →
 *       validationError; 否则 validateHookJson</li>
 *   <li>{@link #processHookJSONOutput(SyncHookOutput, String, String, String)} (hooks.ts:489-737):
 *       continue/decision/systemMessage/hookSpecificOutput 15 子类型 → HookResult 字段</li>
 * </ol>
 *
 * <p><b>message attachment 不实现</b> (Concern H3-1): CC createAttachmentMessage (hooks.ts:710-736)
 * 生成 hook_success / hook_blocking_error attachment, Java 端留 message=null (主 agent 已知悉).
 *
 * <p><b>日志</b>: slf4j + 中文, debug 用 {@code if (log.isDebugEnabled())} 包裹.
 *
 * @since Session H3
 */
public final class HookOutputParser {

    private static final Logger log = LoggerFactory.getLogger(HookOutputParser.class);

    /** 私有 ObjectMapper · 对齐 CommandHookExecutor.parseStdoutJson 既有 readTree 风格. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HookOutputParser() {
    }

    /**
     * 解析结果载体 · 等价 CC parseHookOutput 返回 {@code {json, plainText, validationError}}
     * (hooks.ts:399-403).
     *
     * @param json            成功解析的 {@link HookJSONOutput}; 失败/非 JSON → null
     * @param plainText       stdout 降级为纯文本时携带原文; JSON 成功 → null
     * @param validationError schema 校验失败消息; 成功 → null
     */
    public record ParseResult(HookJSONOutput json, String plainText, String validationError) {
    }

    /**
     * processHookJSONOutput 映射结果 · 等价 CC Partial&lt;HookResult&gt; + awaiting 字段
     * (hooks.ts:512-708). 核心 13+1 字段进 {@link #result}, awaiting 5 字段进各自 accessor
     * (不塞进 HookResult — 那是后续 P1 范围, 本类作为载体透出).
     *
     * @param result                  核心映射结果 (HookResult, 含 hook 字段但由调用方 withHook 填充)
     * @param initialUserMessage      SessionStart hook 重注入 user message (hooks.ts:629)
     * @param permissionRequestResult PermissionRequest hook decision (hooks.ts:659-660)
     * @param elicitationResponse     Elicitation hook 响应 (hooks.ts:676-681)
     * @param elicitationResultResponse ElicitationResult hook 响应 (hooks.ts:692-697)
     * @param watchPaths              SessionStart/CwdChanged/FileChanged 监听路径 (hooks.ts:630-635)
     */
    public record ParsedHookJSONOutput(
        GenericHook.HookResult result,
        String initialUserMessage,
        PermissionRequestResult permissionRequestResult,
        ElicitationResponse elicitationResponse,
        ElicitationResponse elicitationResultResponse,
        List<String> watchPaths
    ) {
    }

    /**
     * 校验 hook JSON 字符串 · 对齐 CC validateHookJson (hooks.ts:382-397).
     *
     * <p><b>[H-WF2-02 0-5 宽松降级] 解析失败与校验失败分离</b>: CC validateHookJson 先
     * {@code jsonParse(jsonString)} (hooks.ts:385) — 非法 JSON (如 {@code {oops}) 直接 throw
     * (JSON.parse 语义), 由调用方 catch 决定降级方向 (parseHookOutput → 静默纯文本, parseHttpHookOutput
     * → validationError); 只有 schema 校验失败才返回 validationError (hooks.ts:386-395 safeParse).
     * 旧实现把 readTree 失败也 catch 成 validationError, 导致 parseHookOutput 对 {@code {oops}
     * 产出 NON_BLOCKING_ERROR (CC 为静默纯文本 hook_success) — 本轮对齐 CC 修正.
     *
     * <p>WHY: Zod safeParse 等价物 — 手动 deserialize 成 {@link HookJSONOutput} (sync ∪ async),
     * schema 失败 → validationError. JSON 必须是对象; 非对象 / 反序列化异常 → validationError.
     *
     * @param jsonString hook stdout 或 HTTP body 的 JSON 文本
     * @return 成功 → json; schema 校验失败 → validationError (plainText 恒 null)
     * @throws IllegalArgumentException 非法 JSON (JSON.parse 等价失败, 调用方按 CC 分支 catch 降级)
     */
    public static ParseResult validateHookJson(String jsonString) {
        // JSON.parse 等价 (hooks.ts:385): readTree 失败 → throw 传播, 调用方 catch 决定降级方向.
        JsonNode node;
        try {
            node = MAPPER.readTree(jsonString);
        } catch (Exception e) {
            throw new IllegalArgumentException("Hook JSON parse failed: " + e.getMessage(), e);
        }
        try {
            HookJSONOutput json = deserializeHookJSONOutput(node);
            if (log.isDebugEnabled()) {
                log.debug("成功解析并校验 hook JSON 输出");
            }
            return new ParseResult(json, null, null);
        } catch (Exception e) {
            String validationError = "Hook JSON output validation failed: " + e.getMessage()
                + "\n\nThe hook's output was: " + jsonString;
            if (log.isDebugEnabled()) {
                log.debug("Hook JSON 校验失败: {}", e.getMessage());
            }
            return new ParseResult(null, null, validationError);
        }
    }

    /**
     * 解析 hook stdout · 对齐 CC parseHookOutput (hooks.ts:399-451).
     *
     * <p>WHY: stdout 可能不是 JSON (hook 打印普通文本), 不以 {@code {} 开头 → plainText
     * (hooks.ts:404-408). 以 {@code {} 开头但校验失败 → plainText + validationError; 解析
     * 抛异常 → plainText (hooks.ts:447-450). [S4 G17] 调用方 (toHookResultCore) 按
     * validationError → hook_non_blocking_error (`JSON validation failed:`) / json →
     * processHookJSONOutput / 纯文本 → status 分流 — 无 json 不再降级 proceed.
     *
     * @param stdout hook 的 stdout 全量 (可 null)
     * @return 等价 CC {json?, plainText?, validationError?}
     */
    public static ParseResult parseHookOutput(String stdout) {
        String trimmed = stdout != null ? stdout.trim() : "";
        if (!trimmed.startsWith("{")) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK 输出不以 { 开头, 视为纯文本");
            }
            return new ParseResult(null, stdout, null);
        }
        try {
            ParseResult validated = validateHookJson(trimmed);
            if (validated.json() != null) {
                return validated;
            }
            // schema 校验失败 → plainText + validationError (hooks.ts:445-446)
            return new ParseResult(null, stdout, validated.validationError());
        } catch (Exception e) {
            // JSON 解析抛异常 → plainText (hooks.ts:447-450)
            if (log.isDebugEnabled()) {
                log.debug("HOOK 输出 JSON 解析失败: {}", e.getMessage());
            }
            return new ParseResult(null, stdout, null);
        }
    }

    /**
     * 解析 HTTP hook body · 对齐 CC parseHttpHookOutput (hooks.ts:453-487).
     *
     * <p>WHY: HTTP hook 契约是"必须返回 JSON", 与 stdout (可纯文本) 不同. 空 body → 空 JSON
     * 对象 {} (hooks.ts:459-467); 非 {@code {} 开头 → validationError (hooks.ts:469-473);
     * 解析失败 → validationError (hooks.ts:482-486).
     *
     * @param body HTTP hook 响应 body (可 null)
     * @return 等价 CC {json?, validationError?} (plainText 恒 null)
     */
    public static ParseResult parseHttpHookOutput(String body) {
        String trimmed = body != null ? body.trim() : "";
        if (trimmed.isEmpty()) {
            // 空 body → 视为空 JSON 对象 {} (hooks.ts:459-467)
            return validateHookJson("{}");
        }
        if (!trimmed.startsWith("{")) {
            String shown = trimmed.length() > 200 ? trimmed.substring(0, 200) + "\u2026" : trimmed;
            String validationError = "HTTP hook must return JSON, but got non-JSON response body: " + shown;
            if (log.isDebugEnabled()) {
                log.debug(validationError);
            }
            return new ParseResult(null, null, validationError);
        }
        try {
            return validateHookJson(trimmed);
        } catch (Exception e) {
            String validationError = "HTTP hook must return valid JSON, but parsing failed: " + e;
            if (log.isDebugEnabled()) {
                log.debug(validationError);
            }
            return new ParseResult(null, null, validationError);
        }
    }

    /**
     * 映射 sync hook JSON 到 HookResult · 对齐 CC processHookJSONOutput (hooks.ts:489-737).
     *
     * <p><b>映射顺序 (hooks.ts:517-708)</b>:
     * <ol>
     *   <li>{@code continue === false} → preventContinuation=true + stopReason (hooks.ts:518-523)</li>
     *   <li>顶层 {@code decision}: 'approve'→ALLOW / 'block'→DENY+blockingError / 其他 → throw
     *       (hooks.ts:525-543)</li>
     *   <li>{@code systemMessage} → systemMessage (hooks.ts:546-547)</li>
     *   <li>PreToolUse.permissionDecision: allow→ALLOW / deny→DENY+blockingError / ask→ASK
     *       (hooks.ts:551-575)</li>
     *   <li>permissionBehavior 非空且 reason 非空 → hookPermissionDecisionReason (hooks.ts:576-578)</li>
     *   <li>hookSpecificOutput 15 子类型 switch (hooks.ts:592-707)</li>
     * </ol>
     *
     * <p><b>expectedHookEvent 校验</b> (hooks.ts:583-590): 非空且不匹配 → throw.
     *
     * <p><b>outcome 语义</b>: CC runHook 在 status 0 JSON 路径恒 yield {@code outcome:'success'}
     * (hooks.ts:2592 / :2610), 故本方法 outcome 恒 SUCCESS (阻断语义由 preventContinuation /
     * permissionBehavior / blockingError 承载).
     *
     * <p><b>[H3 v2 修复] message attachment</b> (Gap 1, H3-GAP-1): CC processHookJSONOutput
     * 末尾 (hooks.ts:710-736) 按 blockingError 有无生成 {@code hook_blocking_error} /
     * {@code hook_success} attachment (createAttachmentMessage 等价). Java 端 message 此前恒 null,
     * hook 成功/阻塞的系统提醒不会注入 LLM. 修复后 message 携带 {@link AttachmentMessageDto}
     * (hook_blocking_error 含 blockingError 文本 / hook_success content:'' 抑制 trivial reminder,
     * 对齐 CC hooks.ts:716-736 content:'' 注释).
     *
     * @param json             sync hook 输出 (async 分支由调用方跳过)
     * @param hookCommand      CC original: command (hooks.ts:503); blockingError.command 字段
     * @param hookName         CC original: hookName (hooks.ts:504); message attachment.hookName
     * @param expectedHookEvent CC original: expectedHookEvent (hooks.ts:506); 非空才校验
     * @param toolUseID        CC original: toolUseID (hooks.ts:505); message attachment.toolUseID
     * @param hookEvent        CC original: hookEvent (hooks.ts:505); message attachment.hookEvent
     * @return 映射后的 ParsedHookJSONOutput
     * @throws IllegalArgumentException 非法 decision / permissionDecision / expectedHookEvent 不匹配
     */
    /**
     * [H3 v2] 4 参便捷重载 · 委托 10 参 (toolUseID/hookEvent/stdout/stderr/exitCode/durationMs=null).
     *
     * <p>WHY: 既有调用方 (AsyncHookRegistry 等) 无 toolUseID/hookEvent 上下文, 用 null 生成
     * attachment 仍可携带 hookName + blockingError, 保证 Gap1 message 修复对所有路径生效.
     */
    public static ParsedHookJSONOutput processHookJSONOutput(HookJSONOutput.SyncHookOutput json,
                                                             String hookCommand,
                                                             String hookName, String expectedHookEvent) {
        return processHookJSONOutput(json, hookCommand, hookName, expectedHookEvent, null, null);
    }

    /**
     * [H3 v2] 6 参便捷重载 · 委托 10 参 (stdout/stderr/exitCode/durationMs=null).
     */
    public static ParsedHookJSONOutput processHookJSONOutput(HookJSONOutput.SyncHookOutput json,
                                                             String hookCommand,
                                                             String hookName, String expectedHookEvent,
                                                             String toolUseID, String hookEvent) {
        return processHookJSONOutput(json, hookCommand, hookName, expectedHookEvent,
            toolUseID, hookEvent, null, null, null, null);
    }

    /**
     * [H3 v3 修复] 10 参全量版本 · 补 stdout/stderr/exitCode/durationMs 载荷透传.
     *
     * <p>WHY (Gap 3): CC processHookJSONOutput (hooks.ts:489-510) 接收 stdout/stderr/exitCode/
     * durationMs 并把它们注入 message attachment (hook_success / hook_blocking_error 的
     * stdout/stderr/exitCode/command/durationMs 载荷, hooks.ts:710-736). Java 端此前 6 参版本
     * 丢弃这些字段 → attachment 载荷薄于 CC (对抗核验 Gap 3).
     */
    public static ParsedHookJSONOutput processHookJSONOutput(HookJSONOutput.SyncHookOutput json,
                                                             String hookCommand,
                                                             String hookName, String expectedHookEvent,
                                                             String toolUseID, String hookEvent,
                                                             String stdout, String stderr,
                                                             Integer exitCode, Long durationMs) {
        boolean preventContinuation = false;
        HookBlockingError blockingError = null;
        String systemMessage = null;
        String additionalContext = null;
        Object message = null; // [H3 v2] 末尾按 blockingError 有无生成 attachment
        Map<String, Object> updatedInput = null;
        Object updatedMCPToolOutput = null;
        Boolean retry = null;
        String hookPermissionDecisionReason = null;
        String stopReason = null;
        PermissionBehavior permissionBehavior = null;

        // awaiting 5 字段 → ParsedHookJSONOutput 载体 (不进 HookResult)
        String initialUserMessage = null;
        PermissionRequestResult permissionRequestResult = null;
        ElicitationResponse elicitationResponse = null;
        ElicitationResponse elicitationResultResponse = null;
        List<String> watchPaths = null;

        // 1. continue === false (hooks.ts:518-523)
        if (Boolean.FALSE.equals(json.continueExecution())) {
            preventContinuation = true;
            if (json.stopReason() != null) {
                stopReason = json.stopReason();
            }
        }

        // 2. 顶层 decision (hooks.ts:525-543)
        if (json.decision() != null) {
            switch (json.decision()) {
                case "approve":
                    permissionBehavior = PermissionBehavior.ALLOW;
                    break;
                case "block":
                    permissionBehavior = PermissionBehavior.DENY;
                    blockingError = new HookBlockingError(
                        json.reason() != null ? json.reason() : "Blocked by hook", hookCommand);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown hook decision type: " + json.decision()
                        + ". Valid types are: approve, block");
            }
        }

        // 3. systemMessage (hooks.ts:546-547)
        if (json.systemMessage() != null) {
            systemMessage = json.systemMessage();
        }

        // 4. PreToolUse.permissionDecision 顶层快捷分支 (hooks.ts:551-575)
        HookSpecificOutput hso = json.hookSpecificOutput();
        if (hso instanceof HookSpecificOutput.PreToolUse pre && pre.permissionDecision() != null) {
            switch (pre.permissionDecision()) {
                case "allow":
                    permissionBehavior = PermissionBehavior.ALLOW;
                    break;
                case "deny":
                    permissionBehavior = PermissionBehavior.DENY;
                    blockingError = new HookBlockingError(
                        json.reason() != null ? json.reason() : "Blocked by hook", hookCommand);
                    break;
                case "ask":
                    permissionBehavior = PermissionBehavior.ASK;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown hook permissionDecision type: "
                        + pre.permissionDecision() + ". Valid types are: allow, deny, ask");
            }
        }

        // 5. permissionBehavior 非空且 reason 非空 → hookPermissionDecisionReason (hooks.ts:576-578)
        if (permissionBehavior != null && json.reason() != null) {
            hookPermissionDecisionReason = json.reason();
        }

        // 6. hookSpecificOutput switch (hooks.ts:581-708)
        if (hso != null) {
            // expectedHookEvent 校验 (hooks.ts:583-590)
            if (expectedHookEvent != null && !expectedHookEvent.isEmpty()
                && !expectedHookEvent.equals(hso.hookEventName())) {
                throw new IllegalArgumentException("Hook returned incorrect event name: expected '"
                    + expectedHookEvent + "' but got '" + hso.hookEventName() + "'");
            }

            switch (hso) {
                case HookSpecificOutput.PreToolUse pre -> {
                    // permissionDecision 更精细覆盖 (hooks.ts:594-614)
                    if (pre.permissionDecision() != null) {
                        switch (pre.permissionDecision()) {
                            case "allow" -> permissionBehavior = PermissionBehavior.ALLOW;
                            case "deny" -> {
                                permissionBehavior = PermissionBehavior.DENY;
                                blockingError = new HookBlockingError(
                                    pre.permissionDecisionReason() != null ? pre.permissionDecisionReason()
                                        : (json.reason() != null ? json.reason() : "Blocked by hook"),
                                    hookCommand);
                            }
                            case "ask" -> permissionBehavior = PermissionBehavior.ASK;
                        }
                    }
                    hookPermissionDecisionReason = pre.permissionDecisionReason();
                    if (pre.updatedInput() != null) {
                        updatedInput = pre.updatedInput();
                    }
                    additionalContext = pre.additionalContext();
                }
                case HookSpecificOutput.UserPromptSubmit up ->
                    additionalContext = up.additionalContext();
                case HookSpecificOutput.SessionStart ss -> {
                    additionalContext = ss.additionalContext();
                    initialUserMessage = ss.initialUserMessage();
                    if (ss.watchPaths() != null) {
                        watchPaths = ss.watchPaths();
                    }
                }
                case HookSpecificOutput.Setup setup -> additionalContext = setup.additionalContext();
                case HookSpecificOutput.SubagentStart sa -> additionalContext = sa.additionalContext();
                case HookSpecificOutput.PostToolUse post -> {
                    additionalContext = post.additionalContext();
                    if (post.updatedMCPToolOutput() != null) {
                        updatedMCPToolOutput = post.updatedMCPToolOutput();
                    }
                }
                case HookSpecificOutput.PostToolUseFailure pf -> additionalContext = pf.additionalContext();
                case HookSpecificOutput.PermissionDenied pd -> retry = pd.retry();
                case HookSpecificOutput.Notification ignored ->
                    // CC 无 case = no-op (types/hooks.ts:117-119)
                    { }
                case HookSpecificOutput.PermissionRequest pr -> {
                    // decision union → permissionRequestResult + permissionBehavior (hooks.ts:657-672)
                    if (pr.decision() != null) {
                        permissionRequestResult = pr.decision();
                        permissionBehavior = pr.decision() instanceof PermissionRequestResult.Allow
                            ? PermissionBehavior.ALLOW : PermissionBehavior.DENY;
                        if (pr.decision() instanceof PermissionRequestResult.Allow allow
                            && allow.updatedInput() != null) {
                            updatedInput = allow.updatedInput();
                        }
                    }
                }
                case HookSpecificOutput.Elicitation el -> {
                    if (el.action() != null) {
                        // [H3 v2 修复] Gap 4 (registered_gap:false): CC types/hooks.ts:138 content 是
                        //   z.record(z.string(), z.unknown()).optional(), hook 只返 action 时 content=undefined.
                        //   Map.of 遇 null value 抛 NPE → 调用方 catch 后静默降级 proceed, elicitation 响应丢失.
                        //   LinkedHashMap 允许 null content, 保留 action 关键响应.
                        // [I-2] 升级 ElicitationResponse record · content 可 null (types/hooks.ts:138 optional)
                        elicitationResponse = new ElicitationResponse(el.action(), el.content());
                        if ("decline".equals(el.action())) {
                            blockingError = new HookBlockingError(
                                json.reason() != null ? json.reason() : "Elicitation denied by hook",
                                hookCommand);
                        }
                    }
                }
                case HookSpecificOutput.ElicitationResult er -> {
                    if (er.action() != null) {
                        // [H3 v2 修复] Gap 4: 同 Elicitation, content optional (types/hooks.ts:143).
                        // [I-2] 升级 ElicitationResponse record · content 可 null (types/hooks.ts:143 optional)
                        elicitationResultResponse = new ElicitationResponse(er.action(), er.content());
                        if ("decline".equals(er.action())) {
                            blockingError = new HookBlockingError(
                                json.reason() != null ? json.reason() : "Elicitation result blocked by hook",
                                hookCommand);
                        }
                    }
                }
                case HookSpecificOutput.CwdChanged ignored ->
                    // [IMP-DC-01] CC processHookJSONOutput switch 无此 case (hooks.ts:592-707);
                    //   watchPaths 改由 toHookResultCore 泛型提取 (executeHooksOutsideREPL:3340-3346
                    //   等价, 事件无关 'watchPaths' in hookSpecificOutput)
                    { }
                case HookSpecificOutput.FileChanged ignored ->
                    // [IMP-DC-01] 同 CwdChanged — CC 无此 case (types/hooks.ts:153-158),
                    //   watchPaths 由 toHookResultCore 泛型提取承担
                    { }
                case HookSpecificOutput.WorktreeCreate ignored ->
                    // CC 无 case = no-op (types/hooks.ts:159-162 worktreePath 不映射 HookResult)
                    { }
            }
        }

        // [H3 v2 修复] Gap 1 (H3-GAP-1): CC hooks.ts:710-736 按 blockingError 有无生成
        //   hook_blocking_error / hook_success attachment (createAttachmentMessage 等价).
        //   blockingError != null → hook_blocking_error (含 blockingError 文本, 注入 LLM 反馈);
        //   否则 → hook_success (content:'' 抑制 trivial reminder, 对齐 hooks.ts:718-726 注释).
        // [H3 v3 修复] Gap 3: 载荷对齐 CC — hook_success 带 stdout/stderr/exitCode/command/durationMs
        //   (utils/attachments.ts:411-418), hook_blocking_error 带 command (blockingError.command).
        if (blockingError != null) {
            message = AttachmentMessageDto.hookBlockingError(
                hookName, toolUseID, hookEvent, blockingError.blockingError(), blockingError.command());
        } else {
            message = AttachmentMessageDto.hookSuccess(
                hookName, toolUseID, hookEvent, "", stdout, stderr, exitCode, hookCommand, durationMs);
        }

        // [Session S07] permissionRequestResult 顶层回填 · 对齐 CC hooks.ts:2882-2886
        //   (`if (result.permissionRequestResult) yield { permissionRequestResult }`) —
        //   PermissionRequest hook 决策从此在 HookResult 顶层承载, parseStdoutJson /
        //   HookRegistry 折叠链不再丢该字段 (旧实现只存 ParsedHookJSONOutput, 消费链断开).
        // [H-WF5a-02] systemMessage/additionalContext 单值 String → List 承载 (折叠链项2/3) ·
        //   CC 逐结果 yield 单值, Java HookResult 折叠层改 List 全保留 (null 保持 null,
        //   List.of(null) 抛 NPE → null 检查).
        java.util.List<String> systemMessages = systemMessage != null ? List.of(systemMessage) : null;
        java.util.List<String> additionalContexts = additionalContext != null ? List.of(additionalContext) : null;
        GenericHook.HookResult result = new GenericHook.HookResult(preventContinuation, blockingError, systemMessages, additionalContexts, message,
            updatedInput, updatedMCPToolOutput, retry, hookPermissionDecisionReason,
            GenericHook.HookOutcome.SUCCESS, stopReason, permissionBehavior,
            permissionRequestResult, null,
            // [2026-08-12 △-01] awaiting 4 字段回填 HookResult 顶层 · 对齐 CC HookResult 18 字段
            //   (utils/hooks.ts:338-357): 旧实现只存 ParsedHookJSONOutput, executeEvent 折叠链
            //   上静默丢失. 现在直接构造进 result, 消费方 (ElicitationHandler/SessionStart) 可取.
            initialUserMessage, watchPaths, elicitationResponse, elicitationResultResponse);

        if (log.isDebugEnabled()) {
            log.debug("HOOK JSON 处理完成: preventContinuation={} permissionBehavior={} blockingError={}",
                preventContinuation, permissionBehavior, blockingError != null);
        }
        return new ParsedHookJSONOutput(result, initialUserMessage, permissionRequestResult,
            elicitationResponse, elicitationResultResponse, watchPaths);
    }

    /**
     * Elicitation 宽松解析结果 · 等价 CC parseElicitationHookOutput 返回值
     * {@code {response?: ElicitationResponse, blockingError?: HookBlockingError}} (hooks.ts:4390-4394).
     *
     * @param response     结构化 Elicitation/ElicitationResult 决策 (action + content); 无决策 → null
     * @param blockingError 阻断错误 (exit 2 / decision block / decline); 无阻断 → null
     */
    public record ElicitationParseResult(ElicitationResponse response, HookBlockingError blockingError) {
    }

    /**
     * Elicitation/ElicitationResult 专用宽松解析 · 对齐 CC parseElicitationHookOutput
     * {@code Open-ClaudeCode/src/utils/hooks.ts:4388-4468} (grep 自验 2026-08-15).
     *
     * <p><b>WHY (WF6-X4 △-9/△-10/△-11 + 决策 0-5 宽松降级)</b>: CC Elicitation 路径用
     * <b>专用宽松解析器</b> — 空/纯文本输出 → 静默 {@code {}}、事件名不匹配 → 静默 {@code {}}、
     * JSON 解析/schema 失败 → catch {@code {}}，且 outside-REPL 命令 hook 不产 message attachment.
     * Java 旧实现复用通用严格解析链 ({@link CommandHookExecutor#toHookResult} → validationError
     * → NON_BLOCKING_ERROR / expectedHookEvent 校验抛错 / 纯文本 → hook_success attachment)，
     * 三处可观察差异:
     * <ul>
     *   <li>△-9 (hooks.ts:4405-4413): 空/纯文本 → CC 静默无 attachment vs Java hook_success attachment</li>
     *   <li>△-10 (hooks.ts:4435-4437): 事件名错配 → CC 静默 vs Java fail-loud IllegalArgumentException</li>
     *   <li>△-11 (hooks.ts:4465-4467): 解析失败 → CC 静默 {} vs Java NON_BLOCKING_ERROR</li>
     * </ul>
     *
     * <p><b>CC 逐分支语义 (hooks.ts:4400-4468)</b>:
     * <ol>
     *   <li>{@code blocked && !succeeded} (exit 2) → blockingError {output || 'Elicitation blocked by hook', command}</li>
     *   <li>空输出 → 静默 {}</li>
     *   <li>非 {@code {} 开头 → 静默 {}</li>
     *   <li>try: JSON.parse + schema (hookJSONOutputSchema().parse 失败即 throw) → 静默 {}；
     *       async / 非 sync → 静默 {}</li>
     *   <li>{@code parsed.decision === 'block' || result.blocked} → blockingError {reason || 'Elicitation blocked by hook', command}</li>
     *   <li>无 hookSpecificOutput 或 hookEventName ≠ expectedEventName → 静默 {}</li>
     *   <li>无 action → 静默 {}</li>
     *   <li>response = {action, content}; action==='decline' → 追加 blockingError
     *       {reason || ('Elicitation'? 'Elicitation denied by hook' : 'Elicitation result blocked by hook'), command}</li>
     *   <li>catch → 静默 {}</li>
     * </ol>
     *
     * <p><b>调用方</b>: 本方法为 Elicitation 分发路径专用宽松分支。生产接线在
     * {@code HookRegistry.executeConfiguredCommand} (共享文件, 见 H-WF2-patch-note.md) —
     * Elicitation/ElicitationResult 事件绕过通用 {@link CommandHookExecutor#toHookResult}，
     * 用本方法解析后映射进 {@link GenericHook.HookResult} 顶层 elicitationResponse /
     * elicitationResultResponse / blockingError (ElicitationHandler.resolveDecision 消费, 零改动)。
     *
     * @param output            CC original: {@code result.output} — status 0 → stdout, 否则 stderr
     *                          (executeHooksOutsideREPL hooks.ts:3337-3338)
     * @param blocked           CC original: {@code result.blocked} — exit code 2 (hooks.ts:3333)
     * @param succeeded         CC original: {@code result.succeeded} — status === 0 (hooks.ts:3348)
     * @param command           CC original: {@code result.command} — hook 命令
     * @param expectedEventName 'Elicitation' | 'ElicitationResult'
     * @return 决策; 无决策/静默降级 → response=null 且 blockingError=null
     */
    public static ElicitationParseResult parseElicitationHookOutput(
        String output, boolean blocked, boolean succeeded, String command, String expectedEventName) {
        // 1. exit 2 = blocking (CC :4400-4405) — blocked && !succeeded → blockingError
        if (blocked && !succeeded) {
            // CC `result.output || 'Elicitation blocked by hook'` (hooks.ts:4402) — 空字符串回退, 原样透传.
            String blockText = (output != null && !output.isEmpty())
                ? output : "Elicitation blocked by hook";
            if (log.isInfoEnabled()) {
                log.info("HOOK Elicitation hook '{}' exit 2 阻断: {}", command, blockText);
            }
            return new ElicitationParseResult(null, new HookBlockingError(blockText, command));
        }
        // 2. 空输出 → 静默 {} (CC :4407-4409)
        if (output == null || output.trim().isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK Elicitation hook '{}' 空输出, 静默无决策 (对齐 CC parseElicitationHookOutput)", command);
            }
            return new ElicitationParseResult(null, null);
        }
        // 3. 非 { 开头纯文本 → 静默 {} (CC :4411-4413)
        String trimmed = output.trim();
        if (!trimmed.startsWith("{")) {
            if (log.isDebugEnabled()) {
                log.debug("HOOK Elicitation hook '{}' 纯文本输出, 静默无决策 (对齐 CC parseElicitationHookOutput)", command);
            }
            return new ElicitationParseResult(null, null);
        }
        try {
            // 4. JSON.parse + schema 校验 — 非法 JSON (validateHookJson throw) / schema 失败
            //    (json()==null) / async / 非 sync 一律静默 {} (CC :4416-4427)
            ParseResult pr = validateHookJson(trimmed);
            if (!(pr.json() instanceof HookJSONOutput.SyncHookOutput sync)) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK Elicitation hook '{}' JSON 非 sync/无效, 静默无决策 (对齐 CC parseElicitationHookOutput)", command);
                }
                return new ElicitationParseResult(null, null);
            }
            // 5. 顶层 decision block 或 blocked → blockingError (CC :4429-4438)
            if ("block".equals(sync.decision()) || blocked) {
                String reason = sync.reason() != null ? sync.reason() : "Elicitation blocked by hook";
                if (log.isInfoEnabled()) {
                    log.info("HOOK Elicitation hook '{}' decision=block/blocked, 阻断: {}", command, reason);
                }
                return new ElicitationParseResult(null, new HookBlockingError(reason, command));
            }
            // 6. 事件名不匹配 → 静默 {} (CC :4440-4443)
            HookSpecificOutput hso = sync.hookSpecificOutput();
            if (hso == null || !expectedEventName.equals(hso.hookEventName())) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK Elicitation hook '{}' 事件名不匹配, 静默无决策 (对齐 CC parseElicitationHookOutput)", command);
                }
                return new ElicitationParseResult(null, null);
            }
            // 7. 无 action → 静默 {} (CC :4445-4447)
            String action = null;
            Map<String, Object> content = null;
            if (hso instanceof HookSpecificOutput.Elicitation el) {
                action = el.action();
                content = el.content();
            } else if (hso instanceof HookSpecificOutput.ElicitationResult er) {
                action = er.action();
                content = er.content();
            }
            if (action == null) {
                if (log.isDebugEnabled()) {
                    log.debug("HOOK Elicitation hook '{}' 无 action, 静默无决策 (对齐 CC parseElicitationHookOutput)", command);
                }
                return new ElicitationParseResult(null, null);
            }
            // 8. response = {action, content}; decline → 追加 blockingError (CC :4449-4463)
            ElicitationResponse response = new ElicitationResponse(action, content);
            if ("decline".equals(action)) {
                String reason = sync.reason() != null ? sync.reason()
                    : ("Elicitation".equals(expectedEventName)
                        ? "Elicitation denied by hook" : "Elicitation result blocked by hook");
                if (log.isInfoEnabled()) {
                    log.info("HOOK Elicitation hook '{}' decline, 阻断: {}", command, reason);
                }
                return new ElicitationParseResult(response, new HookBlockingError(reason, command));
            }
            return new ElicitationParseResult(response, null);
        } catch (Exception e) {
            // CC catch → 静默 {} (hooks.ts:4465-4467)
            if (log.isDebugEnabled()) {
                log.debug("HOOK Elicitation hook '{}' 解析失败, 静默无决策 (对齐 CC parseElicitationHookOutput catch): {}",
                    command, e.getMessage());
            }
            return new ElicitationParseResult(null, null);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 私有反序列化 · 对齐 CommandHookExecutor.parseStdoutJson 既有 readTree→手动取字段 风格
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 手动 deserialize JsonNode → HookJSONOutput · 等价 CC hookJSONOutputSchema.safeParse.
     *
     * <p>async 判别: 顶层 {@code async === true} → {@link AsyncHookOutput} (types/hooks.ts:192);
     * 否则 → {@link SyncHookOutput} 7 顶层字段 + hookSpecificOutput.
     *
     * <p><b>[Session H10] 可见性放宽</b>: private → 包内可见. WHY: {@link AsyncHookRegistry}
     * 逐行解析 async hook stdout (对齐 CC AsyncHookRegistry.ts:192-212) 需要把单行 JSON
     * 转成 SyncHookOutput — 复用本方法可保住 15 子类型 hookSpecificOutput 的完整解析
     * (parseHookSpecificOutput), 复制实现则引入 60+ 行重复 + 两处漂移风险.
     *
     * @param node 已 readTree 的 JSON 节点 (必须是对象)
     * @return sync 或 async 输出
     * @throws IllegalArgumentException 非对象
     */
    static HookJSONOutput deserializeHookJSONOutput(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Hook JSON output must be an object");
        }
        // [S4 G17] async 判别严格化 · 对齐 CC hookJSONOutputSchema (types/hooks.ts:171-176):
        //   async 须 z.literal(true) — 字符串 "true" 不匹配 async 分支, zod union 落到 sync
        //   schema (z.object 默认 strip 未知键 → async 键忽略, 按 sync 处理).
        if (node.has("async") && node.get("async").isBoolean() && node.get("async").asBoolean()) {
            // asyncTimeout: z.number().optional() — 存在但非 number → 拒绝 (types/hooks.ts:173)
            Long asyncTimeout = null;
            if (node.has("asyncTimeout")) {
                if (!node.get("asyncTimeout").isNumber()) {
                    throw new IllegalArgumentException(
                        "asyncTimeout must be a number");
                }
                asyncTimeout = node.get("asyncTimeout").asLong();
            }
            return new HookJSONOutput.AsyncHookOutput(true, asyncTimeout);
        }
        // continue/suppressOutput: z.boolean().optional() — 存在但非 boolean → 拒绝
        //   (types/hooks.ts:52-59; 字符串 "false" 不再容忍, 旧 lenient 路径已删)
        Boolean continueExec = null;
        if (node.has("continue")) {
            if (!node.get("continue").isBoolean()) {
                throw new IllegalArgumentException("continue must be a boolean");
            }
            continueExec = node.get("continue").asBoolean();
        }
        // [IMP-HOOKS-S6 CCJ-T6-05] suppressOutput 解析保留但运行时恒不消费:
        //   CC hooks.ts:2558-2562 门控含 plainText, 但 parseHookOutput (hooks.ts:399-407)
        //   JSON 成功分支恒不返回 plainText → 该门为死代码; Java 无条件 hook_success
        //   附件与 CC 运行时行为一致 (死字段仅承载解析结果, 消费端无读取).
        Boolean suppressOutput = null;
        if (node.has("suppressOutput")) {
            if (!node.get("suppressOutput").isBoolean()) {
                throw new IllegalArgumentException("suppressOutput must be a boolean");
            }
            suppressOutput = node.get("suppressOutput").asBoolean();
        }
        // stopReason/reason/systemMessage: z.string().optional() (types/hooks.ts:60-69)
        String stopReason = textOrThrow(node, "stopReason", "stopReason");
        String decision = textOrThrow(node, "decision", "decision");
        if (decision != null && !"approve".equals(decision) && !"block".equals(decision)) {
            throw new IllegalArgumentException("decision must be one of: approve, block");
        }
        String reason = textOrThrow(node, "reason", "reason");
        String systemMessage = textOrThrow(node, "systemMessage", "systemMessage");
        // hookSpecificOutput: z.union(...).optional() (types/hooks.ts:70-164) — 存在但非对象 → 拒绝
        HookSpecificOutput hso = null;
        if (node.has("hookSpecificOutput")) {
            if (!node.get("hookSpecificOutput").isObject()) {
                throw new IllegalArgumentException("hookSpecificOutput must be an object");
            }
            hso = parseHookSpecificOutput(node.get("hookSpecificOutput"));
        }
        return new HookJSONOutput.SyncHookOutput(
            continueExec,
            suppressOutput,
            stopReason,
            decision,
            reason,
            systemMessage,
            hso);
    }

    /**
     * 宽松反序列化 sync hook 输出 · 对齐 CC {@code AsyncHookRegistry} raw 接受
     * (AsyncHookRegistry.ts:199-205 {@code response = parsed}).
     *
     * <p><b>WHY (OPD-WF2-PRS-06)</b>: CC 在 async hook 逐行提取时对首个可解析 JSON 直接赋
     * raw parsed — 仅 {@code jsonParse} + {@code 'async' in parsed} <b>键存在性</b>判定, 不做
     * zod schema 校验; 类型偏差字段 (如 {@code {"continue":"false"}}) 仍接受为 response 并
     * {@code break} 停止扫描. Java 旧实现经 {@link #deserializeHookJSONOutput} 严格校验 →
     * 类型偏差行 throw → 跳过 → 交付后续行/空对象 — 与 CC "首个可解析非 async 行即交付"
     * 方向相反.
     *
     * <p>本方法为宽松等价: 良构字段照常提取, <b>类型偏差字段静默置 null (绝不 throw)</b>,
     * hookSpecificOutput 严格解析失败置 null — 保证该行被接受为 response 且其余字段可消费
     * (下游 {@code AsyncHookResponse} 消费方仅读 systemMessage / hookSpecificOutput.
     * additionalContext, attachments.ts:3465 → messages.ts:4026-4043).
     *
     * <p><b>仅用于 async hook 逐行提取路径</b> ({@link AsyncHookRegistry}); 主解析链
     * {@link #validateHookJson} 仍走严格 {@link #deserializeHookJSONOutput} (CC zod 语义
     * validationError 不变). 宽松助手 {@link #boolOrNull}/{@link #textOrNull} 只在本方法内使用,
     * 不会削弱严格路径.
     *
     * @param node sync 响应 JSON 对象 (调用方已确认无 'async' 键)
     * @return SyncHookOutput (字段按需宽松提取, 类型偏差字段为 null)
     * @throws IllegalArgumentException 非对象 (readTree 拒绝等价)
     */
    static HookJSONOutput.SyncHookOutput deserializeHookJSONOutputLenient(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("Hook JSON output must be an object");
        }
        Boolean continueExec = boolOrNull(node, "continue");
        Boolean suppressOutput = boolOrNull(node, "suppressOutput");
        String stopReason = textOrNull(node, "stopReason");
        String decision = textOrNull(node, "decision");
        String reason = textOrNull(node, "reason");
        String systemMessage = textOrNull(node, "systemMessage");
        HookSpecificOutput hso = null;
        if (node.has("hookSpecificOutput") && node.get("hookSpecificOutput").isObject()) {
            try {
                hso = parseHookSpecificOutput(node.get("hookSpecificOutput"));
            } catch (IllegalArgumentException e) {
                // 宽松: hookSpecificOutput 结构/类型偏差 → 置 null (CC raw 接受整行, 不拒绝)
                if (log.isDebugEnabled()) {
                    log.debug("HOOK 宽松解析 hookSpecificOutput 失败, 置 null: {}", e.getMessage());
                }
            }
        }
        return new HookJSONOutput.SyncHookOutput(
            continueExec,
            suppressOutput,
            stopReason,
            decision,
            reason,
            systemMessage,
            hso);
    }

    /**
     * 手动按 hookEventName 构造 15 子类型 HookSpecificOutput · [S4 G17] zod 严格化.
     *
     * <p>WHY (H2-GAP-2 + S4 G17): 手动 switch hookEventName 按 CC types/hooks.ts:70-163
     * 逐子类型构造字段. [S4] 对齐 CC zod union 语义 — hookEventName 缺失/非文本/未知名
     * → throw (union 拒绝, 旧 lenient null 降级已删); 子类型字段类型偏差 → throw
     * (z.string()/z.boolean()/z.array(z.string())/z.record 拒绝, 旧 textOrNull/
     * strListOrNull 静默降级已删). 全部 throw 经 validateHookJson catch →
     * validationError → parseStdoutJson 新分支产 hook_non_blocking_error (CC :2504-2531).
     *
     * @param node hookSpecificOutput JSON 对象
     * @return 对应子类型 record
     * @throws IllegalArgumentException 结构/类型偏差 (zod 等价拒绝)
     */
    private static HookSpecificOutput parseHookSpecificOutput(JsonNode node) {
        // hookEventName: 15 union 成员 literal 必填 (types/hooks.ts:72-162) — 缺失/非文本 → 拒绝
        JsonNode eventNameNode = node.get("hookEventName");
        if (eventNameNode == null || !eventNameNode.isTextual()) {
            throw new IllegalArgumentException("hookSpecificOutput.hookEventName is required");
        }
        String eventName = eventNameNode.asText();
        switch (eventName) {
            case "PreToolUse":
                return new HookSpecificOutput.PreToolUse(
                    enumOrThrow(node, "permissionDecision", "permissionDecision", "allow", "deny", "ask"),
                    textOrThrow(node, "permissionDecisionReason", "permissionDecisionReason"),
                    objMapOrThrow(node, "updatedInput"),
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "UserPromptSubmit":
                return new HookSpecificOutput.UserPromptSubmit(
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "SessionStart":
                return new HookSpecificOutput.SessionStart(
                    textOrThrow(node, "additionalContext", "additionalContext"),
                    textOrThrow(node, "initialUserMessage", "initialUserMessage"),
                    strListOrThrow(node, "watchPaths"));
            case "Setup":
                return new HookSpecificOutput.Setup(
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "SubagentStart":
                return new HookSpecificOutput.SubagentStart(
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "PostToolUse":
                return new HookSpecificOutput.PostToolUse(
                    textOrThrow(node, "additionalContext", "additionalContext"),
                    node.has("updatedMCPToolOutput") && !node.get("updatedMCPToolOutput").isNull()
                        ? MAPPER.convertValue(node.get("updatedMCPToolOutput"), Object.class) : null);
            case "PostToolUseFailure":
                return new HookSpecificOutput.PostToolUseFailure(
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "PermissionDenied":
                return new HookSpecificOutput.PermissionDenied(
                    boolOrThrow(node, "retry"));
            case "Notification":
                return new HookSpecificOutput.Notification(
                    textOrThrow(node, "additionalContext", "additionalContext"));
            case "PermissionRequest":
                return new HookSpecificOutput.PermissionRequest(parsePermissionRequestDecision(node));
            case "Elicitation":
                return new HookSpecificOutput.Elicitation(
                    enumOrThrow(node, "action", "action", "accept", "decline", "cancel"),
                    objMapOrThrow(node, "content"));
            case "ElicitationResult":
                return new HookSpecificOutput.ElicitationResult(
                    enumOrThrow(node, "action", "action", "accept", "decline", "cancel"),
                    objMapOrThrow(node, "content"));
            case "CwdChanged":
                return new HookSpecificOutput.CwdChanged(strListOrThrow(node, "watchPaths"));
            case "FileChanged":
                return new HookSpecificOutput.FileChanged(strListOrThrow(node, "watchPaths"));
            case "WorktreeCreate":
                // worktreePath: z.string() 必填 (types/hooks.ts:160-162)
                if (!node.has("worktreePath") || !node.get("worktreePath").isTextual()) {
                    throw new IllegalArgumentException(
                        "WorktreeCreate.worktreePath is required");
                }
                return new HookSpecificOutput.WorktreeCreate(node.get("worktreePath").asText());
            default:
                // 未知名 → union 无匹配成员 → 拒绝 (CC zod union 语义; 旧 lenient null 降级已删)
                throw new IllegalArgumentException(
                    "Unknown hookEventName: " + eventName);
        }
    }

    /**
     * PermissionRequest.decision union → PermissionRequestResult · [S4 G17] 严格化
     * (types/hooks.ts:121-133): decision 必填对象, behavior 必填 'allow'|'deny' —
     * 缺失/非法 → throw (旧 null 降级删除).
     */
    private static PermissionRequestResult parsePermissionRequestDecision(JsonNode node) {
        JsonNode decision = node.get("decision");
        if (decision == null || !decision.isObject()) {
            throw new IllegalArgumentException(
                "PermissionRequest.decision is required");
        }
        JsonNode behaviorNode = decision.get("behavior");
        if (behaviorNode == null || !behaviorNode.isTextual()) {
            throw new IllegalArgumentException(
                "PermissionRequest.decision.behavior is required");
        }
        String behavior = behaviorNode.asText();
        if ("allow".equals(behavior)) {
            return new PermissionRequestResult.Allow(
                objMapOrThrow(decision, "updatedInput"),
                objListOrThrow(decision, "updatedPermissions"));
        }
        if ("deny".equals(behavior)) {
            return new PermissionRequestResult.Deny(
                textOrThrow(decision, "message", "message"),
                boolOrThrow(decision, "interrupt"));
        }
        throw new IllegalArgumentException(
            "PermissionRequest.decision.behavior must be one of: allow, deny");
    }

    /** 字段存在但非文本 → throw (z.string() 拒绝). */
    private static String textOrThrow(JsonNode node, String field, String label) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isTextual()) {
            throw new IllegalArgumentException(label + " must be a string");
        }
        return v.asText();
    }

    /** 字段存在但非 boolean → throw (z.boolean() 拒绝). */
    private static Boolean boolOrThrow(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isBoolean()) {
            throw new IllegalArgumentException(field + " must be a boolean");
        }
        return v.asBoolean();
    }

    /** 字段存在但非 boolean → null (宽松: 类型偏差字段静默降级, 不 throw). 仅 raw-accept 路径使用. */
    private static Boolean boolOrNull(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        return v.isBoolean() ? v.asBoolean() : null;
    }

    /** 字段存在但非文本 → null (宽松: 类型偏差字段静默降级, 不 throw). 仅 raw-accept 路径使用. */
    private static String textOrNull(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        return v.isTextual() ? v.asText() : null;
    }

    /** 字段存在但非对象 → throw (z.record() 拒绝). */
    private static Map<String, Object> objMapOrThrow(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return MAPPER.convertValue(v, new TypeReference<Map<String, Object>>() { });
    }

    /** 字段存在但非字符串数组 (或含非字符串元素) → throw (z.array(z.string()) 拒绝). */
    private static List<String> strListOrThrow(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of strings");
        }
        List<String> list = new ArrayList<>();
        for (JsonNode item : v) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(
                    field + " must be an array of strings");
            }
            list.add(item.asText());
        }
        return list;
    }

    /** 字段存在但非数组 (或含非对象元素) → throw (z.array(permissionUpdateSchema()) 拒绝). */
    private static List<Object> objListOrThrow(JsonNode node, String field) {
        if (!node.has(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isArray()) {
            throw new IllegalArgumentException(field + " must be an array of objects");
        }
        List<Object> list = new ArrayList<>();
        for (JsonNode item : v) {
            if (!item.isObject()) {
                throw new IllegalArgumentException(
                    field + " must be an array of objects");
            }
            list.add(MAPPER.convertValue(item, Object.class));
        }
        return list;
    }

    /** 字段存在但非文本或不在值域 → throw (z.enum() 拒绝). */
    private static String enumOrThrow(JsonNode node, String field, String label,
                                      String... allowed) {
        String value = textOrThrow(node, field, label);
        if (value == null) {
            return null;
        }
        for (String a : allowed) {
            if (a.equals(value)) {
                return value;
            }
        }
        throw new IllegalArgumentException(label + " must be one of: "
            + String.join(", ", allowed));
    }
}
