package com.nexusai.kb.app.image;

import com.nexusai.kb.app.prompt.KbPromptLoader;

import java.util.Locale;
import java.util.Set;

/**
 * 图片理解提示词（中文）· 对齐 WeKnora
 * {@code internal/application/service/image_multimodal.go:31-78}
 * （{@code vlmOCRPrompt} / {@code buildVLMCaptionPrompt}）。
 *
 * <p><b>⭐ 两条提示词各自守一个「模型输出契约」</b>，两者都必须能被代码识别出来：
 * <ol>
 *   <li><b>caption</b>：一句话说明，且「图里没内容」时回 {@code NO_CONTENT}。
 *       ⛔ 不能把 {@code NO_CONTENT} 当成说明存下来 —— 图库里会显示成一堆
 *       「NO_CONTENT」标题，看起来像解析失败。</li>
 *   <li><b>OCR</b>：只转写可见文字，且「没有可辨认文字」时回 {@code No text content.}。
 *       源侧 {@code ocr_sanitizer.go:191-200} 会对这句做大小写 + 尾部标点归一后再比对，
 *       本类沿用同一口径（见 {@link #isNoTextReply}）。</li>
 * </ol>
 *
 * <p><b>为什么提示词外置成 md</b>：本仓铁律要求知识库提示词全部放
 * {@code resources/kb/prompt/}（由 {@link KbPromptLoader} 加载，缺失即抛）。
 * ⛔ 不要为了「省一个文件」把它们写成 Java 字符串常量。
 */
public final class KbImagePrompts {

    private KbImagePrompts() {
    }

    /** 图片说明系统提示词（一句话描述，40 字以内）。 */
    public static final String CAPTION = KbPromptLoader.cached("image/caption.md");

    /** 图片 OCR 系统提示词（只转写可见文字）。 */
    public static final String OCR = KbPromptLoader.cached("image/ocr.md");

    /** caption 提示词约定的「图里没有可描述内容」回包（大写，逐字）。 */
    public static final String CAPTION_NO_CONTENT = "NO_CONTENT";

    /**
     * 已知的「没有文字」回包形态（小写、去尾部标点后比对）。
     *
     * <p>⛔ 只认这几条：模型很爱自作主张回「There is no text in this image.」之类的长句，
     * 若用「包含 no text」这种模糊判据，会把一行真实文字（比如 "No text content specified"）
     * 误判成空 —— OCR 结果就整段消失且不报错。
     */
    private static final Set<String> NO_TEXT_REPLIES = Set.of(
        "no text content", "no text", "no visible text", "no recognizable text",
        "无文字", "没有文字", "无可辨认文字");

    /**
     * OCR 回包是否是「图里没有文字」。
     *
     * <p>归一规则对齐源侧 {@code isKnownEmptyReply}：转小写、trim、去掉尾部标点。
     *
     * @param text 模型原始回包（可 null）
     * @return true = 应视为空（不落 OCR 字段）
     */
    public static boolean isNoTextReply(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String lower = text.strip().toLowerCase(Locale.ROOT);
        // 去尾部标点（中英文都要：模型时而回中文句号）
        int end = lower.length();
        while (end > 0 && ".!?。！？，,".indexOf(lower.charAt(end - 1)) >= 0) {
            end--;
        }
        return NO_TEXT_REPLIES.contains(lower.substring(0, end).strip());
    }

    /**
     * caption 回包是否无效（空 / 约定的无内容标记）。
     *
     * @param text 模型原始回包（可 null）
     * @return true = 不该写进 {@code caption} 字段
     */
    public static boolean isNoCaption(String text) {
        if (text == null || text.isBlank()) {
            return true;
        }
        String t = text.strip();
        // 模型偶尔会把标记包进引号或加句号 ⇒ 归一后比对
        String norm = t.replace("\"", "").replace("'", "").strip();
        int end = norm.length();
        while (end > 0 && ".!?。！？".indexOf(norm.charAt(end - 1)) >= 0) {
            end--;
        }
        return CAPTION_NO_CONTENT.equalsIgnoreCase(norm.substring(0, end).strip());
    }
}
