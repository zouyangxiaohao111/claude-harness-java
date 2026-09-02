package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellTool 同步只读契约测试 · 验证 isReadOnly / isConcurrencySafe 对齐 CC
 * {@code PowerShellTool.tsx:283-315} 同步契约。
 *
 * <p><b>WHY（意图验证，规则九）</b>：
 * <ul>
 *   <li>CC 同步 {@code isReadOnly(input)} 是两步结构：{@code hasSyncSecurityConcerns} 前置正则
 *       （7 模式，readOnlyValidation.ts:1112-1159）→ 无 AST 的 {@code isReadOnlyCommand}
 *       （`if (!parsed) return false` 恒保守 false，readOnlyValidation.ts:1174-1177）。</li>
 *   <li>Java 端原无 isReadOnly/isConcurrencySafe override（继承 Tool default 恒 false），
 *       hasSyncSecurityConcerns 生产 0 调用（RV-D-02 NG-1 / RV-D-04 NG-PD-2 假接线）。</li>
 *   <li>本测试锁契约面：override 存在（declaringClass == PowerShellTool，而非 Tool）、
 *       isConcurrencySafe 委托 isReadOnly（CC :286）、危险命令经安全预检短路、
 *       只读 allowlist 命令同步契约仍保守 false（CC 无 AST 语义，真实 auto-allow 走异步链）。</li>
 * </ul>
 *
 * <p>工具实例用 no-arg {@code new PowerShellTool()}（自建链），isReadOnly 只走正则 +
 * 无 AST 重载（不触发 pwsh 解析），测试不依赖 pwsh 运行时。
 */
class PowerShellToolReadOnlyContractTest {

    private static ObjectNode input(String command) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("command", command);
        return node;
    }

    /** 单参工具实例（no-arg 自建链，不触发 pwsh 解析）。 */
    private static PowerShellTool tool() {
        return new PowerShellTool();
    }

    /**
     * NG-1 闭环：isReadOnly 必须由 PowerShellTool 声明（override），而非继承 Tool default。
     * RED = 无 override 时 getDeclaringClass() == Tool.class；GREEN = PowerShellTool.class。
     */
    @Test
    @DisplayName("isReadOnly 同步契约由 PowerShellTool 声明（NG-1 闭环，非 Tool default）")
    void isReadOnly_syncContract_declaredByPowerShellTool() throws NoSuchMethodException {
        Method m = PowerShellTool.class.getMethod("isReadOnly", JsonNode.class);
        assertEquals(PowerShellTool.class, m.getDeclaringClass(),
            "isReadOnly 必须由 PowerShellTool override（对齐 CC PowerShellTool.tsx:300-315），"
                + "而非继承 Tool.java:321 default false");
    }

    /**
     * RV-D-04 NG-PD-2 / RV-D-02 NG-1 闭环：isConcurrencySafe 由 PowerShellTool 声明
     * 且委托 isReadOnly（CC :286 `isConcurrencySafe(input) { return isReadOnly?.(input) ?? false }`）。
     */
    @Test
    @DisplayName("isConcurrencySafe 由 PowerShellTool 声明并委托 isReadOnly（CC :286）")
    void isConcurrencySafe_delegatesToIsReadOnly() throws NoSuchMethodException {
        Method m = PowerShellTool.class.getMethod("isConcurrencySafe", JsonNode.class);
        assertEquals(PowerShellTool.class, m.getDeclaringClass(),
            "isConcurrencySafe 必须由 PowerShellTool override（对齐 CC PowerShellTool.tsx:283-286）");

        PowerShellTool tool = tool();
        String[] commands = {
            "Get-Process",                 // allowlist 只读
            "$(Get-Process)",              // 子表达式（危险模式①）
            "Get-Process @splat",          // splatting（危险模式②）
            "$x.ToString()",               // 成员调用（危险模式③）
            "$x = 1",                      // 赋值（危险模式④）
            "Get-Content --% raw",         // stop-parsing（危险模式⑤）
            "Get-ChildItem \\\\server\\share", // UNC 反斜杠（危险模式⑥）
            "Get-Item //server/share",     // UNC 正斜杠（危险模式⑥）
            "[System.IO.Path]::GetTempPath()" // 静态方法调用（危险模式⑦）
        };
        for (String cmd : commands) {
            JsonNode in = input(cmd);
            assertEquals(tool.isReadOnly(in), tool.isConcurrencySafe(in),
                "isConcurrencySafe 必须委托 isReadOnly（CC PowerShellTool.tsx:286），命令=" + cmd);
        }
    }

    /**
     * 行为文档：7 个危险模式命令经 hasSyncSecurityConcerns 前置短路 → isReadOnly false。
     * 配合 {@link #isReadOnly_syncContract_declaredByPowerShellTool} 证明生产路径真实走安全预检
     * （hasSyncSecurityConcerns 经工具层可达，闭环 EV-RV-D-PP-019）。
     */
    @Test
    @DisplayName("危险模式命令经 hasSyncSecurityConcerns 前置短路 → isReadOnly false")
    void isReadOnly_dangerousCommand_securityPreCheckShortCircuits() {
        PowerShellTool tool = tool();
        String[] dangerous = {
            "echo $(Get-Process)",
            "Get-Process @splat",
            "$x.ToString()",
            "$x = 1",
            "Get-Content --% raw",
            "Get-ChildItem \\\\server\\share",
            "Get-Item //server/share",
            "[System.IO.Path]::GetTempPath()"
        };
        for (String cmd : dangerous) {
            assertFalse(tool.isReadOnly(input(cmd)),
                "危险模式命令同步 isReadOnly 必须 false（hasSyncSecurityConcerns 短路），命令=" + cmd);
        }
    }

    /**
     * 行为文档：allowlist 只读命令同步契约仍恒 false（CC readOnlyValidation.ts:1174-1177
     * `if (!parsed) return false`——无 AST 保守 false）。真实只读 auto-allow 走异步链
     * PowerShellPermissionChain.check():230 AST 版 isReadOnlyCommand，不重复实现。
     */
    @Test
    @DisplayName("allowlist 只读命令同步契约恒 false（CC 无 AST 保守语义）")
    void isReadOnly_readOnlyAllowlistCommand_conservativeFalseNoAst() {
        PowerShellTool tool = tool();
        String[] allowlistReadOnly = {
            "Get-Process",
            "Get-ChildItem",
            "Get-Content",
            "Get-Service",
            "Get-Command"
        };
        for (String cmd : allowlistReadOnly) {
            assertFalse(tool.isReadOnly(input(cmd)),
                "同步 isReadOnly 无 AST 恒 false（CC readOnlyValidation.ts:1174-1177），命令=" + cmd);
        }
    }

    /** 空 / 缺 command 输入同步契约返回 false（CC hasSyncSecurityConcerns 空串 → false，无 AST → false）。 */
    @Test
    @DisplayName("空或缺失 command 输入 isReadOnly 返回 false")
    void isReadOnly_nullOrBlankCommand_returnsFalse() {
        PowerShellTool tool = tool();
        assertFalse(tool.isReadOnly(input("")), "空命令 isReadOnly 恒 false");
        assertFalse(tool.isReadOnly(null), "null input isReadOnly 恒 false");
        assertTrue(tool.isConcurrencySafe(null) == tool.isReadOnly(null),
            "null input isConcurrencySafe 与 isReadOnly 一致");
    }
}
