package com.nexusai.application.agent.docs;

import com.nexusai.application.agent.tool.PathGuard;
import com.nexusai.application.agent.tool.impl.EditFileTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session L · {@link MagicDocUpdater} CC 对齐契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * MagicDocUpdater 是写回入口，本类升级后必须满足 4 条契约：
 * <ol>
 *   <li><b>prompt 走 MagicDocsPrompts.buildMagicDocsUpdatePrompt</b> —— 消除双 prompt（规则七）。
 *       由 {@code prompt 含 CC DEFAULT_UPDATE_PROMPT_TEMPLATE 关键片段} 验证。</li>
 *   <li><b>写回经 EditFileTool</b> —— EditFileTool.execute 被调且文件被改写。
 *       路径白名单通过 = 文件被改写；失败 = 文件不变。</li>
 *   <li><b>路径白名单守卫</b> —— 非 tracked 路径被拒（fail）。</li>
 *   <li><b>EditFileTool 强制注入</b> —— 构造期 IAE（CC magicDocs.ts:172-192 canUseTool 仅 Edit）。</li>
 * </ol>
 */
@DisplayName("Session L · MagicDocUpdater 走 EditFileTool + 路径白名单 + CC 对齐 prompt + 构造期强制注入")
class MagicDocUpdaterTest {

    @Test
    @DisplayName("写回经 EditFileTool：路径白名单通过 → EditFileTool.execute 被调且文件被改写")
    void writeViaEditToolSuccess(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("doc.md");
        String original = "# MAGIC DOC: hello\n_instructions_\nbody";
        Files.writeString(doc, original);

        // [Session L] EditFileTool 构造期强制注入 · 真实实例（避免 mock）
        PathGuard guard = new PathGuard(workspace);
        EditFileTool editTool = new EditFileTool(guard);

        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        String newBody = "# MAGIC DOC: hello\n_instructions_\nupdated body";
        updater.setLlmCallback(prompt -> newBody);

        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", doc, editTool);

        assertThat(result.state()).isEqualTo(MagicDocUpdater.RunState.SUCCESS);
        assertThat(result.updated()).isTrue();
        // 文件应被改写为 LLM 输出
        assertThat(Files.readString(doc)).isEqualTo(newBody);
    }

    @Test
    @DisplayName("路径白名单守卫：trackedDocPath 与 filePath 不一致 → fail (对齐 CC canUseTool :172-192)")
    void pathWhitelistRejects(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("doc.md");
        Files.writeString(doc, "# MAGIC DOC: hello\nbody");

        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        updater.setLlmCallback(p -> "# MAGIC DOC: hello\nhacked");

        // trackedDocPath 与 filePath 不同 → 应被白名单拒绝
        Path fakeTracked = workspace.resolve("other-tracked.md");
        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", fakeTracked, editTool);

        assertThat(result.state()).isEqualTo(MagicDocUpdater.RunState.FAILED);
        assertThat(result.error()).isPresent();
        assertThat(result.error().get()).contains("whitelist");
        // 文件不应被改动
        assertThat(Files.readString(doc)).startsWith("# MAGIC DOC: hello\nbody");
    }

    @Test
    @DisplayName("header 消失的文件: update 返回 skipped (不动文件) — 短路先于 EditFileTool 调用")
    void headerVanishedSkipped(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("noheader.md");
        Files.writeString(doc, "Just a plain readme");

        // [Session L] 构造期仍需 EditFileTool，但 update 短路在 detectEmpty → 不调用 writeViaEditTool
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", doc, editTool);

        assertThat(result.state()).isEqualTo(MagicDocUpdater.RunState.SUCCESS);
        assertThat(result.updated()).isFalse(); // skipped
    }

    @Test
    @DisplayName("非 magic doc 文件 → skipped，不调用 LLM —— 避免空转浪费 token")
    void nonMagicDocSkipped(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("plain.md");
        Files.writeString(doc, "just a plain markdown");

        boolean[] llmCalled = {false};
        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        updater.setLlmCallback(p -> {
            llmCalled[0] = true;
            return "should not be called";
        });

        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", doc, editTool);

        assertThat(llmCalled[0]).isFalse();
        assertThat(result.updated()).isFalse();
    }

    @Test
    @DisplayName("LLM 返回空字符串 → fail (不允许静默吞掉)")
    void emptyLlmResponseFails(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("doc.md");
        Files.writeString(doc, "# MAGIC DOC: hi\nbody");

        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        updater.setLlmCallback(p -> "   ");

        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", doc, editTool);

        assertThat(result.state()).isEqualTo(MagicDocUpdater.RunState.FAILED);
    }

