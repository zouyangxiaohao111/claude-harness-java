package com.nexusai.application.agent.prompt;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.application.session.SessionArtifactService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 交付物目录指引段单测 · 纯 JUnit，无 Spring 上下文。
 *
 * <p><b>WHY（2026-11-02 用户裁定「做法 A」+ 规则九）</b>：用户问「会话 id 系统自行填入？」——
 * 答案必须是<b>后端渲染真实绝对路径</b>，而不是把 {@code <sessionId>} 占位符丢给模型自己替换
 * （模型会拼错 / 会漏 / 会自创路径）。本测试钉住这条契约：
 * <ol>
 *   <li>段里出现的是**真实路径**（含具体 sessionId 与 deliverables 目录），不是占位符；</li>
 *   <li>明确要求「不要额外列清单」（用户裁定：不给前端加噪音）；</li>
 *   <li>明确要求「源码/临时脚本不要放这里」；</li>
 *   <li>无会话 → 不注入该段。</li>
 * </ol>
 */
class ArtifactsGuidanceSectionTest {

    private static final String SESSION = "sess-guidance";

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        NexusaiPaths.setConfigHomeDirOverride(null);
    }

    private static SystemPromptAssemblyInput inputWith(String sessionId) {
        return new SystemPromptAssemblyInput(
                Set.of("Read", "Edit"),
                "claude-sonnet-4-6",
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                null,
                false,      // tokenBudgetEnabled
                sessionId,  // sessionId
                false,      // nonInteractiveSession
                false,      // scratchpadEnabled
                false,      // frcEnabled
                null        // sessionSlots
        );
    }

    /** 取该段 compute 的文本（未注册 / compute 返回 null ⇒ null）。 */
    private static String sectionText(String sessionId) throws Exception {
        List<SystemPromptSection> sections = SystemPromptSections.buildDynamicSections(inputWith(sessionId));
        SystemPromptSection section = sections.stream()
                .filter(s -> "artifacts_guidance".equals(s.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("artifacts_guidance section 必须被注册"));
        return section.compute().compute().get();
    }

    @Test
    @DisplayName("段文案里是**真实绝对路径**（不是 <sessionId> 占位符）")
    void rendersRealAbsolutePath() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());

        String text = sectionText(SESSION);

        assertThat(text).isNotNull();
        Path expected = SessionArtifactService.deliverablesDir(SESSION);
        assertThat(text)
                .as("必须写入渲染后的真实路径（模型直接可用，无需替换占位符）")
                .contains(expected.toString());
        assertThat(text)
                .as("⛔ 不得出现占位符（模型不会可靠地替换它）")
                .doesNotContain("<sessionId>", "{{sessionId}}", "$SESSION_ID");
    }

    @Test
    @DisplayName("允许「技能要求固定位置 → 生成后再复制过来」（WHY：强制直写会与技能约束冲突）")
    void allowsCopyWhenSkillRequiresFixedLocation() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());

        String text = sectionText(SESSION);

        // 用户 2026-11-03：「有些技能会要求固定地址，会冲突」。
        // 提示词必须给出这条**不必违抗技能**的路：在原位置生成，然后把最终文件复制过来。
        assertThat(text)
            .as("必须明确允许「按技能要求生成，再复制到本目录」")
            .contains("requires a fixed location");
        assertThat(text)
            .as("必须说清复制即可（不要求移动/改写技能产出的位置）")
            .contains("copy the final file into this directory");
        assertThat(text)
            .as("⛔ 不得暗示「必须挪走原件」——那会破坏技能/项目的目录约定")
            .contains("do not move the original away");
        // 脚本同理：可以任意位置输出，只要最后复制过来
        assertThat(text)
            .as("脚本路径也要允许任意输出位置")
            .contains("may output wherever it needs");
        // 但仍然首选直写（不希望因为「允许复制」就把最常见路径写成绕路）
        assertThat(text).contains("Simplest");
    }

    @Test
    @DisplayName("明确要求「不要在回复里另列清单」（后端自己扫目录，不给前端加噪音）")
    void tellsModelNotToEnumerateFiles() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());

        String text = sectionText(SESSION);

        assertThat(text).as("用户裁定：不列出来，后端自己查")
                .contains("Do NOT write a separate list");
        assertThat(text).as("说明是系统自动扫描展示").contains("system scans this");
    }

    @Test
    @DisplayName("明确排除源码 / 临时脚本 / 日志（与后端黑名单同向）")
    void excludesSourceCode() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());

        String text = sectionText(SESSION);

        assertThat(text).contains("Do NOT put source code");
        assertThat(text).contains("temporary scripts");
    }

    /**
     * ⭐ [2026-11-02 用户追问「这个路径也不能固定，我们不是有 configHome 动态的吗」]
     *
     * <p>钉住「路径**跟随 configHome 变化**」：提示词里绝不能出现写死的 {@code ~/.nexusai}。
     * 换 appName（场景版 {@code .nexusai-scene}）或测试覆写 configHome，渲染出的路径必须同步变。
     */
    @Test
    @DisplayName("路径跟随 configHome 动态变化（⛔ 不写死 ~/.nexusai）")
    void pathFollowsConfigHome() throws Exception {
        // ① 覆写到 A
        Path homeA = tempDir.resolve("home-a");
        NexusaiPaths.setConfigHomeDirOverride(homeA.toString());
        String textA = sectionText(SESSION);
        assertThat(textA).contains(homeA.toString());
        assertThat(textA).as("⛔ 不得出现写死的 ~/.nexusai").doesNotContain("/Users/" + System.getProperty("user.name") + "/.nexusai");

        // ② 覆写到 B（模拟换 appName / 换机器）→ 渲染结果必须跟着变
        Path homeB = tempDir.resolve("home-b");
        NexusaiPaths.setConfigHomeDirOverride(homeB.toString());
        String textB = sectionText(SESSION);
        assertThat(textB).contains(homeB.toString());
        assertThat(textB).as("换了 configHome 就不该再出现旧路径").doesNotContain(homeA.toString());
    }

    @Test
    @DisplayName("无会话 ⇒ 不注入该段（交付物目录是会话级的）")
    void noSessionMeansNoSection() throws Exception {
        NexusaiPaths.setConfigHomeDirOverride(tempDir.resolve("config-home").toString());

        assertThat(sectionText(null)).isNull();
        assertThat(sectionText("  ")).isNull();
    }
}
