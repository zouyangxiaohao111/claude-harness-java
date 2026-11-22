package com.nexusai.domain.im_message.fetch;

/**
 * 平台的<b>历史窗口限制</b>（能补拉多早以前的消息）。
 *
 * <h2>为什么需要它</h2>
 * 三平台的「读历史」窗口差别巨大：
 * <ul>
 *   <li>飞书：无窗口，能翻到底；</li>
 *   <li>企微：{@code begin_time} 逐字「仅支持拉取最近 7 天」；</li>
 *   <li>微信：本机库，全量。</li>
 * </ul>
 * ⇒ 「请求的区间早于平台窗口」**不是错误**，而是「那段只能看本地记录」。
 * 拉取层要据此<b>收敛请求区间</b>，上层要据此<b>给用户一句说明</b>（而不是抛异常）。
 *
 * @param earliestSince 该平台能补拉的最早时刻（ISO-8601）；{@code null} = 没有时间墙
 * @param note          给用户看的说明（{@code null} = 无限制，不需要说明）
 */
public record ImFetchWindow(String earliestSince, String note) {

    /** 无时间墙（飞书 / 微信）。 */
    public static final ImFetchWindow UNLIMITED = new ImFetchWindow(null, null);

    /** 是否有时间墙。 */
    public boolean limited() {
        return earliestSince != null && !earliestSince.isBlank();
    }
}
