package com.nexusai.infra.llm;

import com.nexusai.common.RequestContext;
import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SessionIdResolver} 单测：3 级优先级 + 剔除 MDC 的回归守护。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.3。
 *
 * <p><b>本类守护的核心不变量</b>：解析结果**只**能来自显式穿线的 history / options.history，
 * 绝不来自 {@code RequestContext}（MDC）。理由见
 * {@link #ignoresMdcEvenWhenPopulated()}。
 *
 * <p><b>变异自证（反向实验，已实跑确认变红后还原）</b>：
 * ① {@code resolve} 里加回 {@code RequestContext.sessionId()} 兜底 ⇒
 * {@link #ignoresMdcEvenWhenPopulated()} 红（本类的存在意义）；
 * ② {@code fromHistory} 里「取第一条」改成「取最后一条」⇒
 * {@link #prefersFirstNonBlankSessionIdFromHistory()} 红；
 * ③ {@code resolve} 里删掉 {@code fromMain} 提前返回 ⇒
 * {@link #historyTakesPrecedenceOverOptionsHistory()} 红；
 * ④ {@code fromHistory} 里删掉 {@code m != null &&}（解引用前的唯一守卫）⇒
 * {@link #skipsNullElementsInHistory()} 红（NPE）。<b>单点变异即有效</b>——
 * 这里不是「冗余守卫」情形。
 */
@DisplayName("SessionIdResolver · 3 级 sessionId 解析（刻意剔除 MDC）")
class SessionIdResolverTest {

    // ---- ① history ----

    @Test
    @DisplayName("history 中第一条非空 sessionId 优先")
    void prefersFirstNonBlankSessionIdFromHistory() {
        List<ChatMessageDto> h = List.of(msg(null), msg("sess-A"), msg("sess-B"));
        assertEquals("sess-A", SessionIdResolver.fromHistory(h));
    }

    @Test
    @DisplayName("history 全空 / 全 null / 全空白时返回 null")
    void returnsNullWhenHistoryHasNoUsableSessionId() {
        assertNull(SessionIdResolver.fromHistory(null), "null history 应返回 null");
        assertNull(SessionIdResolver.fromHistory(List.of()), "空 history 应返回 null");
        assertNull(SessionIdResolver.fromHistory(List.of(msg(null), msg("  "))), "全 null/空白应返回 null");
    }

    /**
     * 【承重守卫回归】{@code fromHistory} 里 {@code m != null} 是<b>解引用前的唯一守卫</b>，
     * 必须单独钉住。
     *
     * <p><b>WHY（规则九）</b>：本类其余 5 条测试<b>全部</b>用 {@code List.of(...)} 构造 history，
     * 而 {@code List.of} <b>拒绝 null 元素</b> → 「元素为 null」这一支在那些测试里<b>结构上不可达</b>。
     * 后果：若有人「简化」掉 {@code m != null}，5 条测试会<b>全绿</b>，
     * 而 {@code fromHistory} 会对含 null 元素的 history <b>抛 NPE</b>。
     * 真实来源：{@code ChatMessageDto} 列表可由 SQL 结果映射 / 手工拼装产生 null 元素。
     *
     * <p><b>故必须用 {@code Arrays.asList}（允许 null 元素）构造</b>，不能用 {@code List.of}。
     *
     * <p>变异自证：删掉 {@code SessionIdResolver.fromHistory} 的 {@code m != null &&} ⇒
     * 本测试红（NPE: Cannot invoke "…ChatMessageDto.sessionId()" because "m" is null）。
     */
    @Test
    @DisplayName("history 含 null 元素时跳过而不是崩")
    void skipsNullElementsInHistory() {
        assertEquals("sess-A", SessionIdResolver.fromHistory(
            Arrays.asList((ChatMessageDto) null, msg("sess-A"))));
        assertEquals("sess-A", SessionIdResolver.fromHistory(
            Arrays.asList(msg(null), null, msg("sess-A"))));
    }

    // ---- ① → ② 优先级 ----

    @Test
    @DisplayName("优先级：history 先于 options.history")
    void historyTakesPrecedenceOverOptionsHistory() {
        assertEquals("sess-H", SessionIdResolver.resolve(List.of(msg("sess-H")), List.of(msg("sess-O"))),
            "history 有值时不得被 options.history 覆盖");
        assertEquals("sess-O", SessionIdResolver.resolve(List.of(), List.of(msg("sess-O"))),
            "history 空时应回落 options.history");
        assertEquals("sess-O", SessionIdResolver.resolve(null, List.of(msg("sess-O"))),
            "history 为 null 时应回落 options.history");
    }

    // ---- ③ null ----

    @Test
    @DisplayName("两个来源都拿不到时返回 null（由 expand 落兜底常量）")
    void returnsNullWhenNeitherSourceHasSessionId() {
        assertNull(SessionIdResolver.resolve(null, null));
        assertNull(SessionIdResolver.resolve(List.of(), List.of()));
    }

    // ---- 回归守护：剔除 MDC ----

    /**
     * 【关键回归守护】MDC 里有值也**绝不能**被采纳。
     *
     * <p>T3 实测：{@code RequestContext.sessionId()} 读裸 MDC。全仓无 Filter / Interceptor /
     * ChannelInterceptor 写 MDC，但 MDC 另有<b>两类</b>写点：{@code MDC.put}（{@code RequestContext} 一处）
     * 与 <b>{@code MDC.setContextMap} 整表覆盖 12 处</b>（含 {@code LlmAgentLoop:6477} —— 跑在
     * {@code STREAM_EXECUTOR} 虚拟线程内，正是流式链路所在线程）。两类都能写 {@code SESSION_ID}，
     * 详见 {@link SessionIdResolver} 类 javadoc 的普查。
     * 叠加 {@code MemoryController:143} / {@code TaskController:145} /
     * {@code TeamController:95} 三处 {@code setSession} 均无 {@code clear}
     * → Tomcat 线程复用时 MDC 会残留**别的会话**的 sessionId。
     *
     * <p>采纳残留值比返回 null 更坏：它会**静默把 A 会话的亲和 id 发给 B 会话的请求**，
     * 不报错、也不落兜底常量。若这条断言变红，说明有人把 MDC 那一级加回来了 ——
     * 那是降级，不是改进（规范 §6.3）。
     */
    @Test
    @DisplayName("MDC 里有值也必须被忽略（T3 回归守护）")
    void ignoresMdcEvenWhenPopulated() {
        RequestContext.setSession("sess-STALE-FROM-ANOTHER-SESSION");
        try {
            assertNull(SessionIdResolver.resolve(null, null),
                "MDC 残留值被采纳了 —— 会把别的会话的亲和 id 发出去");
            assertEquals("sess-H", SessionIdResolver.resolve(List.of(msg("sess-H")), null),
                "history 有真值时也不得受 MDC 影响");
        } finally {
            RequestContext.clear();
        }
    }

    /** 测试夹具：仅第 1 参 id（恒 null）与第 2 参 sessionId 有意义，其余占位。 */
    private static ChatMessageDto msg(String sid) {
        return new ChatMessageDto(null, sid, Role.user, null, "x",
            null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
    }
}