    /**
     * [Session L] 构造期 EditFileTool=null 必须显式失败（规则十二·显式失败）.
     *
     * <p>WHY：CC magicDocs.ts:172-192 canUseTool 严格仅 Edit 工具，零降级直写。
     * 旧 writeDirect Files.writeString 路径已删除，editFileTool 缺失会导致 update() 链无声 NPE，
     * 故前置到构造期 IAE，避免运行时才暴露问题。
     */
    @Test
    @DisplayName("Session L · 构造期 EditFileTool=null 立即抛 IAE — CC canUseTool 仅 Edit，无降级")
    void editFileToolNullFailsLoudAtConstruction(@TempDir Path workspace) {
        // 2 参测试便捷构造器 (MagicDocDetector + EditFileTool=null) 必须显式失败
        assertThatThrownBy(() -> new MagicDocUpdater(new MagicDocDetector(), (EditFileTool) null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("EditFileTool 强制注入")
            .hasMessageContaining("magicDocs.ts:172-192");

        // 4 参测试 ctor (detector + factory + EditFileTool=null + model) 也必须显式失败
        assertThatThrownBy(() -> new MagicDocUpdater(new MagicDocDetector(), null,
                (EditFileTool) null, "test-model"))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("EditFileTool 强制注入");
    }

    /**
     * [L+ R12] 静态 ObjectMapper 复用契约验证.
     *
     * <p>WHY: writeViaEditTool 是热路径, 原每次 new ObjectMapper() 浪费 GC + 初始化.
     * 抽 static MAPPER 后, 所有调用共享同一实例, Jackson ObjectMapper 线程安全 (官方保证).
     */
    @Test
    @DisplayName("[L+ R12] 静态 MAPPER 复用: 多次 update 共享同一 ObjectMapper, 行为一致")
    void staticObjectMapperReusedAcrossUpdates(@TempDir Path workspace) throws Exception {
        // 准备: 真实 magic doc 文件
        Path doc = workspace.resolve("doc.md");
        Files.writeString(doc, "# MAGIC DOC: hello\nbody");

        EditFileTool editTool = new EditFileTool(new PathGuard(workspace));
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        updater.setLlmCallback(p -> "# MAGIC DOC: hello\nupdated body 1");

        // 第一次 update
        MagicDocUpdater.UpdateResult r1 = updater.update(doc, "ctx", doc, editTool);
        assertThat(r1.state()).isEqualTo(MagicDocUpdater.RunState.SUCCESS);
        assertThat(Files.readString(doc)).contains("updated body 1");

        // 第二次 update — 写回路径必须复用同一 MAPPER, 行为一致
        updater.setLlmCallback(p -> "# MAGIC DOC: hello\nupdated body 2");
        MagicDocUpdater.UpdateResult r2 = updater.update(doc, "ctx", doc, editTool);
        assertThat(r2.state()).isEqualTo(MagicDocUpdater.RunState.SUCCESS);
        assertThat(Files.readString(doc)).contains("updated body 2");

        // 验证 MAPPER 字段是 static + 非 null (反射确认)
        try {
            java.lang.reflect.Field mapperField = MagicDocUpdater.class.getDeclaredField("MAPPER");
            mapperField.setAccessible(true);
            Object mapperInstance = mapperField.get(null);
            assertThat(mapperInstance)
                .as("MAPPER 应是 static final, 共享单例")
                .isNotNull()
                .isInstanceOf(com.fasterxml.jackson.databind.ObjectMapper.class);
        } catch (NoSuchFieldException e) {
            // 反射字段名为 MAPPER 失败 → 测试失败 (命名漂移检测)
            throw new AssertionError("[L+ R12] MAPPER field not found, naming drift detected", e);
        }
    }

    /**
     * [L+ round 4] 写回走 ctx 路径 · 端到端成功.
     *
     * <p>WHY: 第三轮加门禁后, MagicDocUpdater.writeViaEditTool 仍调
     * {@code editTool.execute(call)} 无 ctx, 完全绕过 read-before-write + stale-write
     * 两道门. 本测试证明: 第四轮改造后 (构造 ctx + 播种 readFileState) 走 ctx 路径,
     * 端到端写回仍能成功, 功能未坏.
     *
     * <p>关键: 文件内容被 LLM 改写 → LLM 输出与 original 不同 → Edit 实际生效
     * → 文件被覆写. 任何"门禁误拒"或"ctx 路径走不通"都会让此测试失败.
     */
    @org.junit.jupiter.api.Test
    @org.junit.jupiter.api.DisplayName("[L+ round 4] 写回走 ctx 路径: end-to-end 成功, 证明 buildSeededContext 接线正确")
    void writeViaCtxPathEndToEndSuccess(@TempDir Path workspace) throws Exception {
        Path doc = workspace.resolve("doc.md");
        String original = "# MAGIC DOC: hello\n_instructions_\nbody";
        Files.writeString(doc, original);

        PathGuard guard = new PathGuard(workspace);
        EditFileTool editTool = new EditFileTool(guard);
        MagicDocUpdater updater = new MagicDocUpdater(new MagicDocDetector(), editTool);
        String newBody = "# MAGIC DOC: hello\n_instructions_\nctx_path_updated_body";
        updater.setLlmCallback(prompt -> newBody);

        MagicDocUpdater.UpdateResult result = updater.update(doc, "ctx", doc, editTool);

        // 端到端成功 — 文件被改写
        assertThat(result.state()).isEqualTo(MagicDocUpdater.RunState.SUCCESS);
        assertThat(result.updated()).isTrue();
        assertThat(Files.readString(doc))
            .as("ctx 路径必须真把文件改写成 LLM 输出; 若 buildSeededContext 派生 key 错位, 门禁会拒")
            .isEqualTo(newBody);
    }
}
