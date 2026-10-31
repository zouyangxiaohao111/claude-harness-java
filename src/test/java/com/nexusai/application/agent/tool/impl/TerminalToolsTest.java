package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.terminal.TerminalInfo;
import com.nexusai.application.terminal.TerminalService;
import com.nexusai.infra.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 终端四工具的单测 · <b>不启 Spring、不碰 DB、不真起 PTY</b>。
 *
 * <p><b>WHY 用 Mockito 桩 {@link TerminalService} 而不是起真 PTY</b>：
 * <ol>
 *   <li>真 PTY 的可用性取决于本机原生库，已有 {@code TerminalServiceSmokeTest} 专测那一层；</li>
 *   <li>本类要验证的是<b>工具侧契约</b>——文案、默认终端选择、行数钳制、开关可见性、审批分类。
 *       这些用桩能<b>确定性地断言「传给 TerminalService 的参数」</b>（例如 lines 是否真的钳到 500），
 *       真 PTY 只能观察到副作用、观测不到入参。</li>
 * </ol>
 *
 * <p><b>意图（不只是行为）</b>：
 * <ul>
 *   <li>{@code terminal_read} 的 {@code lines} 上限 500 是<b>上下文保护</b>——终端缓冲最多 1000 行，
 *       不钳制等于让模型一次吃满缓冲；断言的是「下发给 TerminalService 的值」，不是返回值长短。</li>
 *   <li>审批分类断言 {@code Passthrough}（不是 {@code Allow}）：写工具一旦返回 Allow
 *       就是<b>无条件执行</b>，绕过用户审批 —— 这是本批的安全红线。</li>
 *   <li>开关断言含<b>总开关语义</b>：读关掉 ⇒ 写也隐藏（写终端却不允许读，比全关更危险）。</li>
 * </ul>
 */
class TerminalToolsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-terminal-tools-test";

    private TerminalService service;

    private TerminalListTool listTool;
    private TerminalReadTool readTool;
    private TerminalCreateTool createTool;
    private TerminalWriteTool writeTool;

    private ToolUseContext ctx;

    @BeforeEach
    void setUp() {
        service = mock(TerminalService.class);
        listTool = new TerminalListTool(service, true);
        readTool = new TerminalReadTool(service, true);
        createTool = new TerminalCreateTool(service, true, true);
        writeTool = new TerminalWriteTool(service, true, true);
        // 显式 effectiveCwd：避免 ToolUseContext 构造器走 CwdResolution 的会话/DB 解析
        ctx = ToolUseContext.of(null, SESSION, PermissionMode.DEFAULT, List.of(), "",
            AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT, Map.of(), false, "",
            Path.of(System.getProperty("user.home")));
    }

    // ───────────────────────── 只读：terminal_list ─────────────────────────

    @Test
    @DisplayName("terminal_list 无终端 → 返回指定文案（不返回空串，也不伪造终端）")
    void listWithoutTerminals() {
        when(service.list(SESSION)).thenReturn(List.of());

        String text = textOf(listTool.execute(call("terminal_list", "{}"), ctx));
        assertThat(text)
            .as("会话无终端必须给出可读文案；空串会让模型以为工具坏了")
            .isEqualTo("本会话还没有终端（终端由用户手动创建，Agent 不能创建）");
    }

    @Test
    @DisplayName("terminal_list 有终端 → 每条一行：id | shell短名 | 运行中/已退出 | cwd | 启动于")
    void listFormatsEachTerminalOnOneLine() {
        when(service.list(SESSION)).thenReturn(List.of(
            new TerminalInfo("term-aaaa1111", "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
                "D:\\proj", 120, 30, true, "2026-10-29T10:00:00Z"),
            new TerminalInfo("term-bbbb2222", "/bin/bash", "/home/u/proj", 80, 24, false,
                "2026-10-29T11:00:00Z")));

        String text = textOf(listTool.execute(call("terminal_list", "{}"), ctx));

        assertThat(text).isEqualTo(
            "term-aaaa1111 | powershell | 运行中 | cwd=D:\\proj | 启动于 2026-10-29T10:00:00Z\n"
                + "term-bbbb2222 | bash | 已退出 | cwd=/home/u/proj | 启动于 2026-10-29T11:00:00Z");
        assertThat(text).as("已退出终端必须显示「已退出」，不能和运行中混淆").contains("已退出");
    }

    // ───────────────────────── 只读：terminal_read ─────────────────────────

    @Test
    @DisplayName("terminal_read 指定不存在的终端 → 未找到该终端：<id>（且不读快照）")
    void readUnknownTerminalId() {
        when(service.list(SESSION)).thenReturn(List.of(termA()));

        String text = textOf(readTool.execute(call("terminal_read", "{\"terminalId\":\"term-nope\"}"), ctx));

        assertThat(text).isEqualTo("未找到该终端：term-nope");
        verify(service, never()).snapshot(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("terminal_read 缺省取最近启动的终端，且 lines 超上限时钳到 500（下发值可断言）")
    void readDefaultsToMostRecentAndClampsLines() {
        TerminalInfo older = new TerminalInfo("term-aaaa1111", "/bin/bash", "/proj", 80, 24, true,
            "2026-10-29T10:00:00Z");
        TerminalInfo newer = new TerminalInfo("term-bbbb2222", "/bin/zsh", "/proj", 80, 24, true,
            "2026-10-29T11:00:00Z");
        when(service.list(SESSION)).thenReturn(List.of(older, newer));
        when(service.snapshot(SESSION, "term-bbbb2222", 500)).thenReturn("ready in 300ms");

        String text = textOf(readTool.execute(call("terminal_read", "{\"lines\":9999}"), ctx));

        assertThat(text).as("缺省终端 = list() 最后一条（最近启动）")
            .startsWith("terminal=term-bbbb2222 ");
        assertThat(text).contains("ready in 300ms");
        verify(service).snapshot(SESSION, "term-bbbb2222", 500);
    }

    @Test
    @DisplayName("terminal_read 首行元信息：terminal/shell短名/alive/cwd，其后是被请求的末 N 行")
    void readMetaLineAndBody() {
        when(service.list(SESSION)).thenReturn(List.of(termA()));
        when(service.snapshot(SESSION, "term-aaaa1111", 100)).thenReturn("vite v5\nerror: build failed");

        String text = textOf(readTool.execute(call("terminal_read", "{}"), ctx));

        assertThat(text).isEqualTo(
            "terminal=term-aaaa1111 shell=bash alive=true cwd=/proj\nvite v5\nerror: build failed");
        // 缺省 lines = 100（默认值同样要能断言，否则「默认 100」只是注释里的谎话）
        verify(service).snapshot(SESSION, "term-aaaa1111", 100);
    }

    // ───────────────────────── 写：terminal_create ─────────────────────────

    @Test
    @DisplayName("terminal_create 正常 → 调 create(session, shell=null 缺省, 0/0 由后端回落默认) 并回显 id/shell/cwd")
    void createSucceeds() {
        when(service.create(SESSION, null, 0, 0)).thenReturn(
            new TerminalInfo("term-new00001", "/bin/zsh", "/proj", 80, 24, true, "2026-10-29T12:00:00Z"));

        String text = textOf(createTool.execute(call("terminal_create", "{}"), ctx));

        assertThat(text).isEqualTo("已创建终端 id=term-new00001 shell=zsh cwd=/proj");
        // cwd 由后端解析：工具不得把 cwd 透传给 TerminalService（create 只有 4 个参数，这里锁死调用形态）
        verify(service).create(eq(SESSION), isNull(), eq(0), eq(0));
    }

    // ───────────────────────── 写：terminal_write ─────────────────────────

    @Test
    @DisplayName("terminal_write 会话无终端 → 明确提示（本工具不创建终端）；指定不存在 id → 未找到该终端")
    void writeToMissingTerminal() {
        when(service.list(SESSION)).thenReturn(List.of());

        String noTerminal = textOf(writeTool.execute(
            call("terminal_write", "{\"data\":\"npm run dev\"}"), ctx));
        assertThat(noTerminal)
            .as("必须告诉模型两条出路：让用户开，或先调 terminal_create")
            .contains("terminal_create");
        verify(service, never()).write(anyString(), anyString(), anyString());

        when(service.list(SESSION)).thenReturn(List.of(termA()));
        String unknown = textOf(writeTool.execute(
            call("terminal_write", "{\"terminalId\":\"term-nope\",\"data\":\"npm run dev\"}"), ctx));
        assertThat(unknown).isEqualTo("未找到该终端：term-nope");
        verify(service, never()).write(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("terminal_write 缺省 submit=true 补回车(\\r)；submit=false 原样发送；已带 \\n 不重复补")
    void writeSubmitSemantics() {
        when(service.list(SESSION)).thenReturn(List.of(termA()));

        String r1 = textOf(writeTool.execute(call("terminal_write", "{\"data\":\"echo hi\"}"), ctx));
        assertThat(r1).isEqualTo("已写入 8 字节到终端 term-aaaa1111");   // "echo hi" + "\r" = 8 字节
        verify(service).write(SESSION, "term-aaaa1111", "echo hi\r");

        writeTool.execute(call("terminal_write", "{\"data\":\"y\",\"submit\":false}"), ctx);
        verify(service).write(SESSION, "term-aaaa1111", "y");

        writeTool.execute(call("terminal_write", "{\"data\":\"ls\\n\"}"), ctx);
        verify(service).write(SESSION, "term-aaaa1111", "ls\n");
    }

    @Test
    @DisplayName("terminal_write 终端已退出 → 返回写入失败文案（不抛异常，Turn 不被一个工具打死）")
    void writeToExitedTerminal() {
        when(service.list(SESSION)).thenReturn(List.of(termA()));
        org.mockito.Mockito.doThrow(new ConflictException("终端已退出，无法写入: term-aaaa1111"))
            .when(service).write(SESSION, "term-aaaa1111", "ls\r");

        String text = textOf(writeTool.execute(call("terminal_write", "{\"data\":\"ls\"}"), ctx));

        assertThat(text).startsWith("写入失败：").contains("终端已退出");
    }

    // ───────────────────────── 开关与审批 ─────────────────────────

    @Test
    @DisplayName("开关：默认(读/写都开)四个工具可见；写开关关 → 写隐藏、读仍可见；读开关关 → 写也隐藏(总开关)")
    void visibilitySwitches() {
        assertThat(listTool.isEnabled()).isTrue();
        assertThat(readTool.isEnabled()).isTrue();
        assertThat(createTool.isEnabled()).isTrue();
        assertThat(writeTool.isEnabled()).isTrue();

        // 「保留只读、禁止写」这一档
        assertThat(new TerminalCreateTool(service, false, true).isEnabled()).isFalse();
        assertThat(new TerminalWriteTool(service, false, true).isEnabled()).isFalse();
        assertThat(new TerminalListTool(service, true).isEnabled()).isTrue();

        // 总开关语义：读关掉 ⇒ 写一并不可见（构造成 write=true/read=false 的错配也不放行）
        assertThat(new TerminalListTool(service, false).isEnabled()).isFalse();
        assertThat(new TerminalReadTool(service, false).isEnabled()).isFalse();
        assertThat(new TerminalCreateTool(service, true, false).isEnabled()).isFalse();
        assertThat(new TerminalWriteTool(service, true, false).isEnabled()).isFalse();
    }

    @Test
    @DisplayName("审批分类：写工具 = Passthrough（需审批，与 Bash 同级），只读工具 = Allow（不弹窗），isReadOnly 对应")
    void permissionClassification() {
        // 写：Passthrough 才会流到兜底层转 Ask；返回 Allow 就是无条件执行（安全红线）
        assertThat(createTool.checkPermissions(call("terminal_create", "{}").input(), ctx))
            .isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(writeTool.checkPermissions(call("terminal_write", "{\"data\":\"ls\"}").input(), ctx))
            .isInstanceOf(PermissionResult.Passthrough.class);

        // 读：默认 Allow（对齐 Read/Glob，不触发审批）
        assertThat(listTool.checkPermissions(call("terminal_list", "{}").input(), ctx))
            .isInstanceOf(PermissionResult.Allow.class);
        assertThat(readTool.checkPermissions(call("terminal_read", "{}").input(), ctx))
            .isInstanceOf(PermissionResult.Allow.class);

        assertThat(createTool.isReadOnly(call("terminal_create", "{}").input())).isFalse();
        assertThat(writeTool.isReadOnly(call("terminal_write", "{}").input())).isFalse();
        assertThat(listTool.isReadOnly(call("terminal_list", "{}").input())).isTrue();
        assertThat(readTool.isReadOnly(call("terminal_read", "{}").input())).isTrue();
    }

    // ───────────────────────── helpers ─────────────────────────

    private static TerminalInfo termA() {
        return new TerminalInfo("term-aaaa1111", "/bin/bash", "/proj", 80, 24, true, "2026-10-29T10:00:00Z");
    }

    private static ToolUseBlock call(String toolName, String json) {
        try {
            return new ToolUseBlock("toolu_test_1", toolName, JSON.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException("测试 JSON 非法: " + json, e);
        }
    }

    /** 工具结果文本（{@code ToolResult.data()} 即给模型看的 content）。 */
    private static String textOf(AgentToolResult<?> result) {
        Object data = result.data();
        return data == null ? null : String.valueOf(data);
    }
}
