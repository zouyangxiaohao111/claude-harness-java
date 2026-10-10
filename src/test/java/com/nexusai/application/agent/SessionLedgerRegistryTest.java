package com.nexusai.application.agent;

import com.nexusai.application.agent.session.SessionResumeDeserializer;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [会话账本柜 · session-ledger 2026-10-10] 接力棒语义 + 哨兵剔除 单元测试。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>：本表承担「同一会话单一历史构造源」——热接力跳过 DB 重读的
 * 前提是「快照 == DB 等价物」（哨兵剔除）；而任何异常路径必须永不错接（一次性消费 + 完成才交棒，
 * take 后未 commit = 崩 → 下个 run 冷启动）。每项锁死，供 LlmAgentLoop 注入段 / run 出口接线用例
 * 复用语义；冷/热跨进程边界（JVM 重启）由 reset 模拟（对齐 SessionStartSeenRegistryTest 先例）。
 */
@DisplayName("[会话账本柜] SessionLedgerRegistry 接力棒语义")
class SessionLedgerRegistryTest {

    @BeforeEach
    @AfterEach
    void clean() {
        SessionLedgerRegistry.reset();
    }

    private static final String SESS = "sess-ledger-test";

    /** 20 参兼容构造器（同 SessionResumeDeserializer.metaUser 的实参序；12 位 = createdAt）。 */
    private static ChatMessageDto msg(String id, Role role, String content, OffsetDateTime createdAt) {
        return msg(id, role, content, createdAt, false);
    }

    private static ChatMessageDto msg(String id, Role role, String content, OffsetDateTime createdAt, boolean isMeta) {
        return new ChatMessageDto(
            id, SESS, role, null, content, null, null, null, null, null,
            "刚刚", createdAt, null, null, null,
            List.of(), List.of(), null, isMeta, false);
    }

    private static OffsetDateTime at(int minute) {
        return OffsetDateTime.of(2026, 10, 10, 11, minute, 0, 0, ZoneOffset.ofHours(8));
    }

    @Test
    @DisplayName("commit → take 拿到快照（条数/锚点正确）；take 即摘除（柜台变空）")
    void commit_thenTake_returnsSnapshot_andDrains() {
        List<ChatMessageDto> ledger = List.of(
            msg("m1", Role.user, "你好", at(1)),
            msg("m2", Role.assistant, "你好，有什么可以帮你", at(2)));
        SessionLedgerRegistry.commit(SESS, ledger);

        assertThat(SessionLedgerRegistry.contains(SESS)).as("commit 后柜台在场").isTrue();

        SessionLedgerRegistry.LedgerSnapshot snap = SessionLedgerRegistry.take(SESS);
        assertThat(snap).as("take 拿到快照").isNotNull();
        assertThat(snap.messages()).extracting(ChatMessageDto::id).containsExactly("m1", "m2");
        assertThat(snap.lastMessageId()).as("锚点 = 末条 id（DTO/DB 同源）").isEqualTo("m2");
        assertThat(SessionLedgerRegistry.contains(SESS))
            .as("take 即原子摘除（一次性接力棒）").isFalse();
    }

    @Test
    @DisplayName("一次性消费：take 后再 take 为 null（= 未跑完的 run 不复用旧棒）")
    void take_isOneShot_secondTakeNull() {
        SessionLedgerRegistry.commit(SESS, List.of(msg("m1", Role.user, "x", at(1))));
        assertThat(SessionLedgerRegistry.take(SESS)).isNotNull();
        assertThat(SessionLedgerRegistry.take(SESS))
            .as("第一次 take 已摘除 → 第二次 null（下个 run 冷启动重抄）").isNull();
    }

    @Test
    @DisplayName("commit 剔除三类合成哨兵（role+content 双匹配）→ 账本 = DB 等价物")
    void commit_stripsSyntheticSentinels() {
        List<ChatMessageDto> ledger = List.of(
            msg("real1", Role.user, "正常消息一", at(1)),
            // 漏斗步骤 5：Continue meta user（interrupted_turn 恢复合成）
            msg("sent1", Role.user, SessionResumeDeserializer.CONTINUE_FROM_LEFT_OFF, at(2), true),
            // 漏斗步骤 6：No response requested.（末条为 user 时 splice 的 assistant sentinel）
            msg("sent2", Role.assistant, SessionResumeDeserializer.NO_RESPONSE_REQUESTED, at(3)),
            // 漏斗步骤 1.5：部分配对补桩 synthetic error tool_result
            msg("sent3", Role.tool, SessionResumeDeserializer.SYNTHETIC_MISSING_TOOL_RESULT, at(4)),
            msg("real2", Role.assistant, "正常消息二", at(5)));
        SessionLedgerRegistry.commit(SESS, ledger);

        SessionLedgerRegistry.LedgerSnapshot snap = SessionLedgerRegistry.take(SESS);
        assertThat(snap).isNotNull();
        assertThat(snap.messages()).extracting(ChatMessageDto::id)
            .as("三类哨兵全部剔除，只留真实消息（热接产物与冷启动读 DB 逐条一致）")
            .containsExactly("real1", "real2");
        assertThat(snap.lastMessageId()).as("锚点按剔除后计算（哨兵不参与）").isEqualTo("real2");
    }

