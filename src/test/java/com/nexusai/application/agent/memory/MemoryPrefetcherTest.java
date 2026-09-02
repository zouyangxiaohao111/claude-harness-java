package com.nexusai.application.agent.memory;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.tool.FileStateCache;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import com.nexusai.model.session.dto.ToolCallDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-P1-2] MemoryPrefetcher 对齐 CC startRelevantMemoryPrefetch / getRelevantMemoryAttachments /
 * readMemoriesForSurfacing / collectSurfacedMemories / collectRecentSuccessfulTools / filterDuplicateMemoryAttachments。
 *
 * <p>WHY（规则九 · 测试验证意图）: CC 真源关键行为（attachments.ts:2361-2541）——
 * <ul>
 *   <li>门控(2)（isAutoMemoryEnabled && tengu_moth_copse）关闭 → 不预取</li>
 *   <li>单字守卫（CC :2378-2381 无空白字符）→ 不预取（单字 prompt 缺上下文）</li>
 *   <li>60KB 会话预算超限 → 不预取（扫 messages，compact 自然重置）</li>
 *   <li>agent @-mention 隔离（Java 未接线 agentDefinitions → 兜底 autoMemPath 单目录）</li>
 *   <li>readFileState 去重 + mark-after-filter（消费一次不重复展示）</li>
 *   <li>readMemoriesForSurfacing 200 行 / 4096B 双上限截断 + note</li>
 * </ul>
 */
@DisplayName("[IMP-M-P1-2] MemoryPrefetcher 预取对齐 CC attachments.ts")
class MemoryPrefetcherTest {

    static class StubProvider extends MockLlmProvider {
        String response = """
            {"selected_memories": ["a.md"]}
            """.trim();

