package com.nexusai.application.agent;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.context.ClaudemdEngine;
import com.nexusai.application.agent.memory.AutoMemPaths;
import com.nexusai.application.agent.memory.MemoryFileDetection;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * [IMP-M-R2-P0-CLD] nested_memory 内容注入 LLM 消息流的 loop 级契约测试（CLD-06/G-88，F-1/#45）。
 *
 * <p><b>WHY（探查 F-1 最小反例）</b>: CC lazy 链消费端闭环 = ReadFileTool 读成功写触发集
 * （FileReadTool.ts:848/870/1038）→ getNestedMemoryAttachments（attachments.ts:2165-2194）→
 * getAttachmentMessages 逐附件 yield（:2937-2969）→ query.ts:1580-1590 注入 LLM 消息流 →
 * messages.ts:3700-3707 渲染 {@code Contents of {path}:\n\n{content}} isMeta user message。
 * 旧实现 LlmAgentLoop:3116-3121 返回值丢弃 → 读 CWD 下 {@code src/foo/bar.ts} 后
 * {@code src/foo/CLAUDE.md} 内容不进 LLM（仅 audit 发射）。本测试走真实 loop：
 * <ol>
 *   <li>第 1 轮 provider 返回 ReadFileTool tool_call（读 {@code src/foo/bar.ts}）→ 工具真实执行
 *       写触发集 → 第 2 轮组装前 loop 消费触发集 → nested memory 注入</li>
 *   <li>断言第 2 轮 provider 消息数组末尾出现 subtype=nested_memory 的 isMeta user 消息，
 *       内容 = {@code Contents of {claudeMdPath}:\n\n{# Nested instructions}}（CC 渲染契约）</li>
 * </ol>
 *
 * <p>状态消息流（state.appendMessage）同样包含该 meta 消息（CC toolResults.push + 转录持久化）。
 */
@DisplayName("[IMP-M-R2-P0-CLD] nested_memory 注入 LLM 消息流（CLD-06/F-1 最小反例）")
class LlmAgentLoopNestedMemoryInjectionTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path workspace;      // originalCwd + src/foo/bar.ts + src/foo/CLAUDE.md

    private Path configHome;
    private Path managedPath;
    private Path memoryBase;

    private ClaudemdEngine engine;

    @BeforeEach
    void setUpEngine() throws Exception {
        configHome = Files.createTempDirectory("claude-config-home");
        managedPath = Files.createTempDirectory("claude-managed");
        memoryBase = Files.createTempDirectory("claude-memory-base");
        ClaudePaths.setConfigDirOverride(configHome.toString());
        ClaudePaths.setManagedFilePathOverride(managedPath.toString());
        // G5：ClaudemdEngine user memory = nexusai 自有根优先（ClaudemdEngine.java:1559）→ 唯一 appName 隔离
        NexusaiPaths.setAppNameOverride("nexusai-test-" + configHome.getFileName());

        AutoMemPaths autoMemPaths = new AutoMemPaths(
            () -> workspace.toString(),
            () -> memoryBase.toString(),
            () -> workspace.toString() + java.io.File.separator,
            () -> null);
        MemoryFileDetection detection = new MemoryFileDetection(
            autoMemPaths, () -> configHome.toString(), () -> true, () -> false, () -> false);
        // toRealPath 对齐 ReadFileTool 触发路径（Windows 8.3 短名 vs toRealPath 长名不一致会破坏 pathInWorkingPath）
        Path realWorkspace = workspace.toRealPath();
        engine = new ClaudemdEngine(autoMemPaths, detection,
            () -> realWorkspace.toString(),   // CC getOriginalCwd()
            () -> true, () -> true, () -> true,
            () -> false,                      // feature('TEAMMEM')
            () -> List.of());                 // claudeMdExcludes（无 settings 源 → 空）
    }

    @AfterEach
    void tearDown() {
        ClaudePaths.setConfigDirOverride(null);
        ClaudePaths.setManagedFilePathOverride(null);
        NexusaiPaths.setAppNameOverride(null);   // G5：复位 nexusai 自有根 appName 隔离
    }

    /** 驱动真实 loop 2 轮：第 1 轮 ReadFileTool 读 src/foo/bar.ts（写触发集）→ 第 2 轮消费注入。 */
    private List<List<ChatMessageDto>> runTwoTurns() throws Exception {
        Files.createDirectories(workspace.resolve("src").resolve("foo"));
        Path barTs = workspace.resolve("src").resolve("foo").resolve("bar.ts");
        Files.writeString(barTs, "const x = 1;\n");
        Path claudeMd = workspace.resolve("src").resolve("foo").resolve("CLAUDE.md");
        Files.writeString(claudeMd, "# Nested instructions\n");

        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool(new PathGuard(workspace)));
        LlmProviderFactory factory = mock(LlmProviderFactory.class);
        LlmProvider mainProvider = mock(LlmProvider.class);
        List<List<ChatMessageDto>> histories = new ArrayList<>();
        RunRequest request = RunRequest.session("configure the system now",
            "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), UUID.randomUUID(),
            com.nexusai.infra.llm.ProviderConfig.empty(), "test-model", null, null);
        final boolean[] firstRound = {true};
        doAnswer(inv -> {
            histories.add(new ArrayList<>((List<ChatMessageDto>) inv.getArgument(3)));
            Consumer<AssistantMessage> onMsg = inv.getArgument(10);
            Runnable onComplete = inv.getArgument(16);
            if (firstRound[0]) {
                firstRound[0] = false;
                // 第 1 轮：ReadFileTool 读 src/foo/bar.ts → 工具轮真实执行 → 写 nested 触发集
                ObjectNode input = JSON.createObjectNode().put("file_path", "src/foo/bar.ts");
                onMsg.accept(new AssistantMessage("Let me read", "tool_calls",
                    List.of(new ToolUseBlock("toolu_nested_1", "Read", input))));
            } else {
                // 第 2 轮：纯文本 stop → 退出
                onMsg.accept(new AssistantMessage("Done", "stop", List.of()));
            }
            onComplete.run();
            return null;
        }).when(mainProvider).stream(any(), anyString(), anyList(), anyList(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        when(factory.getProvider(any(), any())).thenReturn(mainProvider);

        LlmAgentLoop loop = new LlmAgentLoop(factory, null, registry);
        loop.setClaudemdEngine(engine);

        AgentState state = loop.run(request);
        assertThat(state).as("run() 必须返回非 null state").isNotNull();
        return histories;
    }

    @Test
    @DisplayName("CLD-06 最小反例: 读 src/foo/bar.ts 后下一轮消息流含 `Contents of src/foo/CLAUDE.md` isMeta user 消息（CC messages.ts:3700-3707）")
    void nestedMemoryInjection_afterReadContainsContentsOfClaudeMd() throws Exception {
        List<List<ChatMessageDto>> histories = runTwoTurns();

        assertThat(histories).as("必须 2 轮 LLM 调用").hasSize(2);
        ChatMessageDto firstRoundLast = histories.get(0).get(histories.get(0).size() - 1);
        assertThat(firstRoundLast.subtype())
            .as("第 1 轮（触发集尚未写入）不得注入 nested_memory")
            .isNotEqualTo("nested_memory");

        List<ChatMessageDto> secondRound = histories.get(1);
        ChatMessageDto last = secondRound.get(secondRound.size() - 1);
        String claudeMdPath = workspace.toRealPath().resolve("src").resolve("foo").resolve("CLAUDE.md").toString();
        assertThat(last.subtype()).as("nested_memory 消息位于消息数组末尾（CC toolResults.push）")
            .isEqualTo("nested_memory");
        assertThat(last.isMeta()).as("isMeta=true（CC createUserMessage isMeta:true）").isTrue();
        assertThat(last.content())
            .as("渲染契约：`Contents of {path}:\n\n{content}` 包 <system-reminder>（messages.ts:3700-3707 + :3097-3099）")
            .isEqualTo("<system-reminder>\nContents of " + claudeMdPath + ":\n\n# Nested instructions\n\n</system-reminder>");
    }
}
