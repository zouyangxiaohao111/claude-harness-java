package com.nexusai.application.agent.loop;

import com.nexusai.application.agent.AgentState;
import com.nexusai.application.agent.prompt.PromptCacheGroup;
import com.nexusai.application.agent.prompt.SessionPromptCacheRegistry;
import com.nexusai.application.agent.prompt.SessionPromptCacheStore;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>步骤 5 · 跨午夜 date_change 尾部投递</b>意图测试（对齐 CC
 * {@code getDateChangeAttachments}，utils/attachments.ts:1406-1443）。
 *
 * <p><b>WHY（CLAUDE.md 规则九 · 验证意图而非行为）</b>：本步的对齐点不是「有一条消息被追加」，
 * 而是 CC 源码注释（:1403-1418）明确写下的一对<b>方向相反</b>的取舍，任一侧反了都会让
 * 前缀缓存白修或让模型停在昨天：
 * <ol>
 *   <li><b>头部<b>保留</b>旧日期</b>：{@code messages[0]}（claudeMd + currentDate）跨午夜<b>不变</b>
 *       —— 回写它 = 重新生成前缀 = 整条会话变 cache_creation（CC 给的量级：通宵会话每次跨午夜
 *       约 920K 有效 token ⇒ 缓存修复被这一下抹平）；</li>
 *   <li><b>尾部<b>追加</b>新日期</b>：新日期必须以一条<b>尾部真实消息</b>告知模型
 *       （CC {@code yield attachment; toolResults.push(attachment)}，query.ts:1580-1588），
 *       否则模型永远以为今天是会话开始日。</li>
 * </ol>
 * 另加两条 CC 状态机不变量：<b>首轮只记录不播报</b>（:1422-1426）、<b>同日无动作</b>（:1428-1430），
 * 以及一条本仓映射不变量：<b>lastEmittedDate 只随 /clear 复位</b>（caches.ts:69，全仓唯一 set(null)）。
 *
 * <p>隔离：store 注册表是进程级静态表，逐用例 {@link SessionPromptCacheRegistry#resetForTest()} 清空。
 */
@DisplayName("步骤 5 · 跨午夜 date_change 尾部投递（头部冻结 + 尾部告知）")
class DateChangeTailDeliveryTest {

    @AfterEach
    void tearDown() {
        SessionPromptCacheRegistry.resetForTest();
    }

    private static String newSessionId() {
        return "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }

    /** 今天的本地日 —— 与生产同源（CC getLocalISODate 的 Java 翻译，单点实现）。 */
    private static String today() {
        return SessionPromptCacheStore.localIsoDate();
    }

    /** 造一条「用户消息」当作 messages[0] 的近似（用于验证头部未被回写）。 */
    private static ChatMessageDto userMsg(String sessionId, String content) {
        return new ChatMessageDto(
            java.util.UUID.randomUUID().toString(), sessionId, Role.user, "user",
            content, null, java.util.List.of(), null, null, null,
            "刚刚", java.time.OffsetDateTime.now(), null, null,
            null, java.util.List.of(), java.util.List.of(), null, false);
    }

    // ── 不变量 1 · 首轮只记录不播报（CC :1422-1426）──

    @Test
    @DisplayName("首轮：lastEmittedDate 为 null ⇒ 只记录当天、零追加（否则每个会话开头白播一次）")
    void firstTurn_recordsOnly() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "hello"));
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("首轮必须把当天落位（下次比对才有基线）")
            .isEqualTo(today());
        assertThat(state.rawMessages())
            .as("首轮不得播报（CC :1424-1426 return []）")
            .hasSize(before);
    }

    // ── 不变量 2 · 同日无动作（CC :1428-1430）──

    @Test
    @DisplayName("同日：零追加（跨午夜才播报，同一天反复调用不得每轮追加）")
    void sameDay_noop() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.promptCacheStore().setLastEmittedDate(today());
        state.appendMessage(userMsg(sessionId, "hello"));
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);
        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.rawMessages()).hasSize(before);
    }

    // ── 不变量 3 · 跨午夜：尾部追加新日期消息，且头部消息不被回写 ──

    @Test
    @DisplayName("跨午夜：尾部追加 date_change 真实消息（含新日期），messages[0] 逐字不变")
    void midnight_appendsTailMessage_headUntouched() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        ChatMessageDto head = userMsg(sessionId, "昨天的请求");
        state.appendMessage(head);
        // 模拟「昨天已播报过」：把状态停在昨天（生产上由昨天那一 run 的 maybeEmitDateChange 写入）
        state.promptCacheStore().setLastEmittedDate("1999-01-01");
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);

        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after).as("必须追加一条（尾部告知新日期）").hasSize(before + 1);
        assertThat(after.get(0))
            .as("⛔ 头部（messages[0]）不得被回写 —— 回写即打掉整段前缀缓存（CC :1403-1418 自述 ~920K token）")
            .isSameAs(head);
        assertThat(after.get(after.size() - 1).id())
            .as("新日期消息必须在**尾部**（不插队、不改写前缀）")
            .isNotEqualTo(head.id());

        ChatMessageDto dc = after.get(after.size() - 1);
        assertThat(dc.role()).isEqualTo(Role.user);
        assertThat(dc.isMeta()).as("isMeta ⇒ 前端隐藏、不污染用户转录（CC createUserMessage isMeta:true）").isTrue();
        assertThat(dc.author()).as("attachment 通道产出（与 CC AttachmentMessage 同契约）").isEqualTo("attachment");
        assertThat(dc.subtype()).as("subtype 与 CC attachment.type 同名").isEqualTo("date_change");
        assertThat(dc.content())
            .as("渲染文案对齐 CC messages.ts:4163-4167（含新日期 + 「不要向用户明说」）")
            .contains("The date has changed. Today's date is now " + today() + ".")
            .contains("DO NOT mention this to the user explicitly")
            .startsWith("<system-reminder>");
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("播报后落位新日期 ⇒ 同一 run 内多次迭代不重复播报")
            .isEqualTo(today());
    }

    // ── 不变量 4 · 本仓映射：无会话标识 ⇒ 只记录不追加（CC simple 模式禁用附件的等价门）──

    @Test
    @DisplayName("无会话标识：只记录不追加（无落库通道；CC simple 模式同样禁用附件）")
    void noSession_recordsOnly() {
        AgentState state = new AgentState("sys");
        state.promptCacheStore().setLastEmittedDate("1999-01-01");
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.rawMessages()).hasSize(before);
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("仍需落位新日期：否则每次调用都重判为「跨午夜」")
            .isEqualTo(today());
    }

    // ── 不变量 5 · 清空点唯一：只有 /clear（CLEAR_SESSION_ALL）复位它（CC caches.ts:69）──

    @Test
    @DisplayName("lastEmittedDate 只随 /clear 复位；worktree/compact 集合不含它")
    void onlyClearSessionAllResetsIt() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.promptCacheStore().setLastEmittedDate("1999-01-01");

        // 非 /clear 事件（worktree 进/出 = 只清 A）⇒ 不得复位（否则同一天内会重复播报）
        SessionPromptCacheRegistry.clearPromptCaches(sessionId,
            PromptCacheGroup.WORKTREE_SECTIONS, "test:worktree");
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("worktree 集合不含 E ⇒ 不得复位")
            .isEqualTo("1999-01-01");

        // /clear = 全集 ⇒ 复位为 null（下次调用等同「本会话尚未播报」）
        SessionPromptCacheRegistry.clearPromptCaches(sessionId,
            PromptCacheGroup.CLEAR_SESSION_ALL, "test:/clear");
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("CLEAR_SESSION_ALL 含 E（CC caches.ts:69 setLastEmittedDate(null)）⇒ 复位为 null")
            .isNull();
        assertThat(PromptCacheGroup.CLEAR_SESSION_ALL)
            .as("集合常量本身必须含 E —— 否则 /clear 后跨午夜判据带陈旧状态")
            .contains(PromptCacheGroup.E_LAST_EMITTED_DATE)
            .isEqualTo(Set.of(PromptCacheGroup.A_SECTIONS, PromptCacheGroup.B_USER_CONTEXT,
                PromptCacheGroup.C_SYSTEM_CONTEXT, PromptCacheGroup.D_GIT_STATUS_AND_DATE,
                PromptCacheGroup.E_LAST_EMITTED_DATE));
    }
}
