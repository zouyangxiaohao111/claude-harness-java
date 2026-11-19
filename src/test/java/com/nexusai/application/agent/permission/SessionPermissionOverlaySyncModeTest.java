package com.nexusai.application.agent.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * [权限胶囊切换] {@link SessionPermissionOverlay#syncMode} 的意图测试。
 *
 * <p><b>WHY</b>：V75 {@code session_permission_rules} 的 SESSION setMode 在 per-turn 合并中
 * 优先于 {@code permission_mode} 列（{@link SessionPermissionOverlay#effectiveModeCcLiteral}）。
 * 用户在权限胶囊里显式切换模式时若只 PATCH 列，会被列里的旧 setMode 盖回去 ⇒ 点「绕过权限」
 * 弹回、运行时仍是旧模式（实测 {@code permission_mode=bypassPermissions} 但列
 * {@code mode=acceptEdits}）。本测试锁住「显式切换必须同步覆盖层」的意图。
 */
class SessionPermissionOverlaySyncModeTest {

    private static final String COLUMN = "{\"mode\":\"acceptEdits\","
        + "\"alwaysAllowRules\":[{\"toolName\":\"Edit\",\"ruleContent\":\"~/.nexusai/**\"}],"
        + "\"alwaysDenyRules\":[],\"alwaysAskRules\":[],\"additionalWorkingDirectories\":[]}";

    @Test
    void syncsExistingModeAndKeepsRules() {
        String out = SessionPermissionOverlay.syncMode(COLUMN, PermissionMode.BYPASS_PERMISSIONS);

        assertNotEquals(COLUMN, out, "列里有旧 setMode 时必须改写，否则胶囊/运行时被盖回");
        ToolPermissionContext ctx = SessionPermissionOverlay.toContext(out, PermissionMode.DEFAULT);
        assertEquals(PermissionMode.BYPASS_PERMISSIONS, ctx.mode(),
            "同步后有效 mode 必须是用户显式选择的值（不能仍读回旧 acceptEdits）");
        assertEquals(1, ctx.alwaysAllowRules().get(PermissionRuleSource.SESSION).size(),
            "已授权的 allow 规则必须原样保留（只改 mode）");
    }

    @Test
    void noOpWhenColumnHasNoMode() {
        String noMode = "{\"alwaysAllowRules\":[{\"toolName\":\"Edit\"}],"
            + "\"alwaysDenyRules\":[],\"alwaysAskRules\":[],\"additionalWorkingDirectories\":[]}";
        assertSame(noMode, SessionPermissionOverlay.syncMode(noMode, PermissionMode.BYPASS_PERMISSIONS),
            "列里没有 SESSION setMode 时 permission_mode 列即唯一真源，不得凭空造条目");
    }

    @Test
    void noOpWhenNullOrSameOrBubble() {
        assertNull(SessionPermissionOverlay.syncMode(null, PermissionMode.DEFAULT));
        assertSame(COLUMN, SessionPermissionOverlay.syncMode(COLUMN, PermissionMode.ACCEPT_EDITS),
            "同值不得重写");
        assertSame(COLUMN, SessionPermissionOverlay.syncMode(COLUMN, PermissionMode.BUBBLE),
            "BUBBLE 是子 Agent 冒泡内部标记，不得写进会话列");
    }
}
