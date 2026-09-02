package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.attachment.AttachmentMessageDto;
import com.nexusai.application.agent.hook.PermissionBehavior;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * [Session H3] Hook JSON 输出解析测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks.ts:382-737} parseHookOutput / validateHookJson /
 * parseHttpHookOutput / processHookJSONOutput.
 *
 * <p><b>WHY (意图验证)</b>: H2 期间 parseStdoutJson 只是 4 字段最小子集 (continue/decision/
 * stopReason/systemMessage), H2-GAP-2 诉求是"复杂 JSON 输出不能静默忽略" — 15 子类型
 * hookSpecificOutput (PreToolUse/UserPromptSubmit/SessionStart/PermissionRequest/Elicitation 等)
 * 必须完整映射到 HookResult 字段, 否则 hook 返回的 updatedInput / additionalContext /
 * watchPaths / retry 等指令全部丢失.
 *
 * <p>每个测试验证 CC 真源一个具体分支:
 * <ol>
 *   <li>{@link #continueFalse_setsPreventContinuationAndStopReason} — CC hooks.ts:518-523 continue===false</li>
 *   <li>{@link #decisionBlock_setsDenyAndBlockingError} — CC hooks.ts:525-543 decision='block'</li>
 *   <li>{@link #systemMessage_mapsToSystemMessage} — CC hooks.ts:545-548 systemMessage</li>
 *   <li>{@link #preToolUse_askDecision_asksWithUpdatedInput} — CC hooks.ts:550-578 + :592-623 PreToolUse</li>
 *   <li>{@link #asyncOutput_unionDetection} — CC types/hooks.ts:182-193 isAsyncHookJSONOutput</li>
 *   <li>{@link #nonJsonOutput_degradesToProceed} — CC hooks.ts:404-408 不以 { 开头 → plainText</li>
 *   <li>{@link #emptyStdout_degradesToProceed} — 空 stdout → proceed</li>
 *   <li>{@link #hookField_wiredFromCommandHook} — toHookResult(CommandHook) 后 hook() 携带</li>
 *   <li>{@link #hookSpecificOutput_15subtypes_deserialize} — 15 子类型可反序列化 (≥5 代表类型)</li>
 *   <li>{@link #additionalContext_singleStringValue} — additionalContext 单值 String</li>
 * </ol>
 */
class HookOutputParserTest {

    // ─────────── 1. continue===false → preventContinuation + stopReason (CC :518-523) ───────────

    @Test
    @DisplayName("H3-1 {continue:false, stopReason} → preventContinuation=true + stopReason")
    void continueFalse_setsPreventContinuationAndStopReason() {
        // WHY: command hook 返回 {continue:false} 表示阻止后续流程 (CC processHookJSONOutput :518-523).
        //       若 Java 端不解析 continue, hook 的阻断意图静默丢失 → 主流程继续, 等同 hook 没配.
        GenericHook.HookResult result = parse("{\"continue\":false,\"stopReason\":\"manual gate\"}");

        assertThat(result.preventContinuation()).isTrue();
        assertThat(result.stopReason()).isEqualTo("manual gate");
    }

    // ─────────── 2. decision='block' → permissionBehavior=DENY + blockingError (CC :525-543) ───────────

    @Test
    @DisplayName("H3-2 {decision:block, reason:r} → DENY + blockingError.blockingError=r")
    void decisionBlock_setsDenyAndBlockingError() {
        // WHY: 顶层 decision='block' 是 hook 显式拒绝指令 (CC :530-536), 必须映射为
        //       permissionBehavior=DENY + 结构化 blockingError (含 command), 否则拒绝意图丢失.
        GenericHook.HookResult result = parse("{\"decision\":\"block\",\"reason\":\"forbidden by policy\"}");

        assertThat(result.permissionBehavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(result.blockingError()).isNotNull();
        assertThat(result.blockingError().blockingError()).isEqualTo("forbidden by policy");
        assertThat(result.blockingError().command()).isEqualTo("check.sh");
    }

    // ─────────── 3. systemMessage → result.systemMessage (CC :545-548) ───────────

    @Test
    @DisplayName("H3-3 {systemMessage:m} → systemMessages=[m] (H-WF5a-02 折叠链项3 List 承载)")
    void systemMessage_mapsToSystemMessage() {
        // WHY: systemMessage 是 hook 注入的用户可见系统消息 (CC :546-547), 必须透传到 HookResult.
        //   [H-WF5a-02] HookResult.systemMessages 改 List 全保留 (CC 逐结果 yield N 条).
        GenericHook.HookResult result = parse("{\"systemMessage\":\"disk usage critical\"}");

        assertThat(result.systemMessages()).containsExactly("disk usage critical");
    }

    // ─────────── 4. PreToolUse ask + updatedInput (CC :550-578, :592-623) ───────────

    @Test
    @DisplayName("H3-4 hookSpecificOutput.PreToolUse ask + updatedInput → ASK + updatedInput 映射")
    void preToolUse_askDecision_asksWithUpdatedInput() {
        // WHY: PreToolUse hook 通过 hookSpecificOutput.permissionDecision 决定权限 (CC :592-614),
        //       'ask' → permissionBehavior=ASK; updatedInput 必须映射 (CC :617-620), 否则 hook
        //       对工具入参的修改被静默丢弃.
        GenericHook.HookResult result = parse(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
                + "\"permissionDecision\":\"ask\",\"permissionDecisionReason\":\"needs review\","
                + "\"updatedInput\":{\"key\":\"modified\"}}}");

        assertThat(result.permissionBehavior()).isEqualTo(PermissionBehavior.ASK);
        assertThat(result.hookPermissionDecisionReason()).isEqualTo("needs review");
        assertThat(result.updatedInput()).isNotNull();
        assertThat(result.updatedInput().get("key")).isEqualTo("modified");
    }

    // ─────────── 5. async union 判别 (CC types/hooks.ts:182-193) ───────────

    @Test
    @DisplayName("H3-5 {async:true, asyncTimeout} → isAsyncHookJSONOutput=true (union)")
    void asyncOutput_unionDetection() {
        // WHY: hookJSONOutputSchema 是 sync ∪ async union (types/hooks.ts:175), async 判别 =
        //       'async' in json && async===true (types/hooks.ts:192). Java 端 instanceof AsyncHookOutput 等价.
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"async\":true,\"asyncTimeout\":5000}");

        assertThat(pr.json()).isNotNull();
        assertThat(HookJSONOutput.isAsyncHookJSONOutput(pr.json())).isTrue();
        assertThat(HookJSONOutput.isSyncHookJSONOutput(pr.json())).isFalse();
        assertThat(pr.json()).isInstanceOf(HookJSONOutput.AsyncHookOutput.class);
        assertThat(((HookJSONOutput.AsyncHookOutput) pr.json()).asyncTimeout()).isEqualTo(5000L);
    }

    // ─────────── 6. 非 JSON → 纯文本路径 (CC :404-408) ───────────

    @Test
    @DisplayName("G01 不以 { 开头 → 纯文本 exit 0 → hook_success content=stdout.trim() (CC :2617-2645)")
    void nonJsonOutput_plainTextHookSuccess() {
        // WHY: hook stdout 可能是纯文本 (不以 { 开头), CC parseHookOutput :404-408 视为 plainText,
        //       status 0 → hook_success content=stdout.trim() (S4 G01: 旧 Java 静默 proceed 丢 stdout).
        GenericHook.HookResult result = parse("plain text output, not json");

        assertThat(result.preventContinuation()).isFalse();
        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        AttachmentMessageDto att = (AttachmentMessageDto) result.message();
        assertThat(att.type()).isEqualTo("hook_success");
        assertThat(att.content()).isEqualTo("plain text output, not json");

        // [H-WF2-02 0-5 宽松降级] { 开头但非法 JSON → JSON.parse 失败 (CC :447-450 catch →
        //   plainText, 无 validationError) → status 0 → hook_success (CC :2617-2645). 旧实现
        //   把 readTree 失败也 catch 成 validationError → NON_BLOCKING_ERROR (偏离 CC); 0-5 拍板
        //   对齐 CC 宽松降级. 断言加强: 不抛异常 + 实际产出 hook_success (非 NON_BLOCKING_ERROR).
        GenericHook.HookResult badJson = parse("{not valid json");
        assertThat(badJson.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(badJson.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) badJson.message()).type())
            .as("0-5 宽松降级: 非法 JSON 静默降级为纯文本 hook_success (CC parseHookOutput catch :447-450 + runHook :2617)")
            .isEqualTo("hook_success");
    }

    // ─────────── 7. 空 stdout → hook_success content '' ───────────

    @Test
    @DisplayName("G01 空 stdout exit 0 → hook_success content='' (CC :2617-2645, 无 blank 早退)")
    void emptyStdout_hookSuccess() {
        // WHY: CC 无 blank 早退 — 空 stdout 走 plainText → status 0 → hook_success content=''
        //       (S4 G01: 旧 Java 空白早退 proceed 无 attachment).
        GenericHook.HookResult result = parse("   ");

        assertThat(result.outcome()).isEqualTo(GenericHook.HookOutcome.SUCCESS);
        assertThat(result.message()).isInstanceOf(AttachmentMessageDto.class);
        assertThat(((AttachmentMessageDto) result.message()).type()).isEqualTo("hook_success");
        assertThat(((AttachmentMessageDto) result.message()).content()).isEmpty();
    }


    // ─────────── 8. hook 字段携带 CommandHook (withHook wither) ───────────

    @Test
    @DisplayName("H3-8 toHookResult(CommandHook) → hook() 携带 CommandHook")
    void hookField_wiredFromCommandHook() {
        // WHY: CC HookResult.hook 字段 (hooks.ts:356) 承载触发 hook 的 HookCommand, 供审计/UI
        //       追溯"这条 result 来自哪个 hook". Java 端 toHookResult 重载接收 CommandHook 后 withHook() 填充.
        CommandHook hook = new CommandHook("check.sh", null, null, null, null, null, null, null);
        CommandHookExecutor.CommandHookResult execResult =
            new CommandHookExecutor.CommandHookResult("{\"systemMessage\":\"m\"}", "", "{\"systemMessage\":\"m\"}", 0, false, false);

        GenericHook.HookResult result = CommandHookExecutor.toHookResult(execResult, hook);

        assertThat(result.hook()).isSameAs(hook);
        assertThat(result.systemMessages()).containsExactly("m");
    }

    // ─────────── 9. 15 子类型 hookSpecificOutput 反序列化 (≥5 代表类型) ───────────

    @Test
    @DisplayName("H3-9 hookSpecificOutput 15 子类型可反序列化 (测 PreToolUse/UserPromptSubmit/SessionStart/PostToolUse/PermissionRequest)")
    void hookSpecificOutput_15subtypes_deserialize() {
        // WHY: H2-GAP-2 — 复杂 hookSpecificOutput 不能静默忽略. 覆盖 5 个代表子类型验证
        //       反序列化 + 字段映射: PreToolUse(updatedInput), UserPromptSubmit(additionalContext),
        //       SessionStart(initialUserMessage+watchPaths), PostToolUse(updatedMCPToolOutput),
        //       PermissionRequest(decision allow/deny).
        // PreToolUse
        GenericHook.HookResult pre = parse("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\","
            + "\"permissionDecision\":\"deny\",\"permissionDecisionReason\":\"no\"}}");
        assertThat(pre.permissionBehavior()).isEqualTo(PermissionBehavior.DENY);
        assertThat(pre.blockingError().blockingError()).isEqualTo("no");

        // UserPromptSubmit → additionalContext 单值 → List 承载 (H-WF5a-02 折叠链项2)
        GenericHook.HookResult up = parse("{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\","
            + "\"additionalContext\":\"user said hello\"}}");
        assertThat(up.additionalContexts()).containsExactly("user said hello");

        // SessionStart → initialUserMessage + watchPaths 进 ParsedHookJSONOutput
        HookOutputParser.ParsedHookJSONOutput ss = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\","
                + "\"additionalContext\":\"ctx\",\"initialUserMessage\":\"hi\","
                + "\"watchPaths\":[\"/a\",\"/b\"]}}");
        assertThat(ss.result().additionalContexts()).containsExactly("ctx");
        assertThat(ss.initialUserMessage()).isEqualTo("hi");
        assertThat(ss.watchPaths()).containsExactly("/a", "/b");

        // PostToolUse → updatedMCPToolOutput
        GenericHook.HookResult post = parse("{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\","
            + "\"updatedMCPToolOutput\":{\"replaced\":true}}}");
        assertThat(post.updatedMCPToolOutput()).isNotNull();

        // PermissionRequest allow → updatedInput + ALLOW + [S07] 顶层回填
        HookOutputParser.ParsedHookJSONOutput prr = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\","
                + "\"decision\":{\"behavior\":\"allow\",\"updatedInput\":{\"k\":\"v\"}}}}");
        assertThat(prr.permissionRequestResult()).isInstanceOf(PermissionRequestResult.Allow.class);
        assertThat(prr.result().permissionBehavior()).isEqualTo(PermissionBehavior.ALLOW);
        assertThat(prr.result().updatedInput().get("k")).isEqualTo("v");
        // [Session S07] HookResult 顶层回填 (CC hooks.ts:2882-2886 yield permissionRequestResult) —
        //   parseStdoutJson backfill 后决策不再只存 ParsedHookJSONOutput, 消费链 (coordinator/
        //   interactive runHooks 等价) 从 result.permissionRequestResult() 直接读取.
        assertThat(prr.result().permissionRequestResult())
            .as("S07: PermissionRequest 决策必须顶层回填到 HookResult (CC hooks.ts:2882-2886)")
            .isInstanceOfSatisfying(PermissionRequestResult.Allow.class, allow ->
                assertThat(allow.updatedInput()).containsEntry("k", "v"));

        // PermissionRequest deny → [S07] 顶层回填 + hook message
        HookOutputParser.ParsedHookJSONOutput deny = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\","
                + "\"decision\":{\"behavior\":\"deny\",\"message\":\"policy blocks\",\"interrupt\":true}}}");
        assertThat(deny.result().permissionRequestResult())
            .as("S07: deny 决策必须顶层回填 (CC hooks.ts:2882-2886)")
            .isInstanceOfSatisfying(PermissionRequestResult.Deny.class, d -> {
                assertThat(d.message()).isEqualTo("policy blocks");
                assertThat(d.interrupt()).isTrue();
            });
        assertThat(deny.result().permissionBehavior()).isEqualTo(PermissionBehavior.DENY);
    }

    // ─────────── 10. additionalContext 单值 String 断言 ───────────

    @Test
    @DisplayName("H3-10 + H-WF5a-02 additionalContext 单值 → List 承载 (折叠链项2)")
    void additionalContext_listValue() {
        // WHY: CC additionalContext 是 string (非数组, 单值), Java HookResult 折叠层改
        //       List<String> 承载 (H-WF5a-02: 聚合 N 结果全保留); 单值包成单元素 List.
        HookOutputParser.ParsedHookJSONOutput parsed = parseFull(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"UserPromptSubmit\",\"additionalContext\":\"single\"}}");

        assertThat(parsed.result().additionalContexts()).containsExactly("single");
        assertThat(parsed.result().additionalContexts()).isInstanceOf(List.class);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 11. [S4 G17] zod 严格校验 (types/hooks.ts:50-176) · 类型偏差 → validationError
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("G17 {continue:\"false\"} 字符串 boolean → validationError (CC z.boolean() 拒绝)")
    void g17_continueString_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson("{\"continue\":\"false\"}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 {async:\"true\"} 字符串 → 不匹配 async literal → 按 sync 处理 (async 键 strip, CC zod union)")
    void g17_asyncString_treatedAsSync() {
        // CC: async schema 要求 z.literal(true), "true" 不匹配 → union 落到 sync schema,
        //      z.object 默认 strip 未知键 → 合法 sync 输出 (async 键被忽略).
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"async\":\"true\",\"systemMessage\":\"m\"}");
        assertThat(pr.json()).isNotNull();
        assertThat(HookJSONOutput.isSyncHookJSONOutput(pr.json())).isTrue();
        assertThat(HookJSONOutput.isAsyncHookJSONOutput(pr.json())).isFalse();
    }

    @Test
    @DisplayName("G17 未知 hookEventName → validationError (CC union 拒绝)")
    void g17_unknownHookEventName_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"BogusEvent\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 hookEventName 缺失 → validationError (CC union 必填)")
    void g17_missingHookEventName_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"additionalContext\":\"x\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 decision 非法值 → validationError (CC z.enum(['approve','block']) 拒绝)")
    void g17_invalidDecision_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"decision\":\"maybe\"}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 stopReason 非字符串 → validationError (CC z.string() 拒绝)")
    void g17_stopReasonNonString_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"continue\":false,\"stopReason\":42}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 watchPaths 含非字符串元素 → validationError (CC z.array(z.string()) 拒绝)")
    void g17_watchPathsNonString_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"SessionStart\",\"watchPaths\":[\"/a\",42]}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 WorktreeCreate 缺 worktreePath → validationError (CC z.string() 必填)")
    void g17_worktreeCreateMissingPath_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"WorktreeCreate\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 updatedInput 非对象 → validationError")
    void g17_updatedInputNotObject_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"updatedInput\":\"not-object\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 asyncTimeout 字符串 → validationError (CC z.number() 拒绝)")
    void g17_asyncTimeoutString_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"async\":true,\"asyncTimeout\":\"5000\"}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 Elicitation action 非法值 → validationError (CC z.enum(['accept','decline','cancel']) 拒绝)")
    void g17_elicitationActionInvalid_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"Elicitation\",\"action\":\"dismiss\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 PermissionRequest decision.behavior 非法 → validationError")
    void g17_permissionRequestInvalidBehavior_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\","
                + "\"decision\":{\"behavior\":\"maybe\"}}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    @Test
    @DisplayName("G17 PermissionRequest 缺 decision → validationError (CC union 成员必填)")
    void g17_permissionRequestMissingDecision_rejected() {
        HookOutputParser.ParseResult pr = HookOutputParser.validateHookJson(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PermissionRequest\"}}");
        assertThat(pr.json()).isNull();
        assertThat(pr.validationError()).isNotNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 11.5 [OPD-WF2-PRS-06] AsyncHookRegistry raw-accept 宽松反序列化
    //      deserializeHookJSONOutputLenient (CC AsyncHookRegistry.ts:199-205)
    //      · 与 G17 严格路径分立: 仅 async hook 逐行提取使用, 主解析链仍严格
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PRS-06 lenient: 类型偏差字段静默置 null, 良构字段保留 (CC raw 接受)")
    void lenient_typeDeviantField_null_wellFormedPreserved() {
        // WHY (OPD-WF2-PRS-06): CC AsyncHookRegistry response = parsed 直接赋 raw, 不 schema
        //       校验 — 类型偏差行 (continue 为字符串 "false") 仍被接受为响应. 严格版
        //       (validateHookJson, G17) 该行 → validationError; lenient 版必须不抛且保留良构
        //       字段 (systemMessage) — 否则 async hook 的类型偏差行整行丢失.
        HookJSONOutput.SyncHookOutput out =
            HookOutputParser.deserializeHookJSONOutputLenient(jsonNode("{\"continue\":\"false\",\"systemMessage\":\"first\"}"));

        assertThat(out.continueExecution()).isNull();
        assertThat(out.systemMessage()).isEqualTo("first");
    }

    @Test
    @DisplayName("PRS-06 lenient: hookSpecificOutput 结构偏差 → 置 null 而非拒绝整行")
    void lenient_hookSpecificOutputDeviation_null() {
        // WHY: CC raw 接受整行, 即使 hookSpecificOutput 缺失 hookEventName 也不丢弃其余字段.
        //       严格版 → validationError; lenient 版 hookSpecificOutput 置 null, 顶层字段保留.
        HookJSONOutput.SyncHookOutput out =
            HookOutputParser.deserializeHookJSONOutputLenient(jsonNode("{\"systemMessage\":\"keep\",\"hookSpecificOutput\":{\"foo\":1}}"));

        assertThat(out.systemMessage()).isEqualTo("keep");
        assertThat(out.hookSpecificOutput()).isNull();
    }

    @Test
    @DisplayName("PRS-06 lenient: 良构 hookSpecificOutput 照常解析 (additionalContext 保留)")
    void lenient_wellFormedHookSpecificOutput_preserved() {
        // WHY: raw-accept 不意味着丢弃良构数据 — 良构 hookSpecificOutput 必须照常解析供下游
        //       additionalContext 消费 (messages.ts:4030 'additionalContext' in hookSpecificOutput).
        HookJSONOutput.SyncHookOutput out = HookOutputParser.deserializeHookJSONOutputLenient(
            jsonNode("{\"hookSpecificOutput\":{\"hookEventName\":\"PreToolUse\",\"additionalContext\":\"ctx\"}}"));

        assertThat(out.hookSpecificOutput()).isInstanceOf(HookSpecificOutput.PreToolUse.class);
        assertThat(((HookSpecificOutput.PreToolUse) out.hookSpecificOutput()).additionalContext())
            .isEqualTo("ctx");
    }

    @Test
    @DisplayName("PRS-06 lenient: decision 非枚举值仍接受 (raw, 不 zod 拒绝)")
    void lenient_decisionOutOfEnum_rawAccepted() {
        // WHY: CC raw 接受不校验 z.enum — decision='maybe' 在 lenient 路径原样保留; 严格
        //       validateHookJson (G17) 该值 → validationError. 两路径语义分立, 不互相污染.
        HookJSONOutput.SyncHookOutput out =
            HookOutputParser.deserializeHookJSONOutputLenient(jsonNode("{\"decision\":\"maybe\"}"));

        assertThat(out.decision()).isEqualTo("maybe");
    }

    private static JsonNode jsonNode(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("test JSON 解析失败: " + json, e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // 12. [H-WF2-02 WF6-X4] Elicitation 宽松解析 parseElicitationHookOutput
    //     (CC hooks.ts:4388-4468) · △-9/△-10/△-11 三处偏移对齐
    // ════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("E-1 空输出 → 静默无决策 (CC :4407-4409) [△-9 修复]")
    void elicitation_emptyOutput_silent() {
        // WHY (△-9): CC parseElicitationHookOutput 空输出 → return {} 静默 (无 response,
        //       无 blockingError, 无 message attachment). 旧实现走 toHookResultCore 纯文本
        //       status 0 → hook_success attachment (CC outside-REPL 路径无此副作用).
        HookOutputParser.ElicitationParseResult r =
            parseElicitation("   ", false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-2 非 { 开头纯文本 → 静默无决策 (CC :4411-4413) [△-9 修复]")
    void elicitation_plainText_silent() {
        // WHY (△-9): CC 非 { 开头 → return {} 静默. 旧实现产 hook_success attachment.
        HookOutputParser.ElicitationParseResult r =
            parseElicitation("plain text, not json", false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-3 事件名不匹配 → 静默无决策不抛错 (CC :4435-4437) [△-10 修复]")
    void elicitation_eventNameMismatch_silent() {
        // WHY (△-10): CC Elicitation 专用解析器对 hookSpecificOutput.hookEventName ≠ expected
        //       静默 {}; 通用解析器 (hooks.ts:583-590) 才抛错. 旧实现复用通用链 → fail-loud
        //       IllegalArgumentException. 对齐 CC: 静默, 不抛.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"PostToolUse\",\"additionalContext\":\"x\"}}",
            false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-4 非法 JSON → catch 静默无决策 (CC :4465-4467) [△-11 修复]")
    void elicitation_malformedJson_silent() {
        // WHY (△-11): CC JSON.parse/schema 失败 → catch → {} 静默. 旧实现 → NON_BLOCKING_ERROR.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{not valid json", false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-5 schema 校验失败 → 静默无决策 (CC :4416-4418 catch)")
    void elicitation_schemaFailure_silent() {
        // WHY: CC hookJSONOutputSchema().parse() 失败即 throw → catch → {}. Java validateHookJson
        //      返回 validationError (json()==null) → 同样静默 {}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"decision\":\"maybe\"}", false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-6 exit 2 阻断 → blockingError (CC :4400-4405)")
    void elicitation_exit2_blockingError() {
        // WHY: CC blocked && !succeeded (exit 2) → blockingError {output || 'Elicitation blocked by hook', command}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "user must approve first", true, false, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).isEqualTo("user must approve first");
        assertThat(r.blockingError().command()).isEqualTo("check.sh");
    }

    @Test
    @DisplayName("E-7 顶层 decision block → blockingError (CC :4429-4438)")
    void elicitation_decisionBlock_blockingError() {
        // WHY: CC parsed.decision === 'block' || result.blocked → blockingError {reason || 'Elicitation blocked by hook'}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"decision\":\"block\",\"reason\":\"forbidden by elicitation policy\"}",
            false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).isEqualTo("forbidden by elicitation policy");
        assertThat(r.blockingError().command()).isEqualTo("check.sh");
    }

    @Test
    @DisplayName("E-8 合法 Elicitation accept → response action+content (CC :4449-4463)")
    void elicitation_accept_response() {
        // WHY: CC specific.action 匹配且非 decline → response {action, content}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"Elicitation\",\"action\":\"accept\","
                + "\"content\":{\"approved\":true}}}",
            false, true, "Elicitation");

        assertThat(r.blockingError()).isNull();
        assertThat(r.response()).isNotNull();
        assertThat(r.response().action()).isEqualTo("accept");
        assertThat(r.response().content()).containsEntry("approved", true);
    }

    @Test
    @DisplayName("E-9 合法 Elicitation decline → response + blockingError (CC :4455-4463)")
    void elicitation_decline_responseAndBlockingError() {
        // WHY: CC decline → response + blockingError {reason || 'Elicitation denied by hook'}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"Elicitation\",\"action\":\"decline\","
                + "\"content\":{\"reason\":\"user declined\"}}}",
            false, true, "Elicitation");

        assertThat(r.response()).isNotNull();
        assertThat(r.response().action()).isEqualTo("decline");
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).isEqualTo("Elicitation denied by hook");
    }

    @Test
    @DisplayName("E-10 ElicitationResult decline 文案区分 (CC :4459-4462)")
    void elicitationResult_decline_wording() {
        // WHY: CC decline 文案按 expectedEventName 分流 — 'ElicitationResult' → 'Elicitation result blocked by hook'.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"ElicitationResult\",\"action\":\"decline\"}}",
            false, true, "ElicitationResult");

        assertThat(r.response()).isNotNull();
        assertThat(r.blockingError()).isNotNull();
        assertThat(r.blockingError().blockingError()).isEqualTo("Elicitation result blocked by hook");
    }

    @Test
    @DisplayName("E-11 async 输出 → 静默无决策 (CC :4422-4427)")
    void elicitation_async_silent() {
        // WHY: CC isAsyncHookJSONOutput(parsed) → return {}. Java json 非 SyncHookOutput → {}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"async\":true,\"asyncTimeout\":5000}", false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    @Test
    @DisplayName("E-12 无 action → 静默无决策 (CC :4445-4447)")
    void elicitation_noAction_silent() {
        // WHY: CC specific.action 缺失 → return {}.
        HookOutputParser.ElicitationParseResult r = parseElicitation(
            "{\"hookSpecificOutput\":{\"hookEventName\":\"Elicitation\",\"content\":{\"k\":\"v\"}}}",
            false, true, "Elicitation");

        assertThat(r.response()).isNull();
        assertThat(r.blockingError()).isNull();
    }

    // ════════════════════════════════════════════════════════════════════════
    // helpers · 走 CommandHookExecutor 完整链路 (status==0 委托 HookOutputParser)
    // ════════════════════════════════════════════════════════════════════════

    private static GenericHook.HookResult parse(String stdout) {
        CommandHookExecutor.CommandHookResult execResult =
            new CommandHookExecutor.CommandHookResult(stdout, "", stdout, 0, false, false);
        return CommandHookExecutor.toHookResult(execResult, "check.sh");
    }

    private static HookOutputParser.ParsedHookJSONOutput parseFull(String stdout) {
        HookOutputParser.ParseResult pr = HookOutputParser.parseHookOutput(stdout);
        assertThat(pr.json()).isInstanceOf(HookJSONOutput.SyncHookOutput.class);
        return HookOutputParser.processHookJSONOutput(
            (HookJSONOutput.SyncHookOutput) pr.json(), "check.sh", null, null);
    }

    /** [H-WF2-02] Elicitation 宽松解析 helper · command 恒 "check.sh". */
    private static HookOutputParser.ElicitationParseResult parseElicitation(
        String output, boolean blocked, boolean succeeded, String expectedEventName) {
        return HookOutputParser.parseElicitationHookOutput(
            output, blocked, succeeded, "check.sh", expectedEventName);
    }
}
