package com.nexusai.domain.im_channel.send.impl;

import com.nexusai.domain.im_channel.send.AbstractImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendContext;
import com.nexusai.domain.im_channel.send.ImSendStrategy;
import com.nexusai.domain.im_channel.send.SendCapability;
import org.springframework.stereotype.Component;

/**
 * 钉钉：<b>没有主动发送通道</b> ⇒ 能力声明为「不支持」。
 *
 * <h2>为什么是「不支持」而不是「还没写」</h2>
 * 钉钉自定义机器人的两条通道都<b>要求先有入站上下文</b>：
 * <ul>
 *   <li><b>Webhook</b>：只能往固定的群机器人地址发，发给<b>指定某个用户</b>做不到；</li>
 *   <li><b>Stream 模式</b>：靠长连接收消息，回消息必须带上游给的 {@code sessionWebhook}
 *       （等价于微信的 {@code context_token}），没有它就没有"发给谁"的目标。</li>
 * </ul>
 * 也就是说：**在本仓没有接入钉钉 Stream 机器人之前，任何"主动发消息"请求都不可能成立**。
 * 与其在这里写一个"拼个 URL 猜着发"的实现（发不出去还假装成功），
 * 不如把这件事在<b>能力声明</b>里说清楚 —— 模板方法会在发请求之前就返回 SKIPPED，
 * 并把原因带给用户看（"钉钉无主动发送通道" ⇒ 该换渠道，而不是"网络挂了"）。
 *
 * <p>这正是 {@link SendCapability#unsupported(String)} 存在的意义：
 * <b>能力边界不是异常</b>。调用方拿到的是 SKIPPED + 一句能指导下一步的原因，
 * 而不是一次失败的 HTTP 调用。
 *
 * <p>⚠️ 与 {@link com.nexusai.domain.im_channel.send.UnsupportedSendStrategy} 的区别：
 * 那个是**平台不认识**的兜底（原因会写"平台「xxx」尚未接入主动发送通道"）；
 * 本类是**平台认识、但这个平台确实没有这条通道**（原因写"钉钉无主动发送通道"）。
 * 两者对用户是两件不同的事：前者是"我们没做"，后者是"它做不到"。
 */
@Component
public class DingtalkSendStrategy extends AbstractImSendStrategy {

    /** 能力声明（不可变 ⇒ 复用同一个实例即可）。 */
    private static final SendCapability CAPABILITY =
            SendCapability.unsupported("钉钉无主动发送通道（未接入 Stream 机器人）");

    @Override
    public String platform() {
        return "dingtalk";
    }

    /**
     * 声明「不能主动发送」。
     *
     * <p>模板方法据此在**发请求之前**返回 {@code SKIPPED + IM_SEND_UNSUPPORTED}，
     * 一个 HTTP 请求都不会发出去。
     */
    @Override
    public SendCapability capability() {
        return CAPABILITY;
    }

    /**
     * 不会被执行：模板方法的能力检查先返回 SKIPPED。
     *
     * <p>返回 {@code null} 而不是抛异常，是为了「万一将来有人改坏了能力检查」也不会把
     * 批量投递整批炸掉 —— 与 {@link com.nexusai.domain.im_channel.send.UnsupportedSendStrategy}
     * 的处理保持一致。
     *
     * @return 恒为 null
     */
    @Override
    public String doSend(ImSendContext ctx, String text) {
        return null;
    }
}
