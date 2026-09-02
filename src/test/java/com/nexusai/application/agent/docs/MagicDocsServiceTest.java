package com.nexusai.application.agent.docs;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.tool.FileReadListenerRegistry;
import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import com.nexusai.application.agent.tool.impl.ReadFileTool;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Session L · {@link MagicDocsService} 自动 hook 链路契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * MagicDocsService 是 CC magicDocs.ts 全链路的 Java 等价物（监听登记 + post-sampling
 * hook 更新）。若哪天有人把它改成"只读但不登记"或"登记但永不更新"，
 * MagicDocs 自动维护就废了。本测试断言核心不变量：
 * <ol>
 *   <li><b>读到 magic doc → 登记到 trackedMagicDocs</b>（CC registerMagicDoc :87-94）</li>
 *   <li><b>读到普通文件 → 不登记（不污染追踪集）</b></li>
 *   <li><b>updateTrackedDocs 调 updater.updateWithContent</b>（verify mock 校验调用次数与参数）</li>
 *   <li><b>文件 header 消失 → 移除追踪</b>（CC :155-161 不变量）</li>
 *   <li><b>post-sampling hook 已挂载</b>（CC magicDocs.ts:252 registerPostSamplingHook）
 *       + 三重门控（querySource / 末轮 tool calls / tracked 非空，CC :220-235）</li>
 *   <li><b>updateSingle 经 ReadFileTool 重读</b>（CC :134-137 FileReadTool.call，
 *       外部修改后必须读到新内容；workspace 外路径被 PathGuard 拒 → 移除追踪）</li>
 * </ol>
 */
@DisplayName("Session L · MagicDocsService 自动 hook 链路")
class MagicDocsServiceTest {

    private MagicDocDetector detector;
    private MagicDocUpdater updater;
    private FileReadListenerRegistry registry;
    private EditFileTool editFileTool;
    private ReadFileTool readFileTool;
    private MagicDocsService service;
    /**
     * [L+ R9] 真实 unsubscribe handle · 替代原 tearDown 假清理.
     * <p>WHY：原 tearDown 用 {@code registry.notifyRead("", ...)}（FileReadEvent 已退役，
     * IMP-C5 TR-D1-⊕-2）触发 snapshot 走一遍 → 不会 unregister, listener 跨测试泄漏.
     * CC 测试用 {@code unsubscribe()} 闭包, Java 缺同等生命周期管理 → L+ R9
     * 在 setUp 持有 handle, @AfterEach 显式 run.
     */
    private Runnable unsubscribe;

