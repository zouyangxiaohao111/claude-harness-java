package com.nexusai.application.agent.memory;
import com.anthropic.errors.AnthropicRetryableException;

import com.nexusai.infra.llm.LlmApiException;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.LlmProviderFactory;
import com.nexusai.infra.llm.MockLlmProvider;
import com.nexusai.infra.llm.ModelConfigResolver;
import com.nexusai.infra.llm.ProviderConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-M-P1-2] FindRelevantMemories 对齐 CC findRelevantMemories.ts。
 *
 * <p>WHY（规则九 · 测试验证意图）: CC 真源关键行为——
 * <ul>
 *   <li>单 side-query（DEL-M-35：旧实现每轮双发 LLM 成本翻倍）</li>
 *   <li>selectRelevantMemories 用 sonnet + json_schema{selected_memories} + max_tokens=256 + querySource='memdir_relevance'</li>
 *   <li>alreadySurfaced 在 Sonnet 调用前过滤（5-slot 预算花在新鲜候选）</li>
 *   <li>失败/中止返回 []（DEL-M-33：无关键词降级，下轮重试）</li>
 *   <li>manifest 格式 '- [type] filename (ISO-ts): description'（CC memoryScan.ts:84-94）</li>
 * </ul>
 */
@DisplayName("[IMP-M-P1-2] FindRelevantMemories 检索对齐 CC findRelevantMemories.ts")
class FindRelevantMemoriesTest {

    /** 可编程 stub provider · 记录 userMessage + options（参数断言载体）。 */
    static class StubProvider extends MockLlmProvider {
        String response = """
            {"selected_memories": ["a.md"]}
            """.trim();
        RuntimeException toThrow;
        final List<String> userMessages = new ArrayList<>();
        final List<LlmProvider.ChatRequestOptions> captured = new ArrayList<>();

        @Override
        public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, LlmProvider.ChatRequestOptions options) {
            captured.add(options);
            userMessages.add(userMessage == null ? "" : userMessage);
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    /** 固定返回 stub 的工厂。 */
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

    private FindRelevantMemories build(StubProvider stub) {
        // [RV14B-WIRE-02] 注入 stub resolver（sonnet 字面量 → DB 名 → 真实 config），
        //   验证 side-query 走真实配置而非 ProviderConfig.empty()（恒 mock 根因修复）。
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolveFastModelName(Mockito.anyString())).thenReturn("claude-sonnet");
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        return new FindRelevantMemories(new FixedFactory(stub), "sonnet", new MemoryScanner(), resolver);
    }

    private void writeMemory(Path dir, String name, String type, String desc) throws Exception {
        Files.writeString(dir.resolve(name),
            "---\nname: " + name.replace(".md", "") + "\ntype: " + type + "\ndescription: " + desc + "\n---\nbody\n");
    }

    @Test
    @DisplayName("findRelevantMemories 单次调用恰好 1 次 side-query（DEL-M-35 双发消除）")
    void singleSideQuery_noDoubleFire(@TempDir Path memoryDir) throws Exception {
        // WHY: 旧实现每轮双发（prefetchAsync 内部 + LlmAgentLoop 直路）→ LLM 成本翻倍 + 2×2s 阻塞。
        //      对齐 CC findRelevantMemories.ts:39-75 —— findRelevantMemories 内恰好一次 selectRelevantMemories。
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        writeMemory(memoryDir, "b.md", "reference", "beta api");
        StubProvider stub = new StubProvider();
        FindRelevantMemories fr = build(stub);

        fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

        assertThat(stub.captured).as("一次检索必须恰好一次 side-query 调用").hasSize(1);
    }

