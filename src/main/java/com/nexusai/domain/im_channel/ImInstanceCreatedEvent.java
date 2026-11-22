package com.nexusai.domain.im_channel;

/**
 * 渠道实例刚创建完成（凭证已落库）。
 *
 * <p>用户 2026-11-22 定：**连接后默认启动**，不让用户多点一步「启动连接」。
 * 所以创建路径（手填凭证 / 扫码 / 一键创建）统一发这个事件，
 * 由 {@code ImInboundManager} 负责真正拉起长连接 —— 服务层不直接依赖入站管理器，
 * 避免循环依赖。
 */
public record ImInstanceCreatedEvent(String instanceId) {}
