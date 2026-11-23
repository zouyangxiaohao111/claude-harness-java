package com.nexusai.domain.im_channel.send;

/**
 * 飞书错误码 → 人话（<b>发送与拉取共用</b>）。
 *
 * <p>原先只有 {@code FeishuSendStrategy.sendHint()} 一处映射，拉取路径拿不到
 * ⇒ 扫描摘要里只能看到「HTTP 400」，看不出平台真实原因（真机：4 个机器人不在的群
 * 每 5 分钟重试一次，{@code last_error} 里永远只有 400）。两边必须共用同一份映射，
 * 否则同一张错误码表迟早分成两个口径。
 *
 * <p>两类文案：
 * <ul>
 *   <li>{@link #hint(int)} = 「该怎么办」的长提示（发送异常消息用，拼在原始 code/msg 之后）；</li>
 *   <li>{@link #shortReason(int)} = 摘要/列表用的短原因（如「机器人不在该会话」）。</li>
 * </ul>
 * ⛔ 只做解释，不改判定：能否重试、要不要换票仍由调用方决定。
 */
public final class FeishuErrorHints {

    private FeishuErrorHints() {
    }

    /**
     * 常见拒绝码 → 一句「该怎么办」的中文提示（拼在原始 code/msg 之后）。
     *
     * <p>未列出的码返回空串（原文保留，便于对飞书文档）。
     */
    public static String hint(int code) {
        return switch (code) {
            case 99991672 -> "（缺少所需权限 scope：请让管理员在开放平台为应用开通 "
                    + "im:message.send_as_user 等权限；若用本人身份发送，开通后需重新点「授权本人身份」刷新授权范围）";
            case 99991663, 99991661, 99991668 -> "（令牌无效或已过期：机器人身份会自动换票重试一次；"
                    + "本人身份请重新授权）";
            case 230001, 230013 -> "（接收方 id 无效：私聊/群请用 oc_ 开头的 chat_id，"
                    + "通讯录里的人请用 ou_ 开头的 open_id）";
            case 230002 -> "（机器人不在该会话里：请把机器人拉进群，或让对方先跟机器人发起会话）";
            case 230006 -> "（机器人能力未开启：请在开放平台为该应用开通机器人能力）";
            case 230027 -> "（缺少必要权限，或该外部群不支持该操作：请确认应用已开通会话权限；"
                    + "外部群还需要外部会话操作权限）";
            default -> "";
        };
    }

    /**
     * 短原因（{@code ImMessageSyncService.last_error} 摘要用）。
     *
     * <p>未收录 ⇒ {@code null}（调用方回退到异常原文，不新造词）。
     */
    public static String shortReason(int code) {
        return switch (code) {
            case 230001 -> "请求参数无效";
            case 230002 -> "机器人不在该会话";
            case 230006 -> "机器人能力未开启";
            case 230013 -> "接收方 id 无效";
            case 230027 -> "缺少权限或外部群不支持";
            case 99991663, 99991661, 99991668 -> "令牌无效或已过期";
            case 99991672 -> "缺少权限 scope";
            default -> null;
        };
    }

    /**
     * 按平台码字符串取短原因（{@code ImMessageFetchException.platformCode()} 是字符串）。
     *
     * <p>非数字（HTTP 状态码以外的形态）或未收录 ⇒ {@code null}。
     */
    public static String shortReason(String platformCode) {
        if (platformCode == null || platformCode.isBlank()) {
            return null;
        }
        try {
            return shortReason(Integer.parseInt(platformCode.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
