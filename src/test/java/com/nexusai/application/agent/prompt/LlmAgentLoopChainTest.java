package com.nexusai.application.agent.prompt;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [IMP-SP-08] LlmAgentLoop s10 新链意图测试 · 对齐 CC QueryEngine.ts:286-325 组装链
 * （fetchSystemPromptParts → buildEffectiveSystemPrompt → appendSystemContext →
 * prependUserContext → splitSysPromptPrefix）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则 9 · 测试验证意图，非行为）：本 Session 把旧 6-section 单
 * String 模型整类删除，单次切换到 CC 新机制。核心意图：
 * <ol>
 *   <li><b>custom 双指令消除（V-场景 HIGH）</b>：旧链在 custom system prompt 存在时仍拼接
 *       default sections + 再 append custom → 模型收到双份指令。CC 语义 custom 完整替换
 *       default（systemPrompt.ts:118-119 + queryContext.ts:62-63 短路）——custom 存在时
 *       default 段完全不出现在最终发送 blocks。本测试用真实组装器 + 真实 default sections
 *       验证替换而非追加。</li>
 *   <li><b>userContext 前置 meta user 消息</b>（api.ts:449-474）：claudeMd/currentDate 渲染为
 *       {@code <system-reminder>} 包裹的 isMeta user 消息置于队首（CLAUDE.md 顶部上下文通道）。</li>
 *   <li><b>systemContext 并入 systemPrompt</b>（api.ts:437-447）：gitStatus 以
 *       {@code gitStatus: ...} 行并入。</li>
 *   <li><b>boundary 默认 3P → 不插入</b>（OPD-SP-27）：useGlobalCacheScope=false → split 落默认模式。</li>
 * </ol>
 */
class LlmAgentLoopChainTest {

    @TempDir
    Path tmp;

    private SystemPromptContextProvider provider;
    private SystemPromptAssembler assembler;

    @BeforeEach
    void setUp() {
        GitStatusProvider fakeGit = new GitStatusProvider(tmp) {
            @Override
            public String getGitStatus() {
                return "Current branch: main\nStatus: clean";
            }
        };
        UserContextProvider fakeUser = new UserContextProvider(tmp) {
            @Override
            public String claudeMd() {
                return "项目指令";
            }

            @Override
            public String currentDate(String sessionStartDate) {
                return "Today's date is " + sessionStartDate + ".";
            }
        };
        // boundary gate：默认 3P → false（OPD-SP-27；useGlobalCacheScope 恒 false）
        assembler = new SystemPromptAssembler(new SystemPromptSectionCache(), () -> false);
        provider = new SystemPromptContextProvider("2026-08-05", fakeUser, fakeGit);
    }

    /** default 组装入口（真实组装器 → default sections 含 7 静态 + 动态 session_guidance 等）。 */
    private SystemPrompt assembleDefault() {
        SystemPromptAssemblyInput input = new SystemPromptAssemblyInput(Set.of("Bash", "Read"), "claude-sonnet-4-6", List.of(), List.of(), null, List.of("commit"), null, null, false);
        return assembler.assemble(input);
    }