    @BeforeEach
    void setUp() {
        detector = new MagicDocDetector();
        // 用 mock（避免依赖 LlmProviderFactory Spring 上下文）
        updater = mock(MagicDocUpdater.class);
        // mock updater.updateWithContent 默认返回 SUCCESS+updated 让 service.updateTrackedDocs 不 NPE
        when(updater.updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class)))
            .thenReturn(new MagicDocUpdater.UpdateResult(true, Optional.empty(),
                MagicDocUpdater.RunState.SUCCESS));
        registry = new FileReadListenerRegistry();
        editFileTool = mock(EditFileTool.class);
        // mock ReadFileTool: 默认成功返回 magic doc 内容（具体测试可覆盖/换真实实例）
        readFileTool = mock(ReadFileTool.class);
        // name() 必须 stub: updateSingle 用它构造 ToolUseBlock (name blank 会抛 IAE)
        when(readFileTool.name()).thenReturn("read_file");
        when(readFileTool.execute(any(ToolUseBlock.class), any()))
            .thenReturn(ToolResult.success("mock-read", "# MAGIC DOC: mock\nbody"));
        service = new MagicDocsService(detector, updater, registry, editFileTool, readFileTool);
        // enabled 强制 true（绕过 @Value 默认 false）
        ReflectionTestUtils.setField(service, "enabled", true);
        // PostConstruct 等价：手动注册（@PostConstruct 在 new 时不触发）
        service.registerAsListener();
        // [L+ R9] 持有真实 unsubscribe handle, @AfterEach 显式 run
        unsubscribe = registry.register(service);
    }

    @AfterEach
    void tearDown() {
        // [L+ R9] 真实清理 — unsubscribe.run() 真正从 registry 移除 listener
        if (unsubscribe != null) {
            unsubscribe.run();
            unsubscribe = null;
        }
        // hook 注册在静态 PostSamplingHookRegistry, 必须清空防跨测试泄漏
        PostSamplingHookRegistry.clearAll();
    }

    /** 空闲主线程 hook 上下文（无消息）· 门控应全部放行. */
    private static PostSamplingContext idleMainThreadContext() {
        return new PostSamplingContext(List.of(), List.of("systemPrompt"), Map.of(), Map.of(),
            null, QuerySource.REPL_MAIN_THREAD);
    }

    @Test
    @DisplayName("读到 magic doc → 登记到 trackedMagicDocs（幂等）—— CC registerMagicDoc :87-94 等价")
    void readMagicDocRegisters() {
        String content = "# MAGIC DOC: hello\n_some instructions_\nbody";

        service.onFileRead("docs/note.md", content);

        assertThat(service.trackedCount()).isEqualTo(1);
        // 再发一次同 path → 仍 1（幂等登记）
        service.onFileRead("docs/note.md", content);
        assertThat(service.trackedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("读到普通文件 → 不登记 —— 避免 trackedMagicDocs 被无关文件污染")
    void readNonMagicDocDoesNotRegister() {
        service.onFileRead("README.md", "# just a readme");

        assertThat(service.trackedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("updateTrackedDocs 经 ReadFileTool 重读后调 updater.updateWithContent —— CC :134-137 + :217-240 等价")
    void updateTrackedDocsInvokesUpdater(@TempDir Path workspace) throws IOException {
        // 准备真实 magic doc 文件（绝对路径在 workspace 内 → PathGuard 放行）
        Path docPath = workspace.resolve("docs.md").toAbsolutePath();
        Files.writeString(docPath, "# MAGIC DOC: hello\n_instructions_\nbody");
        // 真实 ReadFileTool + PathGuard(workspace) —— 覆盖"经 ReadFileTool 重读"链路
        service = newServiceWithRealReadFileTool(workspace);
        service.onFileRead(docPath.toString(),
            Files.readString(docPath));

        service.updateTrackedDocs(idleMainThreadContext());

        // updater.updateWithContent 应被调 1 次，参数含 trackedPath 与已读内容
        verify(updater, times(1)).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
    }

    @Test
    @DisplayName("header 消失的文件：updateTrackedDocs 跳过该文件且从 tracked 移除 —— CC :155-161 不变量")
    void updateRemovesTrackedWhenHeaderVanishes(@TempDir Path workspace) throws IOException {
        Path docPath = workspace.resolve("vanish.md").toAbsolutePath();
        Files.writeString(docPath, "# MAGIC DOC: hello\nbody");
        service = newServiceWithRealReadFileTool(workspace);
        service.onFileRead(docPath.toString(),
            Files.readString(docPath));
        assertThat(service.trackedCount()).isEqualTo(1);

        // 模拟外部改动：移除 magic doc header
        Files.writeString(docPath, "Just a normal readme now");

        service.updateTrackedDocs(idleMainThreadContext());

        // 验证：updater.updateWithContent 没被调（header 不命中），且 tracked 已移除
        verify(updater, never()).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
        assertThat(service.trackedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("disabled 状态：onFileRead 不登记 + updateTrackedDocs 跳过已登记 doc —— 开关硬门控")
    void disabledDoesNothing(@TempDir Path workspace) throws IOException {
        // [L+ R10] 断言严谨化: 先登记 1 个 doc (此时 enabled=true), 再 setEnabled(false),
        // 验证 updateTrackedDocs 不会调 updater.updateWithContent.
        // 原版顺序: 先 setEnabled(false) → 登记 0 个 → trackedCount=0 → updater 不调 (coincidence,
        //           因 trackedCount=0 触发的, 不是 enabled=false 拦截)
        // 修复后: 登记 1 个 → setEnabled(false) → trackedCount=1 (确认登记成功)
        //        → updateTrackedDocs → verify(never) (确认 enabled=false 真拦截)
        Path docPath = workspace.resolve("ignored.md");
        Files.writeString(docPath, "# MAGIC DOC: ignored\n");

        // 步骤 1: 先以 enabled=true 登记 1 个 doc
        service.onFileRead("ignored.md",
            Files.readString(docPath));
        assertThat(service.trackedCount())
            .as("登记成功后 trackedMagicDocs 应有 1 个条目")
            .isEqualTo(1);

        // 步骤 2: 切到 enabled=false
        ReflectionTestUtils.setField(service, "enabled", false);

        // 步骤 3: 调 updateTrackedDocs — enabled=false 拦截, updater 不该被调
        service.updateTrackedDocs(idleMainThreadContext());
        verify(updater, never()).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));

        // 步骤 4: 再调 onFileRead — enabled=false 也拦截登记
        service.onFileRead("ignored2.md",
            "# MAGIC DOC: ignored2\n");
        assertThat(service.trackedCount())
            .as("enabled=false 后续 onFileRead 也不能再登记, trackedCount 仍为 1")
            .isEqualTo(1);
    }

    // ═════════════ Session L 新增：post-sampling hook 挂载 + 门控 ═════════════

    // [IMP-HOOKS-S7 T7-⊕1] count() 已删除（CC 数组无 size 查询 API）：原
    //   postSamplingHookRegisteredWhenEnabled/postSamplingHookNotRegisteredWhenDisabled
    //   两 count 断言测试删除 —— 挂载行为由下方门控 + executeAll 行为测试承担
    //   （注册后 executeAll 触发/不触发 updater），避免重复且不依赖 ⊕ API。

    @Test
    @DisplayName("门控：末轮 assistant 消息含 tool calls → hook 跳过更新 —— CC :226-230 对话不空闲")
    void gatingSkipsWhenLastAssistantTurnHasToolCalls() {
        // 先登记 1 个 doc（若门控失效会走到 updater，verify 兜底）
        service.onFileRead("docs/note.md", "# MAGIC DOC: hello\nbody");
        ChatMessageDto assistantWithTools = new ChatMessageDto(
            "m1", null, Role.assistant, "assistant",
            "", null, List.of(new ToolCallDto("t1", "read_file", "{}", null, null)),
            null, null, null, "now", null, null, null, null, null, null);
        PostSamplingContext ctx = new PostSamplingContext(List.of(assistantWithTools), List.of("sys"),
            Map.of(), Map.of(), null, QuerySource.REPL_MAIN_THREAD);

        PostSamplingHookRegistry.executeAll(ctx, null).join();

        verify(updater, never()).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
    }

    @Test
    @DisplayName("门控：querySource 非主线程 → hook 跳过更新 —— CC :222-224")
    void gatingSkipsWhenQuerySourceNotMainThread() {
        service.onFileRead("docs/note.md", "# MAGIC DOC: hello\nbody");
        PostSamplingContext ctx = new PostSamplingContext(List.of(), List.of("sys"),
            Map.of(), Map.of(), null, QuerySource.SUBAGENT);

        PostSamplingHookRegistry.executeAll(ctx, null).join();

        verify(updater, never()).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
    }

    @Test
    @DisplayName("门控放行：主线程 + 空闲 + tracked 非空 → hook 触发更新 —— CC :237-239 串行遍历")
    void gatingProceedsWhenIdleMainThread() {
        service.onFileRead("docs/note.md", "# MAGIC DOC: hello\nbody");

        PostSamplingHookRegistry.executeAll(idleMainThreadContext(), null).join();

        verify(updater, times(1)).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
    }

    @Test
    @DisplayName("updateSingle 经 ReadFileTool 重读：文件被外部修改后更新读到新内容 —— CC :134-137 FileReadTool.call")
    void updateSingleReReadsLatestContent(@TempDir Path workspace) throws IOException {
        Path docPath = workspace.resolve("latest.md").toAbsolutePath();
        Files.writeString(docPath, "# MAGIC DOC: hello\nv1 body");
        service = newServiceWithRealReadFileTool(workspace);
        service.onFileRead(docPath.toString(),
            "# MAGIC DOC: hello\nv1 body");

        // 外部修改文件（header 保留，内容升级）—— dedup 必须不命中，读到新内容
        Files.writeString(docPath, "# MAGIC DOC: hello\nv2 body updated externally");

        service.updateTrackedDocs(idleMainThreadContext());

        ArgumentCaptor<String> contentCaptor = ArgumentCaptor.forClass(String.class);
        verify(updater, times(1)).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), contentCaptor.capture());
        assertThat(contentCaptor.getValue())
            .as("updateSingle 必须经 ReadFileTool 读到最新内容（而非旧缓存/旧快照）")
            .contains("v2 body updated externally");
    }

    @Test
    @DisplayName("workspace 外 magic doc：ReadFileTool PathGuard 拒 → 移除追踪 —— CC :142-153 读失败语义")
    void workspaceOutsideDocRemovedFromTracking(@TempDir Path workspace) {
        service = newServiceWithRealReadFileTool(workspace);
        // 登记一个 workspace 之外的绝对路径 doc
        service.onFileRead("C:/outside/doc.md",
            "# MAGIC DOC: outside\nbody");
        assertThat(service.trackedCount()).isEqualTo(1);

        service.updateTrackedDocs(idleMainThreadContext());

        // PathGuard 拒（SecurityException → ToolResult.error）→ 对齐 CC 读失败移除追踪
        assertThat(service.trackedCount()).isEqualTo(0);
        verify(updater, never()).updateWithContent(any(Path.class), any(String.class),
            any(Path.class), any(EditFileTool.class), any(String.class));
    }

    /** 构造带真实 ReadFileTool（PathGuard=workspace）的已启用 service. */
    private MagicDocsService newServiceWithRealReadFileTool(Path workspace) {
        MagicDocsService fresh = new MagicDocsService(detector, updater, registry, editFileTool,
            new ReadFileTool(new PathGuard(workspace)));
        ReflectionTestUtils.setField(fresh, "enabled", true);
        return fresh;
    }
}
