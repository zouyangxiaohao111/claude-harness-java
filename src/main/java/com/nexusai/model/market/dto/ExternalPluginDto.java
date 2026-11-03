package com.nexusai.model.market.dto;

import java.util.List;

/**
 * 外部市场单个插件 DTO · GET /api/market/external 返回（plugins[] 元素）。
 *
 * <p>字段对齐用户 marketplace.json 的扩展条目（全英文键）：name 为安装定位标识，
 * displayName 为中文名称，其余为展示元数据。category 用于前端分流到专家/技能/连接器 Tab。
 *
 * <p><b>[2026-11-01 技能市场 v4] 新增 4 个字段</b>（用户拍板「统计数字要真实的 + 补开发者概念」）：
 * <ul>
 *   <li>{@link #useCount()} / {@link #downloadCount()} —— <b>市场侧真实统计</b>（原始整数，
 *       由前端格式化；⛔ 不在 wire 上传「615.64w」这种展示字符串，否则排序/聚合/国际化全废）。
 *       缺省 null = 市场没这数据 ⇒ 前端<b>不显示该统计</b>（⛔ 不显示 0，0 与「无数据」是两回事）。</li>
 *   <li>{@link #authorName()} / {@link #authorVerified()} —— 开发者。
 *       缺省时后端填 {@link #DEFAULT_AUTHOR_NAME}（用户拍板：没有开发者的统一归到公司开发组），
 *       ⇒ 前端永远拿得到作者名，不需要处理「无开发者」分支。</li>
 * </ul>
 *
 * @param name           插件英文标识（marketplace.json entry.name，安装定位用）
 * @param displayName    中文展示名
 * @param description    描述
 * @param version        版本号
 * @param category       分类（expert / skill / connector）
 * @param tags           标签
 * @param createdAt      创建时间（ISO 8601）
 * @param useCount       使用次数（市场统计 · 原始整数 · null = 无数据）
 * @param downloadCount  下载次数（市场统计 · 原始整数 · null = 无数据）
 * @param authorName     开发者名（缺省 → {@link #DEFAULT_AUTHOR_NAME}）
 * @param authorVerified 是否认证发布者（缺省 false）
 * @param authorAvatarUrl 开发者头像 URL（可选 · null → 前端用首字母色块）
 * @param authorEmail    开发者邮箱（可选 · 详情页「关于开发者」展示）
 */
public record ExternalPluginDto(
        String name,
        String displayName,
        String description,
        String version,
        String category,
        List<String> tags,
        String createdAt,
        Long useCount,
        Long downloadCount,
        String authorName,
        Boolean authorVerified,
        String authorAvatarUrl,
        String authorEmail
) {

    /**
     * 无开发者信息时的默认作者名（用户 2026-11-01 拍板：「已有的没有开发者就默认数字公司开发组就行」）。
     *
     * <p><b>为什么由后端填而不是前端兜底</b>：作者名会参与「按 author.name 分组」
     * （开发者信息弹窗列出该开发者全部技能）。前端兜底会让「空 author」与
     * 「显式 author=数字公司开发组」在聚合时分裂成两组。
     */
    public static final String DEFAULT_AUTHOR_NAME = "数字公司开发组";
}
