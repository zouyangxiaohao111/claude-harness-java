package com.nexusai.application.agent.compact.fork;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.nexusai.application.agent.compact.StreamCompactSummary;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import com.nexusai.application.agent.prompt.SystemPrompt;
import com.nexusai.application.agent.prompt.SystemPromptAssembler;
import com.nexusai.application.agent.prompt.SystemPromptBlock;
import com.nexusai.application.agent.prompt.SystemPromptContextProvider;
import com.nexusai.application.agent.prompt.UserContextProvider;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.ToolRegistry;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.infra.llm.AssistantMessage;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.infra.llm.ProviderConfig;
import com.nexusai.infra.llm.TaskBudgetParam;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.FinishReason;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [E-1a] fork 系统提示 pre-append 契约测试。
 *
 * <p><b>WHY 本测试存在（意图 · CLAUDE.md 规则九）</b>：{@code appendSystemContext}
 * <b>不是幂等函数</b> —— 每次调用都把 {@code systemContext} map join 成字符串<b>追加为末尾元素</b>
 * （{@code SystemPromptContextProvider.appendSystemContext}）。若上游存 post-append 值、
 * 而消费点（fork 发送边界）再 append 一次，fork 发给 provider 的 system blocks 就比主线程多
 * 一段 → prompt cache key 前缀不一致 → <b>cache 永不命中</b>（IMP-MV2-09 T9 修掉的同类 bug 重演）。
 *
 * <p>本测试钉死三件事：
 * <ol>
 *   <li><b>金样</b>：{@code CacheSharingParamsBuilder}（生产压缩 fork 组装链）→
 *       {@code StreamCompactSummary} → 真 {@link ProductionForkedQuery} → provider 实收
 *       {@code SystemPromptBlock} 列表 = 逐字节金样（钉住「E-1a 不改发送字节」）。</li>
 *   <li><b>形态等价</b>：<b>pre-append 原料 + 独立 systemContext map</b>（= E-1a 后
 *       {@code CacheSafeParams}/{@code ForkRawMaterial}/{@code PostSamplingContext} 的形态）
 *       经同一发送边界产出的 blocks，与生产 builder 路径<b>逐字节相同</b>。
 *       这是「append 移到使用点」的等价性证明 —— 上游存 pre-append 不会丢 systemContext 段。</li>
 *   <li><b>非幂等证据</b>：对 post-append 值再 append 一次 ≠ 一次，证明第 2 条的等价性
 *       依赖「恰好并入一次」而非巧合。</li>
 * </ol>
 *
 * <p><b>RED teeth</b>：删掉 {@code ProductionForkedQuery} 使用点的 append（或把上游改回
 * post-append 而使用点仍 append）→ 第 1/2 条断言 RED（blocks 少一段 gitStatus / 多一段）。
 */
@DisplayName("[E-1a] fork 系统提示 pre-append 契约：发送边界恰 append 一次")
class ForkSystemPromptPreAppendContractTest {

    private static final String SESSION_DATE = "2026-08-06";
    private static final String SUMMARY_REQUEST = "请对会话做摘要";

    /** 组装段数组（pre-append 形态 · 与 CacheSharingParamsBuilderTest 同款输入）。 */
    private static final List<String> ASSEMBLED_SEGMENTS = List.of(
        "DEFAULT-1", "DEFAULT-2",
        SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY, "DYNAMIC-1", "APPEND-CMD");

    /** 金样（改动前 master 上捕获的 ProductionForkedQuery 实发 blocks 文本）。 */
    private static final String GOLDEN_3P_BLOCK_TEXT =
        "DEFAULT-1\n\nDEFAULT-2\n\n__SYSTEM_PROMPT_DYNAMIC_BOUNDARY__\n\nDYNAMIC-1\n\nAPPEND-CMD"
            + "\n\ngitStatus: GIT-BLOCK";

    @TempDir
    Path tmp;