    @Test
    @DisplayName("哨兵判别是 role+content 双匹配：内容对但 role 错 → 不剔除（防误伤真实消息）")
    void isSyntheticSentinel_requiresRoleAndContentMatch() {
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(
            msg("a", Role.user, SessionResumeDeserializer.CONTINUE_FROM_LEFT_OFF, at(1), true)))
            .as("Continue + user（meta）→ 判哨兵").isTrue();
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(
            msg("b", Role.assistant, SessionResumeDeserializer.CONTINUE_FROM_LEFT_OFF, at(1))))
            .as("同文本但 role=assistant（真实消息恰用同句）→ 不判哨兵").isFalse();
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(
            msg("c", Role.assistant, SessionResumeDeserializer.NO_RESPONSE_REQUESTED, at(1))))
            .as("No response requested. + assistant → 判哨兵").isTrue();
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(
            msg("d", Role.tool, SessionResumeDeserializer.SYNTHETIC_MISSING_TOOL_RESULT, at(1))))
            .as("Tool result missing + tool → 判哨兵").isTrue();
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(
            msg("e", Role.user, "普通用户消息", at(1))))
            .as("普通消息 → 不判哨兵").isFalse();
        assertThat(SessionLedgerRegistry.isSyntheticSentinel(null)).isFalse();
    }

    @Test
    @DisplayName("剔除后为空（全哨兵·理论罕见）→ 不交棒：下个 run 冷启动重抄（安全兜底）")
    void commit_allSentinels_skipsPut() {
        SessionLedgerRegistry.commit(SESS, List.of(
            msg("s1", Role.user, SessionResumeDeserializer.CONTINUE_FROM_LEFT_OFF, at(1), true)));
        assertThat(SessionLedgerRegistry.contains(SESS))
            .as("无真实消息 → 不交棒").isFalse();
        assertThat(SessionLedgerRegistry.take(SESS)).isNull();
    }

    @Test
    @DisplayName("remove 清柜（/clear 与 会话删除 两挂点共用）→ take null；reset 全清（JVM 重启模拟）")
    void remove_and_reset_clearLedgers() {
        SessionLedgerRegistry.commit(SESS, List.of(msg("m1", Role.user, "x", at(1))));
        SessionLedgerRegistry.remove(SESS);
        assertThat(SessionLedgerRegistry.take(SESS))
            .as("/clear（或会话删除）后不得接力旧账本").isNull();

        SessionLedgerRegistry.commit("sess-a", List.of(msg("a1", Role.user, "x", at(1))));
        SessionLedgerRegistry.commit("sess-b", List.of(msg("b1", Role.user, "y", at(1))));
        SessionLedgerRegistry.reset();
        assertThat(SessionLedgerRegistry.take("sess-a")).as("JVM 重启模拟 → 全冷").isNull();
        assertThat(SessionLedgerRegistry.take("sess-b")).isNull();
    }

    @Test
    @DisplayName("入参防御：null/空白 sessionId 与 null/空消息列表均为 no-op（不产生可接力快照）")
    void defensive_nullsAndBlanks() {
        SessionLedgerRegistry.commit(null, List.of(msg("m1", Role.user, "x", at(1))));
        SessionLedgerRegistry.commit("  ", List.of(msg("m1", Role.user, "x", at(1))));
        SessionLedgerRegistry.commit(SESS, null);
        SessionLedgerRegistry.commit(SESS, List.of());
        assertThat(SessionLedgerRegistry.contains(SESS)).as("全部 no-op").isFalse();
        assertThat(SessionLedgerRegistry.take(null)).isNull();
        assertThat(SessionLedgerRegistry.take("  ")).isNull();
        SessionLedgerRegistry.remove(null);
        SessionLedgerRegistry.remove("  ");
    }
}
