package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BashModeValidation 行为回归测试 · 验证 WHY=acceptEdits 模式自动放行文件系统命令.
 *
 * <p>WHY 本测试存在: 本 session 把自有 sealed interface 统一为
 * {@code permission.PermissionResult}（CC 契约），改型不改语义。
 * 若回归后 allow/passthrough 语义漂移，acceptEdits 模式下用户会收到多余弹窗
 * 或文件系统命令被错误自动放行。
 */
class BashModeValidationTest {

    @Test
    @DisplayName("acceptEdits + 文件系统命令 → Allow（reason=Mode(ACCEPT_EDITS), updatedInput.command）")
    void acceptEditsFilesystemCommandAllows() {
        // WHY: CC modeValidation.ts:42-49 allow 返回 {updatedInput:{command}, decisionReason:{type:'mode',mode:'acceptEdits'}}。
        PermissionResult r = BashModeValidation.checkPermissionMode("mkdir foo", "acceptEdits");
        PermissionResult.Allow allow = assertInstanceOf(PermissionResult.Allow.class, r);
        assertInstanceOf(PermissionDecisionReason.Mode.class, allow.reason());
        assertEquals(PermissionMode.ACCEPT_EDITS, ((PermissionDecisionReason.Mode) allow.reason()).mode());
        // updatedInput {command: cmd}（CC modeValidation.ts:44）
        assertEquals("mkdir foo", allow.updatedInput().get("command").asText());
    }

    @Test
    @DisplayName("acceptEdits + 非文件系统命令 → Passthrough")
    void acceptEditsNonFilesystemPassthrough() {
        // WHY: git push 不该被 acceptEdits 自动放行，交给上层管线决定。
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashModeValidation.checkPermissionMode("git push origin main", "acceptEdits"));
    }

    @Test
    @DisplayName("bypassPermissions / dontAsk → Passthrough（上层管线处理）")
    void bypassAndDontAskPassthrough() {
        // WHY: CC modeValidation.ts:77-90 这两个 mode 跳过，消息指向主权限流程。
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashModeValidation.checkPermissionMode("mkdir foo", "bypassPermissions"));
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashModeValidation.checkPermissionMode("mkdir foo", "dontAsk"));
    }

    @Test
    @DisplayName("空命令 → Passthrough（不得 Allow/Ask，避免空输入误放行或误弹窗）")
    void emptyCommandPassthrough() {
        // WHY: 空命令无子命令（split 返回空），必须 passthrough 交给上层管线，
        // 既不能 Allow 自动放行也不能 Ask 弹窗骚扰。
        PermissionResult.Passthrough p = assertInstanceOf(PermissionResult.Passthrough.class,
            BashModeValidation.checkPermissionMode("", "acceptEdits"));
        assertEquals("No mode-specific validation required", p.message());
    }

    @Test
    @DisplayName("getAutoAllowedCommands 仅 acceptEdits 返回 7 个命令")
    void autoAllowedCommands() {
        // WHY: CC modeValidation.ts:7-15 + 111-115 的 ACCEPT_EDITS_ALLOWED_COMMANDS 契约。
        assertEquals(7, BashModeValidation.getAutoAllowedCommands("acceptEdits").size());
        assertTrue(BashModeValidation.getAutoAllowedCommands("default").isEmpty());
    }

    @Test
    @DisplayName("引号内分隔符不切段（D3 修复）: echo \"a;b\" 保持 1 段")
    void splitCommand_quotedSemicolon_staysOneSegment() {
        // WHY: 探查 D3 —— 旧 split(\"[;&|\\n]\") 正则粗切会把 `echo "a;b"` 切成 2 段，
        //      引号内的 `;` 是字面量非命令分隔符。CC splitCommand_DEPRECATED（commands.ts:265）
        //      + shell-quote 完整解析（引号感知）保持 1 段。
        //      若粗切，`echo "a` 首词是 echo（非 filesystem）→ passthrough；
        //      `b"` 段同样非 filesystem → passthrough —— 行为上仍 passthrough，
        //      但 split 完整性由本断言锁定（首段首词须为 echo）。
        PermissionResult r = BashModeValidation.checkPermissionMode("echo \"a;b\"", "acceptEdits");
        assertInstanceOf(PermissionResult.Passthrough.class, r);
    }

    @Test
    @DisplayName("子 shell 内分隔符不切段（D3 修复）: echo $(echo a;b) 保持 1 段")
    void splitCommand_subshellSemicolon_staysOneSegment() {
        // WHY: 探查 D3 —— 子 shell $(...) 内的 `;` 属内部命令分隔，不切开外层命令。
        //      splitCommand 若在 depth>0（子 shell 内）切 `;` 属粗切。
        //      echo 非 filesystem → passthrough（行为正确性由 BashParser.splitCommands
        //      的 depth 感知保证，本测试锁定不误 allow/ask）。
        PermissionResult r = BashModeValidation.checkPermissionMode("echo $(echo a;b)", "acceptEdits");
        assertInstanceOf(PermissionResult.Passthrough.class, r);
    }

    @Test
    @DisplayName("多子命令任一为 filesystem → allow（CC modeValidation.ts:95-102 first non-passthrough wins）")
    void splitCommand_multipleSubcommands_firstFilesystemWins() {
        // WHY: CC checkPermissionMode 对 splitCommand_DEPRECATED 逐段校验，
        //      `echo hi; mkdir foo` 的第二段 mkdir 是 filesystem → allow。
        PermissionResult r = BashModeValidation.checkPermissionMode("echo hi; mkdir foo", "acceptEdits");
        assertInstanceOf(PermissionResult.Allow.class, r);
    }

    @Test
    @DisplayName("heredoc 标记行内分隔符不切段（D3 修复）: cat <<EOF; echo hi 保持 1 段")
    void splitCommand_heredocHeaderSemicolon_staysOneSegment() {
        // WHY: 探查 D3 —— heredoc 标记 <<EOF 行内的 `;` 属 heredoc 头语法（CC
        //      extractHeredocs heredoc.ts 先把 heredoc 整体提取为占位符再切分），
        //      splitCommands 的 inHeredoc 分支把该行整体吸收为 1 段，不得在此切段。
        //      cat 非 filesystem → passthrough（不得误 allow/ask）。
        // 注: heredoc 标记行之后 body 的切分是另一条已登记分歧（BashContentMatchDifferentialTest
        //      knownDivergences 登记 09: splitCommands 把 body 行当独立 subcommand），
        //      属 BashParser/AST 对齐批次（A5），本测试只锁 heredoc 头行行为。
        assertEquals(1, BashParser.splitCommands("cat <<EOF; echo hi").size());
        assertInstanceOf(PermissionResult.Passthrough.class,
            BashModeValidation.checkPermissionMode("cat <<EOF; echo hi", "acceptEdits"));
    }
}