    @Test
    @DisplayName("金样: 生产 builder 链 → fork 发送 blocks = [单 ORG block · 含 systemContext 段] 逐字节")
    void golden_productionBuilderPath_bytesUnchanged() {
        List<SystemPromptBlock> blocks = sendViaBuilder(false, false);

        assertThat(blocks).as("3P 默认（gate=false）→ rest 以 \\n\\n join 为单 block").hasSize(1);
        assertThat(blocks.get(0).text())
            .as("金样：组装段数组 + appendSystemContext 并入的 gitStatus 块（末尾恰一段）")
            .isEqualTo(GOLDEN_3P_BLOCK_TEXT);
        // gate=true（firstParty/boundary 场景）: boundary 剥离 + 静态→GLOBAL + 动态（含并入的
        //   systemContext 段）→NULL —— 同批改动前后逐字节一致（金样 BLOCKS=2）。
        List<SystemPromptBlock> gated = sendViaBuilder(true, true);
        assertThat(gated).as("boundary 剥离后 2 block（静态 + 动态）").hasSize(2);
        assertThat(gated).extracting(SystemPromptBlock::text)
            .as("boundary 字面量永不发送给 LLM（api.ts:369-379）")
            .noneMatch(t -> t != null && t.contains(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY));
    }

    @Test
    @DisplayName("形态等价: pre-append 原料 + systemContext map 经同一发送边界 = 生产 builder 路径逐字节相同")
    void preAppendMaterial_byteIdenticalToBuilderPath() {
        List<SystemPromptBlock> fromBuilder = sendViaBuilder(false, false);
        List<SystemPromptBlock> fromCapturedMaterial = send(safeParams(
            ASSEMBLED_SEGMENTS, Map.of("claudeMd", "项目指令"), Map.of("gitStatus", "GIT-BLOCK"),
            false));

        // E-1a 前：捕获原料形态产出 blocks 少 gitStatus 段（len 79 ≠ 101）—— 因为旧契约要求上游
        //   自己 append（存 post-append），捕获点若存 pre-append 就丢段。E-1a 后 append 在使用点，
        //   两种上游形态产出完全一致。
        assertThat(fromCapturedMaterial).as("pre-append 原料 → 发送边界 append → 与生产路径同字节")
            .isEqualTo(fromBuilder);
        assertThat(fromCapturedMaterial.get(0).text())
            .as("systemContext 段（gitStatus）必须在最终发送 blocks 中")
            .contains("gitStatus: GIT-BLOCK");
    }

    @Test
    @DisplayName("非幂等证据: 对 post-append 值再 append 一次 = 多一段 → 与主线程 blocks 不一致")
    void appendSystemContext_notIdempotent_secondCallAddsSegment() {
        Map<String, String> sysContext = Map.of("gitStatus", "GIT-BLOCK");
        List<String> once = SystemPromptContextProvider.appendSystemContext(
            SystemPrompt.from(ASSEMBLED_SEGMENTS), sysContext);
        List<String> twice = SystemPromptContextProvider.appendSystemContext(
            SystemPrompt.from(once), sysContext);

        assertThat(once).as("一次 append → 原段 + 1 段 context").hasSize(ASSEMBLED_SEGMENTS.size() + 1);
        assertThat(twice)
            .as("再 append → 多一段（这就是「上游 post-append + 使用点再 append」产生 cache 破坏的机制）")
            .hasSize(ASSEMBLED_SEGMENTS.size() + 2);
        assertThat(String.join("\n\n", twice)).isNotEqualTo(String.join("\n\n", once));
    }

    // ════════════════════════════════════════════════════════════════════
    // 发送链（真 ProductionForkedQuery · 断言面 = provider 实收 blocks）
    // ════════════════════════════════════════════════════════════════════

