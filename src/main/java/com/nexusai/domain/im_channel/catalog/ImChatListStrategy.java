package com.nexusai.domain.im_channel.catalog;

import com.nexusai.domain.im_channel.send.ImSendException;

import java.util.List;

/**
 * 「这个平台有哪些会话」的策略接口（每平台一个实现）。
 *
 * <h2>为什么要有策略层（而不是直接查 {@code im_chat}）</h2>
 * 本地目录 {@code im_chat} 只是**以前拉过 / 入站见过**的缓存，它有两个盲区：
 * <ul>
 *   <li>用户刚建的群 / 刚加的联系人<b>还不在</b>库里；</li>
 *   <li>不同平台的"会话"来源完全不同 —— 企微是 {@code message/aibot/sessions/list}(机器人视角)
 *       + {@code chat/groups/list}(群视角)；飞书是 {@code im/v1/chats}；
 *       微信是本机聊天记录的扫描产物。</li>
 * </ul>
 * 把「怎么拉」关进策略里，工具层（{@code ListImChats}）只负责「拉完落库 + 排序返回」，
 * 新增平台时只加一个 {@code @Component}，工具与工厂都不用改。
 *
 * <h2>能力声明</h2>
 * {@link #canRefresh()} 是静态事实：钉钉这类没有会话列表接口的平台显式声明 false，
 * 工具据此**如实告诉 AI「只能看已同步的目录」**，而不是返回一个空列表让 AI 误判"没有会话"。
 */
public interface ImChatListStrategy {

    /** 平台标识，取值同 {@code ImChannelService.PLATFORMS}。 */
    String platform();

    /** 本平台能否实时拉会话列表（静态声明）。false ⇒ 工具只读本地目录。 */
    boolean canRefresh();

    /**
     * 拉一次会话列表（网络调用，可能失败）。
     *
     * <p>⛔ 不要吞异常返回空列表 —— 那会让"平台拒绝"看起来像"没有会话"。
     * 调用方（工具）会把异常转成给 AI 的明确错误。
     *
     * @param instanceId 渠道实例（据此取凭证）
     * @return 候选会话（可为空列表，表示平台确实没有会话）
     * @throws ImSendException 凭证无效 / 平台拒绝 / 网络失败
     */
    List<ImChatCandidate> fetch(String instanceId) throws ImSendException;
}
