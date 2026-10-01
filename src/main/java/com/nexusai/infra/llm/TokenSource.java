package com.nexusai.infra.llm;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 一个 token 数字的<b>来源</b> · 「上下文分析」面板四态标注的后端真源。
 *
 * <p><b>为什么需要它</b>：/context analyze 面板上的每个数字实际有四种来源，但 wire 上原先被塌成
 * 同一个裸 int（{@code raw == null ? 0 : raw}，三处 collapse 点）：
 * <ol>
 *   <li>{@link #API} —— 服务端真实计数（Anthropic {@code POST {baseUrl}/v1/messages/count_tokens}）；</li>
 *   <li>{@link #ESTIMATE} —— 本地估算（OpenAI 走 tiktoken；Skills 一栏恒为
 *       {@code SkillsLoader.estimateSkillFrontmatterTokens} 的 {@code round(len/4)}）；</li>
 *   <li>{@link #UNAVAILABLE} —— <b>算不出来</b>：端点缺失 / 调用失败 / 输入非法，
 *       此时数值为 {@code null}（<b>绝不</b>写成 0）；</li>
 *   <li>真 0（空原料短路 / 服务端确实返回 0）—— 不是独立来源，而是「来源 + 数值 0」的组合。</li>
 * </ol>
 *
 * <p>四态在 wire 上由 <b>{@code 数值是否为 null} × {@code tokenSource}</b> 两轴共同决定：
 * <pre>
 *   (a1) 服务端真实计数 → tokens=非空, tokenSource="api"
 *   (a2) 本地估算       → tokens=非空, tokenSource="estimate"   （Skills 一栏同属此类）
 *   (b)  算不出来       → tokens=null, tokenSource="unavailable"
 *   (c)  真 0           → tokens=0,    tokenSource="api" | "estimate"
 * </pre>
 * ⛔ 不要让 0 兼任「零」与「未知」两义 —— 这正是本次要修的根（原 {@code tokens||0}）。
 *
 * <p><b>来源由「实际被装配的客户端」决定，不由 provider 类型猜</b>：CountTokensClient bean 在
 * {@code @Bean} 构造期按 provider 类型求值一次（ToolRegistrationConfig#countTokensClient），
 * 用户切 provider 后 bean 不重建 ⇒ 前端按 {@code Provider.type === 'anthropic'} 猜会与实际使用的
 * 客户端错位；且 {@code type: anthropic} 的第三方兼容商恰恰就是「没这个端点」那批。
 * 故来源随客户端实例本身走（{@link CountTokensClient#sourceKind()}）。
 */
public enum TokenSource {

    /** 服务端真实计数（精确）。对应「上下文分析」面板 (a1) 态：数值原样展示，不加角标。 */
    API("api"),

    /** 本地估算（近似）。对应 (a2)/(a3) 态：数值旁标注「估算」。 */
    ESTIMATE("estimate"),

    /** 算不出来（未知）。对应 (b) 态：数值位显示「—」并标注「不可用」。 */
    UNAVAILABLE("unavailable");

    private final String wire;

    TokenSource(String wire) {
        this.wire = wire;
    }

    /**
     * wire 表示（小写）· 前端 {@code ContextTokenSource} 联合类型的唯一真源。
     *
     * @return {@code "api"} / {@code "estimate"} / {@code "unavailable"}
     */
    @JsonValue
    public String wire() {
        return wire;
    }

    /**
     * 由数值反推来源：{@code null} ⇒ {@link #UNAVAILABLE}；非 null ⇒ 调用方给出的实际计数方式。
     *
     * <p>收敛三处 collapse 点的统一写法（原先各有各的 {@code raw == null ? 0 : raw}）。
     *
     * @param tokens       计数结果（{@code null} = 算不出来）
     * @param computedKind 产生该数值的客户端的来源类别（见 {@link CountTokensClient#sourceKind()}）
     * @return 该数值的来源
     */
    public static TokenSource of(Integer tokens, TokenSource computedKind) {
        return tokens == null ? UNAVAILABLE : computedKind;
    }

    /**
     * 合成两个来源：取<b>较弱</b>一方（{@code UNAVAILABLE > ESTIMATE > API}）。
     *
     * <p>用于扣减/求和语义（如 categories 的「内置工具（不含技能）」= builtIn - skill，CC 原名 'System tools'）：
     * 只要参与运算的
     * 任一项是估算，合计就只能是估算；任一项算不出来，合计就无从谈起。
     *
     * @param a 来源 a
     * @param b 来源 b
     * @return 较弱一方
     */
    public static TokenSource weaker(TokenSource a, TokenSource b) {
        if (a == UNAVAILABLE || b == UNAVAILABLE) {
            return UNAVAILABLE;
        }
        if (a == ESTIMATE || b == ESTIMATE) {
            return ESTIMATE;
        }
        return API;
    }
}
