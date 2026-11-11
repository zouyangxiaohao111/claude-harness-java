package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.prompt.KbPromptLoader;

/**
 * 查询改写提示词 · 语义逐条对齐 WeKnora
 * {@code config/prompt_templates/rewrite.yaml} 的 {@code default_rewrite} 模板，
 * <b>但改为中文</b>（用户 2026-11-04 要求「最好转为中文 所有提示词」）。
 *
 * <p><b>为什么保留每一条细则</b>：源侧那段提示词里的规则几乎每条都对应一次真实误判。
 * 删掉任何一条都会让意图判定退化：
 * <ul>
 *   <li>「提到知识库就不是 summarize」—— 否则「整理知识库的数据」被当成总结对话</li>
 *   <li>「{@code <未附图 />} 时禁止判成 image_only」—— 否则无图时也会判成看图</li>
 *   <li>「不确定时一律 kb_search」—— fail-open，见 {@link KbQueryIntent} 的类注释</li>
 *   <li>「改写要保留实体/关键词，不要生成『请搜索…』这类元指令」—— 否则检索查询里全是动词没有名词</li>
 * </ul>
 *
 * <p>⚠️ 占位符：{@code {{language}}} / {@code {{current_time}}} / {@code {{current_week}}} /
 * {@code {{query}}}。本类只做字符串替换，⛔ 不引入模板引擎。
 */
final class KbQueryRewritePrompts {

    private KbQueryRewritePrompts() {
    }

    /**
     * 系统提示词（对应源侧 {@code content} 字段，中文版）。
     * <p>正文：{@code resources/kb/prompt/rewrite/query-rewrite.system.md}（外置，改内容改这里）。
     */
    static final String SYSTEM = KbPromptLoader.cached("rewrite/query-rewrite.system.md");

    /**
     * 用户提示词模板（中文版，对应源侧 {@code user} 字段）。
     *
     * <p>占位符：{@code {{current_time}}} / {@code {{current_week}}} / {@code {{query}}}。
     * <p>正文：{@code resources/kb/prompt/rewrite/query-rewrite.user.md}（外置，改内容改这里）。
     */
    static final String USER_TEMPLATE = KbPromptLoader.cached("rewrite/query-rewrite.user.md");

    /**
     * 渲染用户提示词。
     *
     * @param now   当前时间文本（如 {@code 2026-11-04 16:30}）
     * @param week  星期文本（如 {@code 星期二}）
     * @param query 用户问题 + 附件/图片标记（由调用方拼好）
     * @return 渲染后的提示词
     */
    static String renderUser(String now, String week, String query) {
        return USER_TEMPLATE
            .replace("{{current_time}}", now == null ? "" : now)
            .replace("{{current_week}}", week == null ? "" : week)
            .replace("{{query}}", query == null ? "" : query);
    }

    /**
     * 渲染系统提示词（只替换 {@code {{language}}}）。
     *
     * @param language 语言提示（空 = 用「跟随原问题语言」）
     * @return 渲染后的提示词
     */
    static String renderSystem(String language) {
        String lang = language == null || language.isBlank()
            ? "用户提问所使用的语言" : language;
        return SYSTEM.replace("{{language}}", lang);
    }
}