        @Override
        public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, LlmProvider.ChatRequestOptions options) {
            return response;
        }
    }

    static class FixedFactory extends LlmProviderFactory {
        private final LlmProvider provider;

        FixedFactory(LlmProvider provider) {
            this.provider = provider;
        }

        @Override
        public LlmProvider getProvider(ProviderConfig config, String providerType) {
            return provider;
        }

        @Override
        public LlmProvider getProvider(ProviderConfig config) {
            return provider;
        }
    }

    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            null, null, null, null, null, null, null, null, null,
            java.util.List.of(), java.util.List.of(), null,
            false, false, null, null);
    }

    private static ChatMessageDto surfacedMetaMsg(String content) {
        return new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.user, "user", content, null,
            null, null, null, null, null, null, null, null, null,
            java.util.List.of(), java.util.List.of(), null,
            true, false, null, "relevant_memories");
    }

    private MemoryPrefetcher build(Path memDir, boolean mothCopseOn) {
        AutoMemPaths paths = new AutoMemPaths(
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> null);
        return new MemoryPrefetcher(
            new FindRelevantMemories(new FixedFactory(new StubProvider()), "sonnet", new MemoryScanner(),
                // [RV14B-WIRE-02] stub resolver：字面量 → DB 名 → 真实 config（side-query 不再恒 mock）
                new ModelConfigResolver() {
                    @Override
                    public String resolveFastModelName(String fallbackModelName) {
                        return "claude-sonnet";
                    }
                    @Override
                    public com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolve(String modelName) {
                        return new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                            new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk");
                    }
                }),
            paths,
            new MemoryAge(),
            () -> true,
            () -> mothCopseOn,
            null,   // agentRegistry（@-mention 隔离测试单独注入）
            null);  // agentMemoryDirectory
    }

    /** 带 agent 注册中心 + agent-memory 目录的构造（G-19/G-69 @-mention 隔离测试用）。 */
    private MemoryPrefetcher buildWithAgents(Path memDir, Path cwd,
                                             com.nexusai.application.agent.subagent.AgentDefinitionRegistry registry) {
        AutoMemPaths paths = new AutoMemPaths(
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> memDir.toString(),
            () -> null);
        com.nexusai.application.agent.agent.AgentMemoryDirectory agentMemoryDir =
            new com.nexusai.application.agent.agent.AgentMemoryDirectory(
                () -> cwd.toString(),                                      // CC getCwd()
                () -> java.nio.file.Paths.get(System.getProperty("user.home"), ".claude"),  // CC getMemoryBaseDir()
                () -> null,                                                // CLAUDE_CODE_REMOTE_MEMORY_DIR
                () -> cwd,                                                // CC getProjectRoot()
                null,                                                      // sanitizePathFn（目录解析不用）
                null,                                                      // ensureDirConsumer
                null,                                                      // coworkExtraGuidelines
                () -> true,                                                // isAutoMemoryEnabled
                com.nexusai.application.agent.memory.MemoryPromptBuilder.productionDefault());
        return new MemoryPrefetcher(
            new FindRelevantMemories(new FixedFactory(new StubProvider()), "sonnet", new MemoryScanner(),
                new ModelConfigResolver() {
                    @Override
                    public String resolveFastModelName(String fallbackModelName) {
                        return "claude-sonnet";
                    }
                    @Override
                    public com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel resolve(String modelName) {
                        return new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                            new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk");
                    }
                }),
            paths,
            new MemoryAge(),
            () -> true,
            () -> true,
            () -> registry,
            agentMemoryDir);
    }

    private void writeMemory(Path dir, String name, String type, String desc) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name),
            "---\nname: " + name.replace(".md", "") + "\ntype: " + type + "\ndescription: " + desc + "\n---\nbody\n");
    }

    @Test
    @DisplayName("门控(2) 关闭（tengu_moth_copse=false）→ startPrefetch 返回 null")
    void gateClosed_returnsNull(@TempDir Path memDir) throws Exception {
        // WHY: CC attachments.ts:2365-2370 —— 任一门控关闭 → undefined；预取不启动、零成本。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, false);

        var handle = prefetcher.startPrefetch(List.of(userMsg("configure the system now")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("mothCopse 门控关闭必须不预取").isNull();
    }

    @Test
    @DisplayName("单字守卫 → startPrefetch 返回 null（CC :2378-2381 无空白字符）")
    void singleWord_returnsNull(@TempDir Path memDir) throws Exception {
        // WHY: CC :2378-2381 —— !input || !whitespaceRegex.test(input.trim()) → undefined；
        //       单字 prompt 缺乏足够上下文做有意义的 term 提取。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);

        var handle = prefetcher.startPrefetch(List.of(userMsg("remember")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("单字 user 消息必须不预取").isNull();
    }

    @Test
    @DisplayName("全角空格 U+3000（中文「记得　查一下」）→ 预取触发（CC /\\s/ 含 Unicode 空白）")
    void ideographicSpace_triggersPrefetch(@TempDir Path memDir) throws Exception {
        // WHY（IMP-MV2-07 △-4）：CC attachments.ts:2379 /\s/ 为 ECMAScript 空白（含 U+3000）；
        //       Java 默认 \s 仅 ASCII → 旧 `matches(".*\\s.*")` 漏判，全角空格分隔的中文短语被拒预取。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);

        MemoryPrefetcher.MemoryPrefetch handle = prefetcher.startPrefetch(
            List.of(userMsg("记得\u3000查一下")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("含 U+3000 的多字输入必须触发预取（CC 语义）").isNotNull();
        List<MemoryPrefetcher.RelevantMemoryAttachment> attachments =
            handle.promise.get(5, TimeUnit.SECONDS);
        assertThat(attachments).as("命中 a.md 记忆").hasSize(1);
        assertThat(attachments.get(0).path()).endsWith("a.md");
    }

    @Test
    @DisplayName("纯全角空格消息 → 不预取（isBlank，单字守卫边界）")
    void fullWidthSpaceOnly_returnsNull(@TempDir Path memDir) throws Exception {
        // WHY: 单字守卫第一支 —— !input（空 / 全空白输入裁剪后为空）；全角空格 String.isBlank
        //       （Character.isWhitespace 含 U+3000）→ 恒拒，与 CC trim→'' → !input 语义一致。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);

        var handle = prefetcher.startPrefetch(
            List.of(userMsg("\u3000")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("纯全角空白消息必须不预取").isNull();
    }

    @Test
    @DisplayName("纯 emoji 无空白 → 不预取（无 ECMAScript 空白字符，CC 同拒）")
    void emojiOnly_returnsNull(@TempDir Path memDir) throws Exception {
        // WHY: 单字守卫第二支 —— trim 后无任何 ECMAScript 空白 → 拒；纯 emoji 输入两端无空白，
        //       /\s/ 不命中，与 CC 判定一致（边界用例，02 §2.5 E04 验收）。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);

        var handle = prefetcher.startPrefetch(
            List.of(userMsg("\uD83C\uDF89")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("纯 emoji 单字消息必须不预取").isNull();
    }

    @Test
    @DisplayName("60KB 会话预算超限 → startPrefetch 返回 null（CC :2383-2386）")
    void sessionBudgetExceeded_returnsNull(@TempDir Path memDir) throws Exception {
        // WHY: CC :2383-2386 —— collectSurfacedMemories(messages).totalBytes >= MAX_SESSION_BYTES → undefined；
        //       扫 messages 而非 toolUseContext → compact 自然重置（:2246-2249）。
        String big = "x".repeat(60 * 1024);
        MemoryPrefetcher prefetcher = build(memDir, true);
        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(userMsg("configure the system now"));
        messages.add(surfacedMetaMsg("<system-reminder>\nMemory (saved today): "
            + memDir.resolve("a.md") + ":\n\n" + big + "\n</system-reminder>"));

        var handle = prefetcher.startPrefetch(messages, ToolUseContext.createFileStateCache(), null);

        assertThat(handle).as("累计注入 >= 60KB 必须停止预取").isNull();
    }

    @Test
    @DisplayName("预取完成可消费一次（settledAt 置位 + consumed 标记）")
    void prefetchCompletes_consumedOnce(@TempDir Path memDir) throws Exception {
        // WHY: CC attachments.ts:2346-2353 + query.ts:1599-1614 —— promise 完成 → settledAt 非 null；
        //       消费点 settled && 未消费 → filterDuplicate + 注入，consumed 置位（Java 命名 consumed，
        //       CC 消费轮字段 -1 until consumed）。预取永不阻塞 turn。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);

        MemoryPrefetcher.MemoryPrefetch handle = prefetcher.startPrefetch(List.of(userMsg("configure the system now")), ToolUseContext.createFileStateCache(), null);

        assertThat(handle).isNotNull();
        List<MemoryPrefetcher.RelevantMemoryAttachment> attachments =
            handle.promise.get(5, TimeUnit.SECONDS);
        assertThat(handle.settledAt).as("promise 完成必须置 settledAt").isNotEqualTo(0L);
        assertThat(attachments).as("命中 a.md 记忆").hasSize(1);
        assertThat(attachments.get(0).path()).endsWith("a.md");
        assertThat(handle.consumed).as("未消费前必须 -1").isEqualTo(-1);

        handle.consumed = 1;
        assertThat(handle.consumed).isNotEqualTo(-1);
    }

    @Test
    @DisplayName("readFileState 去重 + mark-after-filter（CC attachments.ts:2520-2541）")
    void readFileState_dedupAndMark(@TempDir Path memDir) throws Exception {
        // WHY: CC :2513-2519 —— mark-after-filter 顺序 load-bearing（预取期写 readFileState 会让过滤
        //       自引用全丢）；幸存者过滤后写 readFileState，后续 turn 不重复展示（DEL-M-34 去重语义）。
        writeMemory(memDir, "a.md", "project", "alpha config");
        MemoryPrefetcher prefetcher = build(memDir, true);
        FileStateCache readFileState = ToolUseContext.createFileStateCache();
        String aPath = memDir.resolve("a.md").toAbsolutePath().toString();
        readFileState.set(aPath, ToolUseContext.ReadState.full(System.currentTimeMillis()));

        MemoryPrefetcher.MemoryPrefetch handle = prefetcher.startPrefetch(List.of(userMsg("configure the system now")), readFileState, null);
        List<MemoryPrefetcher.RelevantMemoryAttachment> attachments =
            handle.promise.get(5, TimeUnit.SECONDS);

        assertThat(attachments).as("readFileState 已含 a.md → 过滤剔除").isEmpty();

        List<MemoryPrefetcher.RelevantMemoryAttachment> toInject =
            List.of(new MemoryPrefetcher.RelevantMemoryAttachment(aPath, "content", System.currentTimeMillis(), "h", null));
        FileStateCache fresh = ToolUseContext.createFileStateCache();
        List<MemoryPrefetcher.RelevantMemoryAttachment> kept = prefetcher.filterDuplicateMemoryAttachments(toInject, fresh);
        assertThat(kept).hasSize(1);
        assertThat(fresh.has(aPath)).as("幸存者必须写入 readFileState").isTrue();
    }

    @Test
    @DisplayName("readMemoriesForSurfacing 超 200 行截断 + note（CC attachments.ts:2279-2321）")
    void readMemoriesForSurfacing_truncatesOverLines(@TempDir Path memDir) throws Exception {
        // WHY: CC :2297-2307 —— MAX_MEMORY_LINES=200 截断 + note 引导用 Read 工具看完整文件；
        //       截断保留 frontmatter + 开头上下文（最相关记忆仍展示）。
        StringBuilder sb = new StringBuilder("---\nname: big\ntype: project\ndescription: big file\n---\n");
        for (int i = 0; i < 250; i++) {
            sb.append("line ").append(i).append("\n");
        }
        Files.createDirectories(memDir);
        Files.writeString(memDir.resolve("big.md"), sb.toString());
        MemoryPrefetcher prefetcher = build(memDir, true);

        long mtime = Files.getLastModifiedTime(memDir.resolve("big.md")).toMillis();
        List<MemoryPrefetcher.RelevantMemoryAttachment> results = prefetcher.readMemoriesForSurfacing(
            List.of(new FindRelevantMemories.RelevantMemory(memDir.resolve("big.md").toAbsolutePath().toString(), mtime)));

        assertThat(results).hasSize(1);
        assertThat(results.get(0).content()).contains("> This memory file was truncated");
        assertThat(results.get(0).content()).contains("first 200 lines");
        assertThat(results.get(0).limit()).as("截断 → limit 为实际注入行数").isNotNull();
        assertThat(results.get(0).header()).startsWith("Memory (saved");
    }

    @Test
    @DisplayName("collectSurfacedMemories 提取路径 + 累计字节（CC :2251-2266）")
    void collectSurfacedMemories_extractsPathsAndBytes(@TempDir Path memDir) throws Exception {
        // WHY: CC :2251-2266 —— 扫 messages 中 relevant_memories attachment：paths（selector 去重）
        //       + totalBytes（会话总预算节流）。
        String aPath = memDir.resolve("a.md").toAbsolutePath().toString();
        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(userMsg("configure the system now"));
        messages.add(surfacedMetaMsg("<system-reminder>\nMemory (saved today): " + aPath + ":\n\nhello world\n</system-reminder>"));

        MemoryPrefetcher.SurfacedMemories surfaced = MemoryPrefetcher.collectSurfacedMemories(messages);

        assertThat(surfaced.paths()).contains(aPath);
        assertThat(surfaced.totalBytes()).isPositive();
    }

    @Test
    @DisplayName("collectRecentSuccessfulTools 返回成功工具排除失败（CC :2465-2503）")
    void collectRecentSuccessfulTools_filtersFailed() throws Exception {
        // WHY: CC :2492-2502 —— 任一错误 → 工具排除（模型挣扎，docs 保留）；无结果 → 也排除。
        ChatMessageDto user = userMsg("configure the system now");
        ChatMessageDto assistant = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.assistant, "assistant", "ok", null,
            java.util.List.of(
                new ToolCallDto("id-1", "Bash", "{}", null, null),
                new ToolCallDto("id-2", "Read", "{}", null, null)),
            null, null, null, null, null, null, null, null,
            java.util.List.of(), java.util.List.of(), null, false, false, null, null);
        ChatMessageDto toolResultOk = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, "tool", "ok", null,
            null, null, null, null, null, null, "id-1", null, null,
            java.util.List.of(), java.util.List.of(), null, false, false, null, null);
        ChatMessageDto toolResultErr = new ChatMessageDto(
            UUID.randomUUID().toString(), null, Role.tool, "tool", "err", null,
            null, null, null, null, null, null, "id-2", null, null,
            java.util.List.of(), java.util.List.of(), null, false, true, null, null);
        List<ChatMessageDto> messages = new ArrayList<>();
        messages.add(user);
        messages.add(assistant);
        messages.add(toolResultOk);
        messages.add(toolResultErr);

        List<String> tools = MemoryPrefetcher.collectRecentSuccessfulTools(messages, user);

        assertThat(tools).as("Bash 成功保留，Read 报错排除").containsExactly("Bash");
    }

    // ── G-19/G-69: agent @-mention 检索隔离（CC attachments.ts:2204-2213）──

    private static com.nexusai.application.agent.subagent.AgentDefinition agentDef(String type, String memoryScope) {
        var builder = com.nexusai.application.agent.subagent.AgentDefinition.CustomAgentDefinition.builder(
            type, "desc", "userSettings", "prompt");
        if (memoryScope != null) {
            builder.memory(memoryScope);
        }
        return builder.build();
    }

    @Test
    @DisplayName("G-19: 输入含 @agent-<type> → 仅搜索匹配 agent 的 memory 目录")
    void agentMention_resolvesAgentMemoryDir(@TempDir Path memDir) throws Exception {
        // WHY: CC attachments.ts:2206-2211 —— mention → agentDef.memory → getAgentMemoryDir 单目录；
        //       跨 agent 记忆不得泄露进上下文（OPD-M-34 尊重 + rev2 F3 补登闭环）。
        com.nexusai.application.agent.subagent.AgentDefinitionRegistry registry =
            new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
                java.util.Map.of(), java.util.List.of(agentDef("code-x", "project")));
        Path cwd = Files.createTempDirectory("cwd-agent");
        MemoryPrefetcher prefetcher = buildWithAgents(memDir, cwd, registry);

        List<Path> dirs = prefetcher.resolveMemoryDirs("help me @agent-code-x refactor this");

        assertThat(dirs).as("agent @-mention → 仅 agent memory 目录（project scope）")
            .containsExactly(cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("code-x"));
    }

    @Test
    @DisplayName("G-69: 引号形态 @\"<type> (agent)\" 同样识别（autocomplete 选中）")
    void agentMention_quotedFormat(@TempDir Path memDir) throws Exception {
        com.nexusai.application.agent.subagent.AgentDefinitionRegistry registry =
            new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
                java.util.Map.of(), java.util.List.of(agentDef("reviewer", "project")));
        Path cwd = Files.createTempDirectory("cwd-agent");
        MemoryPrefetcher prefetcher = buildWithAgents(memDir, cwd, registry);

        List<Path> dirs = prefetcher.resolveMemoryDirs("please @\"reviewer (agent)\" check this");

        assertThat(dirs).as("引号形态 mention 必须识别（attachments.ts:2812-2818）")
            .containsExactly(cwd.resolve(NexusaiPaths.getProjectDirName()).resolve("agent-memory").resolve("reviewer"));
    }

    @Test
    @DisplayName("G-19: 无 @-mention → 兜底 [getAutoMemPath()] 单目录")
    void noMention_fallsBackToAutoMemPath(@TempDir Path memDir) throws Exception {
        com.nexusai.application.agent.subagent.AgentDefinitionRegistry registry =
            new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
                java.util.Map.of(), java.util.List.of(agentDef("code-x", "project")));
        Path cwd = Files.createTempDirectory("cwd-agent");
        MemoryPrefetcher prefetcher = buildWithAgents(memDir, cwd, registry);

        List<Path> dirs = prefetcher.resolveMemoryDirs("help me refactor this");

        assertThat(dirs).as("无 mention → autoMemPath（CC :2213）").containsExactly(memDir);
    }

    @Test
    @DisplayName("G-19: mention 的 agent 无 memory scope → 兜底 autoMemPath")
    void mentionWithoutMemoryScope_fallsBackToAutoMemPath(@TempDir Path memDir) throws Exception {
        com.nexusai.application.agent.subagent.AgentDefinitionRegistry registry =
            new com.nexusai.application.agent.subagent.AgentDefinitionRegistry(
                java.util.Map.of(), java.util.List.of(agentDef("plain", null)));
        Path cwd = Files.createTempDirectory("cwd-agent");
        MemoryPrefetcher prefetcher = buildWithAgents(memDir, cwd, registry);

        List<Path> dirs = prefetcher.resolveMemoryDirs("@agent-plain help me");

        assertThat(dirs).as("agentDef.memory 缺失 → [] → 兜底 autoMemPath（CC :2209-2213）")
            .containsExactly(memDir);
    }

    @Test
    @DisplayName("G-19: extractAgentMentions 双形态 + 去重（CC attachments.ts:2802-2828）")
    void extractAgentMentions_bothFormatsAndUniq() {
        List<String> mentions = MemoryPrefetcher.extractAgentMentions(
            "see @agent-code-x and @\"reviewer (agent)\" and @agent-code-x again");

        assertThat(mentions).as("引号形态先收集、非引号形态后收集（CC :2812-2825 双循环序），去重")
            .containsExactly("reviewer", "agent-code-x");
    }

    // ── MEM-04/G-23: readFileInRange 剥 BOM ──

    @Test
    @DisplayName("MEM-04: readFileInRange 剥 BOM（CC readFileInRange.ts:138）")
    void readFileInRange_stripsBom(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("m.md");
        Files.write(f, "\uFEFF# title\ncontent line\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MemoryPrefetcher.MemoryFileData data = MemoryPrefetcher.readFileInRange(f, 10, 4096);

        assertThat(data.content()).as("首行不得含 \\uFEFF 污染").startsWith("# title");
    }

    // ── MEM-05/G-25: collectSurfacedMemories UTF-16 单元口径 ──

    @Test
    @DisplayName("MEM-05: contentBytesOfMeta 按 UTF-16 单元计数（CC attachments.ts:2261 content.length）")
    void contentBytesOfMeta_utf16Units() {
        // "中文内容" = 4 个 UTF-16 单元（Java String.length 与 JS .length 同口径）但 12 个 UTF-8 字节
        String persisted = "<system-reminder>\nMemory (saved today): /x/a.md:\n\n中文内容\n</system-reminder>";

        long units = MemoryPrefetcher.contentBytesOfMeta(persisted);

        assertThat(units).as("CJK 内容按 UTF-16 单元计（旧 UTF-8 字节口径预算提前触顶）").isEqualTo(4L);
    }
}
