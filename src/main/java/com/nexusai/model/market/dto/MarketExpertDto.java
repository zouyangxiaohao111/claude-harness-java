package com.nexusai.model.market.dto;

import java.util.List;

/**
 * 腾讯 workbuddy 技能市场「专家」统一 DTO · 后端代调腾讯返回（远端专家，remote=true）。
 *
 * <p>字段对齐腾讯 expert/list 响应 data.experts[]（字段中文注释标注腾讯原名）：
 * <pre>
 *   marketId       ← expert_id
 *   agentName      ← agent_name
 *   displayName    ← display_name_zh（展示名）
 *   icon           ← icon（CDN 图标 URL）
 *   profession     ← profession_zh（职业/领域）
 *   description    ← description_zh（简介）
 *   tags           ← tags_zh（中文标签）
 *   categories     ← categories（分类）
 *   useCount       ← use_count（使用次数原始整数）
 *   useCountDisplay 格式化展示（如 118000 → "11.8万"）
 *   preinstalled   ← preinstalled（是否预装）
 *   featured       ← featured（是否推荐/精选）
 *   remote         恒 true —— 本 DTO 恒代表远端市场条目，非本地会话条目
 * </pre>
 *
 * @param marketId    远端专家唯一标识（= 腾讯 expert_id，供 POST /api/market/expert/{marketId}/use）
 * @param agentName   腾讯 agent_name（英文程序名；「使用」时以 wb- 前缀构造本地 agentType）
 * @param displayName 中文展示名（display_name_zh）
 * @param icon        图标 CDN URL
 * @param profession  职业/领域（profession_zh）
 * @param description 专家简介（description_zh）
 * @param tags        中文标签（tags_zh）
 * @param categories  分类列表
 * @param useCount    使用次数（整数）
 * @param useCountDisplay 使用次数格式化文案（≥1万 显示 "N.N万"，否则原整数）
 * @param preinstalled 是否预装
 * @param featured     是否精选/推荐
 * @param remote       恒 true（远端市场条目标记）
 */
public record MarketExpertDto(
        String marketId,
        String agentName,
        String displayName,
        String icon,
        String profession,
        String description,
        List<String> tags,
        List<String> categories,
        int useCount,
        String useCountDisplay,
        boolean preinstalled,
        boolean featured,
        boolean remote
) {}
