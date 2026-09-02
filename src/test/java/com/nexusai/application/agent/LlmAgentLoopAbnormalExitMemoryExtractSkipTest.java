package com.nexusai.application.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [DEC-RV-05] 异常结束路径跳过 s09 memory extract + autoDream（源码锚定测试）。
 *
 * <p><b>WHY</b> (CLAUDE.md 规则 9 · 测试验证意图):
 * CC 的 memory extract 位于 {@code handleStopHooks} 内部（stopHooks.ts:149
 * {@code executeExtractMemories} + :155 {@code executeAutoDream}），而 {@code handleStopHooks}
 * 只在 {@code !needsFollowUp} 正常路径触达（query.ts:1062 → :1267）。以下异常 reason 均在
 * handleStopHooks 之前提前 return，永不触达 memory extract（CC 语义：模型未产生有效响应，
 * 跑 stop hooks 评估会成死亡螺旋 —— query.ts:1168-1172 注释）：
 * <ul>
 *   <li>{@code model_error} —— query.ts:996（withRetry 抛错）</li>
 *   <li>{@code aborted_streaming} —— query.ts:1051（流式中止，Java 合并入 ABORTED）</li>
 *   <li>{@code aborted_tools} —— query.ts:1515（工具调用中止，Java 合并入 ABORTED）</li>
 *   <li>{@code image_error} —— query.ts:977 + :1175</li>
 *   <li>{@code prompt_too_long} —— query.ts:1175 + :1182</li>
 *   <li>{@code hook_stopped} —— query.ts:1520（V-SH 返工既有排除）</li>
 *   <li>{@code blocking_limit} —— query.ts:646</li>
 *   <li>{@code max_turns} —— query.ts:1711</li>
 *   <li>max_output_tokens 恢复耗尽 —— query.ts:1254-1264（surface 后 lastMessage.isApiErrorMessage
 *       → return {reason:'completed'}，仍在 handleStopHooks 之前；Java ExitReason.MAX_OUTPUT_TOKENS）</li>
 * </ul>
 * <p>反之 {@code stop_hook_prevented}（query.ts:1279）由 handleStopHooks 内部返回，extract 已
 * fire-and-forget 执行（stopHooks.ts:149），<b>必须保留</b>；{@code NORMAL}（query.ts:1357
 * {@code return {reason:'completed'}} 正常路径）同样保留。
 *
 * <p><b>锚点策略（H-WF4-01 重构后）</b>: 独立方法 s09 门控（局部变量 {@code s09Reason} +
 * {@code preCompactSnapshot}）已被 H-WF4-01 移除 —— 阶段 4 extract/dream 移至 in-loop Stop hook
 * 评估段（:5756-5787 {@code StopHookPipeline.executeExtractMemoriesAndAutoDream}），在
 * {@code executeStopHooksCollecting}（:5804）<b>之前</b> fire（对齐 CC stopHooks.ts:149 hook
 * 执行前触发）。异常 reason 的"跳过"语义现由两处承载：
 * <ol>
 *   <li><b>DEC-RV-05 契约注释块</b>（:6419-6453）声明排除集合 + STOP_HOOK_PREVENTED/NORMAL 保留；
 *       本测试逐 reason 锚定该契约（阻止语义漂移）。</li>
 *   <li><b>in-loop 结构性触发</b> —— extract 仅在「模型产生有效响应 + 纯文本非空」路径执行，
 *       且 fire 于 hook 评估之前（结构性保证 STOP_HOOK_PREVENTED 路径 extract 已执行）。</li>
 * </ol>
 */
class LlmAgentLoopAbnormalExitMemoryExtractSkipTest {

    private static final String LLM_LOOP_PATH =
        "src/main/java/com/nexusai/application/agent/LlmAgentLoop.java";

