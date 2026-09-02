package com.nexusai.application.agent.bash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DestructiveCommandWarning 回归测试 · 验证 WHY=安全语义.
 *
 * <p>WHY 本测试存在: CC destructiveCommandWarning.ts:58 的 rm 递归强制删除 pattern
 * 两分支都必须命中 —— 无论 -r 在前 (rm -rf) 还是 -f 在前 (rm -fr)。
 * 之前的 Java 实现第二分支尾带 {@code /}，导致 {@code rm -fr build/} 漏报，
 * 用户在权限弹窗上看不到 "recursively force-remove" 告警即放行，属 P0 漏报。
 */
class DestructiveCommandWarningTest {

    private static final String RECURSIVE_FORCE_REMOVE = "Note: may recursively force-remove files";

    @Test
    @DisplayName("rm -fr build/ 必须告警（-f 在前分支，CC 尾无 /）")
    void rmFThenRwarns() {
        // WHY: CC pattern 第二分支 (^|[;&|\n]\s*)rm\s+-[a-zA-Z]*f[a-zA-Z]*[rR] 无尾 /；
        // Java 旧实现尾带 / 使 "rm -fr build/" 整串不匹配 → 漏报。
        assertEquals(RECURSIVE_FORCE_REMOVE,
            DestructiveCommandWarning.getDestructiveCommandWarning("rm -fr build/"));
    }

    @Test
    @DisplayName("rm -rf build/ 必须告警（-r 在前分支）")
    void rmRThenFwarns() {
        // WHY: -r 在前分支原已正确，回归保护。
        assertEquals(RECURSIVE_FORCE_REMOVE,
            DestructiveCommandWarning.getDestructiveCommandWarning("rm -rf build/"));
    }

    @Test
    @DisplayName("无害命令 ls -la 不告警")
    void harmlessCommandNoWarning() {
        // WHY: 告警是纯信息展示，无害命令必须返回 null，避免误报骚扰用户。
        assertNull(DestructiveCommandWarning.getDestructiveCommandWarning("ls -la"));
    }

    @Test
    @DisplayName("rm -fr 后接参数（无尾 /）仍命中第二分支")
    void rmFThenRnofTrailingSlash() {
        // WHY: CC pattern 不要求 rm 递归参数后有 /，'rm -fr build' 同样应告警。
        assertEquals(RECURSIVE_FORCE_REMOVE,
            DestructiveCommandWarning.getDestructiveCommandWarning("rm -fr build"));
    }

    @Test
    @DisplayName("rm -fr 单独出现（无目标路径）也命中")
    void bareRmFThenR() {
        assertEquals(RECURSIVE_FORCE_REMOVE,
            DestructiveCommandWarning.getDestructiveCommandWarning("rm -fr"));
    }

    @Test
    @DisplayName("小写 drop table 必须告警（CC destructiveCommandWarning.ts:72 /i 大小写不敏感）")
    void lowercaseDropTableWarns() {
        // WHY: CC 数据库 pattern /\b(DROP|TRUNCATE)\s+(TABLE|DATABASE|SCHEMA)\b/i 带 /i，
        // Java 旧实现大小写敏感 → 小写 'drop table users' 漏告警；G21 接线后输出在权限弹窗
        // 可见，漏报会让用户在无警告下放行破坏性 SQL，属 P0 漏报（IMP-H R2）。
        assertEquals("Note: may drop or truncate database objects",
            DestructiveCommandWarning.getDestructiveCommandWarning("drop table users"));
    }

    @Test
    @DisplayName("小写 truncate schema 必须告警（CC destructiveCommandWarning.ts:72 /i）")
    void lowercaseTruncateSchemaWarns() {
        assertEquals("Note: may drop or truncate database objects",
            DestructiveCommandWarning.getDestructiveCommandWarning("truncate schema public"));
    }

    @Test
    @DisplayName("小写 delete from 必须告警（CC destructiveCommandWarning.ts:76 /i）")
    void lowercaseDeleteFromWarns() {
        // WHY: CC 数据库 pattern /\bDELETE\s+FROM\s+\w+[ \t]*(;|"|'|\n|$)/i 带 /i，
        // Java 旧实现大小写敏感 → 小写 'delete from orders' 漏告警（IMP-H R2）。
        assertEquals("Note: may delete all rows from a database table",
            DestructiveCommandWarning.getDestructiveCommandWarning("delete from orders"));
    }

    @Test
    @DisplayName("混合大小写 DROP TABLE 仍告警（回归保护，CC /i 语义）")
    void mixedCaseDropTableWarns() {
        assertEquals("Note: may drop or truncate database objects",
            DestructiveCommandWarning.getDestructiveCommandWarning("DROP TABLE users"));
    }
}
