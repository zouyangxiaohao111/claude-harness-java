package com.nexusai.kb.app.wiki;

import com.nexusai.kb.repo.entity.KbKnowledgeBaseRecord;
import com.nexusai.kb.repo.mapper.KbKnowledgeBaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 手动补生成 Wiki · {@code manual} 标志的判别力测试。
 *
 * <h2>WHY（规则九：测试要验证意图，不只是行为）</h2>
 * {@code auto_generate=false} 的语义是「<b>不要自动生成</b>」，不是「<b>不允许生成</b>」。
 * 若手动路径也被同一行开关静默拦掉，用户会看到：<b>HTTP 200 + status=skipped + 页面上什么都没发生</b>，
 * 而且不报错 —— 唯一的补救手段是整篇重新解析（重跑分块与索引，成本高、块 ID 会变）。
 * 这条测试守的就是「手动路径真的绕过了这道闸」。
 *
 * <h2>怎么在不碰 LLM / 不碰真库的前提下测到生产代码</h2>
 * 直接调真实的 {@link KbWikiIngestService#runBatch}（5 参），把它挡在「文档列表为空」这个
 * <b>已知出口</b>上：
 * <ul>
 *   <li>开关判定<b>先</b>执行 ⇒ 若被开关拦下，{@code skipReason} = {@code "auto_generate 已关闭"}；</li>
 *   <li>开关判定被绕过 ⇒ 走到下游的空列表出口，{@code skipReason} = {@code "没有待处理文档"}。</li>
 * </ul>
 * 两个出口的文案不同 ⇒ 只凭返回值就能判定「走的是哪条分支」，不需要 mock 整条 LLM 链路。
 * 知识库行用 {@link Proxy} 里的假 mapper 提供（{@code selectOneById} 是唯一被调到的方法）。
 */
@DisplayName("[Wiki] 手动补生成绕过 auto_generate")
class KbWikiManualRebuildTest {

    private static final String KB_ID = "kb-manual-test";

    private KbWikiIngestService service;

    @BeforeEach
    void setUp() {
        service = new KbWikiIngestService(
            null,                       // knowledgeMapper：本用例走不到（文档列表为空）
            fakeKbMapper("{\"auto_generate\": false}"),
            null,                       // chunkMapper：走不到
            null, null, null, null      // Map/Reduce/Finalize/PageService：走不到
        );
    }

    // ── 用例 1（核心）─────────────────────────────────────────────────

    @Test
    @DisplayName("⭐⭐ auto_generate=false + 自动路径 ⇒ 仍被开关拦下（行为与改造前一致）")
    void autoPathStillHonoursSwitch() {
        KbWikiIngestService.RunSummary s = service.runBatch(
            KB_ID, List.of(), "model-x", false, false);

        assertThat(s.skipped()).isTrue();
        assertThat(s.skipReason())
            .as("自动路径必须保留 auto_generate 检查；否则用户关掉自动生成后，"
                + "每次上传文档都会继续烧 LLM 调用")
            .isEqualTo("auto_generate 已关闭");
    }

    @Test
    @DisplayName("⭐⭐ auto_generate=false + 手动路径 ⇒ 不被开关拦下（走到下游空列表出口）")
    void manualPathBypassesSwitch() {
        KbWikiIngestService.RunSummary s = service.runBatch(
            KB_ID, List.of(), "model-x", false, true);

        assertThat(s.skipped())
            .as("手动触发本意就是要生成，开关不该把它拦成「静默无动作」")
            .isTrue();
        assertThat(s.skipReason())
            .as("⭐ 关键判据：必须是下游的「没有待处理文档」，"
                + "而不是上层的「auto_generate 已关闭」—— 两者文案不同才能证明走了哪条分支")
            .isEqualTo("没有待处理文档")
            .isNotEqualTo("auto_generate 已关闭");
    }

    @Test
    @DisplayName("纯函数层：shouldSkipForAutoGenerate 与 runBatch 同语义")
    void helperMatchesRunBatchSemantics() {
        KbWikiConfig off = KbWikiConfig.parse("{\"auto_generate\": false}");
        KbWikiConfig on = KbWikiConfig.parse("{\"auto_generate\": true}");

        assertThat(KbWikiIngestService.shouldSkipForAutoGenerate(off, false)).isTrue();
        assertThat(KbWikiIngestService.shouldSkipForAutoGenerate(off, true)).isFalse();
        assertThat(KbWikiIngestService.shouldSkipForAutoGenerate(on, false)).isFalse();
        assertThat(KbWikiIngestService.shouldSkipForAutoGenerate(on, true)).isFalse();
    }

    // ── 用例 2（回归保护）──────────────────────────────────────────────

    @Test
    @DisplayName("⭐ auto_generate=true 时两种 manual 值行为一致（不因新增参数改变原行为）")
    void enabledSwitchBehavesIdentically() {
        KbWikiIngestService svc = new KbWikiIngestService(
            null, fakeKbMapper("{\"auto_generate\": true}"), null, null, null, null, null);

        KbWikiIngestService.RunSummary auto =
            svc.runBatch(KB_ID, List.of(), "model-x", false, false);
        KbWikiIngestService.RunSummary manual =
            svc.runBatch(KB_ID, List.of(), "model-x", false, true);

        assertThat(auto.skipReason()).isEqualTo("没有待处理文档");
        assertThat(manual.skipReason()).isEqualTo("没有待处理文档");
        assertThat(manual.skipReason()).isEqualTo(auto.skipReason());
    }

    @Test
    @DisplayName("⭐ auto_generate 缺失（null ⇒ 开）时手动路径同样正常放行")
    void missingSwitchTreatedAsEnabled() {
        KbWikiIngestService svc = new KbWikiIngestService(
            null, fakeKbMapper("{}"), null, null, null, null, null);

        assertThat(svc.runBatch(KB_ID, List.of(), "m", false, false).skipReason())
            .isEqualTo("没有待处理文档");
        assertThat(svc.runBatch(KB_ID, List.of(), "m", false, true).skipReason())
            .isEqualTo("没有待处理文档");
    }

    @Test
    @DisplayName("⭐ 4 参重载 == 5 参 manual=false（向后兼容，自动调用点行为不变）")
    void fourArgOverloadEqualsManualFalse() {
        KbWikiIngestService.RunSummary four =
            service.runBatch(KB_ID, List.of(), "model-x", false);
        KbWikiIngestService.RunSummary five =
            service.runBatch(KB_ID, List.of(), "model-x", false, false);

        assertThat(four.skipReason()).isEqualTo(five.skipReason())
            .isEqualTo("auto_generate 已关闭");
    }

    // ── 假 mapper ─────────────────────────────────────────────────────

    /**
     * 只实现 {@code selectOneById} 的假 {@link KbKnowledgeBaseMapper}。
     *
     * <p>⚠️ 不用 mocking 框架：本仓测试统一用 assertj + 手造对象，
     * 且这里只需要「按 id 返回一行」这一个行为。
     *
     * @param wikiConfigJson 该知识库的 {@code wiki_config} 列值
     * @return 代理对象
     */
    private static KbKnowledgeBaseMapper fakeKbMapper(String wikiConfigJson) {
        KbKnowledgeBaseRecord row = new KbKnowledgeBaseRecord();
        row.setId(KB_ID);
        row.setWikiConfig(wikiConfigJson);
        // summary_model_id 留空：本用例不需要模型（在开关判定/空列表出口就返回）

        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "selectOneById" -> row;
            case "toString" -> "FakeKbKnowledgeBaseMapper(" + KB_ID + ")";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
        return (KbKnowledgeBaseMapper) Proxy.newProxyInstance(
            KbKnowledgeBaseMapper.class.getClassLoader(),
            new Class<?>[] {KbKnowledgeBaseMapper.class},
            handler);
    }
}
