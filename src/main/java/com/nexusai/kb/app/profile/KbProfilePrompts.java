package com.nexusai.kb.app.profile;

/**
 * 知识库画像提示词 · 批次 W4。
 *
 * <p><b>源侧对照</b>：WeKnora {@code POST /knowledge-bases/{id}/profile/generate}
 * （{@code internal/application/service/knowledgebase_profile*.go}）。
 *
 * <h2>⭐ 什么是「画像」</h2>
 * 一段 2–4 句的中文描述，概括这个知识库<b>覆盖什么</b>。它会被拼进检索前的理解环节，
 * 帮模型判断「这个问题该不该查这个库」。不是给用户看的营销文案。
 *
 * <h2>⭐⭐ 按标题生成，不读全文</h2>
 * 源侧只喂文档标题（有时加描述），不喂正文。原因：
 * <ul>
 *   <li><b>成本</b>：一个库可能几百篇文档，读全文的 token 是标题的百倍；</li>
 *   <li><b>更准</b>：标题是人写的、概括性更强；正文里大量格式噪声反而干扰。</li>
 * </ul>
 *
 * <h2>⭐⭐ 没有文档时必须说「没有」，不许编</h2>
 * 空库生成画像 → 模型会用「知识库名」编出一段听起来很像的概述。
 * 用户会以为库里真有这些内容。⇒ 空库直接返回固定的「暂无内容」文案，<b>不调模型</b>。
 */
public final class KbProfilePrompts {

    private KbProfilePrompts() {
    }

    /** 系统提示词（中文，逐字可用）。 */
    public static final String SYSTEM = """
        你是知识库描述撰写助手。给定一个知识库的名称和它的文档标题列表，
        用中文写出 2-4 句、总计不超过 200 字的描述，概括这个知识库**实际覆盖的主题**。

        规则：
        1. 只依据给出的标题，⛔ 不要补充标题里没有的主题；
        2. 不要写「本知识库」「该知识库」以外的营销用语，直接描述内容范围；
        3. 不要罗列全部标题，提炼共性；
        4. 如果标题不足以判断主题，就如实说「内容主题不明确」；
        5. 只输出描述正文，不要加标题、不要加引号、不要加项目符号。
        """;

    /** 用户消息模板。 */
    public static String userMessage(String kbName, java.util.List<String> titles) {
        StringBuilder sb = new StringBuilder();
        sb.append("知识库名称：").append(kbName == null || kbName.isBlank() ? "（未命名）" : kbName)
            .append("\n\n文档标题（共 ").append(titles.size()).append(" 条）：\n");
        // ⚠️ 上限 200 条：再多的标题对提炼主题没有增量，只增加 token。
        int limit = Math.min(titles.size(), 200);
        for (int i = 0; i < limit; i++) {
            String t = titles.get(i);
            if (t == null || t.isBlank()) {
                continue;
            }
            sb.append("- ").append(t.trim()).append('\n');
        }
        if (titles.size() > limit) {
            sb.append("（其余 ").append(titles.size() - limit).append(" 条略）\n");
        }
        return sb.toString();
    }

    /** 空库时的固定文案（⛔ 不调模型）。 */
    public static final String EMPTY_PROFILE = "该知识库暂无内容。";

    /** 生成失败时的兜底：保留旧画像，⛔ 不写空串（空串会让前端显示空白并看似「已生成」）。 */
    public static String fallback(String previous) {
        return previous == null || previous.isBlank() ? "" : previous;
    }
}