    @Test
    @DisplayName("custom 双指令消除：custom 非空 → default sections 完全不出现在最终 blocks（CC systemPrompt.ts:118-119 替换语义）")
    void custom_replacesDefault_singleInstruction() {
        String custom = "You are a specialized reviewer. Only review, never write code.";

        // 1. fetchSystemPromptParts（custom 短路 I-13）
        SystemPromptParts parts = provider.fetchSystemPromptParts(custom, this::assembleDefault);
        assertThat(parts.defaultSystemPrompt()).as("I-13：custom 定义 → default 短路为 []（跳过 default 组装）").isEmpty();
        assertThat(parts.systemContext()).as("I-13：custom 定义 → systemContext 短路为 {}（不会拼到未使用的 default）").isEmpty();

        // 2. buildEffectiveSystemPrompt（custom 替换 default）
        SystemPrompt effective = EffectiveSystemPromptBuilder.build(
            () -> SystemPrompt.from(parts.defaultSystemPrompt()),
            null, custom, null);
        assertThat(effective.elements())
            .as("custom 非空 → 结果仅 [custom]（不含任何 default section）")
            .containsExactly(custom);

        // 3. appendSystemContext（systemContext 空 → 无并入块，api.ts:437-447 filter(Boolean)）
        List<String> full = provider.appendSystemContext(effective, parts.systemContext());
        assertThat(full).as("custom + 空 systemContext → 仅 [custom]").containsExactly(custom);

        // 4. prependUserContext（userContext 前置 meta user 消息）
        List<com.nexusai.model.session.dto.ChatMessageDto> messages =
            com.nexusai.application.agent.loop.AgentLoopContext.prependUserContext(List.of(), parts.userContext());
        assertThat(messages).hasSize(1);
        assertThat(messages.get(0).isMeta()).as("userContext 前置消息必须 isMeta=true（CC createUserMessage isMeta:true）").isTrue();
        assertThat(messages.get(0).content())
            .as("claudeMd 注入 system-reminder（CLAUDE.md 顶部上下文）")
            .contains("<system-reminder>")
            .contains("# claudeMd\n项目指令")
            .contains("# currentDate\nToday's date is 2026-08-05.");

        // 5. splitSysPromptPrefix（默认 3P → boundary 模式不触发，默认模式）
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(full, false, false);
        String joined = blocks.stream().map(SystemPromptBlock::text).reduce((a, b) -> a + "\n\n" + b).orElse("");
        assertThat(joined).as("custom 双指令消除：最终发送文本无 default identity/session_guidance 段").contains(custom);
        assertThat(joined).as("default sections 不得泄露进发送文本（双指令消除核心断言）")
            .doesNotContain("You are an interactive agent")
            .doesNotContain("# Session-specific guidance");
    }

    @Test
    @DisplayName("无 custom → default 组装全量发送 + userContext 前置（默认路径）")
    void noCustom_defaultAssembledAndPrependUser() {
        // 1-2. fetch + buildEffectiveSystemPrompt（无 custom → default 全量）
        SystemPromptParts parts = provider.fetchSystemPromptParts(null, this::assembleDefault);
        assertThat(parts.defaultSystemPrompt()).as("无 custom → default 正常组装（非空）").isNotEmpty();
        SystemPrompt effective = EffectiveSystemPromptBuilder.build(
            () -> SystemPrompt.from(parts.defaultSystemPrompt()), null, null, null);
        assertThat(effective.elements())
            .as("default 元素贯通（7 静态 + 动态 + null filter）")
            .anySatisfy(s -> assertThat(s).contains("You are an interactive agent"));

        // 3. appendSystemContext
        List<String> full = provider.appendSystemContext(effective, parts.systemContext());

        // 4. prependUserContext：空 userContext → 原列表（api.ts:455 Object.entries 空返回原 messages）
        List<com.nexusai.model.session.dto.ChatMessageDto> noUser =
            com.nexusai.application.agent.loop.AgentLoopContext.prependUserContext(List.of(), Map.of());
        assertThat(noUser).as("空 userContext → 不前置 meta 消息（api.ts:453-456）").isEmpty();

        // 5. splitSysPromptPrefix（default + gitStatus，默认模式）
        List<SystemPromptBlock> blocks = SystemPromptSplitter.splitSysPromptPrefix(full, false, false);
        assertThat(blocks).as("默认模式：无 boundary → 单 org block 或 prefix+rest 拆分").isNotEmpty();
    }

    @Test
    @DisplayName("boundary gate 3P 默认不插入 → split 落默认模式（OPD-SP-27，无 boundary 元素）")
    void boundary_gate_off_defaultMode() {
        // gate=false → assembler 不插 boundary
        SystemPrompt assembled = assembleDefault();
        assertThat(assembled.elements())
            .as("useGlobalCacheScope=false → 不插入 SYSTEM_PROMPT_DYNAMIC_BOUNDARY（prompts.ts:573 门控）")
            .doesNotContain(SystemPromptAssembler.SYSTEM_PROMPT_DYNAMIC_BOUNDARY);
    }
}
