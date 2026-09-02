package com.nexusai.application.agent.compact;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.AgentState;
import com.nexusai.model.session.dto.ChatMessageDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P1-6-READ-1] createSkillAttachmentIfNeeded 读取/重注入测试 ·
 * 对齐 CC services/compact/compact.ts:1494-1534 createSkillAttachmentIfNeeded
 * + :558/:950 两调用点（compactConversation / partialCompactConversation 成功路径）。
 *
 * <p><b>WHY (CLAUDE.md 规则 9 · 测试验证意图)</b>: skill 内容被压缩摘要吞掉后必须重注入，
 * 否则模型丢失 skill 使用指引。本测试验证 CC 算法核心：
 * <ol>
 *   <li>getInvokedSkillsForAgent 读取（空 Map → null，不注入）</li>
 *   <li>invokedAt 降序排序（most-recent-first，预算压力丢最旧）</li>
 *   <li>per-skill truncateToTokens(5_000)（超预算长文截断带 SKILL_TRUNCATION_MARKER，
 *       保留文件头部 setup/usage 指引）</li>
 *   <li>累加 roughTokenCountEstimation 超 25_000 丢弃（过滤基于<b>截断后</b>内容）</li>
 *   <li>成功分支注入（调用方仅在成功路径调用工厂，CC:558/:950）</li>
 *   <li>replace 语义 = 每轮压缩重建 transcript，restore() 每次恰好一份 invoked_skills 无累积</li>
 * </ol>
 *
 * <p><b>新架构断言载体（CompactContext 已删）</b>: invoked_skills 附件现由
 * {@link AgentState#getInvokedSkillsForAgent} 读取 + {@link PostCompactAttachmentRestorer#skillAttachment}
 * 构建，载体为 {@link ChatMessageDto}（author='attachment'，subtype='invoked_skills'，
 * content=JSON payload），经 {@link CompactConversationContext#setAdditionalPostCompactAttachments}
 * + {@link PostCompactAttachmentRestorer#restore} 进入重建消息列表。旧测试经
 * {@code state.attachments()}（List&lt;AttachmentMessageDto&gt;）断言的路径已不存在，
 * 断言改在工厂/restore 层进行。
 *
 * <p><b>排序稳定性（2026-08-05 本机实证）</b>: {@code addInvokedSkill} 用
 * {@code System.currentTimeMillis()}（AgentState.java:539），本机实测 6 次连续调用
 * 返回完全相同的毫秒值（系统计时器粒度 ~16ms）。故测试在相邻 addInvokedSkill 之间
 * {@code Thread.sleep(30)}：30ms &gt; 系统计时器周期，数学上保证 invokedAt 至少跨越
 * 一个 tick 而严格递增，most-recent-first 断言确定不 flake（不改数据源，仅测试侧控制）。
 */
class P16Read1SkillAttachmentTest {

    /** 长文 22000 字符 → 估算 5500 tokens &gt; 5000 触发截断（同旧测试规模） */
    private static final int LONG_SKILL_CHARS = 22_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    @DisplayName("成功分支注入 invoked_skills 附件: 排序/截断/预算丢弃正确")
    void afterCompactSuccessInjectsInvokedSkillsAttachment() throws Exception {
        AgentState state = new AgentState("test");
        String shortContent = "short content preserved";   // 短内容 · 不截断 (~6 tokens)
        String longContent = "a".repeat(LONG_SKILL_CHARS); // 5500 tokens > 5000 · 触发截断

        // 按调用顺序: long1 最旧 → short 最新 (invokedAt 严格递增, 相邻调用间 sleep(30))
        addInvokedSkillInOrder(state, "long1", "/skills/long1.md", longContent);
        addInvokedSkillInOrder(state, "long2", "/skills/long2.md", longContent);
        addInvokedSkillInOrder(state, "long3", "/skills/long3.md", longContent);
        addInvokedSkillInOrder(state, "long4", "/skills/long4.md", longContent);
        addInvokedSkillInOrder(state, "long5", "/skills/long5.md", longContent);
        addInvokedSkillInOrder(state, "short", "/skills/short.md", shortContent);

        ChatMessageDto attachment = buildSkillAttachment(state);
        assertThat(attachment).isNotNull();
        // 新载体契约: author='attachment' + subtype='invoked_skills'
        assertThat(attachment.subtype()).isEqualTo("invoked_skills");
        assertThat(attachment.author()).isEqualTo(PostCompactAttachmentRestorer.ATTACHMENT_AUTHOR);

        // content 为 JSON payload（经 safeJson 转义）→ Jackson 解析后取值（自动反转义）
        JsonNode root = MAPPER.readTree(attachment.content());
        assertThat(root.path("type").asText()).isEqualTo("invoked_skills");
        JsonNode skills = root.path("skills");
        assertThat(skills.isArray()).isTrue();
        // 5 条长文 * 5000 tokens = 25000 (short ~6 tokens) → 第 6 条(最旧 long1)超预算被丢弃
        assertThat(skills.size()).isEqualTo(5);

        List<String> names = new ArrayList<>();
        skills.forEach(s -> names.add(s.path("name").asText()));
        // most-recent-first: short 最新在最前，与旧断言逐条对应
        assertThat(names).containsExactly("short", "long5", "long4", "long3", "long2");

        // ① short 最新在最前
        assertThat(skills.get(0).path("name").asText()).isEqualTo("short");
        // ② 短内容未截断
        assertThat(skills.get(0).path("content").asText()).isEqualTo(shortContent);
        // ③ 长文全部带截断 marker (保留头部)；marker 的 \\n 经 Jackson 解析还原为真实换行
        for (int i = 0; i < skills.size(); i++) {
            JsonNode skill = skills.get(i);
            if (!"short".equals(skill.path("name").asText())) {
                String content = skill.path("content").asText();
                assertThat(content).startsWith("a");
                assertThat(content).endsWith(SkillContentTruncator.SKILL_TRUNCATION_MARKER);
            }
        }
        // ④ 超预算条目(最旧 long1)被丢弃
        assertThat(names).doesNotContain("long1");
    }

    @Test
    @DisplayName("空 invokedSkills Map → 不注入 attachment")
    void emptyInvokedSkillsProducesNoAttachment() {
        AgentState state = new AgentState("test");

        ChatMessageDto attachment = buildSkillAttachment(state);

        // 对齐 CC compact.ts:1499-1501 invokedSkills.size===0 → return null
        assertThat(attachment).isNull();
    }

    @Test
    @DisplayName("空技能列表 → 工厂返回 null（失败分支不注入由调用方门控保证）")
    void failedCompactProducesNoAttachment() {
        // 新架构工厂无 success 参数：门控在调用方（仅成功路径调用 createSkillAttachmentIfNeeded，
        // CC compact.ts:558/:950）。失败分支不产生附件由该调用方门控保证，不保留旧
        // success flag 兼容壳（CLAUDE.md 规则七）。工厂对空技能列表返回 null（CC:1526-1528）。
        ChatMessageDto attachment = PostCompactAttachmentRestorer.skillAttachment(List.of());
        assertThat(attachment).isNull();
    }

    @Test
    @DisplayName("replace 语义: restore() 每次恰好一份 invoked_skills 无累积")
    void repeatedCompactReplacesAttachment() {
        AgentState state = new AgentState("test");
        state.addInvokedSkill("short", "/skills/short.md", "short content preserved");

        // 新架构 replace 语义 = 每轮压缩重建 transcript（旧 removeAttachmentsByType +
        // appendAttachment 机制已被取代），每轮经 restore() 恰好注入一份 invoked_skills。
        ChatMessageDto att1 = buildSkillAttachment(state);
        assertThat(att1).isNotNull();
        assertThat(att1.subtype()).isEqualTo("invoked_skills");

        ChatMessageDto att2 = buildSkillAttachment(state);
        assertThat(att2).isNotNull();
        assertThat(att2.subtype()).isEqualTo("invoked_skills");

        // 单实例/无累积: 经 setAdditionalPostCompactAttachments + restore 后结果列表
        // 中 subtype=='invoked_skills' 恰有 1 条
        CompactConversationContext ctx = new CompactConversationContext()
            .setAdditionalPostCompactAttachments(List.of(att1));
        List<ChatMessageDto> restored =
            PostCompactAttachmentRestorer.restore(ctx, java.util.Map.of(), List.of());
        List<ChatMessageDto> skillAtts = restored.stream()
            .filter(m -> "invoked_skills".equals(m.subtype()))
            .toList();
        assertThat(skillAtts).hasSize(1);
    }

    /**
     * state.invokedSkills → skillAttachment 工厂 · 对齐 CC getInvokedSkillsForAgent +
     * createSkillAttachmentIfNeeded 的读取路径。getInvokedSkillsForAgent(null) 返回新
     * HashMap（无序），但 skillAttachment 内部按 invokedAt 降序排序
     * （PostCompactAttachmentRestorer.java:217），故输入列表顺序无关紧要。
     */
    private static ChatMessageDto buildSkillAttachment(AgentState state) {
        List<PostCompactAttachmentRestorer.SkillInfo> skills =
            state.getInvokedSkillsForAgent(null).values().stream()
                .map(info -> new PostCompactAttachmentRestorer.SkillInfo(
                    info.skillName(), info.skillPath(), info.content(), info.invokedAt()))
                .toList();
        return PostCompactAttachmentRestorer.skillAttachment(skills);
    }

    /**
     * addInvokedSkill + sleep(30) 保证 invokedAt 严格递增（most-recent-first 排序断言确定性）。
     * 本机实测连续调用 System.currentTimeMillis() 全同 ms，30ms 睡眠 &gt; 计时器周期，
     * 数学上保证至少跨越一个 tick。
     */
    private static void addInvokedSkillInOrder(AgentState state, String name, String path, String content) {
        state.addInvokedSkill(name, path, content);
        try {
            Thread.sleep(30);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while stamping invokedAt", e);
        }
    }
}
