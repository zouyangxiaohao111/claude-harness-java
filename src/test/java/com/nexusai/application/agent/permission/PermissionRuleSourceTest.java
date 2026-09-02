package com.nexusai.application.agent.permission;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PermissionRuleSource 删除候选闭环测试 · DEL-WF2-01-01 / OPD-WF2-01-01。
 *
 * <p><b>WHY（规则九 · 验证意图）</b>: {@code configKey()} 为全仓 0 调用方死代码（CC 无 key 概念，
 * source→文件路径表达由各 SettingsLoader.resolvePath() 承载）。原 OD-WF2-07 以虚假调用方
 * （CheckLayer1a_DenyRule.java:63,78）作保留依据，v4 WF-2 域返工后推翻（DEL-WF2-01-01），
 * 用户 2026-08-18 拍板删除。本测试以反射断言该方法<b>不存在</b>，防止未来重新引入
 * Java 独有 key 概念污染 CC 对齐。
 */
class PermissionRuleSourceTest {

    @Test
    @DisplayName("configKey 已删除：方法不存在（RED→GREEN 删除断言）")
    void configKey_methodDeleted() {
        // RED：删除前 configKey() 存在 → getDeclaredMethod 不抛 NoSuchMethodException → 断言失败。
        // GREEN：删除后方法不存在 → 抛 NoSuchMethodException → 断言通过。
        assertThatThrownBy(() -> PermissionRuleSource.class.getDeclaredMethod("configKey"))
            .as("configKey() 必须已删除（DEL-WF2-01-01：全仓 0 调用方，CC 无 key 概念）")
            .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("回归：isReadOnly 保留（POLICY/FLAG/COMMAND 不可删改，PermissionUpdateApplier 守卫）")
    void isReadOnly_retained() {
        // WHY：OD-WF2-07 保留依据成立仅针对 isReadOnly（PermissionUpdateApplier.java:177
        // removeRules 守卫拒绝删除只读源规则）——configKey 依据被推翻，isReadOnly 不随之删除。
        assertThat(PermissionRuleSource.POLICY_SETTINGS.isReadOnly()).isTrue();
        assertThat(PermissionRuleSource.FLAG_SETTINGS.isReadOnly()).isTrue();
        assertThat(PermissionRuleSource.COMMAND.isReadOnly()).isTrue();
        assertThat(PermissionRuleSource.USER_SETTINGS.isReadOnly()).isFalse();
        assertThat(PermissionRuleSource.SESSION.isReadOnly()).isFalse();
    }
}