    /** 生产 builder 组装链（压缩 fork 路径）。 */
    private List<SystemPromptBlock> sendViaBuilder(boolean gate, boolean remote) {
        Map<String, String> env = new HashMap<>();
        if (remote) {
            env.put("CLAUDE_CODE_REMOTE", "true");
        }
        SystemPromptContextProvider provider = new SystemPromptContextProvider(
            SESSION_DATE,
            new UserContextProvider(tmp) {
                @Override public String claudeMd() { return "项目指令"; }

                @Override public String currentDate(String sessionStartDate) {
                    return "Today's date is " + sessionStartDate + ".";
                }
            },
            new GitStatusProvider(tmp) {
                @Override public String getGitStatus() { return "GIT-BLOCK"; }
            },
            env::get);
        CacheSafeParams cs = CacheSharingParamsBuilder.build(
            provider,
            () -> SystemPrompt.from(ASSEMBLED_SEGMENTS),
            null, null, baseContext(), List.of(userMessage("c1", "ctx1")), gate);
        return send(cs);
    }

    /** 构造 CacheSafeParams（E-1a pre-append 契约形态）。 */
    private static CacheSafeParams safeParams(List<String> systemPrompt,
                                              Map<String, String> userContext,
                                              Map<String, String> systemContext,
                                              boolean gate) {
        return new CacheSafeParams(systemPrompt, userContext, systemContext,
            baseContext(), List.of(userMessage("c1", "ctx1")), gate);
    }

    /** 走真 StreamCompactSummary → RunForkedAgent → ProductionForkedQuery，返回 provider 实收 blocks。 */
    private List<SystemPromptBlock> send(CacheSafeParams cs) {
        Capturing provider = new Capturing();
        StreamCompactSummary scs = new StreamCompactSummary(
            () -> provider, () -> "model", ProviderConfig::empty,
            () -> cs, () -> new AbortController(), null, null,
            false, true, false, null, null, null);
        scs.setForkedQuery(new ProductionForkedQuery(
            () -> provider, () -> "model", ProviderConfig::empty, new ToolRegistry()));
        scs.streamCompactSummary(
            List.of(userMessage("u1", "ctx")), SUMMARY_REQUEST, 0,
            "model", provider, ProviderConfig.empty());
        assertThat(provider.blocks).as("fake provider 必须真实收到 fork 请求（否则本测试空转）").isNotNull();
        return provider.blocks;
    }

    private static ToolUseContext baseContext() {
        return new ToolUseContext(
            UUID.randomUUID(), "sess-" + UUID.randomUUID().toString().substring(0, 8),
            PermissionMode.DEFAULT, Map.of(), List.of(), "", new AbortController(), List.of());
    }

    private static ChatMessageDto userMessage(String id, String content) {
        return new ChatMessageDto(
            id, null, Role.user, "user", content, null, List.of(), FinishReason.stop,
            null, null, "刚刚", OffsetDateTime.now(), null, null, null,
            List.of(), List.of(), null, false, false);
    }

    /** 捕获真实发给 provider 的 system blocks（断言面 = 生产路径最后一道）。 */
    static final class Capturing implements LlmProvider {
        volatile List<SystemPromptBlock> blocks;

        @Override public String type() { return "test"; }

        @Override public String chat(ProviderConfig c, String m, String sp, String userMessage) {
            return "summary text";
        }

        @Override
        public void stream(ProviderConfig config, String modelName,
                           List<SystemPromptBlock> systemPromptBlocks,
                           List<ChatMessageDto> history, ArrayNode tools,
                           Integer maxOutputTokensOverride, TaskBudgetParam taskBudget,
                           String effortValue, String querySource,
                           Consumer<String> onChunk,
                           Consumer<AssistantMessage> onAssistantMessage,
                           Consumer<ToolUseBlock> onToolCallComplete,
                           Consumer<String> onReasoningChunk,
                           Runnable onStreamingFallback,
                           AbortController abortController,
                           Consumer<Throwable> onError,
                           Runnable onComplete, Boolean skipCacheWrite) {
            this.blocks = systemPromptBlocks == null ? null : List.copyOf(systemPromptBlocks);
            onAssistantMessage.accept(new AssistantMessage("summary text", "stop", List.of()));
            onComplete.run();
        }
    }
}
