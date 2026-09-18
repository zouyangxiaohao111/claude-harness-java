package com.nexusai.model.session.dto;

import com.nexusai.model.provider.dto.ModelTag;

/** PATCH /api/v1/sessions/{id} 请求 · 全字段可选 */
public record SessionUpdateRequest(
    String title,
    ModelTag model,
    String modelName,
    String mainProjectId,
    Boolean bareMode,               // 可选 · 会话级 bare（精简）模式开关（V33 列 bare_mode）；null = 不改动
    String permissionMode,          // 可选 · 会话级权限模式覆盖（V44 列 permission_mode）；null = 不改动
    String mainThreadAgent,         // 可选 · 会话指定主线程 agent（V58 列 main_thread_agent）；null = 不改动
    /**
     * 可选 · 会话级 coordinator 模式覆盖（V75 列 coordinator_mode）· true/false = 显式设置
     * （false 是「本会话强制普通」，<b>不是</b>未设置）；null = <b>不改动</b>该列（与 bareMode /
     * permissionMode 同款 PATCH 语义）。
     */
    Boolean coordinatorMode
) {
    /**
     * 七参兼容构造器 · 保留 V75 加列前的既有调用形态（{@code new SessionUpdateRequest(title, model,
     * modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent)}）——不传 coordinatorMode
     * 即「不改动本列」。Jackson 反序列化仍走规范构造器（本构造器无注解、非 property-based creator）。
     */
    public SessionUpdateRequest(String title, ModelTag model, String modelName, String mainProjectId,
                                Boolean bareMode, String permissionMode, String mainThreadAgent) {
        this(title, model, modelName, mainProjectId, bareMode, permissionMode, mainThreadAgent, null);
    }
}
