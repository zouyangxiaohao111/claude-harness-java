package com.nexusai.application.agent.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * workspace trust 状态源 · 对齐 CC {@code checkHasTrustDialogAccepted()} (utils/config.ts)
 * 与 {@code getIsNonInteractiveSession()} (bootstrap/state.ts:1057-1059) 的 Java 等价状态载体。
 *
 * <p>WHY (IMPL-04 D9 / OD-13 / EV-L01-030): trust 概念已建模于 mcp 域
 * {@link HeadersHelper}（注入式 {@code BooleanSupplier}）但 hook 执行链未接入 ——
 * CC shouldSkipHookDueToTrust (hooks.ts:286-296) 要求交互模式全部 hook 需要 workspace trust
 * （防 RCE 安全门）。本组件是 trust 判定状态的<b>单一生产来源</b>：
 * <ul>
 *   <li>{@link #nonInteractiveSupplier()} / {@link #trustDialogAcceptedSupplier()} 两个
 *       {@link HeadersHelper.BooleanSupplier} bean —— 同时供 {@link HeadersHelper}（构造注入，
 *       mcp 域自身）与 hook 域 {@code HookRegistry.setTrustGateSuppliers} 复用同一状态;</li>
 *   <li>{@link #setTrustDialogAccepted(boolean)} / {@link #acceptTrustDialog()} —— 未来 web 端
 *       workspace trust dialog 接受入口（当前 web 后端无 trust dialog，保持 CC 默认：未接受）;</li>
 *   <li>{@link #setNonInteractiveSession(boolean)} —— 非交互会话标记（SDK/-p 等价；web 后端
 *       默认交互式 false）。</li>
 * </ul>
 *
 * <p><b>默认值（2026-09-01 用户拍板 A）</b>:
 * <ul>
 *   <li>{@code trustDialogAccepted = true}：Web 后端无 CC CLI 的 trust dialog，Web 用户使用
 *       自己的应用即视为信任 workspace → hook 全执行（SessionStart 技能注入 / PreToolUse
 *       权限 hook 等）；原 CC 对齐默认 false（全新安装未 trust → hook 全跳过）在 Web 场景
 *       导致技能说明不注入（using-zjkycode 等）、权限 hook 失效，改默认 true 修复;</li>
 *   <li>{@code nonInteractiveSession = false}：web 后端会话默认交互式
 *       （LlmAgentLoop:1078 同默认）。</li>
 * </ul>
 *
 * <p><b>local-only 约束</b>: 本组件不向外发送任何数据，仅本地 trust 状态查询。
 *
 * @see HeadersHelper
 * @see com.nexusai.application.agent.permission.hook.HookRegistry#setTrustGateSuppliers
 */
@Component
public class WorkspaceTrustState {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceTrustState.class);

    /**
     * 非交互会话标记 · 对齐 CC {@code STATE.isInteractive} 取反 (state.ts:1057-1059,
     * main.tsx:802-812 进程启动一次设定). true = 非交互 (SDK/-p) → trust 隐式.
     * web 后端会话默认交互式 (false).
     */
    private volatile boolean nonInteractiveSession = false;

    /**
     * trust dialog 接受标记 · 对齐 CC {@code checkHasTrustDialogAccepted()} (config.ts).
     * <b>[2026-09-01 用户拍板 A] 默认 true</b> —— Web 后端无 CC CLI 的 trust dialog，Web 用户
     * 使用自己的应用即视为信任 workspace；默认 false 会拦截全部 hook（SessionStart 技能注入 /
     * PreToolUse 权限 hook 等），导致 using-zjkycode 等技能说明不注入、模型看不到。
     */
    private volatile boolean trustDialogAccepted = true;

    /** 是否非交互会话. */
    public boolean isNonInteractiveSession() {
        return nonInteractiveSession;
    }

    /**
     * 设置非交互会话标记 · 对齐 CC {@code setNonInteractiveSession}（SDK/-p 会话入口调用）.
     *
     * @param nonInteractive true = 本进程处于非交互会话
     */
    public void setNonInteractiveSession(boolean nonInteractive) {
        this.nonInteractiveSession = nonInteractive;
        if (log.isDebugEnabled()) {
            log.debug("WorkspaceTrustState: nonInteractiveSession={}", nonInteractive);
        }
    }

    /** 是否已接受 workspace trust dialog. */
    public boolean isTrustDialogAccepted() {
        return trustDialogAccepted;
    }

    /**
     * 设置 trust dialog 接受状态 · 对齐 CC {@code checkHasTrustDialogAccepted()} 的写入侧
     * （CC config.ts 持久化于全局配置；web 后端由未来 trust dialog 入口调用）.
     *
     * @param accepted true = 用户已接受 workspace trust
     */
    public void setTrustDialogAccepted(boolean accepted) {
        this.trustDialogAccepted = accepted;
        if (log.isDebugEnabled()) {
            log.debug("WorkspaceTrustState: trustDialogAccepted={}", accepted);
        }
    }

    /** 便捷入口：接受 workspace trust dialog（web 端 trust dialog 接线点）. */
    public void acceptTrustDialog() {
        setTrustDialogAccepted(true);
    }

    /**
     * {@link HeadersHelper} 构造注入复用 · 非交互会话 supplier（与 {@link HeadersHelper}
     * 构造器第一参同型同义，EV-L01-030: trust 概念建模于 mcp 域 HeadersHelper）.
     *
     * @return 读取 {@link #nonInteractiveSession} 的惰性 supplier
     */
    @Bean(name = "workspaceNonInteractiveSupplier")
    public HeadersHelper.BooleanSupplier nonInteractiveSupplier() {
        return this::isNonInteractiveSession;
    }

    /**
     * {@link HeadersHelper} 构造注入复用 · trust dialog 接受 supplier（与 {@link HeadersHelper}
     * 构造器第二参同型同义；hook 域 {@code HookRegistry.setTrustGateSuppliers} 经本 bean 接线）.
     *
     * @return 读取 {@link #trustDialogAccepted} 的惰性 supplier
     */
    @Bean(name = "workspaceTrustDialogAcceptedSupplier")
    public HeadersHelper.BooleanSupplier trustDialogAcceptedSupplier() {
        return this::isTrustDialogAccepted;
    }
}
