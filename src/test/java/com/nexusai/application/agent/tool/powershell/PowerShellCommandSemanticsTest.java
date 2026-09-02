package com.nexusai.application.agent.tool.powershell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PowerShellCommandSemantics 聚焦单测 · 验证外部可执行文件退出码语义（CC PowerShellTool/commandSemantics.ts）。
 *
 * <p><b>WHY（意图验证，非仅行为）</b>：PowerShell 原生 cmdlet 失败不走退出码；但 grep.exe / findstr.exe /
 * robocopy.exe 用非零码表达「无匹配 / 复制成功 / 已同步」等信息而非失败。若无此语义，
 * {@code robocopy} 的「文件复制成功（exit 1）」会被 PowerShellTool 误抛 ShellError —— 这是 Windows
 * 「CI 失败但实际没出错」的头号坑（CC commandSemantics.ts:79 注释）。本测试锁定：
 * <ul>
 *   <li>grep/findstr exit 1 = 无匹配（非 error），exit 2+ = error</li>
 *   <li>robocopy 位域：0-7 成功、8+ error、1 = 复制成功、0 = 已同步</li>
 *   <li>未识别命令走 DEFAULT（0 以外 error）</li>
 *   <li>extractBaseCommand 剥 &/. 调用操作符 + 引号 + 路径 + .exe 后缀</li>
 *   <li>heuristicallyExtractBaseCommand 按 ;| 切段取末段</li>
 * </ul>
 */
class PowerShellCommandSemanticsTest {

    private final PowerShellCommandSemantics semantics = new PowerShellCommandSemantics();

    @Test
    @DisplayName("grep exit 1 → 非 error（'No matches found'，无匹配≠失败）")
    void grepExit1IsNotError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("grep foo file.txt", 1, "", "");
        assertFalse(r.isError(), "grep exit 1 = 无匹配，不得当作错误（CC GREP_SEMANTIC）");
        assertEquals("No matches found", r.message());
    }

    @Test
    @DisplayName("grep exit 2 → error（语法/IO 错误）")
    void grepExit2IsError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("grep foo file.txt", 2, "", "");
        assertTrue(r.isError(), "grep exit 2+ = 错误（CC GREP_SEMANTIC isError=exit>=2）");
    }

    @Test
    @DisplayName("findstr exit 1 → 非 error（Windows 原生文本搜索无匹配）")
    void findstrExit1IsNotError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("findstr foo file.txt", 1, "", "");
        assertFalse(r.isError(), "findstr exit 1 = 无匹配（CC :69 findstr → GREP_SEMANTIC）");
        assertEquals("No matches found", r.message());
    }

    @Test
    @DisplayName("robocopy exit 1 → 非 error（'Files copied successfully'，位域 bit0）")
    void robocopyExit1IsNotError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("robocopy src dst", 1, "", "");
        assertFalse(r.isError(), "robocopy exit 1 = 文件复制成功，不得误判失败（CC 位域语义）");
        assertEquals("Files copied successfully", r.message());
    }

    @Test
    @DisplayName("robocopy exit 0 → 非 error（'No files copied (already in sync)'）")
    void robocopyExit0AlreadyInSync() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("robocopy src dst", 0, "", "");
        assertFalse(r.isError());
        assertEquals("No files copied (already in sync)", r.message());
    }

    @Test
    @DisplayName("robocopy exit 2 → 非 error（'Robocopy completed (no errors)'，位域非 bit0）")
    void robocopyExit2NotError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("robocopy src dst", 2, "", "");
        assertFalse(r.isError(), "robocopy exit 2 = 检测到额外文件（无复制），非错误");
        assertEquals("Robocopy completed (no errors)", r.message());
    }

    @Test
    @DisplayName("robocopy exit 8 → error（复制错误，位域 bit3）")
    void robocopyExit8IsError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("robocopy src dst", 8, "", "");
        assertTrue(r.isError(), "robocopy exit 8 = 复制错误（CC isError=exit>=8）");
    }

    @Test
    @DisplayName("robocopy exit 16 → error（严重错误）")
    void robocopyExit16IsError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("robocopy src dst", 16, "", "");
        assertTrue(r.isError(), "robocopy exit 16 = 严重错误（CC isError=exit>=8）");
    }

    @Test
    @DisplayName("未知命令 exit 7 → error（DEFAULT_SEMANTIC：0 以外都失败）")
    void unknownCommandExit7IsError() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("Set-Thing -Force", 7, "", "");
        assertTrue(r.isError(), "未识别命令走 DEFAULT_SEMANTIC（exit != 0 → error）");
        assertEquals("Command failed with exit code 7", r.message());
    }

    @Test
    @DisplayName("'& \"grep.exe\"' 剥 .exe/引号/调用操作符后命中 grep 语义（exit 1 非 error）")
    void invocationOperatorExeStripped() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("& \"grep.exe\" foo file.txt", 1, "", "");
        assertFalse(r.isError(), "extractBaseCommand 应剥 & 调用操作符 + 引号 + .exe → grep");
        assertEquals("No matches found", r.message());
    }

    @Test
    @DisplayName("'a | robocopy ...' 管道取末段 robocopy（exit 1 非 error）")
    void pipelineLastSegment() {
        PowerShellCommandSemantics.Result r = semantics.interpretCommandResult("Get-ChildItem | robocopy src dst", 1, "", "");
        assertFalse(r.isError(), "heuristicallyExtractBaseCommand 按 | 切段取末段 robocopy");
        assertEquals("Files copied successfully", r.message());
    }

    @Test
    @DisplayName("extractBaseCommand：路径 basename + .exe 剥离（C:\\bin\\rg.exe → rg）")
    void extractBaseCommandPathAndExe() {
        assertEquals("rg", PowerShellCommandSemantics.extractBaseCommand("C:\\bin\\rg.exe -n foo"));
        assertEquals("findstr", PowerShellCommandSemantics.extractBaseCommand(".\\findstr.exe foo"));
        assertEquals("grep", PowerShellCommandSemantics.extractBaseCommand("& 'grep' foo"));
    }

    @Test
    @DisplayName("grep exit 1 message 非 null（有语义），grep exit 0 message null（无特殊语义）")
    void messageOnlyWhenMeaningful() {
        assertNull(semantics.interpretCommandResult("grep foo f", 0, "", "").message(),
            "exit 0 成功无特殊 message");
        assertNull(semantics.interpretCommandResult("grep foo f", 3, "", "").message(),
            "exit 3 error 亦无特殊 message（isError 已表达错误）");
    }
}
