package com.nexusai.application.chat;

import com.nexusai.repository.session.entity.SessionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [V44] ChatService 有效初始权限模式测试（per-call ?? 会话 override → RunRequest.permissionModeCli）。
 *
 * <p><b>WHY（规则九 · 测试验证意图）</b>：V44 三态链第一段——per-call（HTTP 请求体
 * {@code SendMessageRequest.permissionMode}）恒胜会话 override（{@code sessions.permission_mode}
 * 列），两者共享 CLI 槽（{@code RunRequest.permissionModeCli} → {@code InitialPermissionModeResolver}
 * 链第 2 优先级，恒胜 settings 槽）。{@link ChatService#resolveEffectivePermissionMode} 是
 * {@code processUserMessage} 里 {@code loop.run(RunRequest.session(...))} 喂 permissionModeCli 的
 * 唯一数据源——直接锁定该纯函数三种形态：
 * <ol>
 *   <li><b>会话 override 生效</b>——per-call null + session.permission_mode='plan' → 'plan'
 *       （会话初始化传 permissionModeCli）；</li>
 *   <li><b>per-call 恒胜</b>——per-call='dontAsk' + session 'plan' → 'dontAsk'
 *       （每次调用可临时覆盖会话默认）；</li>
 *   <li><b>回落全局</b>——两者均 null → null（resolver 回落 settings 槽 DB 全局 ?? 磁盘 → default）。</li>
 * </ol>
 * 变异点：任一分支破坏（如 per-call 不优先 / session override 不生效）→ 对应用例红。
 */
@DisplayName("[V44] ChatService 有效初始权限模式 = per-call ?? 会话 override")
class ChatServiceEffectivePermissionModeTest {

    private static SessionRecord session(String permissionMode) {
        SessionRecord s = new SessionRecord();
        s.setId("sess-1");
        s.setPermissionMode(permissionMode);
        return s;
    }

    @Test
    @DisplayName("① 会话 override：per-call null + session.permission_mode='plan' → 'plan'（会话初始化传 permissionModeCli）")
    void sessionOverride_usedWhenNoPerCall() {
        // WHY: 用户 PATCH 会话权限模式 plan → 该会话所有调用无显式 per-call 时恒走 plan
        //   （会话级覆盖，V44 列承载）。
        String effective = ChatService.resolveEffectivePermissionMode(session("plan"), null);
        assertThat(effective)
            .as("会话 override plan 在无 per-call 时必须生效（→ RunRequest.permissionModeCli）")
            .isEqualTo("plan");
    }

    @Test
    @DisplayName("② per-call 恒胜：per-call='dontAsk' + session 'plan' → 'dontAsk'")
    void perCallWinsOverSessionOverride() {
        // WHY: 请求体显式 permissionMode 是本次调用意图，必须覆盖会话默认（CC --permission-mode
        //   进程级 flag 的 web per-call 等价）。
        String effective = ChatService.resolveEffectivePermissionMode(session("plan"), "dontAsk");
        assertThat(effective)
            .as("per-call dontAsk 恒胜会话 override plan")
            .isEqualTo("dontAsk");
    }

    @Test
    @DisplayName("③ 回落全局：session 无 override + per-call null → null（resolver 回落 settings 槽）")
    void nullWhenNoOverrideAndNoPerCall() {
        // WHY: 两者均 null → 返回 null → InitialPermissionModeResolver 回落 settings 槽
        //   （DB 全局 settings.permission_mode ?? 磁盘 settings.json defaultMode ?? default）。
        String effective = ChatService.resolveEffectivePermissionMode(session(null), null);
        assertThat(effective)
            .as("无 per-call 且无会话 override → null（回落全局默认链）")
            .isNull();
    }

    @Test
    @DisplayName("session 为 null（异常路径）→ per-call 透传 / null 回落")
    void nullSession_safe() {
        // WHY: processUserMessage 内 session 非 null（:183-186 前置 return），但纯函数对 null session
        //   必须安全（防御语义，不 NPE）。
        assertThat(ChatService.resolveEffectivePermissionMode(null, "plan")).isEqualTo("plan");
        assertThat(ChatService.resolveEffectivePermissionMode(null, null)).isNull();
    }
}
