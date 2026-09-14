package com.nexusai.model.session.dto;

import com.nexusai.model.provider.dto.ModelTag;
import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/v1/sessions 请求 · <b>至少需要 title 或 modelName 之一</b>，且
 * <b>{@code mainProjectId} 必填</b>。
 *
 * <p><b>[S3 · F-03a 2026-09-14] 为什么 mainProjectId 用 {@code @NotBlank} 而不是 {@code @NotNull}</b>：
 * 前端存在把 {@code EMPTY_PROJECT.id === ''} 送进来的路径，{@code @NotNull} 会让 {@code ""} 通过 →
 * 落库 {@code main_project_id=''} → 读数侧按「空即未绑定」判定（ToolRegistrationConfig）⇒ 空转。
 * 只有 {@code @NotBlank}（null + 空 + 纯空白全拒）才真正堵死「未绑定会话」的制造点。
 *
 * <p><b>为什么不加「项目必须存在」校验（档三）</b>：需要 SessionService 引入 ProjectMapper /
 * ProjectService 依赖，跨聚合分界未定，本批不做。
 */
public record SessionCreateRequest(
    String title,
    ModelTag model,                 // 缺省后端默认 DS
    String modelName,               // 可选 · 仅显式传入时落库 sessions.model_name（会话 override）；缺省 null，读时运行时解析
    @NotBlank String mainProjectId, // 必填 · 会话绑定项目 id（V1:52 sessions.main_project_id）；空/空白 ⇒ 400
    Boolean bareMode                // 可选 · 会话级 bare（精简）模式开关（V33 列 bare_mode）；null = 不设置（回落 env/默认 false）
) {}
