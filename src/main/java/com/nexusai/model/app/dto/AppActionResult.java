package com.nexusai.model.app.dto;

import java.util.List;

/**
 * {@code POST /api/v1/apps/{key}/start|stop} 响应。
 *
 * @param app          操作后的应用条目（含 serverId/canStart/lastError）
 * @param enabledApps  操作后该会话的 enabled_apps（列表；null 不应出现——操作后总为显式快照）
 * @param started      当前是否「已启动」（start 成功 / stop 后 false；启动失败 false）
 * @param message      失败/提示原因（成功且无提示 → null；失败必须如实给原因）
 */
public record AppActionResult(
    AppDto app,
    List<String> enabledApps,
    boolean started,
    String message
) {
    public AppActionResult {
        enabledApps = enabledApps == null ? List.of() : List.copyOf(enabledApps);
    }
}
