package com.nexusai.model.provider.dto;

import com.nexusai.infra.exception.ValidationException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model 标签工具（设计 D5：tag 由 5 值枚举改为自由文本）。
 *
 * <p>值域：1-8 位大写字母/数字（展示用短标签；前端按字符串哈希配色）。
 * DB 列 {@code models.tag} / {@code sessions.model_tag} 本就是无约束 TEXT，无迁移。
 *
 * <p>两条路径语义不同：{@link #normalize} 是写路径（非法即拒），{@link #parse} 是读路径
 * （脏数据只 warn 不抛 —— 一行历史脏 tag 不得让会话列表 500）。
 */
public final class ModelTags {

    /** 新建会话的默认标签（沿用历史值 DS，避免默认路径行为漂移） */
    public static final String DEFAULT = "DS";

    private static final Logger log = LoggerFactory.getLogger(ModelTags.class);
    private static final Pattern FORMAT = Pattern.compile("^[A-Z0-9]{1,8}$");

    private ModelTags() {}

    /** 写路径：null / 空白 / 超长 / 非法字符 → {@link ValidationException}。 */
    public static String normalize(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new ValidationException("tag 不能为空（要求 1-8 位大写字母/数字）");
        }
        String t = tag.trim().toUpperCase(Locale.ROOT);
        if (!FORMAT.matcher(t).matches()) {
            throw new ValidationException("tag 非法: " + tag + "（要求 1-8 位大写字母/数字）");
        }
        return t;
    }

    /** 读路径：非法值 warn 后返回 null（不抛）。 */
    public static String parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String t = raw.trim();
        if (!FORMAT.matcher(t).matches()) {
            log.warn("[ModelTags] 非法 tag 值（历史/脏数据），按 null 返回: [{}]", raw);
            return null;
        }
        return t;
    }
}
