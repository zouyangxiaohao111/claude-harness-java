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
 * 另加两条 CC 状态机不变量：<b>首轮播报日期</b>（真源 2.1.322 exe offset 308666094：
 * {@code oe===undefined ⇒ oe!==Y ⇒ 发 `Today's date is ${date}.`}）、<b>同日无动作</b>（:1428-1430），
 * 以及一条本仓映射不变量：<b>lastEmittedDate 只随 /clear 复位</b>（caches.ts:69，全仓唯一 set(null)）。
 *
 * <p><b>[2.1.322 对齐变更]</b>判据从「会话级 {@code store.lastEmittedDate()}」改为
 * 「<b>本 agent 消息历史</b>里的最后一次 date/date_change」（CC {@code Fki(e)} 语义）。
 * WHY：子代理的消息从 initialMessages 起是全新的 ⇒ 历史无日期 ⇒ 首轮即播报；
 * 若读会话槽位，子代理会因主线程当天已播报而恒判"同日"⇒ 永远拿不到日期。
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

    // ── 不变量 1 · 首轮播报日期（真源 2.1.322 · exe offset 308666094）──

    @Test
    @DisplayName("首轮：历史无日期 ⇒ 尾部播报一条裸 `Today's date is YYYY-MM-DD.`（无免责声明包裹）")
    void firstTurn_broadcastsDate() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "hello"));
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("仍需把当天落位（观测用；判据已改为历史扫描）")
            .isEqualTo(today());
        List<ChatMessageDto> after = state.rawMessages();
        assertThat(after)
            .as("首轮必须播报：CC 2.1.322 在 `oe===undefined` 时 `oe!==Y` 为真 ⇒ 发首次文案")
            .hasSize(before + 1);

        ChatMessageDto date = after.get(after.size() - 1);
        assertThat(date.subtype()).as("subtype 与 CC attachment.type 同名").isEqualTo("date");
        assertThat(date.isMeta()).as("isMeta ⇒ 前端隐藏、不污染用户转录").isTrue();
        assertThat(date.author()).isEqualTo("attachment");
        assertThat(date.content())
            .as("⛔ 必须是裸文案 —— 不带 <system-reminder> 免责声明包裹。"
                + "否则「may or may not be relevant … should not respond unless highly relevant」"
                + "会让指定人格的模型把日期当噪声忽略（这正是本次修的缺陷）")
            .isEqualTo("Today's date is " + today() + ".")
            .doesNotContain("system-reminder")
            .doesNotContain("may or may not be relevant");
    }

    // ── 不变量 2 · 同日无动作（CC :1428-1430）──

    @Test
    @DisplayName("同日：零追加（历史里已有今天的 date ⇒ 不重复播报）")
    void sameDay_noop() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        state.appendMessage(userMsg(sessionId, "hello"));
        ChatMessageDto todays = AgentLoopContext.todayDateMessage(sessionId, today());
        assertThat(todays).isNotNull();
        state.appendMessage(todays);
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);
        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.rawMessages()).hasSize(before);
    }

    // ── 不变量 3 · 跨午夜：尾部追加新日期消息，且头部消息不被回写 ──

    @Test
    @DisplayName("跨日：尾部追加 date_change 真实消息（含新日期），messages[0] 逐字不变")
    void midnight_appendsTailMessage_headUntouched() {
        String sessionId = newSessionId();
        AgentState state = new AgentState("sys", sessionId, null);
        ChatMessageDto head = userMsg(sessionId, "昨天的请求");
        state.appendMessage(head);
        // 模拟「昨天已播报过」：历史里有一条昨天的 date 消息（生产上由昨天那一 run 写入）
        state.appendMessage(AgentLoopContext.todayDateMessage(sessionId, "1999-01-01"));
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
            .as("逐字对齐 CC 2.1.322 mLt（offset 299045186）changed 分支："
                + "旧 2.1.88 的 `DO NOT mention … already aware` 已改，且不再包 system-reminder")
            .isEqualTo("The date has changed. Today's date is now " + today() + ". "
                + "No need to announce the new date \u2014 the user's own clock shows it.")
            .doesNotContain("system-reminder")
            .doesNotContain("DO NOT mention");
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("播报后落位新日期 ⇒ 同一 run 内多次迭代不重复播报")
            .isEqualTo(today());
    }

    // ── 不变量 4 · 本仓映射：无会话标识 ⇒ 只记录不追加（CC simple 模式禁用附件的等价门）──

    @Test
    @DisplayName("无会话标识：只记录不追加（无落库通道；CC simple 模式同样禁用附件）")
    void noSession_recordsOnly() {
        AgentState state = new AgentState("sys");
        // 历史里有昨天日期 ⇒ 走「跨日」分支（首轮分支也需 sessionId，两条分支都必须守住不投递）
        state.appendMessage(userMsg(null, "昨天的请求"));
        state.appendMessage(AgentLoopContext.todayDateMessage(null, "1999-01-01"));
        int before = state.rawMessages().size();

        AgentLoopContext.maybeEmitDateChange(state);

        assertThat(state.rawMessages()).hasSize(before);
        assertThat(state.promptCacheStore().lastEmittedDate())
            .as("仍需落位新日期：否则每次调用都重判为「跨日」")
            .isEqualTo(today());
    }

    // ── 不变量 6 · auxiliary 分类：只看「从未发过」，跨日不再重发（CC Ci 的 S 分支）──

    @Test
    @DisplayName("auxiliary 来源：历史无日期 ⇒ 发；历史有旧日期 ⇒ 跨日也**不发**（CC `S ? oe===undefined : oe!==Y`）")
    void auxiliary_onlyEmitsWhenNeverEmitted() {
        // (a) 从未发过 ⇒ 发 · 用 MARBLE_ORIGAMI（auxiliary 但不是 fork/compaction 系）
        //     ⛔ 不能用 COMPACT/SESSION_MEMORY/EXTRACT_MEMORIES/AUTO_DREAM/FORK —— 它们被
        //     CC 的 bare/resumeActive 门挡掉（见 isBareOrResumeForkSource）。
        String s1 = newSessionId();
        AgentState st1 = new AgentState("sys", s1, null);
        st1.appendMessage(userMsg(s1, "hello"));
        AgentLoopContext.maybeEmitDateChange(st1, com.nexusai.application.agent.QuerySource.MARBLE_ORIGAMI);
        assertThat(st1.rawMessages().get(st1.rawMessages().size() - 1).subtype())
            .as("auxiliary + 历史无日期 ⇒ 必须发（oe===undefined）")
            .isEqualTo("date");

        // (b) 历史有昨天日期 ⇒ 不发（auxiliary 档 cross-day 语义被 S 分支短路）
        String s2 = newSessionId();
        AgentState st2 = new AgentState("sys", s2, null);
        st2.appendMessage(userMsg(s2, "hello"));
        st2.appendMessage(AgentLoopContext.todayDateMessage(s2, "1999-01-01"));
        int before = st2.rawMessages().size();
        AgentLoopContext.maybeEmitDateChange(st2, com.nexusai.application.agent.QuerySource.MARBLE_ORIGAMI);
        assertThat(st2.rawMessages())
            .as("auxiliary 档跨日**不重发**（CC: `S ? oe===undefined : oe!==Y` 取前者 ⇒ 有旧日期即不命中）")
            .hasSize(before);
    }

    @Test
    @DisplayName("fork/compaction 系：一律不发（CC Ci 开头 `if(s||n.bare)return[]`）")
    void forkFamily_neverEmits() {
        for (var qs : List.of(com.nexusai.application.agent.QuerySource.FORK,
                com.nexusai.application.agent.QuerySource.COMPACT,
                com.nexusai.application.agent.QuerySource.SESSION_MEMORY,
                com.nexusai.application.agent.QuerySource.EXTRACT_MEMORIES,
                com.nexusai.application.agent.QuerySource.AUTO_DREAM)) {
            String sid = newSessionId();
            AgentState st = new AgentState("sys", sid, null);
            st.appendMessage(userMsg(sid, "hello"));
            int before = st.rawMessages().size();
            AgentLoopContext.maybeEmitDateChange(st, qs);
            assertThat(st.rawMessages())
                .as("fork/compaction 系不得追加 date（会破坏 fork 前缀 == 父前缀 的契约）: " + qs)
                .hasSize(before);
        }
    }

    @Test
    @DisplayName("isAuxiliaryQuerySource：main/subagent 为 false，其余 true（对齐 CC ns() 三分）")
    void auxiliaryClassification_matchesCcNs() {
        assertThat(AgentLoopContext.isAuxiliaryQuerySource(null))
            .as("CC ns(undefined)=undefined ⇒ 非 auxiliary").isFalse();
        for (var qs : List.of(com.nexusai.application.agent.QuerySource.USER,
                com.nexusai.application.agent.QuerySource.REPL_MAIN_THREAD,
                com.nexusai.application.agent.QuerySource.SDK,
                com.nexusai.application.agent.QuerySource.SUBAGENT,
                com.nexusai.application.agent.QuerySource.FORK,
                com.nexusai.application.agent.QuerySource.HOOK_AGENT)) {
            assertThat(AgentLoopContext.isAuxiliaryQuerySource(qs))
                .as("main/subagent 档不得归 auxiliary: " + qs).isFalse();
        }
        for (var qs : List.of(com.nexusai.application.agent.QuerySource.COMPACT,
                com.nexusai.application.agent.QuerySource.SESSION_MEMORY,
                com.nexusai.application.agent.QuerySource.EXTRACT_MEMORIES,
                com.nexusai.application.agent.QuerySource.AUTO_DREAM,
                com.nexusai.application.agent.QuerySource.MARBLE_ORIGAMI,
                com.nexusai.application.agent.QuerySource.WORKFLOW)) {
            assertThat(AgentLoopContext.isAuxiliaryQuerySource(qs))
                .as("backend/auxiliary 查询必须归 auxiliary: " + qs).isTrue();
        }
    }

    // ── 不变量 7 · UTC 文案（CC assumedUtc 分支）──

    @Test
    @DisplayName("dateAttachmentText：四分支逐字对齐 CC mLt（offset 299045186）")
    void dateText_fourBranches_matchCcMlt() {
        String d = "2026-11-11";
        assertThat(AgentLoopContext.dateAttachmentText(d, false, false))
            .isEqualTo("Today's date is 2026-11-11.");
        assertThat(AgentLoopContext.dateAttachmentText(d, true, false))
            .isEqualTo("The date has changed. Today's date is now 2026-11-11. "
                + "No need to announce the new date \u2014 the user's own clock shows it.");
        assertThat(AgentLoopContext.dateAttachmentText(d, false, true))
            .startsWith("The UTC date is 2026-11-11. The user's local date can differ.")
            .contains("that is the user's local date: treat it as today's date.");
        assertThat(AgentLoopContext.dateAttachmentText(d, true, true))
            .startsWith("The UTC date is now 2026-11-11. The user's local date can differ.")
            .contains("other than an earlier \"Today's date is\" line");
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
