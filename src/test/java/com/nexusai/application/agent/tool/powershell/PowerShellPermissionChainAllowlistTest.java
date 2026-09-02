package com.nexusai.application.agent.tool.powershell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * isAllowlistedCommand 公共参数 continue 跳过测试 · 对齐 CC readOnlyValidation.ts:1502-1504。
 *
 * <p>WHY（意图验证）：
 * <ul>
 *   <li>{@code -ErrorAction}/{@code -Verbose}/{@code -Debug} 经 [CmdletBinding()] 被每个 cmdlet 接受，
 *       只路由 error/warning/progress 流，不能让只读 cmdlet 写。CC 在 safeFlags 校验前用
 *       {@code COMMON_PARAMETERS.has(paramLower)} 直接 continue（readOnlyValidation.ts:1502-1504）——
 *       没有此跳过，{@code Get-Content file.txt -ErrorAction SilentlyContinue} 会因 -ErrorAction
 *       不在 get-content 的 safeFlags 而误 prompt。</li>
 *   <li>防 fail-open：公共参数跳过只覆盖 CC 明确列出的 12 个公共参数（commonParameters.ts:12-30），
 *       未知 flag（如 {@code -Foo}）仍须落 safeFlags 校验返回 false——不能因补公共参数而把
 *       safeFlags 白名单整体放空。</li>
 * </ul>
 *
 * <p>直接调用包私有 {@link PowerShellPermissionChain#isAllowlistedCommand}（不启动 pwsh，
 * 不依赖真实 AST），只测 flag 循环内的公共参数 continue 分支。
 */
class PowerShellPermissionChainAllowlistTest {

    private final PowerShellPermissionChain chain = new PowerShellPermissionChain(new PowerShellAstService());

    /** 命令元素构建：args 与 elementTypes 一一对应，命令名占 elementTypes[0]。 */
    private static PowerShellAstService.CommandElement cmd(String name, List<String> args,
                                                           List<String> argElementTypes) {
        String text = name + (args.isEmpty() ? "" : " " + String.join(" ", args));
        List<String> elementTypes = new ArrayList<>();
        elementTypes.add("StringConstant"); // 命令名元素类型
        elementTypes.addAll(argElementTypes);
        return new PowerShellAstService.CommandElement(name, "cmdlet", "CommandAst", args, elementTypes, text, List.of(), List.of());
    }

    @Test
    @DisplayName("公共参数 -ErrorAction 值参数命中只读 allowlist（CC readOnlyValidation.ts:1502-1504）")
    void errorActionCommonParamIsSkipped() {
        // Get-Content file.txt -ErrorAction SilentlyContinue：-ErrorAction 不在 get-content
        // safeFlags，但属 COMMON_PARAMETERS → continue 跳过，命令仍只读放行。
        PowerShellAstService.CommandElement c = cmd("Get-Content",
            List.of("file.txt", "-ErrorAction", "SilentlyContinue"),
            List.of("StringConstant", "Parameter", "StringConstant"));
        assertTrue(chain.isAllowlistedCommand(c),
            "-ErrorAction 是 [CmdletBinding()] 公共参数，只路由错误流，不能使只读 cmdlet 写，必须 continue 跳过");
    }

    @Test
    @DisplayName("公共 switch -Verbose 命中只读 allowlist（CC commonParameters.ts:12 COMMON_SWITCHES）")
    void verboseSwitchIsSkipped() {
        PowerShellAstService.CommandElement c = cmd("Get-Content", List.of("-Verbose"), List.of("Parameter"));
        assertTrue(chain.isAllowlistedCommand(c),
            "-Verbose 是公共 switch（COMMON_SWITCHES），不能使只读 cmdlet 写，必须 continue 跳过");
    }

    @Test
    @DisplayName("公共 switch -Debug 命中只读 allowlist（CC commonParameters.ts:12 COMMON_SWITCHES）")
    void debugSwitchIsSkipped() {
        PowerShellAstService.CommandElement c = cmd("Get-Content", List.of("-Debug"), List.of("Parameter"));
        assertTrue(chain.isAllowlistedCommand(c),
            "-Debug 是公共 switch（COMMON_SWITCHES），不能使只读 cmdlet 写，必须 continue 跳过");
    }

    @Test
    @DisplayName("未知 flag -Foo 仍拒绝（防 fail-open，CC readOnlyValidation.ts:1506-1510 safeFlags 校验）")
    void unknownFlagStillRejected() {
        // -Foo 不在 COMMON_PARAMETERS，也不在 get-content safeFlags → 必须返回 false，
        // 证明公共参数 continue 没有把 safeFlags 白名单整体放空。
        PowerShellAstService.CommandElement c = cmd("Get-Content", List.of("-Foo"), List.of("Parameter"));
        assertFalse(chain.isAllowlistedCommand(c),
            "未知 flag 不得因补公共参数跳过而被放行——safeFlags 白名单校验必须保留");
    }
}