    /**
     * DEC-RV-05 排除契约注释块（:6419-6453，锚 :6419 → :6454 [rev2 EX-01] 块首）。
     * H-WF4-01 后异常 reason 的排除语义由该契约声明 + in-loop 触发结构承载（s09Reason /
     * preCompactSnapshot 旧字段已删，不再有独立门代码）。
     */
    private String s09DocSource() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int gateIdx = source.indexOf("// [DEC-RV-05] 异常结束路径跳过 memory extract + autoDream");
        assertThat(gateIdx).as("DEC-RV-05 契约注释必须存在（锚点漂移检测）").isGreaterThan(-1);
        int bodyIdx = source.indexOf("// [rev2 EX-01/OPD-R2-EX-01]", gateIdx);
        assertThat(bodyIdx).as("DEC-RV-05 契约注释块 body 必须存在（锚点漂移检测）").isGreaterThan(gateIdx);
        return source.substring(gateIdx, bodyIdx);
    }

    /**
     * s09 段（:6408-6462，锚 :6408 段注释 → :6462 return state）。H-WF4-01 后本段仅保留
     * NORMAL 兜底回退（extract 已移 in-loop），NORMAL 是正常完成路径 reason，不得被排除。
     */
    private String s09FallbackSource() throws IOException {
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int gateIdx = source.indexOf("// ── s09: Memory extract + autoDream ──");
        assertThat(gateIdx).as("s09 段注释必须存在（锚点漂移检测）").isGreaterThan(-1);
        int bodyIdx = source.indexOf("return state;", gateIdx);
        assertThat(bodyIdx).as("s09 段尾部必须存在（锚点漂移检测）").isGreaterThan(gateIdx);
        return source.substring(gateIdx, bodyIdx);
    }

    @Test
    @DisplayName("model_error 退出跳过 memory extract（CC query.ts:996 withRetry 抛错提前 return）")
    void modelErrorSkipsMemoryExtract() throws IOException {
        // WHY: query.ts:996 catch withRetry 抛错 → return {reason:'model_error'}，在 :1267
        //   handleStopHooks 之前。H-WF4-01 后无独立 s09Reason 排除门，语义由 DEC-RV-05 契约
        //   （:6426 列 model_error + :6450 摘要含 MODEL_ERROR）声明，且异常 turn 不产生有效
        //   响应文本 → in-loop 阶段 4 不执行（结构性等价跳过）。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 model_error 排除（query.ts:996 withRetry 抛错）")
            .contains("withRetry 抛错")
            .contains("MODEL_ERROR");
    }

    @Test
    @DisplayName("prompt_too_long 退出跳过 memory extract（CC query.ts:1175 + :1182 提前 return）")
    void promptTooLongSkipsMemoryExtract() throws IOException {
        // WHY: PTL/media 恢复失败 surface → return {reason: isWithheldMedia ? 'image_error' :
        //   'prompt_too_long'}（:1175）/ contextCollapse withheld → :1182，均不触达 handleStopHooks
        //   （:1168-1172 注释：对 PTL 跑 stop hooks 会成死亡螺旋）。契约锚 :6430 + :6450 摘要。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 prompt_too_long 排除（query.ts:1175 + :1182）")
            .contains("query.ts:1175 + :1182")
            .contains("PROMPT_TOO_LONG");
    }

    @Test
    @DisplayName("image_error 退出跳过 memory extract（CC query.ts:977 + :1175 提前 return）")
    void imageErrorSkipsMemoryExtract() throws IOException {
        // WHY: ImageSize/ResizeError → return {reason:'image_error'}（:977）；媒体恢复失败 → :1175
        //   isWithheldMedia 三元 image_error。Java 端 :3134 ImageValidator 前置校验也设 IMAGE_ERROR。
        //   契约锚 :6429 + :6451 摘要。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 image_error 排除（query.ts:977 + :1175）")
            .contains("query.ts:977 + :1175")
            .contains("IMAGE_ERROR");
    }

    @Test
    @DisplayName("aborted 退出跳过 memory extract（CC query.ts:1051 aborted_streaming + :1515 aborted_tools 合并）")
    void abortedSkipsMemoryExtract() throws IOException {
        // WHY: 流式中止 :1051 / 工具调用中止 :1515 均在 handleStopHooks 之前 return。Java 单
        //   ExitReason.ABORTED 合并两个 CC reason（AgentState.java:1037-1041），排除 ABORTED 覆盖二者。
        //   契约锚 :6427/:6428 列两源 + :6450 摘要含 ABORTED。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 aborted 排除（aborted_streaming :1051 + aborted_tools :1515 合并）")
            .contains("aborted_streaming")
            .contains("aborted_tools")
            .contains("ABORTED");
    }

    @Test
    @DisplayName("STOP_HOOK_PREVENTED 仍执行 memory extract（CC query.ts:1279 handleStopHooks 内部返回）")
    void stopHookPreventedKeepsMemoryExtract() throws IOException {
        // WHY: stop_hook_prevented 由 handleStopHooks 内部返回（:1279），此时 executeExtractMemories
        //   已 fire-and-forget 执行（stopHooks.ts:149）。若排除 STOP_HOOK_PREVENTED，优雅终止场景
        //   memory 提取会丢失 —— 与 CC 相反。H-WF4-01 后由两处锚定：
        //   (1) DEC-RV-05 契约声明 STOP_HOOK_PREVENTED 保留（:6435/:6452）；
        //   (2) 结构性 —— in-loop 段 executeExtractMemoriesAndAutoDream（:5787）在
        //       executeStopHooksCollecting（:5804）之前 fire，stopAborted/preventContinuation 设
        //       STOP_HOOK_PREVENTED 时 extract 已执行。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 STOP_HOOK_PREVENTED 保留（CC stop_hook_prevented 时 extract 已执行）")
            .contains("STOP_HOOK_PREVENTED 保留");
        // 结构性锚点：in-loop 阶段 4 触发必须位于 hook 评估之前
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int extractIdx = source.indexOf("StopHookPipeline.executeExtractMemoriesAndAutoDream(");
        int hookEvalIdx = source.indexOf("executeStopHooksCollecting");
        assertThat(extractIdx).as("in-loop 阶段 4 触发必须存在").isGreaterThan(-1);
        assertThat(hookEvalIdx)
            .as("阶段 4 必须在 executeStopHooksCollecting 之前 fire（fire-and-forget 先于 hook 评估）")
            .isGreaterThan(extractIdx);
    }

    @Test
    @DisplayName("NORMAL 仍执行 memory extract（CC query.ts:1357 正常 completed 路径）")
    void normalKeepsMemoryExtract() throws IOException {
        // WHY: query.ts:1357 return {reason:'completed'} 是 handleStopHooks（:1267）之后的正常路径，
        //   memory extract 已执行。H-WF4-01 后 s09 段仅保留 NORMAL 兜底回退（:6460
        //   state.setExitReason(ExitReason.NORMAL)），契约注释块不将 NORMAL 列入排除集合。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约不得将 NORMAL 列入排除集合（正常完成路径 extract 必须执行）")
            .doesNotContain("NORMAL");
        assertThat(s09FallbackSource())
            .as("s09 段必须保留 NORMAL 兜底回退（正常完成路径）")
            .contains("state.setExitReason(ExitReason.NORMAL);");
    }

    @Test
    @DisplayName("HOOK_STOPPED 仍被排除（V-SH-5 回归 · CC query.ts:1520 hook_stopped 提前 return）")
    void hookStoppedStillExcluded() throws IOException {
        // WHY: V-SH 返工既有排除（CC query.ts:1519-1520 hook_stopped 立即 return，不触达
        //   handleStopHooks）。H-WF4-01 后由三处锚定：
        //   (1) DEC-RV-05 契约 :6425 列 hook_stopped + :6451 摘要含 HOOK_STOPPED；
        //   (2) 结构性 —— in-loop :5548-5555 hasHookStoppedContinuation 终止在阶段 4 触发
        //       （:5787）之前，turn 提前 break 不达 extract；
        //   (3) §14 门 :6049 state.exitReason() != ExitReason.HOOK_STOPPED 双重排除。
        assertThat(s09DocSource())
            .as("DEC-RV-05 契约必须声明 hook_stopped 排除（query.ts:1520 停止续行）")
            .contains("hook_stopped")
            .contains("HOOK_STOPPED");
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        assertThat(source).as("in-loop hasHookStoppedContinuation 终止检测必须存在")
            .contains("hasHookStoppedContinuation");
        assertThat(source).as("in-loop HOOK_STOPPED 终止必须存在")
            .contains("state.setExitReason(ExitReason.HOOK_STOPPED);");
        assertThat(source).as("§14 门必须排除 HOOK_STOPPED")
            .contains("state.exitReason() != ExitReason.HOOK_STOPPED");
    }

    @Test
    @DisplayName("stop hook 执行中被中断映射 STOP_HOOK_PREVENTED 而非 ABORTED（DEC-RV-05 返工 · CC stopHooks.ts:283-294 → query.ts:1278-1279）")
    void stopAbortedMapsToStopHookPrevented() throws IOException {
        // WHY (DEC-RV-05 返工 · 规则 9): stopAborted 路径（stop hook 执行中被用户中断）在 CC 的终止 reason
        //   是 stop_hook_prevented，不是 aborted：stopHooks.ts:283-294 abort 检测 → return
        //   {blockingErrors:[], preventContinuation:true} → query.ts:1278-1279 return
        //   {reason:'stop_hook_prevented'}。且 memory extract（stopHooks.ts:149 executeExtractMemories
        //   fire-and-forget）在 hook 执行【前】已触发 → s09 门保留 STOP_HOOK_PREVENTED 时 extract 正常执行。
        //   Java 旧实现两处 stopAborted（in-loop + §14）映射 ABORTED → DEC-RV-05 门排除 ABORTED →
        //   该路径 extract 被跳过 = 回归 CC（DEC-RV-05 前仅 agentId==null 门控，extract 会执行）。
        //   本测试锚定：两处 stopAborted 分支都必须写 STOP_HOOK_PREVENTED，且不得写 ABORTED。
        String source = Files.readString(Path.of(LLM_LOOP_PATH));
        int firstAbortIdx = source.indexOf("if (stopAborted) {");
        assertThat(firstAbortIdx)
            .as("必须存在 stopAborted 分支（in-loop）")
            .isGreaterThan(-1);
        int secondAbortIdx = source.indexOf("if (stopAborted) {", firstAbortIdx + 1);
        assertThat(secondAbortIdx)
            .as("必须存在第二处 stopAborted 分支（in-loop + §14 两路径）")
            .isGreaterThan(firstAbortIdx);
        // [IMP-HOOKS-S5 D-11] 重构：两处 stopAborted 的 else 分支从旧 `} else if (stopResult.blockingError()`
        //   折叠判断改为 `} else {` + 内部循环注入全部 blockingError（CC stopHooks.ts:257-267
        //   blockingErrors.push → query.ts:1274-1277 全部 append user message），故分支结束锚改用
        //   `} else {`。两分支体内部均无 `} else {` 文本（stopAborted 命中即 break/设 reason），锚定安全。
        String firstBranch = source.substring(firstAbortIdx,
            source.indexOf("} else {", firstAbortIdx));
        String secondBranch = source.substring(secondAbortIdx,
            source.indexOf("} else {", secondAbortIdx));
        assertThat(firstBranch)
            .as("in-loop stopAborted 必须映射 STOP_HOOK_PREVENTED（CC query.ts:1278-1279）")
            .contains("state.setExitReason(ExitReason.STOP_HOOK_PREVENTED);")
            .doesNotContain("state.setExitReason(ExitReason.ABORTED);");
        assertThat(secondBranch)
            .as("§14 stopAborted 必须映射 STOP_HOOK_PREVENTED（CC query.ts:1278-1279）")
            .contains("state.setExitReason(ExitReason.STOP_HOOK_PREVENTED);")
            .doesNotContain("state.setExitReason(ExitReason.ABORTED);");
    }
}
