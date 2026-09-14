package com.nexusai.infra.llm;

import com.nexusai.model.session.dto.ChatMessageDto;
import com.nexusai.model.session.dto.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link SessionIdResolver} 单测：3 级优先级（history → options.history → null）。
 *
 * <p>设计见 docs/zjkycode/specs/2026-09-12-provider-custom-headers-design.md §6.3。
 *
 * <p><b>本类守护的不变量</b>：解析结果只能来自显式穿线的 history / options.history。
 *
 * <p><b>⚠ 关于「拒绝 ambient 会话来源」的边界（[欠账清理批] 更正原 javadoc 的假声明）</b>：
 * 原类 javadoc 曾写「resolve 里加回任一 ambient 会话读取兜底 ⇒ {@code ignoresAmbientSessionStateWhenPresent}
 * 红（本类的存在意义）」，并据此保留了一个「ambient 诱饵」用例。**该声明不成立**：该用例没有
 * 任何装置能把值装进一个尚未存在的 ambient 载体（无 setter 可调）⇒ 有人新加 `static ThreadLocal`
 * 兜底时它**仍全绿**（"声称守护 X 实际守不住"，本仓第 N 次）。该用例已删（见文件内去重记录）。
 * 「不得有 ambient 会话来源」的真实守卫在三处，均不在本类：
 * <ol>
 *   <li><b>结构性</b>：ambient 载体（`RequestContext` / 裸 MDC 会话槽）已从 main 全量删除
 *       （批 3c 收口核对 {@code TOTAL_CODE_HITS=0}）；</li>
 *   <li><b>接线级</b>：{@code ProviderSessionIdWiringGuardTest} 以源码声明锚扫描两个 SDK provider
 *       的 {@code buildClient(config} 调用点所在成员，禁止出现环境态会话槽读取（含
 *       {@code RequestContext} / {@code MDC.get} 的回归守卫）—— 已由变异实验证实会精确变红；</li>
 *   <li><b>入口级</b>：REST 端点缺 {@code ?sessionId=} ⇒ 400（各 ControllerTest 覆盖）。</li>
 * </ol>
 *
 * <p><b>变异自证（反向实验，已实跑确认变红后还原）</b>：
 * ① {@code fromHistory} 里「取第一条」改成「取最后一条」⇒
 * {@link #prefersFirstNonBlankSessionIdFromHistory()} 红；
 * ② {@code resolve} 里删掉 {@code fromMain} 提前返回 ⇒
 * {@link #historyTakesPrecedenceOverOptionsHistory()} 红；
 * ③ {@code fromHistory} 里删掉 {@code m != null &&}（解引用前的唯一守卫）⇒
 * {@link #skipsNullElementsInHistory()} 红（NPE）。<b>单点变异即有效</b>——
 * 这里不是「冗余守卫」情形。
 */
@DisplayName("SessionIdResolver · 3 级 sessionId 解析（刻意剔除任何 ambient 会话来源）")
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
        // [欠账清理批] 从已删用例 `ignoresAmbientSessionStateWhenPresent` 迁入 —— 该断言是它唯一
        //   未被本类其他用例覆盖的一条（options.history 形参为 null 的支路）。迁入后原用例整体
        //   去重（见文件末尾去重记录），覆盖面无损失。
        assertEquals("sess-H", SessionIdResolver.resolve(List.of(msg("sess-H")), null),
            "options.history 为 null 时不得崩，且不得影响 history 取值");
    }

    // ---- ③ null ----

    @Test
    @DisplayName("两个来源都拿不到时返回 null（由 expand 落兜底常量）")
    void returnsNullWhenNeitherSourceHasSessionId() {
        assertNull(SessionIdResolver.resolve(null, null));
        assertNull(SessionIdResolver.resolve(List.of(), List.of()));
    }

    // ---- [欠账清理批] 去重记录：原「拒绝任何 ambient 会话来源」用例已删 ----

    /*
     * 原 `ignoresAmbientSessionStateWhenPresent`（T3 时代的「有诱饵」鉴别性守护）已删，理由三条：
     *
     * ① **诱饵载体已不存在**：它靠「在本线程写入一个『别的会话』的裸 MDC 残留值」当诱饵，再断言
     *    resolve 不采纳。批 3c 把裸 MDC 会话槽整类删除 ⇒ 诱饵无法构造，用例只剩
     *    `resolve(null,null)==null` + `resolve(history,null)==history` 两条断言。
     * ② **两条断言均已由兄弟用例覆盖**：前者 = `returnsNullWhenNeitherSourceHasSessionId` 的第一条
     *    （逐字相同）；后者 = `historyTakesPrecedenceOverOptionsHistory` 中「history 有值即赢」的
     *    null-options 支路 —— 该条**已迁入** `historyTakesPrecedenceOverOptionsHistory`，覆盖面无损失。
     * ③ ⭐ **它对自己鉴别力的声明是假的**（"声称守护 X 实际守不住" 模式）：原类 javadoc 写「resolve
     *    里加回任一 ambient 会话读取兜底 ⇒ 本用例红」。实测不成立 —— 本用例**无法把任何值装进**
     *    一个尚未存在的 ambient 载体（没有 setter 可调）⇒ 即便有人新加 `static ThreadLocal`
     *    兜底，本用例<b>仍全绿</b>。真正的守卫在别处且更强：
     *    (a) **结构性**：ambient 载体（`RequestContext`/裸 MDC 会话槽）已从 main 全量删除
     *        （批 3c 收口核对 `TOTAL_CODE_HITS=0`）；
     *    (b) **接线级**：`ProviderSessionIdWiringGuardTest` 以源码声明锚扫描两个 SDK provider 的
     *        `buildClient(config` 调用点所在成员，禁止出现任何环境态会话槽读取
     *        （含 `RequestContext` / `MDC.get` 的回归守卫）—— 那条才是「调用点照抄 ambient 兜底」
     *        的自动化守卫，且已由变异实验（注入 `MDC.get`）证实会精确变红。
     */

    /** 测试夹具：仅第 1 参 id（恒 null）与第 2 参 sessionId 有意义，其余占位。 */
    private static ChatMessageDto msg(String sid) {
        return new ChatMessageDto(null, sid, Role.user, null, "x",
            null, null, null, null, null, null, null, null, null, null, List.of(), List.of());
    }
}
