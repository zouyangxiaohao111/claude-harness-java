package com.nexusai.kb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.kb.domain.KnowledgeBaseService;
import com.nexusai.kb.dto.KnowledgeBaseDto;
import com.nexusai.kb.app.wiki.KbWikiPageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [KB 内置 Agent 对齐 · 真机回归] Wiki 工具的<b>知识库作用域三态</b>。
 *
 * <h2>⭐⭐⭐ 这条测试锁定的是一个真机发现的 bug</h2>
 * 端到端跑 `builtin-wiki-researcher` 时，每条 `wiki_search` 都返回
 * {@code {"pages":[],"note":"请求的知识库均不在本智能体的授权范围内"}} ——
 * 而那个会话<b>根本没有设置任何知识库限制</b>。
 *
 * <p>根因：{@code KbAgentToolScope.allowedKnowledgeBaseIds} 有<b>三种</b>返回值，
 * 我第一版把前两种当成了同一种：
 * <table border="1">
 *   <tr><th>返回值</th><th>语义</th><th>正确行为</th></tr>
 *   <tr><td>{@code null}</td><td><b>不限制</b>（未绑定 agent / agent 未配范围）</td><td>检索<b>全部</b>库</td></tr>
 *   <tr><td>{@code []}</td><td><b>明确禁止</b>（绑定了但一个都没授权）</td><td>返回空结果</td></tr>
 *   <tr><td>{@code [a,b]}</td><td>白名单</td><td>只查这几个</td></tr>
 * </table>
 *
 * <p>这个错误的隐蔽性在于<b>不报错、只是永远搜不到东西</b> ——
 * 模型会反复换 query 重试，用户看到的是「知识库是空的」。
 * 同族缺陷在 {@code KbWikiReadPageTool} 也有一份，两处都修了。
 */
class KbWikiToolScopeThreeStateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── 测试替身 ────────────────────────────────────────────────────────────

    /** 固定返回预设值的 scope（模拟三种状态）。 */
    static class FixedScope extends KbAgentToolScope {
        private final List<String> value;

        FixedScope(List<String> value) {
            this.value = value;
        }

        @Override
        public List<String> allowedKnowledgeBaseIds(ToolUseContext ctx) {
            return value;
        }
    }

    /** 只有 list() 的库服务替身（mock，避免依赖 mapper）。 */
    private static KnowledgeBaseService fakeKbService(List<String> ids) {
        KnowledgeBaseService svc = org.mockito.Mockito.mock(KnowledgeBaseService.class);
        List<KnowledgeBaseDto> dtos = ids.stream()
            .map(KbWikiToolScopeThreeStateTest::kbDto)
            .toList();
        org.mockito.Mockito.when(svc.list()).thenReturn(dtos);
        return svc;
    }

    /** 造一个最小 KnowledgeBaseDto（只有 id 有意义）。 */
    private static KnowledgeBaseDto kbDto(String id) {
        return new KnowledgeBaseDto(id, null, null, null, false, null, null,
            null, null, null, null, null, null, false, null, null, null, null, null, null);
    }

    /**
     * 句柄解析替身：原样返回入参。
     *
     * <p>⚠️ 这两个用例传了 {@code knowledge_base_ids}，工具会先过
     * {@link KbHandleResolver#resolveKnowledgeBase(String)}；不注入的话是 NPE
     * （存量测试腐烂，与本次改动无关 —— HEAD 版本的 KbWikiSearchTool 已依赖该字段）。
     */
    private static KbHandleResolver fakeResolver() {
        KbHandleResolver r = org.mockito.Mockito.mock(KbHandleResolver.class);
        org.mockito.Mockito.when(r.resolveKnowledgeBase(org.mockito.ArgumentMatchers.anyString()))
            .thenAnswer(inv -> inv.getArgument(0));
        return r;
    }

    /** 反射注入（字段是 private，测试里不引入 Spring）。 */
    private static void inject(Object target, String field, Object value) {
        try {
            Class<?> c = target.getClass();
            Field f = null;
            while (c != null) {
                try {
                    f = c.getDeclaredField(field);
                    break;
                } catch (NoSuchFieldException e) {
                    c = c.getSuperclass();
                }
            }
            if (f == null) {
                throw new IllegalStateException("找不到字段 " + field);
            }
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把私有方法 resolveTargets 的结果暴露出来（通过反射调用）。 */
    @SuppressWarnings("unchecked")
    private static List<String> resolveTargets(Object tool, JsonNode input, ToolUseContext ctx) {
        try {
            var m = tool.getClass().getDeclaredMethod("resolveTargets", JsonNode.class, ToolUseContext.class);
            m.setAccessible(true);
            return (List<String>) m.invoke(tool, input, ctx);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode input(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ── wiki_search ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("⭐ wiki_search · scope=null（不限制）⇒ 检索全部库，不是空列表")
    void search_null_scope_means_all() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", null);
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));

        List<String> got = resolveTargets(tool, input("{}"), null);

        assertThat(got)
            .as("scope=null 是「不限制」，必须枚举全部库；返回空会让工具永远搜不到")
            .containsExactly("kb-1", "kb-2");
    }

    @Test
    @DisplayName("wiki_search · scope=[]（明确禁止）⇒ 空列表")
    void search_empty_scope_means_none() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", new FixedScope(List.of()));
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));

        List<String> got = resolveTargets(tool, input("{}"), null);

        assertThat(got).as("明确禁止就该是空，⛔ 不能退化成全部").isEmpty();
    }

    @Test
    @DisplayName("wiki_search · scope=[kb-1] ⇒ 只用白名单")
    void search_whitelist() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", new FixedScope(List.of("kb-1")));
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));

        assertThat(resolveTargets(tool, input("{}"), null)).containsExactly("kb-1");
    }

    @Test
    @DisplayName("wiki_search · 显式指定 kb 时与白名单取交集（越权的不给）")
    void search_explicit_intersects_whitelist() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", new FixedScope(List.of("kb-1")));
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));
        inject(tool, "handleResolver", fakeResolver());

        List<String> got = resolveTargets(tool,
            input("{\"knowledge_base_ids\":[\"kb-1\",\"kb-2\"]}"), null);

        assertThat(got).containsExactly("kb-1");
    }

    @Test
    @DisplayName("wiki_search · 不限制时可显式指定任意 kb")
    void search_unrestricted_allows_explicit() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", null);
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));
        inject(tool, "handleResolver", fakeResolver());

        List<String> got = resolveTargets(tool,
            input("{\"knowledge_base_ids\":[\"kb-2\"]}"), null);

        assertThat(got).containsExactly("kb-2");
    }

    @Test
    @DisplayName("wiki_search · 无 scope bean 时也不崩（返回全部）")
    void search_null_scope_bean_safe() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", null);
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1")));

        assertThat(resolveTargets(tool, input("{}"), null)).containsExactly("kb-1");
    }

    @Test
    @DisplayName("wiki_search · 列库失败返回空且不抛（工具级降级）")
    void search_list_failure_degrades() {
        KbWikiSearchTool tool = new KbWikiSearchTool();
        inject(tool, "scope", null);
        {
            KnowledgeBaseService broken = org.mockito.Mockito.mock(KnowledgeBaseService.class);
            org.mockito.Mockito.when(broken.list()).thenThrow(new IllegalStateException("db down"));
            inject(tool, "knowledgeBaseService", broken);
        }

        assertThat(resolveTargets(tool, input("{}"), null)).isEmpty();
    }

    // ── wiki_read_page（同族缺陷） ───────────────────────────────────────────

    @Test
    @DisplayName("⭐ wiki_read_page · scope=null（不限制）⇒ 也在全部库里找")
    void read_null_scope_means_all() {
        KbWikiReadPageTool tool = new KbWikiReadPageTool();
        inject(tool, "scope", null);
        inject(tool, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));

        List<String> got = resolveTargets(tool, input("{}"), null);

        assertThat(got).as("同族缺陷：read_page 也不能把「不限制」当「禁止」")
            .containsExactly("kb-1", "kb-2");
    }

    @Test
    @DisplayName("wiki_read_page · scope=[] ⇒ 空；scope=[kb-1] ⇒ 白名单")
    void read_scope_states() {
        KbWikiReadPageTool empty = new KbWikiReadPageTool();
        inject(empty, "scope", new FixedScope(List.of()));
        inject(empty, "knowledgeBaseService", fakeKbService(List.of("kb-1")));
        assertThat(resolveTargets(empty, input("{}"), null)).isEmpty();

        KbWikiReadPageTool one = new KbWikiReadPageTool();
        inject(one, "scope", new FixedScope(List.of("kb-1")));
        inject(one, "knowledgeBaseService", fakeKbService(List.of("kb-1", "kb-2")));
        assertThat(resolveTargets(one, input("{}"), null)).containsExactly("kb-1");
    }

    // ── 保证 wikiPageService 字段存在（工具真能用到的依赖） ──────────────────

    @Test
    @DisplayName("两个工具都持有 wikiPageService 依赖（装配完整性）")
    void both_have_wiki_page_service() {
        assertThat(hasField(new KbWikiSearchTool(), "wikiPageService")).isTrue();
        assertThat(hasField(new KbWikiReadPageTool(), "wikiPageService")).isTrue();
    }

    private static boolean hasField(Object target, String name) {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                c.getDeclaredField(name);
                return true;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return false;
    }

    /** 只为编译期确认 KbWikiPageService 类型可用（工具构造依赖）。 */
    @SuppressWarnings("unused")
    private static KbWikiPageService typeAnchor() {
        return null;
    }
}
