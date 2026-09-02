package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.QuerySource;
import com.nexusai.application.agent.hook.PostSamplingContext;
import com.nexusai.application.agent.hook.PostSamplingHookRegistry;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook.ProjectSkill;
import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import com.nexusai.infra.llm.LlmProvider;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P2-16] SkillImprovement 侧信道 LLM 查询 options 测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/skillImprovement.ts:236-249} 的
 * {@code queryModelWithoutStreaming} 查询选项。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的侧信道查询 (applier + 检测器) 必须携带
 * {@code thinkingConfig:{type:'disabled'}} (:236) + {@code tools: []} (:237) +
 * {@code temperatureOverride: 0} (:245) + {@code querySource:'skill_improvement_apply'} (:247) +
 * 每次新建的 AbortController (:238) — 这些选项决定 LLM 行为 (关闭 thinking、温度 0 确定性输出、
 * 来源可区分)。旧架构 {@code modelQuery} 是 2 参 {@code BiFunction<String,String,String>}
 * (systemPrompt, prompt) 无法传 options — 3 参升级丢失任一选项都属契约错位。
 *
 * <p>RED→GREEN: 改造前 modelQuery 是 2 参 BiFunction, 本测试 3 参 lambda 无法编译
 * (RED = 编译失败证据); 改造后 ({@code SkillImprovementModelQuery} 3 参) 编译通过且断言
 * options 三字段 (GREEN).
 */
@DisplayName("[P2-16] SkillImprovement 侧信道 LLM 查询携带 CC 查询选项")
class SkillImprovementSideChannelTest {

    private static ChatMessageDto userMsg(String content) {
        return new ChatMessageDto("m", "sess", Role.user, "user", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    private static ChatMessageDto assistantMsg(String content) {
        return new ChatMessageDto("m", "sess", Role.assistant, "assistant", content, null, null, null,
                null, null, "刚刚", OffsetDateTime.now(), null, null, null, null, null);
    }

    private static PostSamplingContext mainThreadContext(List<ChatMessageDto> messages) {
        return new PostSamplingContext(messages, List.of("system"), Map.of(), Map.of(), null, QuerySource.REPL_MAIN_THREAD);
    }

    private static List<ChatMessageDto> withUserCount(int n) {
        List<ChatMessageDto> msgs = new ArrayList<>();
        for (int i = 0; i < n; i++) msgs.add(userMsg("user msg " + i));
        msgs.add(assistantMsg("ok"));
        return msgs;
    }

    /**
     * WHY: applier (doApplySkillImprovement) 的侧信道查询必须带 CC options
     * (skillImprovement.ts:236-249) — 否则 LLM 可能开 thinking / 非确定性温度 / 来源不可区分.
     * 断言 capturedOptions 三字段: thinkingConfig.disabled + temperature=0 + querySource +
     * abortController 非 null (每次 apply 新建, :238).
     */
    @Test
    @DisplayName("applier 侧信道查询收到 options: thinkingConfig=disabled + temperature=0 + querySource=skill_improvement_apply + abort")
    void applier_sideChannelCarriesCcQueryOptions(@TempDir Path tempDir) throws Exception {
        // R9-2：项目级技能目录随 appName 动态（决策 D1/D6）= <baseDir>/<getProjectDirName()>/skills
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "# Original content");

        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, prompt, options) -> {
                    capturedOptions.set(options);
                    return "<updated_file># Rewritten</updated_file>";
                },
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        CompletableFuture<Void> future = hook.applySkillImprovement("my-skill",
                List.of(new SkillUpdate("new step", "ask energy", "user asked")));
        future.join();

        LlmProvider.ChatRequestOptions options = capturedOptions.get();
        assertThat(options).isNotNull();
        // CC skillImprovement.ts:236 thinkingConfig:{type:'disabled'}
        assertThat(options.thinkingConfig()).isNotNull();
        assertThat(options.thinkingConfig().type()).isEqualTo("disabled");
        // CC :245 temperatureOverride: 0 → ChatRequestOptions.temperature == 0d
        assertThat(options.temperature()).isEqualTo(0d);
        // CC :247 querySource:'skill_improvement_apply'
        assertThat(options.querySource()).isEqualTo("skill_improvement_apply");
        // CC :238 createAbortController().signal — 每次 apply 新建, 非 null 且未 abort
        assertThat(options.abortController()).isNotNull();
        assertThat(options.abortController().isCancelled()).isFalse();
        // CC :237 tools: [] — 空工具数组 = 不调工具 (Java 端 tools 未设置)
        assertThat(options.tools()).isNull();
    }

    /**
     * WHY: 检测器 (createSkillImprovementHook executor) 的侧信道查询必须带
     * querySource='skill_improvement' (apiQueryHookHelper.ts:104 config.name) + thinking disabled
     * (:88) + temperature 0 (:102) — 闭合 B8. 若 executor 不带 options, 检测器与 applier
     * 双轨漂移, 来源标记不可区分.
     */
    @Test
    @DisplayName("检测器 executor 收到 options: querySource=skill_improvement + thinkingConfig=disabled + temperature=0")
    void detector_sideChannelCarriesCcQueryOptions() {
        AtomicReference<LlmProvider.ChatRequestOptions> capturedOptions = new AtomicReference<>();
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, prompt, options) -> {
                    capturedOptions.set(options);
                    return "<updates>[{\"section\":\"new step\",\"change\":\"c\",\"reason\":\"r\"}]</updates>";
                },
                () -> Optional.of(new ProjectSkill("proj-skill", "# Steps\n1. do x")),
                new Telemetry(),
                (skillName, updates) -> {},
                Path.of("."));

        PostSamplingHookRegistry.PostSamplingHook postHook = hook.createSkillImprovementHook();
        postHook.onSampled(mainThreadContext(withUserCount(10)));

        LlmProvider.ChatRequestOptions options = capturedOptions.get();
        assertThat(options).isNotNull();
        // CC apiQueryHookHelper.ts:104 querySource: config.name → 'skill_improvement'
        assertThat(options.querySource()).isEqualTo("skill_improvement");
        // CC :88 thinkingConfig:{type:'disabled'}
        assertThat(options.thinkingConfig()).isNotNull();
        assertThat(options.thinkingConfig().type()).isEqualTo("disabled");
        // CC :102 temperatureOverride: 0
        assertThat(options.temperature()).isEqualTo(0d);
        // CC :90 createAbortController().signal
        assertThat(options.abortController()).isNotNull();
    }
}
