package com.nexusai.model.market.dto;

import java.util.List;

/**
 * 腾讯 workbuddy 技能市场「技能」统一 DTO · 后端代调腾讯返回（远端技能，remote=true）。
 *
 * <p>字段对齐腾讯 skill/list 响应 data.skills[]：
 * <pre>
 *   marketId     ← skill_id
 *   name         ← name（技能英文名）
 *   displayName  ← display_name_zh（中文展示名）
 *   icon         ← icon
 *   description  ← description_zh
 *   categories   ← categories
 *   examples     ← examples_zh（示例 prompt）
 *   preinstalled ← preinstalled
 * </pre>
 *
 * @param marketId    远端技能唯一标识（= 腾讯 skill_id）
 * @param name        技能名（name）
 * @param displayName 中文展示名（display_name_zh）
 * @param icon        图标 CDN URL
 * @param description 技能简介（description_zh）
 * @param categories  分类列表
 * @param examples    示例列表（examples_zh）
 * @param preinstalled 是否预装
 * @param remote       恒 true（远端市场条目标记）
 */
public record MarketSkillDto(
        String marketId,
        String name,
        String displayName,
        String icon,
        String description,
        List<String> categories,
        List<String> examples,
        boolean preinstalled,
        boolean remote
) {}
