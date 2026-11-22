package com.nexusai.wechatlog.model;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息类型（{@code local_type}）到可读文本的映射。
 *
 * <h2>类型值来源</h2>
 * 取自两个上游实现交叉核对：
 * <ul>
 *   <li>rmqg {@code export.py} 的 {@code TYPE_LABEL}：3 图片 / 34 语音 / 43 视频 /
 *       47 动画表情 / 48 位置 / 42 名片 / 50 音视频通话 / 51 视频号 / 63 视频 / 66 分享</li>
 *   <li>chatlog {@code model/message_v4.go} 的 {@code Wrap} 与 {@code render}</li>
 * </ul>
 *
 * <h2>⚠️ 高 32 位必须掩掉</h2>
 * 见 {@link WeChatMessage} 类注释。{@code normalize} 负责这一步。
 */
public final class WeChatMessageType {

    private WeChatMessageType() {
    }

    /** 文本。 */
    public static final int TEXT = 1;
    /** 图片。 */
    public static final int IMAGE = 3;
    /** 语音。 */
    public static final int VOICE = 34;
    /** 名片。 */
    public static final int CARD = 42;
    /** 视频。 */
    public static final int VIDEO = 43;
    /** 动画表情。 */
    public static final int STICKER = 47;
    /** 位置。 */
    public static final int LOCATION = 48;
    /** 音视频通话记录。 */
    public static final int VOIP = 50;
    /** 视频号 / 引用 / 文件 / 链接等（XML 载荷，再按子标签细分）。 */
    public static final int APP = 49;
    /** 系统消息（入群提示、撤回等）。 */
    public static final int SYSTEM = 10000;
    /** 其它未知。 */
    public static final int UNKNOWN = -1;

    /**
     * 掩掉 {@code local_type} 高 32 位，得到真实基础类型。
     *
     * <p>微信把子类型打包在高位；不掩掉会让 {@code == 1} 之类的判断永不成立。
     */
    public static int normalize(long localType) {
        return (int) (localType & 0xFFFFFFFFL);
    }

    /**
     * 把消息渲染成可读文本。
     *
     * @param baseType 归一化后的基础类型
     * @param raw      原始载荷（已解压、未剥前缀）
     * @return 可读文本；媒体类返回 {@code [图片]} 之类的占位
     */
    public static String render(int baseType, String raw) {
        if (raw == null) {
            return "";
        }
        String content = stripSenderPrefix(raw);

        switch (baseType) {
            case TEXT:
                return content;
            case SYSTEM:
                return "[系统] " + stripTags(content).replaceAll("\\s+", " ").trim();
            case APP:
                return renderAppMessage(content);
            case IMAGE:
                return "[图片]";
            case VOICE:
                return "[语音]";
            case VIDEO:
                return "[视频]";
            case STICKER:
                return "[动画表情]";
            case LOCATION:
                return "[位置]";
            case CARD:
                return "[名片]";
            case VOIP:
                return "[音视频通话]";
            default:
                // 未知类型不吞掉内容：能读多少给多少，便于人工排查
                String t = stripTags(content).trim();
                return t.isEmpty() ? "[" + baseType + "]" : t;
        }
    }

    /**
     * 剥掉 {@code "<sender_wxid>:\n<payload>"} 的发送者前缀。
     *
     * <p>微信 4.x 的 {@code message_content} 形如 {@code wxid_abc:\n你好}。
     * 只剥最前面那一段，正文里的冒号不能被误伤。
     */
    static String stripSenderPrefix(String s) {
        Matcher m = SENDER_PREFIX.matcher(s);
        return m.find() ? s.substring(m.end()) : s;
    }

    /** {@code [0-9A-Za-z_\-@.]+:\n} */
    private static final Pattern SENDER_PREFIX =
            Pattern.compile("^[0-9A-Za-z_\\-@.]+:\\n");

    /** 处理 {@code <appmsg>} 载荷（文件 / 链接 / 引用 / 转账 / 视频号）。 */
    private static String renderAppMessage(String content) {
        String appType = tag(content, "type");
        String label = switch (appType) {
            case "6" -> "[文件]";
            case "5" -> "[链接]";
            case "57", "53" -> "[引用]";
            case "2000" -> "[转账]";
            case "51" -> "[视频号]";
            case "19" -> "[聊天记录]";
            case "33", "36" -> "[小程序]";
            default -> "[链接/文件]";
        };
        String title = tag(content, "title");
        String des = tag(content, "des");
        StringBuilder sb = new StringBuilder(label);
        if (!title.isEmpty()) {
            sb.append(' ').append(title);
        }
        if (!des.isEmpty()) {
            sb.append(' ').append(des);
        }
        return sb.toString().trim();
    }

    /** 取 XML 里某个标签的文本内容（取第一个匹配）。 */
    static String tag(String xml, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">",
                Pattern.DOTALL).matcher(xml);
        return m.find() ? m.group(1).trim() : "";
    }

    /** 去掉所有 XML/HTML 标签。 */
    static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "");
    }

    /** 类型的中文名（用于日志与工具输出）。 */
    public static String name(int baseType) {
        return switch (baseType) {
            case TEXT -> "文本";
            case IMAGE -> "图片";
            case VOICE -> "语音";
            case VIDEO -> "视频";
            case STICKER -> "表情";
            case LOCATION -> "位置";
            case CARD -> "名片";
            case VOIP -> "通话";
            case APP -> "应用消息";
            case SYSTEM -> "系统";
            default -> "类型" + baseType;
        };
    }

    /** 是否为纯文本（可用于全文检索的候选）。 */
    public static boolean isIndexableText(int baseType) {
        return baseType == TEXT || baseType == APP || baseType == SYSTEM;
    }

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
