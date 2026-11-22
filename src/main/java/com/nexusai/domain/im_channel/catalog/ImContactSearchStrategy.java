package com.nexusai.domain.im_channel.catalog;

import com.nexusai.domain.im_channel.send.ImSendException;

import java.util.List;

/**
 * 「在这个平台按关键词找人」的策略接口（每平台一个实现）。
 *
 * <h2>为什么需要它（{@code ListImChats} 覆盖不到）</h2>
 * {@code ListImChats} 只能列出<b>机器人参与过的会话</b>。要让 AI「以用户名义给某个同事发消息」，
 * 那个同事如果从没和机器人说过话，就根本不在会话列表里 —— 必须能从通讯录里找人。
 *
 * <h2>企微的实测事实（2026-11-18）</h2>
 * {@code contact/users/search} 返回的 {@code userid} 是<b>加密形态</b>
 * （{@code woNH-7HAAA…}），可直接作为 {@code message/send} 的 chat_id 使用（真机发成功）。
 * ⚠️ 这与入站帧里的 {@code from.userid}（明文 {@code ZhengChangWei}）<b>不是一回事</b>：
 * 明文 userid 当 chat_id 会回 {@code 40073}。
 */
public interface ImContactSearchStrategy {

    /** 平台标识，取值同 {@code ImChannelService.PLATFORMS}。 */
    String platform();

    /** 本平台能否搜索通讯录（静态声明）。 */
    boolean canSearch();

    /**
     * 按关键词搜人。
     *
     * @param instanceId 渠道实例（据此取凭证）
     * @param keyword    关键词（姓名/拼音/英文名）
     * @return 命中的人（可为空列表）
     * @throws ImSendException 凭证无效 / 平台拒绝 / 网络失败
     */
    List<ImContactCandidate> search(String instanceId, String keyword) throws ImSendException;
}