    @Test
    @DisplayName("selectRelevantMemories 参数对齐 sonnet/json_schema/max_tokens=256/memdir_relevance")
    void sideQueryParams_alignCC(@TempDir Path memoryDir) throws Exception {
        // WHY: CC findRelevantMemories.ts:98-122 —— getDefaultSonnetModel + max_tokens:256 +
        //       json_schema{selected_memories} + querySource:'memdir_relevance'；recentTools 段 :92-95。
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        StubProvider stub = new StubProvider();
        FindRelevantMemories fr = build(stub);

        fr.findRelevantMemories("configure alpha", memoryDir, List.of("Bash", "Read"), Set.of(), null);

        LlmProvider.ChatRequestOptions opts = stub.captured.get(0);
        assertThat(opts.outputFormat()).as("output_format 必须是 json_schema").isNotNull();
        assertThat(opts.outputFormat().type()).isEqualTo("json_schema");
        assertThat(opts.outputFormat().schema().get("properties").has("selected_memories"))
            .as("schema 必须含 selected_memories 数组属性").isTrue();
        assertThat(opts.maxTokens()).as("max_tokens 必须 = 256（CC :108）").isEqualTo(256);
        assertThat(opts.querySource()).as("querySource 必须 = memdir_relevance（CC :121）").isEqualTo("memdir_relevance");
        // recentTools 段（CC :92-95）必须出现在 userMessage 中
        assertThat(stub.userMessages.get(0)).contains("Recently used tools: Bash, Read");
    }

    @Test
    @DisplayName("alreadySurfaced 在 Sonnet 调用前过滤（manifest 不含已展示文件）")
    void alreadySurfaced_filteredBeforeSelector(@TempDir Path memoryDir) throws Exception {
        // WHY: CC findRelevantMemories.ts:46-48 —— scanMemoryFiles 后 filter(!alreadySurfaced.has(filePath))，
        //       selector 5-slot 预算花在新鲜候选。已展示文件不应出现在 manifest（LLM 无法重选）。
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        writeMemory(memoryDir, "b.md", "reference", "beta api");
        StubProvider stub = new StubProvider();
        FindRelevantMemories fr = build(stub);
        Path aPath = memoryDir.resolve("a.md").toAbsolutePath();

        fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(aPath.toString()), null);

