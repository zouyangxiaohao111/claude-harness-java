package com.nexusai.domain.im_message.fetch;

import com.nexusai.model.im_message.ImMessageEntry;

import java.util.List;

/**
 * 「从平台拉消息」的三平台策略（V315 拉取层）。
 *
 * <h2>为什么要策略 + 工厂</h2>
 * 三平台的读法差得很远（企微走 {@code /cli/*} 网关且<b>没有 messageId</b>；
 * 飞书走 {@code /open-apis/im/v1/messages} 且<b>要自己翻页</b>；
 * 微信<b>不联网</b>、查本机 SQLite）。
 * 把差异关进策略里 ⇒ 新增平台只加一个 {@code @Component}，调用方（{@code ImMessageQueryService}）不动。
 * 范式对齐仓里既有的 {@code ImSendStrategyFactory} / {@code ImContactSearchStrategyFactory}。
 *
 * <h2>⚠️ chatId 的口径（最容易搞错的一处）</h2>
 * 本接口的 {@code chatId} 是<b>平台侧会话 id</b>（飞书 {@code oc_xxx} / 企微加密 chatid /
 * 微信 {@code wxid} 或 {@code xxx@chatroom}），<b>不是</b>本地目录的 {@code imch-xxx}。
 * 本地 id → 平台 id 的翻译在 {@code ImMessageQueryService} 里做（{@code ImChatService.findById}
 * ⇒ {@code peerId}）。
 *
 * <h2>⚠️ 时间参数的形态</h2>
 * {@code sinceIso} / {@code untilIso} 传进来是 <b>ISO-8601</b>；实现方负责按各自平台的
 * 要求转成该平台要的时间形态（企微要 {@code yyyy-MM-dd HH:mm:ss}、飞书要<b>秒级 epoch</b>、
 * 微信要<b>秒级 epoch</b>）。
 * 写入 {@code ImMessageEntry.sentAt} 时<b>必须</b>用
 * {@link com.nexusai.domain.im_message.ImMessageTime#canonical(String)} 归一化。
 */
public interface ImMessageFetcher {

    /** 平台标识（wecom / feishu / wechat），与 {@code im_channel_instance.platform} 同口径。 */
    String platform();

    /**
     * 拉某会话在 {@code [since, until]} 内的消息。
     *
     * @param instanceId 渠道实例 id（换凭证用）
     * @param chatId     <b>平台侧</b>会话 id（不是 {@code imch-xxx}）
     * @param sinceIso   起（不含）；{@code null} = 不限（实现方按平台能力取默认）
     * @param untilIso   止（含）；{@code null} = 现在
     * @param maxMessages 最多返回多少条（实现方应尽量遵守）
     * @return 拉到的消息（时间已归一化；{@code origin='official'}）
     * @throws ImMessageFetchException 拉取失败（平台拒绝 / 网络故障 / 凭证问题）
     */
    List<ImMessageEntry> fetch(String instanceId, String chatId, String sinceIso, String untilIso,
                               int maxMessages) throws ImMessageFetchException;

    /**
     * 该平台的历史窗口限制（默认无限制）。
     *
     * <p>调用方据此：① 收敛请求区间（不越界拉）② 给用户一句说明（「企微仅能补拉最近 7 天」）。
     * <b>不实现</b>意味着「没有时间墙」。
     */
    default ImFetchWindow window() {
        return ImFetchWindow.UNLIMITED;
    }
}
