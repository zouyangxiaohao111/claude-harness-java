package com.nexusai.application.schedule;

import com.nexusai.model.schedule.dto.ScheduleDto;
import com.nexusai.model.schedule.dto.ScheduleKind;
import com.nexusai.model.schedule.dto.ScheduleScope;
import com.nexusai.model.schedule.dto.SessionMode;
import com.nexusai.repository.session.mapper.SessionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ScheduleSessionResolver} 的四形态分流（V306）。
 *
 * <p><b>为什么这些用例必须存在</b>（规则九：验证意图，不是验证行为）：
 * <ul>
 *   <li><b>D 形态若误建会话</b> ⇒ 用户明确选了「不绑定会话 / 后台静默」，却每次执行都
 *       往左栏堆一个会话 —— 与「最省资源」的承诺相反；</li>
 *   <li><b>REUSE 绑定的会话被删后若原样返回旧 id</b> ⇒ agent loop 把结果写进一个不存在的会话：
 *       transcript 丢失、状态显示成功、事后完全查不到。用户对这条的裁定是「不许跑了没痕迹」
 *       ⇒ 必须降级新建，绝不能返回死会话；</li>
 *   <li><b>NEW_ONCE 若每次都新建</b> ⇒ 「专属会话 · 长期累积」名不副实，历史永远只有最新一轮；</li>
 *   <li><b>任何一步失败若抛出</b> ⇒ 整个定时任务 fire 失败。用户裁定：建会话失败只降级 headless，
 *       <b>不阻断 fire</b>（任务照常跑，只是没有 transcript）。</li>
 * </ul>
 *
 * <p><b>测试策略</b>：不启 Spring（⛔ {@code @SpringBootTest} 会迁移用户真库），
 * 依赖字段用反射注入。`SessionMapper == null` 时 `isAlive()` 是 fail-open（视为存活），
 * 这正好让「会话存活」路径可以在无 DB 的情况下被验证；「会话已删」路径用
 * {@link Proxy} 造一个 `selectOneById` 恒返 null 的 mapper。
 *
 * <p>⚠️ 本类不覆盖「真的建出会话」那条路（它要文件系统 + ProjectService + SessionService），
 * 那部分只做「依赖缺失时不炸」的兜底断言。
 */
class ScheduleSessionResolverTest {

    /** 构造一个最小可用的任务 DTO（只填分流逻辑会读的字段）。 */
    private static ScheduleDto schedule(SessionMode mode, String sessionId) {
        return new ScheduleDto(
            "sch-test",              // id
            "联调任务",               // name
            ScheduleKind.cron,       // kind
            "0 57 8 ? * *",          // cron
            null,                    // intervalSeconds
            null,                    // runAt
            "打印当前时间",            // command
            null,                    // description
            null,                    // lastRunAt
            null,                    // lastRunStatus
            ScheduleScope.DURABLE,   // scope
            sessionId,               // sessionId
            null,                    // agentId
            null,                    // boundProject
            mode,                    // sessionMode
            List.of(),               // notifyChats
            List.of()                // notifyChannelIds
        );
    }

    /** 让 `selectOneById` 恒返 null 的 mapper ⇒ 任何会话都判为「已被删除」。 */
    private static ScheduleSessionResolver withAllSessionsDeleted() throws Exception {
        SessionMapper dead = (SessionMapper) Proxy.newProxyInstance(
            ScheduleSessionResolverTest.class.getClassLoader(),
            new Class<?>[]{SessionMapper.class},
            (proxy, method, args) -> null);
        ScheduleSessionResolver r = new ScheduleSessionResolver();
        var f = ScheduleSessionResolver.class.getDeclaredField("sessionMapper");
        f.setAccessible(true);
        f.set(r, dead);
        return r;
    }

