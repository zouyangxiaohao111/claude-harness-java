package com.nexusai.kb.app.qa;

import com.nexusai.kb.app.prompt.KbPromptLoader;

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
     * <p>正文：{@code resources/kb/prompt/qa/qa.system-with-context.md}（外置，改内容改这里）。
     */
    public static final String SYSTEM_WITH_CONTEXT = KbPromptLoader.cached("qa/qa.system-with-context.md");

    /**
     * 系统提示词（未检索到资料时）。
     *
     * <p>⚠️ 这条不是「让模型硬答」的许可，而是「老实说没有」的指令。
     * 用它而不是直接返回固定文案，是因为用户问题常常需要澄清方向
     * （「没找到 XX，你是想问 YY 吗」），这比一句冷冰冰的「未找到」有用。
     * <p>正文：{@code resources/kb/prompt/qa/qa.system-no-context.md}（外置，改内容改这里）。
     */
    public static final String SYSTEM_NO_CONTEXT = KbPromptLoader.cached("qa/qa.system-no-context.md");

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
