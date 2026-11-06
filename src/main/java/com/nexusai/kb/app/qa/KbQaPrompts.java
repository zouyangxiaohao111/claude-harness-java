package com.nexusai.kb.app.qa;

/**
 * 知识库问答的提示词（全中文）。
 *
 * <h2>三条不能删的约束</h2>
 * <ol>
 *   <li><b>只答资料里有的</b>：知识库问答的价值就是「有据可查」。
 *       允许模型自由发挥 ⇒ 用户拿到看起来对、实际是编的答案，比「没找到」糟糕得多。</li>
 *   <li><b>资料不足要直说</b>：⛔ 不要用常识补全。必须明确告诉用户「资料里没有」，
 *       并说明缺什么 —— 用户才知道该补哪份文档。</li>
 *   <li><b>引用编号必须来自上下文</b>：⛔ 不许发明编号。编造的编号会让引用链断裂，
 *       前端点开是空的。没有依据的句子就不该带编号。</li>
 * </ol>
 *
 * <h2>⚠️ 与 {@link KbContextBuilder} 的编号约定</h2>
 * 上下文里每段以 {@code [n]} 开头，提示词要求模型用 {@code [n]} 回指。
 * 两边的编号体系必须一致，否则引用落库后全部错位。
 */
public final class KbQaPrompts {

    private KbQaPrompts() {
    }

    /**
     * 系统提示词（有检索资料时）。
     *
     * <p>⚠️ 不要在运行时给这段加日期/用户名之类的变量 —— 那会让 prompt cache 每次都失效。
     */
    public static final String SYSTEM_WITH_CONTEXT = """
        你是企业知识库的问答助手。用户的问题会附带从知识库中检索到的资料。

        ## 回答规则

        1. **只使用提供的资料回答**。资料里没有的内容，不要凭常识或推测补充。
        2. **资料不足时明确说明**。例如「提供的资料中没有关于 XX 的内容」，
           并指出还需要哪方面的资料。不要为了显得有帮助而编造。
        3. **在用到资料的句子后面标注编号**，格式为 `[1]`、`[2]`。
           编号必须来自资料中已有的编号，不要发明。
           一句话综合了多段资料时，可并列多个编号，如 `[1][3]`。
        4. **不要复述原文**。用自己的话概括，只保留与问题相关的部分。
        5. **资料之间若互相矛盾**，指出矛盾并分别给出各自出处，不要擅自裁决。
        6. 用中文回答。技术名词、代码、型号保持原样。
        7. 只输出回答本身，不要写「根据提供的资料」这类开场白。

        ## 格式

        - 用 Markdown。步骤、并列项用有序/无序列表。
        - 数值、单位、时间点必须原样保留，不要四舍五入或换算。
        """;

    /**
     * 系统提示词（未检索到资料时）。
     *
     * <p>⚠️ 这条不是「让模型硬答」的许可，而是「老实说没有」的指令。
     * 用它而不是直接返回固定文案，是因为用户问题常常需要澄清方向
     * （「没找到 XX，你是想问 YY 吗」），这比一句冷冰冰的「未找到」有用。
     */
    public static final String SYSTEM_NO_CONTEXT = """
        你是企业知识库的问答助手。

        ## 情况

        本轮**没有从知识库检索到任何相关资料**。

        ## 回答规则

        1. 明确告诉用户：在知识库中没有找到与该问题相关的内容。
        2. **不要凭常识或推测作答** —— 用户问的是知识库里的内容，
           用通用知识回答会被误当成「知识库里是这么写的」。
        3. 可以做的：请用户补充关键词、说明可能相关的文档名或知识库范围。
        4. 用中文，简短（不超过 3 句）。只输出回答本身。
        """;

    /**
     * 模型返回空回答时的兜底文案（阶段 11 对齐源侧
     * {@code chat_completion_stream.go:EmptyTruncatedAnswerFallback} 的同款语义）。
     *
     * <p>空回答不是「成功的空」：下游会显示一个空气泡，用户以为界面坏了。
     * 这里的文案要说清「本轮没产出内容 + 可以怎么办」，而不是假装回答了。
     */
    public static final String EMPTY_ANSWER_FALLBACK =
        "抱歉，本轮没有生成任何内容。请换一种问法，或缩小问题范围后重试。";

    /**
     * 长度截断且从未产出文本时的回退（源侧 {@code EmptyTruncatedAnswerFallback}）。
     *
     * <p>⭐ 与 {@link #EMPTY_ANSWER_FALLBACK} 分开：那个是「模型没话可说」，
     * 这个是「模型想说但被输出上限截在第一个字之前」。两种情况用户该做的
     * 事不一样（前者换问法，后者可以要求更短的答案）。
     */
    public static final String EMPTY_TRUNCATED_FALLBACK =
        "抱歉，本次回答因长度限制在产出内容前就被截断了。可以要求我「简短回答」，或把问题拆小一些再试。";

    /**
     * 回答被长度截断时的追加提示（源侧把这种情况转成错误；本仓保留正文 + 提示）。
     *
     * <p>⛔ 不改写已推出去的正文 —— 前端已经显示过，改历史会让用户看到内容跳变。
     */
    public static final String TRUNCATED_SUFFIX = "\n\n（以上回答因长度限制被截断，可能需要继续追问。）";

    /**
     * 判断 finishReason 是否表示「被输出上限截断」。
     *
     * <p>⚠️ 不能只看 {@code "length"}：不同 provider 的取值不同 ——
     * OpenAI 是 {@code length}，Anthropic 是 {@code max_tokens}，
     * 有的兼容层（DeepSeek）也给 {@code length}。这里同时认这几种。
     *
     * @param finishReason provider 给的值（可 null）
     * @return 是否被截断
     */
    public static boolean isLengthFinishReason(String finishReason) {
        if (finishReason == null || finishReason.isBlank()) {
            return false;
        }
        String v = finishReason.strip().toLowerCase(java.util.Locale.ROOT);
        return "length".equals(v) || "max_tokens".equals(v) || "max_output_tokens".equals(v)
            || "incomplete".equals(v);
    }

    /**
     * 拼 user 消息：资料 + 问题。
     *
     * <p>⚠️ 资料在前、问题在后。反过来的话，长资料会把问题推到很后面，
     * 部分模型对末尾内容注意力更强，问题在末尾更容易被准确对应。
     *
     * @param context   {@link KbContextBuilder} 产出的上下文（空串表示无资料）
     * @param question  用户问题（已改写）
     * @return user 消息正文
     */
    public static String userMessage(String context, String question) {
        String q = question == null ? "" : question.strip();
        if (context == null || context.isBlank()) {
            return q;
        }
        return context.strip() + "\n\n---\n\n## 问题\n\n" + q;
    }

    /**
     * 选系统提示词。
     *
     * @param hasContext 是否有检索资料
     * @return 对应的系统提示词
     */
    public static String systemPrompt(boolean hasContext) {
        return hasContext ? SYSTEM_WITH_CONTEXT : SYSTEM_NO_CONTEXT;
    }
}
