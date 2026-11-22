package com.nexusai.model.im_message;

/**
 * 扫描渠道配置（domain record）· V315 {@code im_scan_channel}。
 *
 * <p><b>为什么要独立一张表</b>：「要不要定时扫这个渠道」是<b>渠道级的运营决策</b>，
 * 不是渠道实例的属性 —— 用户可能只想扫企微（群消息拉不到）、不想扫飞书（入站事件已经实时推了）。
 * 独立表还能不重建实例地随开随关，并记下「上次扫到哪」。
 *
 * @param enabled       是否参与定时扫描
 * @param lastScanAt    上次<b>成功</b>扫描到的时间（ISO-8601）= 下次拉取的增量起点
 * @param lastChatCount 上次扫了几个会话
 * @param lastNewCount  上次新增了几条消息
 * @param lastError     上次失败原因；成功时为 null（让用户能看到「为什么没扫到」）
 */
public record ImScanChannelEntry(
    String instanceId,
    boolean enabled,
    String lastScanAt,
    Integer lastChatCount,
    Integer lastNewCount,
    String lastError,
    String createdAt,
    String updatedAt
) {
    /** 上次扫描是否失败。 */
    public boolean failed() {
        return lastError != null && !lastError.isBlank();
    }
}
