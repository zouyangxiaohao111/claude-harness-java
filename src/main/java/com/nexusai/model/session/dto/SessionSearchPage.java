package com.nexusai.model.session.dto;

import java.util.List;

/**
 * 会话模糊搜索的一页结果（{@code GET /api/v1/sessions/search}）。
 *
 * <p>匹配口径：标题或会话 id 的**子串**匹配（SQL {@code LIKE %kw%}，ASCII 大小写不敏感），
 * 定序与 {@code GET /sessions} 一致（{@code updated_at DESC, id ASC}），保证翻页稳定。
 *
 * @param items    本页会话（完整 {@link SessionDto}，前端可直接展示副行）
 * @param total    命中总数（不受分页影响，供「第 x/y 页」）
 * @param page     当前页（1 起）
 * @param pageSize 每页条数（实际生效值）
 */
public record SessionSearchPage(List<SessionDto> items, long total, int page, int pageSize) {
}
