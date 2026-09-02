package com.nexusai.application.agent.team;

import com.nexusai.infra.util.Mailbox;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * MailboxBridge · 对齐 CC hooks/useMailboxBridge.ts:9-22。
 *
 * <p>L1 语义: 把 mailbox 轮询桥接到消息提交。当 loading 时不轮询; 非 loading 时轮询一条消息,
 * 若有则把 {@code msg.content} 提交给 onSubmitMessage。CC 用 useSyncExternalStore 订阅 revision
 * 触发重跑, Java 端把这一步简化为显式 {@code pump()} 调用 (由事件总线/revision 变更驱动)。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: static boolean pump(boolean isLoading, Supplier&lt;Mailbox.Message&gt; poll, Consumer&lt;String&gt; submit)</li>
 *   <li><b>A2 Golden Trace</b>: !isLoading + poll 返回 msg → submit(msg.content), 返回 true</li>
 *   <li><b>A3 纯逻辑</b>: 无内部状态, 仅编排 poll → submit</li>
 *   <li><b>A4 边界</b>: isLoading=true → 不 poll 不 submit, 返回 false; poll 返回 null → 不 submit, 返回 false</li>
 *   <li><b>A5 业务场景</b>: teammate inbox 有消息且主循环空闲 → 自动把消息内容作为下一轮输入</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS React hook (useEffect/useSyncExternalStore) → Java 静态编排函数;
 * TS {@code onSubmitMessage(content): boolean} 副作用 → Java Consumer, 桥接结果用返回值表达。
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 *   (teammate 桥接部分; mailbox 底层 infra/util/Mailbox.java 已按决策 3 对齐 CC, 不在此列)
 */
public final class MailboxBridge {

    private MailboxBridge() {}

    /**
     * CC useMailboxBridge.ts:16-21 useEffect —
     * <pre>
     * if (isLoading) return
     * const msg = mailbox.poll()
     * if (msg) onSubmitMessage(msg.content)
     * </pre>
     *
     * @param isLoading 主循环是否忙碌 (true = 跳过本次轮询)
     * @param poll      轮询一条待处理消息的 supplier (无消息返回 null)
     * @param submit    消息内容提交回调
     * @return 是否真正提交了一条消息
     */
    public static boolean pump(boolean isLoading, Supplier<Mailbox.Message> poll, Consumer<String> submit) {
        if (isLoading) {
            return false;
        }
        Mailbox.Message msg = poll.get();
        if (msg == null) {
            return false;
        }
        submit.accept(msg.content());
        return true;
    }
}
