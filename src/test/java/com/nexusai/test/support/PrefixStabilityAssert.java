package com.nexusai.test.support;

import org.assertj.core.api.Assertions;

import java.util.List;

/**
 * <b>步骤 8 · 前缀稳定性判据</b>：把「会话级头部冻结 + 消息严格前缀扩展」写成可判绿的不变量。
 *
 * <p>本类逐条移植 dsh 的三层判据里的<b>离线层</b>
 * （{@code D:/code/deepseek-harness/packages/core/agent-loop/tests/request-reconstruction.spec.ts:55-60}
 * 的 {@code expectPrefixExtension} + 同文件 {@code THEOREM} 型 {@code :566-612}），
 * 并补上 dsh 用<b>运行时不变量</b>表达的那一条
 * （{@code .../src/invariant.ts:21-53}：请求头部必须冻结且等于从会话日志派生出来的值）。
 *
 * <h2>CC/dsh 真源 → 本仓映射（逐条）</h2>
 * <table border="1">
 *   <caption>断言映射</caption>
 *   <tr><th>dsh 断言</th><th>真源</th><th>本仓对应物</th></tr>
 *   <tr><td>{@code current.messages.length > previous.messages.length}</td>
 *       <td>spec:56</td><td>同（{@link #expectPrefixExtension}）</td></tr>
 *   <tr><td>{@code current.messages.slice(0, n) === previous.messages}</td>
 *       <td>spec:57</td><td>线级投影前缀相等（{@link OutboundRequest#wireMessages()}）——
 *           ⛔ 不用 DTO 深比较，理由见 {@link OutboundRequest} 类 javadoc</td></tr>
 *   <tr><td>{@code current.system === previous.system}（逐字节）</td>
 *       <td>spec:58</td><td>{@link OutboundRequest#systemText()} 逐字节相等</td></tr>
 *   <tr><td>{@code current.tools === previous.tools}</td>
 *       <td>spec:59</td><td>{@link OutboundRequest#toolsText()} 逐字节相等</td></tr>
 *   <tr><td>「请求必须冻结且等于 deriveMessages 的结果」</td>
 *       <td>invariant.ts:23-53</td><td>{@link #expectHeadFrozen} —— 同一会话相邻两次请求的
 *           system / tools / {@code messages[0]} 三者逐字节相等（跨 run 边界）</td></tr>
 * </table>
 *
 * <h2>⛔ 为什么「头部相等」与「前缀扩展」必须<b>分开</b>成两个方法</h2>
 * <p>{@link #expectPrefixExtension} 只覆盖 dsh 的四条；它<b>不</b>足以表达本仓的对齐目标 ——
 * 本仓的故障形态（每 run 重建头部）会让 {@code messages[0]} 也漂移，而 dsh 的
 * {@code slice(0, n)} 比较已经把 {@code messages[0]} 含在内了（它的 messages 就是 wire 消息，
 * 头部元消息在其中）。本仓 {@code messages[0]} 是每轮<b>重新生成</b>的 userContext 元消息
 * （{@code prependUserContext}），其 DTO id 每轮不同 ⇒ 必须<b>显式</b>给出
 * {@link #expectHeadFrozen} 这条以「会话级冻结值」为断言对象的判据，
 * 否则「头部冻结」这条核心契约在本仓没有任何一条断言直接覆盖它。
 */
public final class PrefixStabilityAssert {

    private PrefixStabilityAssert() {
    }

    /**
     * <b>严格前缀扩展</b>（dsh {@code spec:55-60} 逐条）。
     *
     * @param previous 前一次出站请求（同一会话）
     * @param current  后一次出站请求（同一会话）
     */
    public static void expectPrefixExtension(OutboundRequest previous, OutboundRequest current) {
        Assertions.assertThat(current.messageCount())
            .as("前缀扩展：后一次请求的消息条数必须严格多于前一次（%d → %d）",
                previous.messageCount(), current.messageCount())
            .isGreaterThan(previous.messageCount());

        List<String> prevWire = previous.wireMessages();
        List<String> curWire = current.wireMessages();
        Assertions.assertThat(curWire.subList(0, prevWire.size()))
            .as("前缀扩展：后一次请求的前 %d 条消息必须与前一次逐字节相同"
                + "（相等口径 = 线级投影，见 OutboundRequest javadoc）\n前一次=%s\n后一次=%s",
                prevWire.size(), head(prevWire), head(curWire))
            .isEqualTo(prevWire);

        Assertions.assertThat(current.systemText())
            .as("前缀扩展：system 串必须逐字节相等（system 位于前缀最前，一变整段失效）")
            .isEqualTo(previous.systemText());
        Assertions.assertThat(current.toolsText())
            .as("前缀扩展：tools 数组必须逐字节相等（tools 紧随 system，一变整段失效）")
            .isEqualTo(previous.toolsText());
    }

