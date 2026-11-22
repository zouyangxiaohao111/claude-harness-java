package com.nexusai.wechatlog.key.elevation;

import com.nexusai.wechatlog.key.WeChatKeyExtractor.KeyExtractionException;

/**
 * 取密钥失败的「真实分支 → 用户文案」映射。
 *
 * <p>这是修错误归因的收口点：过去「没权限」和「0 候选/版本不支持」都甩同一句
 * 「请用管理员重启」。现在每一类都有自己的短因与可执行建议，且<b>类别来自结构化判据</b>，
 * 不靠 message 子串。
 */
public final class WeChatKeyExtractionMessages {

    private WeChatKeyExtractionMessages() {
    }

    /** 用户在 UAC 里点了取消。 */
    public static KeyExtractionException cancelled() {
        return new KeyExtractionException(
                "已取消提权，微信渠道未激活",
                "读取本机微信聊天记录需要管理员授权（读微信进程内存）。"
                        + "再点一次「同步聊天记录」，在系统授权窗口选「是」；"
                        + "或改用企业微信/飞书渠道。",
                KeyExtractionException.Kind.PERMISSION);
    }

    /** 需要提权，但调用方不允许（如定时任务）或还没尝试。 */
    public static KeyExtractionException elevationNotAttempted() {
        return new KeyExtractionException(
                "读取微信进程内存需要管理员权限",
                "点「同步聊天记录」时会请求一次管理员授权（UAC），点「是」即可；"
                        + "定时任务不会自动提权。",
                KeyExtractionException.Kind.PERMISSION);
    }

    /** 自动提权无法启动（系统策略/安全软件拦截等）。 */
    public static KeyExtractionException elevationUnavailable(String permissionMessage) {
        return new KeyExtractionException(
                "自动提权不可用（可能被系统策略或安全软件拦截）",
                "请以管理员身份重新启动 NexusAI 后再试；或改用企业微信/飞书渠道。"
                        + (permissionMessage == null ? "" : "原始错误：" + permissionMessage),
                KeyExtractionException.Kind.PERMISSION);
    }

    /** 没找到运行中的微信进程。 */
    public static KeyExtractionException noProcess() {
        return new KeyExtractionException(
                "未找到正在运行的微信进程（Weixin.exe / WeChat.exe）",
                "请先启动微信并登录（窗口不必可见），然后重试。",
                KeyExtractionException.Kind.NO_PROCESS);
    }

    /**
     * 已经读到内存但 0 候选。
     *
     * <p>⚠️ 这里的建议<b>绝不能</b>再写「以管理员身份重启」——那正是被本次修正掉的错误归因。
     */
    public static KeyExtractionException noCandidates() {
        return new KeyExtractionException(
                "读到微信进程内存，但没有解析出密钥候选",
                "请先在微信里打开几个会话并滚动聊天记录后重试（配置对象可能尚未初始化）。"
                        + "若仍失败，可能是微信版本变化：可在 "
                        + "~/.nexusai/wechat-log/wechat-offsets.properties 配置该版本偏移，"
                        + "或先用企业微信/飞书渠道。",
                KeyExtractionException.Kind.NO_CANDIDATES);
    }

    /** 扫描预算不足。 */
    public static KeyExtractionException scanBudgetExhausted(String detail) {
        return new KeyExtractionException(
                "内存扫描未覆盖到微信特征数据（扫描预算不足）",
                "可用 -Dnexusai.wechat.scan.maxBytes 提高预算后重试。"
                        + (detail == null ? "" : detail),
                KeyExtractionException.Kind.SCAN_BUDGET_EXHAUSTED);
    }

    /** 取到了候选，但没有一个能通过数据库校验。 */
    public static KeyExtractionException candidatesNotMatch(int count) {
        return new KeyExtractionException(
                "取到 " + count + " 个密钥候选，但没有一个能通过数据库校验",
                "可能原因：微信版本变化、候选不属于该账号、或库已损坏。"
                        + "可重试；若持续失败，请确认微信版本，或先用企业微信/飞书渠道。",
                KeyExtractionException.Kind.VERSION_UNSUPPORTED);
    }

    /** 当前微信版本未适配。 */
    public static KeyExtractionException versionUnsupported(String detail) {
        return new KeyExtractionException(
                "当前微信版本未适配",
                "可在 ~/.nexusai/wechat-log/wechat-offsets.properties 添加该版本偏移；"
                        + "或先用企业微信/飞书渠道。"
                        + (detail == null ? "" : detail),
                KeyExtractionException.Kind.VERSION_UNSUPPORTED);
    }

    /** 提权子进程其它失败。 */
    public static KeyExtractionException extractFailed(int exitCode, String detail) {
        return new KeyExtractionException(
                "提权取密钥失败（退出码 " + exitCode + "）",
                "请重试；若反复失败，请以管理员身份重启 NexusAI 或改用企业微信/飞书渠道。"
                        + (detail == null ? "" : detail),
                KeyExtractionException.Kind.UNKNOWN);
    }
}
