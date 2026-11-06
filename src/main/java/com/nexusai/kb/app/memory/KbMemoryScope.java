package com.nexusai.kb.app.memory;

/**
 * 记忆主体作用域（批次 W9）。
 *
 * <p><b>用户 2026-11-05 裁定：单机单主体</b>。
 *
 * <p>源侧 {@code subject_id} 是登录用户（{@code web_user:<uuid>}）；本仓是单机应用、
 * 没有 users 表 ⇒ 固定 {@link #LOCAL_SUBJECT} 一行。
 *
 * <p><b>⭐ 为什么仍然做成一个类型而不是到处写常量</b>：
 * 将来接多用户时，只需要改 {@link #current()} 的实现（从请求上下文取用户），
 * 所有调用点、所有表结构、所有 SQL 都不用动。
 * ⛔ 如果现在把常量散落到 20 处，将来就是 20 处一起改、漏一个就是串号。
 *
 * @param subjectId 主体标识
 */
public record KbMemoryScope(String subjectId) {

    /** 单机主体的固定标识。 */
    public static final String LOCAL_SUBJECT = "local_user:default";

    /**
     * 当前请求的主体。
     *
     * <p>⚠️ 现在恒返回单机主体。接入多用户时改这里，不要改调用点。
     */
    public static KbMemoryScope current() {
        return new KbMemoryScope(LOCAL_SUBJECT);
    }
}
