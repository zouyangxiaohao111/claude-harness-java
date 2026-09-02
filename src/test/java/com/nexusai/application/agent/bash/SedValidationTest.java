package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nexusai.application.agent.permission.PermissionDecisionReason;
import com.nexusai.application.agent.permission.PermissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SedValidation.checkSedConstraints 行为回归测试 · 验证 WHY=危险 sed 写操作必须 ask.
 *
 * <p>WHY 本测试存在: 本 session 把自有 {@code PermissionResult} record 统一为
 * {@code permission.PermissionResult}（CC 契约），改型不改语义。
 * ask 必须携带 decisionReason {@code {type:'other', reason}}（CC sedValidation.ts:666-677），
 * 供上层审计/弹窗归因。
 */
class SedValidationTest {

    @Test
    @DisplayName("危险 sed 写命令 → Ask（decisionReason.type='other'）")
    void dangerousSedCommandAsks() {
        // WHY: CC sedValidation.ts:666-677 白名单外（含写文件操作 w）必须 ask，
        // 不能静默放行或 passthrough。
        PermissionResult r = SedValidation.checkSedConstraints("sed 'w file'", "default");
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionDecisionReason.Other other = assertInstanceOf(PermissionDecisionReason.Other.class, ask.reason());
        assertEquals("sed command contains operations that require explicit approval "
            + "(e.g., write commands, execute commands)", other.reason());
    }

    @Test
    @DisplayName("非 sed 命令 → Passthrough")
    void nonSedCommandPassthrough() {
        // WHY: 只拦 sed，ls 等命令交给上层通用权限管线（CC sedValidation.ts:679-684）。
        assertInstanceOf(PermissionResult.Passthrough.class,
            SedValidation.checkSedConstraints("ls -la", "default"));
    }

    @Test
    @DisplayName("sed -n 只读行打印 → Passthrough")
    void readOnlyLinePrintingPassthrough() {
        // WHY: 只读操作不需要批准（CC isLinePrintingCommand 放行）。
        assertInstanceOf(PermissionResult.Passthrough.class,
            SedValidation.checkSedConstraints("sed -n '1p' file", "default"));
    }

    @Test
    @DisplayName("acceptEdits 下 sed -i 就地编辑 → Passthrough")
    void acceptEditsInPlaceEditPassthrough() {
        // WHY: CC sedValidation.ts:654-656 allowFileWrites = mode === 'acceptEdits'，
        // -i 就地编辑在 acceptEdits 模式放行但危险操作仍拦。
        assertInstanceOf(PermissionResult.Passthrough.class,
            SedValidation.checkSedConstraints("sed -i 's/foo/bar/' file", "acceptEdits"));
    }

    @Test
    @DisplayName("&& 复合后危险 sed → Ask（splitter 须切 &&，否则 deny→allow 反向安全分歧）")
    void compoundAmpersandSedAsks() {
        // WHY: CC splitCommand_DEPRECATED（commands.ts:265，COMMAND_LIST_SEPARATORS:523 含 &&）把
        //      "echo hi && sed 'w file'" 切成 ["echo hi", "sed 'w file'"]，第二段危险 sed → ask。
        //      Java 旧 splitOnTopLevel 仅切顶层 ;| 不切 & → 整串 1 段 baseCmd=echo 被跳过 → passthrough，
        //      危险 sed 写命令静默放行（deny→allow 反向安全分歧）。本测试锁定 splitter 必须切 &&。
        PermissionResult r = SedValidation.checkSedConstraints("echo hi && sed 'w file'", "default");
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionDecisionReason.Other other =
            assertInstanceOf(PermissionDecisionReason.Other.class, ask.reason());
        assertEquals("sed command contains operations that require explicit approval "
            + "(e.g., write commands, execute commands)", other.reason());
    }

    @Test
    @DisplayName("s/…/…/e 执行替换 → Ask（e 是 shell 执行危险标志）")
    void substitutionExecFlagAsks() {
        // WHY: sed 的 e 标志把替换结果当 shell 命令执行，属 CC sedValidation.ts:557-563
        //      偏执 s-command 拦截（结尾 e）。即使文件参数已在无 -i 时触发拦截，
        //      本断言证明 denylist 本身对 e 标志也命中（不依赖文件参数路径）。
        PermissionResult r = SedValidation.checkSedConstraints("sed 's/foo/bar/e' file", "default");
        PermissionResult.Ask ask = assertInstanceOf(PermissionResult.Ask.class, r);
        PermissionDecisionReason.Other other = assertInstanceOf(PermissionDecisionReason.Other.class, ask.reason());
        assertEquals("sed command contains operations that require explicit approval "
            + "(e.g., write commands, execute commands)", other.reason());
        // denylist 直接命中：替换结尾 e 是执行标志（独立于文件参数逻辑）
        assertTrue(SedValidation.containsDangerousOperations("s/foo/bar/e"));
    }
}