    /**
     * <b>会话级头部冻结</b>（对应 dsh {@code invariant.ts:23-53} 与计划 §4 步骤 8 第 3 条）：
     * 同一会话相邻两次请求的 {@code system} 串、{@code tools} 数组、{@code messages[0]} 三者
     * <b>逐字节相等</b>。
     *
     * <p>⚠ 本方法<b>只</b>看头部三项，不看消息条数 —— 它是 {@link #expectPrefixExtension} 的
     * 必要补充而非替代（dsh 的 {@code slice(0,n)} 之所以够用，是因为它的 {@code messages[0]}
     * 就是同一个对象；本仓 {@code messages[0]} 每轮新生成，必须按线级投影单独钉）。
     */
    public static void expectHeadFrozen(OutboundRequest previous, OutboundRequest current) {
        Assertions.assertThat(current.systemText())
            .as("头部冻结：system 串必须跨 run 逐字节相等（否则 DeepSeek 前缀缓存在头部即断）")
            .isEqualTo(previous.systemText());
        Assertions.assertThat(current.toolsText())
            .as("头部冻结：tools 数组必须跨 run 逐字节相等")
            .isEqualTo(previous.toolsText());
        Assertions.assertThat(current.headWireMessage())
            .as("头部冻结：messages[0]（userContext 元消息）必须跨 run 逐字节相等"
                + "（它由会话冻结的 claudeMd/currentDate 渲染 ⇒ 冻结生效时恒等）")
            .isEqualTo(previous.headWireMessage());
    }

    /** {@link #expectHeadFrozen} + {@link #expectPrefixExtension} 的组合（判绿需两条同时成立）。 */
    public static void expectFrozenHeadAndPrefixExtension(OutboundRequest previous, OutboundRequest current) {
        expectHeadFrozen(previous, current);
        expectPrefixExtension(previous, current);
    }

    /**
     * <b>反面断言</b>：证明 {@link #expectPrefixExtension} 是「<b>能红的</b>」——
     * 在<b>显式失效事件</b>之后（且其失效源确实变了）同一断言必须失败。
     *
     * <p><b>WHY 必须显式断言「它红了」而不是「它没红」</b>：判据的失效模式是<b>恒绿</b>
     * （断言写得太弱 / 变异没走到底守护路径）⇒ 只有「断言确实抛错」才能证明它有判别力。
     * 本方法把这条反向实验固化下来，防止后人把判据改弱（改弱后本方法会红）。
     *
     * @param previous 失效事件<b>之前</b>的请求
     * @param current  失效事件<b>之后</b>的请求
     * @param mutation 本次使用的变异形态（进失败信息，便于审计「用的是哪种最激进变异」）
     */
    public static void expectPrefixExtensionFails(OutboundRequest previous, OutboundRequest current,
                                                  String mutation) {
        Assertions.assertThatThrownBy(() -> expectPrefixExtension(previous, current))
            .as("反面断言必须变红（否则判据恒绿 = 无判别力）。变异形态：%s\n"
                + "前一次头部=%s\n后一次头部=%s",
                mutation, head(previous.wireMessages()), head(current.wireMessages()))
            .isInstanceOf(AssertionError.class);
    }

    /**
     * <b>反面断言（头部维度）</b>：证明 {@link #expectHeadFrozen} 能红 ——
     * 用于步骤 8 第 3 条的「显式失效事件之后同一断言必须失败」。
     *
     * @param previous 失效事件前的请求
     * @param current  失效事件后的请求
     * @param mutation 变异形态描述
     */
    public static void expectHeadFrozenFails(OutboundRequest previous, OutboundRequest current,
                                             String mutation) {
        Assertions.assertThatThrownBy(() -> expectHeadFrozen(previous, current))
            .as("反面断言必须变红（头部冻结判据的判别力证明）。变异形态：%s", mutation)
            .isInstanceOf(AssertionError.class);
    }

    /**
     * 尾部维度判据（步骤 8 第 4 条的一半）：变更提示必须落在<b>尾部</b>。
     *
     * <p>⚠ 必须与 {@link #expectHeadFrozen} <b>同时</b>成立才算判绿 —— 只测头部稳定会把
     * 「freshness 通道被砍掉」判成「已对齐」（计划 §4 步骤 8 第 4 条的原话）。
     *
     * @param current     变更后的那次请求
     * @param tailSubtype 期望出现在尾部的消息子类型（{@code "edited_text_file"} / {@code "date_change"}）
     * @param mustContain 尾部消息必须包含的文本片段（如变更文件路径）
     */
    public static void expectTailDelivery(OutboundRequest current, String tailSubtype, String mustContain) {
        List<com.nexusai.model.session.dto.ChatMessageDto> messages = current.messages();
        Assertions.assertThat(messages)
            .as("尾部投递：变更提示必须真的进入本次请求的消息（子类型=%s）", tailSubtype)
            .isNotEmpty();
        Assertions.assertThat(messages.get(messages.size() - 1).subtype())
            .as("尾部投递：变更提示必须位于消息数组<b>末尾</b>（append-only；⛔ 不得回写前缀）"
                + "，实际末尾 subtype=%s", messages.get(messages.size() - 1).subtype())
            .isEqualTo(tailSubtype);
        Assertions.assertThat(messages.get(messages.size() - 1).content())
            .as("尾部投递：末尾消息必须携带变更内容（须含 `%s`）", mustContain)
            .contains(mustContain);
    }

    /** 失败信息用的小样本（头部 3 条 + 条数），避免把整段 prompt 打进日志。 */
    private static String head(List<String> wire) {
        int n = Math.min(3, wire.size());
        return "条数=" + wire.size() + " 前" + n + "条=" + wire.subList(0, n);
    }
}
