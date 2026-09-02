package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.permission.hook.SkillImprovementHook.SkillUpdate;
import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.agent.telemetry.Telemetry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-14] SkillImprovementHook applier systemPrompt 语义测试 · 对齐 CC
 * {@code Open-ClaudeCode/src/utils/hooks/skillImprovement.ts:233-235}.
 *
 * <p>WHY (规则九 · 测试验证意图): CC 的 applier ({@code applySkillImprovement}) 向侧信道 LLM 发送
 * <b>applier 专属</b> systemPrompt {@code 'You edit skill definition files to incorporate user
 * preferences. Output only the updated file content.'} (skillImprovement.ts:234), 而非检测器
 * SYSTEM_PROMPT {@code 'You detect user preferences and process improvements...'} (:129-130).
 * 若发错 prompt, LLM 会按<b>检测器</b>指令 (找用户偏好) 而非<b>改写</b>指令 (编辑 skill 文件) 执行 —
 * 改写结果语义错误 (输出偏好检测 JSON 而非 &lt;updated_file&gt;, 或改写风格完全跑偏).
 *
 * <p>RED→GREEN (P1-14): 改造前 {@code SkillImprovementHook} 5 参/6 参构造收 {@code Function<String,String>}
 * 单参函数, 本测试传 {@code BiFunction} 无法编译 (RED = 编译失败证据); 改造后 (modelQuery 升级
 * {@code BiFunction<String,String,String>}, systemPrompt 按调用传入) 编译通过且断言 applier
 * 收到 APPLIER_SYSTEM_PROMPT (GREEN).
 * <p>RED→GREEN (P2-16): modelQuery 再升级为 {@link SkillImprovementHook.SkillImprovementModelQuery}
 * 3 参 (systemPrompt, prompt, options), 本测试 lambda 迁移为 3 参 (编译失败即 RED 迁移证据).
 */
@DisplayName("[P1-14] applier 侧信道 LLM 收到 applier 专属 systemPrompt (非检测器 SYSTEM_PROMPT)")
class SkillImprovementApplierTest {

    private static final String REWRITTEN = "# Rewritten by applier\nimproved content";

    /**
     * WHY: applier (doApplySkillImprovement) 调侧信道 LLM 时 systemPrompt 必须是
     * APPLIER_SYSTEM_PROMPT 而非检测器 SYSTEM_PROMPT — 两 prompt 语义不同 (检测器找偏好 vs
     * applier 改文件). 旧架构 buildModelQuery 硬编码 SYSTEM_PROMPT 使 applier 复用检测器 prompt,
     * 属 P1-14 修复的核心错位.
     */
    @Test
    @DisplayName("applier 侧信道查询收到 APPLIER_SYSTEM_PROMPT 而非检测器 SYSTEM_PROMPT")
    void applier_sendsApplierSystemPromptNotDetectorSystemPrompt(@TempDir Path tempDir) throws Exception {
        // R9-2：项目级技能目录随 appName 动态（决策 D1/D6）= <baseDir>/<getProjectDirName()>/skills
        Path skillDir = tempDir.resolve(NexusaiPaths.getProjectDirName()).resolve("skills").resolve("my-skill");
        Files.createDirectories(skillDir);
        Path skillMd = skillDir.resolve("SKILL.md");
        Files.writeString(skillMd, "# Original content");

        AtomicReference<String> capturedSystemPrompt = new AtomicReference<>();
        AtomicReference<String> capturedUserPrompt = new AtomicReference<>();
        SkillImprovementHook hook = new SkillImprovementHook(
                (systemPrompt, prompt, options) -> {
                    capturedSystemPrompt.set(systemPrompt);
                    capturedUserPrompt.set(prompt);
                    return "<updated_file>" + REWRITTEN + "</updated_file>";
                },
                () -> Optional.empty(),
                new Telemetry(),
                (skillName, updates) -> {},
                tempDir);

        CompletableFuture<Void> future = hook.applySkillImprovement("my-skill",
                List.of(new SkillUpdate("new step", "ask energy", "user asked")));
        future.join();

        // applier 收到 applier 专属 prompt (CC skillImprovement.ts:233-235), 非检测器 prompt
        assertThat(capturedSystemPrompt.get())
                .isEqualTo(SkillImprovementHook.APPLIER_SYSTEM_PROMPT)
                .isNotEqualTo(SkillImprovementHook.SYSTEM_PROMPT);
        // 用户 prompt 含 skill 文件与改进清单 (CC skillImprovement.ts:215-230)
        assertThat(capturedUserPrompt.get()).contains("<current_skill_file>");
        assertThat(capturedUserPrompt.get()).contains("# Original content");
        assertThat(capturedUserPrompt.get()).contains("<improvements>");
        assertThat(capturedUserPrompt.get()).contains("- new step: ask energy");
        // 写回成功 (LLM 返回 <updated_file> → 提取 → 写回, CC :252-263)
        assertThat(Files.readString(skillMd)).isEqualTo(REWRITTEN);
    }
}