    // ------------------------------------------------------------------
    // D · 不绑定会话
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D 形态：不产生会话（null），这是「后台静默、最省资源」的全部含义")
    void noneMode_producesNoSession() {
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(schedule(SessionMode.NONE, null), "test")).isNull();
    }

    @Test
    @DisplayName("D 形态：即使行上残留 sessionId（改形态后没清干净）也绝不能复用 —— "
        + "否则用户选了「不绑会话」却还在往某个会话里写")
    void noneMode_ignoresResidualSessionId() {
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(schedule(SessionMode.NONE, "sess-residual"), "test")).isNull();
    }

    // ------------------------------------------------------------------
    // A · 绑定已有会话
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A 形态：绑定会话存活 ⇒ 原样复用（不新建），这是「共享该会话全部历史」的前提")
    void reuseMode_reusesLiveSession() {
        // sessionMapper 未注入 ⇒ isAlive 走 fail-open（视为存活）
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(schedule(SessionMode.REUSE, "sess-alive"), "test"))
            .isEqualTo("sess-alive");
    }

    @Test
    @DisplayName("A 形态：绑定的会话已被删除 ⇒ 绝不返回那个死会话 id（降级新建；"
        + "返回死 id 会让结果写进不存在的会话 = 跑了没痕迹）")
    void reuseMode_neverReturnsDeletedSession() throws Exception {
        var r = withAllSessionsDeleted();
        // 依赖（SessionService/ProjectService）未注入 ⇒ 降级新建失败 ⇒ null
        assertThat(r.resolve(schedule(SessionMode.REUSE, "sess-deleted"), "test"))
            .isNotEqualTo("sess-deleted");
    }

    @Test
    @DisplayName("A 形态：没绑会话（历史脏行/改形态后）⇒ 同样不返回 null 以外的死值")
    void reuseMode_withoutSessionId_degradesToCreate() {
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(schedule(SessionMode.REUSE, null), "test")).isNull();
    }

    // ------------------------------------------------------------------
    // B · 每次新建
    // ------------------------------------------------------------------

    @Test
    @DisplayName("B 形态：即使行上残留 sessionId 也不复用它 —— B 的语义是「每次全新上下文」")
    void newEachMode_neverReusesResidualSessionId() {
        var r = new ScheduleSessionResolver();
        // 依赖缺失 ⇒ 新建失败返回 null；关键断言是「没有退回那个残留 id」
        assertThat(r.resolve(schedule(SessionMode.NEW_EACH, "sess-old"), "test"))
            .isNotEqualTo("sess-old");
    }

    // ------------------------------------------------------------------
    // C · 新建后绑定住
    // ------------------------------------------------------------------

    @Test
    @DisplayName("C 形态：专属会话已建立且存活 ⇒ 每次都用同一个（这是「长期累积」的前提，"
        + "若每次新建则历史永远只有最新一轮）")
    void newOnceMode_reusesPinnedSession() {
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(schedule(SessionMode.NEW_ONCE, "sess-pinned"), "test"))
            .isEqualTo("sess-pinned");
    }

    @Test
    @DisplayName("C 形态：专属会话被删 ⇒ 重建（不返回死会话）；重建失败也只是本轮 headless")
    void newOnceMode_recreatesWhenPinnedSessionDeleted() throws Exception {
        var r = withAllSessionsDeleted();
        assertThat(r.resolve(schedule(SessionMode.NEW_ONCE, "sess-gone"), "test"))
            .isNotEqualTo("sess-gone");
    }

    // ------------------------------------------------------------------
    // 失败语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("建会话依赖未注入时 resolve 不抛 —— 用户裁定：失败只降级 headless，不阻断 fire")
    void missingDependencies_neverThrow() {
        var r = new ScheduleSessionResolver();
        assertThatCode(() -> {
            r.resolve(schedule(SessionMode.NEW_EACH, null), "test");
            r.resolve(schedule(SessionMode.NEW_ONCE, null), "test");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null DTO 不抛（防御 fire 侧脏数据）")
    void nullDto_isSafe() {
        var r = new ScheduleSessionResolver();
        assertThat(r.resolve(null, "test")).isNull();
    }

    // ------------------------------------------------------------------
    // 通知目标透传（供 AgentJob 使用）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("notifyChatsOf：null / 空列表都归一为空列表（下游按「空 = 不通知」处理）")
    void notifyChatsOf_isNullSafe() {
        var r = new ScheduleSessionResolver();
        assertThat(r.notifyChatsOf(null)).isEmpty();
        assertThat(r.notifyChatsOf(schedule(SessionMode.NONE, null))).isEmpty();
    }
}