        assertThat(stub.captured).hasSize(1);
        // 已展示文件不应出现在发送给 selector 的 manifest 中
        assertThat(stub.userMessages.get(0)).doesNotContain("a.md");
        assertThat(stub.userMessages.get(0)).contains("b.md");
    }

    @Test
    @DisplayName("检索失败返回空列表（DEL-M-33：无关键词降级）")
    void failure_returnsEmpty(@TempDir Path memoryDir) throws Exception {
        // WHY: CC findRelevantMemories.ts:131-140 catch → return []；无关键词降级（旧 keywordFallback 删除）。
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        StubProvider stub = new StubProvider();
        stub.toThrow = new RuntimeException("provider down");
        FindRelevantMemories fr = build(stub);

        List<FindRelevantMemories.RelevantMemory> result =
            fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

        assertThat(result).as("失败必须返回空列表，由下轮重试").isEmpty();
    }

    @Test
    @DisplayName("候选为空（目录空）返回空且不发 side-query")
    void emptyMemories_returnsEmptyWithoutSideQuery(@TempDir Path memoryDir) throws Exception {
        // WHY: CC findRelevantMemories.ts:49-51 —— memories.length===0 → return []，不发 side-query。
        StubProvider stub = new StubProvider();
        FindRelevantMemories fr = build(stub);

        List<FindRelevantMemories.RelevantMemory> result =
            fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

        assertThat(result).isEmpty();
        assertThat(stub.captured).as("无候选不得发 side-query").isEmpty();
    }

    @Test
    @DisplayName("manifest 格式 '- [type] filename (ISO-ts): description'（CC memoryScan.ts:84-94）")
    void manifestFormat_alignsCC(@TempDir Path memoryDir) throws Exception {
        // WHY: CC memoryScan.ts:90-91 —— `${tag}${filename} (${ISO ts}): ${description}`，无 description
        //       省略 ': desc'。ISO 时间戳是 prompt-cache 稳定字节 + freshness 参照。
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        writeMemory(memoryDir, "no-desc.md", "user", "");
        FindRelevantMemories fr = build(new StubProvider());
        List<MemoryEntry> entries = new MemoryScanner().scan(memoryDir, null);

        String manifest = fr.formatManifest(entries);

        assertThat(manifest).contains("- [project] a.md (");
        assertThat(manifest).contains("): alpha config");
        // 无 description → 无 ': ' 后缀（CC :91 省略分支）
        assertThat(manifest).contains("- [user] no-desc.md (");
        assertThat(manifest.lines().anyMatch(l -> l.endsWith(")"))).as("无 description 行以 ')' 结尾").isTrue();
    }

    @Test
    @DisplayName("[rev2 X-1] manifest 时间戳恒 3 位毫秒（CC toISOString）：整秒补 .000、毫秒原样、纳秒截断（NEW-3/memoryScan.ts:88）")
    void formatManifest_timestampAlwaysThreeDigitMillis() {
        // WHY: CC memoryScan.ts:88 new Date(m.mtimeMs).toISOString() 恒输出
        //   yyyy-MM-dd'T'HH:mm:ss.SSS'Z'（整秒补 .000；毫秒零填充）；旧 Instant.toString()
        //   整秒省略毫秒（...T04:35:12Z）/纳秒原样（...T04:35:12.345678900Z）→ 注入 LLM 的
        //   manifest 字节差异（跨模块 X-1 两处消费方同病，rev2 P1-EX 收敛于单一入口）。
        MemoryEntry wholeSecond = new MemoryEntry(MemoryType.PROJECT, "alpha config", "a.md",
            Path.of("x/a.md"), java.time.Instant.parse("2026-08-06T04:35:12Z"));
        MemoryEntry withMillis = new MemoryEntry(MemoryType.USER, null, "b.md",
            Path.of("x/b.md"), java.time.Instant.parse("2026-08-06T04:35:12.345Z"));
        MemoryEntry withNanos = new MemoryEntry(null, "c desc", "c.md",
            Path.of("x/c.md"), java.time.Instant.parse("2026-08-06T04:35:12.345678900Z"));

        String manifest = FindRelevantMemories.formatManifest(
            List.of(wholeSecond, withMillis, withNanos));

        assertThat(manifest)
            .as("整秒必须补 .000（CC toISOString 恒 3 位毫秒）")
            .contains("- [project] a.md (2026-08-06T04:35:12.000Z): alpha config");
        assertThat(manifest)
            .as("毫秒原样 3 位；无 desc 省略冒号后缀")
            .contains("- [user] b.md (2026-08-06T04:35:12.345Z)");
        assertThat(manifest)
            .as("不得残留 Instant.toString 形态（整秒省略毫秒/纳秒原样）")
            .doesNotContain("12Z)").doesNotContain("12.345678")
            .doesNotContain("(2026-08-06T04:35:12Z)");
    }

    // ── MEM-01/G-21 + G-1: getDefaultSonnetModel env 覆盖 + provider 分支（不经 fast 链） ──

    @Test
    @DisplayName("MEM-01: env ANTHROPIC_DEFAULT_SONNET_MODEL 覆盖直用（CC model.ts:120-122）")
    void modelEnvOverride_wins() {
        // WHY: CC getDefaultSonnetModel 首查 env；设置后换模型、Java 旧实现忽略 env（EV-R2-M03-10）。
        com.nexusai.infra.llm.ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);

        String model = FindRelevantMemories.resolveDefaultSonnetModelName(
            "claude-sonnet-4-7-test", "sonnet", resolver);

        assertThat(model).as("env 覆盖必须直用，不触碰 resolver").isEqualTo("claude-sonnet-4-7-test");
        Mockito.verifyNoInteractions(resolver);
    }

    @Test
    @DisplayName("MEM-01: settings 未提供 → firstParty provider → sonnet46 默认（CC model.ts:127）")
    void modelProviderBranch_firstParty_sonnet46() {
        com.nexusai.infra.llm.ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        // G-1: 探针 base = DEFAULT_SONNET（sonnet46），resolveFastModelName 绝不再被调用
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                new ProviderConfig("https://api.anthropic.com", "sk-test"), "anthropic"));

        String model = FindRelevantMemories.resolveDefaultSonnetModelName(null, "sonnet", resolver);

        assertThat(model).as("firstParty（官方端点）→ sonnet46 默认")
            .isEqualTo(com.nexusai.application.agent.prompt.PromptCaching.DEFAULT_SONNET);
        Mockito.verify(resolver, Mockito.never()).resolveFastModelName(Mockito.anyString());
    }

    @Test
    @DisplayName("MEM-01: settings 未提供 → 3P provider → sonnet45 默认（CC model.ts:124-126）")
    void modelProviderBranch_thirdParty_sonnet45() {
        com.nexusai.infra.llm.ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        // G-1: 探针 base = DEFAULT_SONNET（sonnet46），resolveFastModelName 绝不再被调用
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));

        String model = FindRelevantMemories.resolveDefaultSonnetModelName(null, "sonnet", resolver);

        assertThat(model).as("3P → sonnet45 默认（configs.ts:44 firstParty 值）")
            .isEqualTo(com.nexusai.application.agent.prompt.PromptCaching.DEFAULT_SONNET_45);
        Mockito.verify(resolver, Mockito.never()).resolveFastModelName(Mockito.anyString());
    }

    @Test
    @DisplayName("G-1: fast/weak 档已配置但 medium 未配置 → sonnet 档绝不经 fast 链回退 haiku")
    void modelFastConfigured_mediumUnset_noFallbackToFast() {
        // WHY: CC findRelevantMemories.ts:99 恒 getDefaultSonnetModel()（model.ts:119-130），永不 consult
        //   fast/weak settings。旧实现 resolveDefaultSonnetModelName:419 走 resolveFastModelName 落入
        //   fast→weak→haiku —— operator 配 fast/weak 未配 medium 时记忆 side-query 静默降级 haiku
        //   （对齐度报告 G-1，P0 BUG）。回归：fast 档即使返回 haiku 名，medium 档也必须忽略。
        com.nexusai.infra.llm.ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolveFastModelName(Mockito.anyString())).thenReturn("claude-haiku-4-5-20251001");
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new com.nexusai.infra.llm.ModelConfigResolver.ResolvedModel(
                new ProviderConfig("https://api.anthropic.com", "sk-test"), "anthropic"));

        String model = FindRelevantMemories.resolveDefaultSonnetModelName(null, "sonnet", resolver);

        assertThat(model).as("G-1: medium 档直落 sonnet46，绝不用 fast/weak 档模型（haiku）")
            .isEqualTo(com.nexusai.application.agent.prompt.PromptCaching.DEFAULT_SONNET);
        Mockito.verify(resolver, Mockito.never()).resolveFastModelName(Mockito.anyString());
    }

    @Test
    @DisplayName("G-1: medium 未配置且 DB 无 sonnet 系 → 回落 DEFAULT_SONNET（fail-loud 不落 haiku）")
    void modelMediumUnset_noSonnetInDb_fallsBackToSonnet() {
        // WHY: 探针 resolve 均未命中（DB 无 sonnet）→ 仍回落 CC firstParty 默认 sonnet46（model.ts:127），
        //   调用链再 resolve 失败将 warn+skip 返回空 —— fail-loud，绝不因 fast/weak 命中而静默选 haiku。
        com.nexusai.infra.llm.ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);

        String model = FindRelevantMemories.resolveDefaultSonnetModelName(null, "sonnet", resolver);

        assertThat(model).as("探针全失败 → CC firstParty 默认 sonnet46")
            .isEqualTo(com.nexusai.application.agent.prompt.PromptCaching.DEFAULT_SONNET);
        Mockito.verify(resolver, Mockito.never()).resolveFastModelName(Mockito.anyString());
    }

    // ── MEM-02/G-22: sideQuery maxRetries=2 重试 ──

    /** 可编程重试 stub：前 failCount 次抛 LlmApiException，之后成功。 */
    static class RetryStub extends MockLlmProvider {
        int failCount;
        int status;
        int calls;
        final List<LlmProvider.ChatRequestOptions> captured = new ArrayList<>();

        @Override
        public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, LlmProvider.ChatRequestOptions options) {
            captured.add(options);
            if (calls++ < failCount) {
                throw new LlmApiException(status, java.util.Map.of(), "transient");
            }
            return "{\"selected_memories\": [\"a.md\"]}";
        }
    }

    /**
     * F2-MEM-02（返工）生产 provider 包装面 stub：前 failCount 次抛
     * {@code RuntimeException(failure)} —— AnthropicSdkProvider.chatWithOptions:976
     * 全异常包 RuntimeException 的等价形态；failure 可为包装的 LlmApiException /
     * SDK 可重试异常 / IOException 连接错误。
     */
    static class WrappedRetryStub extends MockLlmProvider {
        int failCount;
        final Throwable failure;
        int calls;
        final List<LlmProvider.ChatRequestOptions> captured = new ArrayList<>();

        WrappedRetryStub(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public String chatWithOptions(ProviderConfig config, String modelName, String systemPrompt,
                                      String userMessage, LlmProvider.ChatRequestOptions options) {
            captured.add(options);
            if (calls++ < failCount) {
                throw new RuntimeException("provider failed: " + failure.getMessage(), failure);
            }
            return "{\"selected_memories\": [\"a.md\"]}";
        }
    }

    private static FindRelevantMemories buildWith(MockLlmProvider stub) {
        ModelConfigResolver resolver = Mockito.mock(ModelConfigResolver.class);
        Mockito.when(resolver.resolveFastModelName(Mockito.anyString())).thenReturn("claude-sonnet");
        Mockito.when(resolver.resolve(Mockito.anyString())).thenReturn(
            new ModelConfigResolver.ResolvedModel(
                new ProviderConfig("http://fake.local", "sk-test"), "openai_sdk"));
        return new FindRelevantMemories(new FixedFactory(stub), "sonnet", new MemoryScanner(), resolver);
    }

    @Test
    @DisplayName("MEM-02: 瞬时 429 重试后成功（CC sideQuery.ts:116 maxRetries=2 → 2 次调用）")
    void retry_transient429_onceThenSuccess(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            RetryStub stub = new RetryStub();
            stub.failCount = 1;
            stub.status = 429;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("1 次失败 + 1 次重试 = 2 次调用").hasSize(2);
            assertThat(result).as("重试成功必须返回选中记忆").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("MEM-02: 连续 429 重试耗尽 → 3 次调用后返回空（maxRetries=2 → 共 3 次尝试）")
    void retry_transient429_exhausted(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            RetryStub stub = new RetryStub();
            stub.failCount = 99;
            stub.status = 429;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("maxRetries=2 → 恰好 3 次尝试").hasSize(3);
            assertThat(result).as("重试耗尽返回空（CC 失败返回空、下轮重试）").isEmpty();
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("MEM-02: 非瞬时错误（400）不重试（SDK 重试状态集 408/409/429/≥500）")
    void retry_nonRetryable_noRetry(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            RetryStub stub = new RetryStub();
            stub.failCount = 99;
            stub.status = 400;
            FindRelevantMemories fr = buildWith(stub);

            fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("400 不可重试 → 单次调用").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("F2-MEM-02: Anthropic 包装面——RuntimeException 包瞬时 429 仍重试（AnthropicSdkProvider:976 全异常包 RuntimeException → cause 链解包判状态）")
    void retry_anthropicWrapped429_retries(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            WrappedRetryStub stub = new WrappedRetryStub(
                new LlmApiException(429, java.util.Map.of(), "transient"));
            stub.failCount = 1;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("包装 429 亦须重试（1 次失败 + 1 次重试 = 2 次调用）").hasSize(2);
            assertThat(result).as("重试成功必须返回选中记忆").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("F2-MEM-02: Anthropic 包装面——连续 429 重试耗尽（maxRetries=2 → 共 3 次尝试）")
    void retry_anthropicWrapped429_exhausted(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            WrappedRetryStub stub = new WrappedRetryStub(
                new LlmApiException(429, java.util.Map.of(), "transient"));
            stub.failCount = 99;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("包装 429 耗尽 → 恰好 3 次尝试").hasSize(3);
            assertThat(result).as("重试耗尽返回空（CC 失败返回空、下轮重试）").isEmpty();
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("F2-MEM-02: 连接错误（IOException）重试（CC SDK APIConnectionError 重试面；OpenAiSdkProvider 连接错误原样上抛面）")
    void retry_connectionError_ioException_retries(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            WrappedRetryStub stub = new WrappedRetryStub(new java.io.IOException("connection reset"));
            stub.failCount = 1;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("连接错误须重试（1 次失败 + 1 次重试 = 2 次调用）").hasSize(2);
            assertThat(result).as("重试成功必须返回选中记忆").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("F2-MEM-02: SDK 可重试标记异常（AnthropicRetryableException）重试（SDK 客户端 maxRetries 面）")
    void retry_sdkRetryableException_retries(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            WrappedRetryStub stub = new WrappedRetryStub(
                new AnthropicRetryableException("retryable"));
            stub.failCount = 1;
            FindRelevantMemories fr = buildWith(stub);

            List<FindRelevantMemories.RelevantMemory> result =
                fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("SDK 可重试标记 → 重试（2 次调用）").hasSize(2);
            assertThat(result).as("重试成功必须返回选中记忆").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    @Test
    @DisplayName("F2-MEM-02: 纯逻辑错误（RuntimeException 包 IllegalStateException）不重试（非连接/非 API 状态 → 单次调用）")
    void retry_wrappedLogicError_noRetry(@TempDir Path memoryDir) throws Exception {
        long oldBackoff = FindRelevantMemories.retryBackoffBaseMs;
        FindRelevantMemories.retryBackoffBaseMs = 0L;
        try {
            writeMemory(memoryDir, "a.md", "project", "alpha config");
            WrappedRetryStub stub = new WrappedRetryStub(new IllegalStateException("logic bug"));
            stub.failCount = 99;
            FindRelevantMemories fr = buildWith(stub);

            fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), null);

            assertThat(stub.captured).as("非 API/连接错误不重试 → 单次调用").hasSize(1);
        } finally {
            FindRelevantMemories.retryBackoffBaseMs = oldBackoff;
        }
    }

    // ── MEM-03/G-20: signal 全链透传 ──

    @Test
    @DisplayName("MEM-03: signal 透传 → ChatRequestOptions.abortController 同一实例（CC findRelevantMemories.ts:117）")
    void signal_passedToSideQueryOptions(@TempDir Path memoryDir) throws Exception {
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        RetryStub stub = new RetryStub();
        FindRelevantMemories fr = buildWith(stub);
        com.nexusai.application.agent.tool.AbortController signal =
            new com.nexusai.application.agent.tool.AbortController();

        fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), signal);

        assertThat(stub.captured).hasSize(1);
        assertThat(stub.captured.get(0).abortController())
            .as("signal 必须原样进入 ChatRequestOptions（provider 请求前 abort 预检）")
            .isSameAs(signal);
    }

    @Test
    @DisplayName("MEM-03: abort 后不发起 side-query 调用（CC signal.throwIfAborted 请求前语义）")
    void signal_aborted_noSideQueryCall(@TempDir Path memoryDir) throws Exception {
        writeMemory(memoryDir, "a.md", "project", "alpha config");
        RetryStub stub = new RetryStub();
        FindRelevantMemories fr = buildWith(stub);
        com.nexusai.application.agent.tool.AbortController signal =
            new com.nexusai.application.agent.tool.AbortController();
        signal.abort();

        List<FindRelevantMemories.RelevantMemory> result =
            fr.findRelevantMemories("configure alpha", memoryDir, List.of(), Set.of(), signal);

        assertThat(stub.captured).as("abort 后不得发起调用（scan 亦被取消 → 无候选）").isEmpty();
        assertThat(result).isEmpty();
    }
}
