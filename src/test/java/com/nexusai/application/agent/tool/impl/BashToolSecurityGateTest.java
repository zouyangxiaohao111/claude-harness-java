package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RV-D-01 NG-1] BashSecurity 门禁接线 RED→GREEN。
 *
 * <p>WHY：注入向量在改前经 checkPermissions 为 passthrough/allow（无 BashSecurity 门禁），
 * 改后应返回 {@link PermissionResult.Ask} 且 {@code isBashSecurityCheckForMisparsing()==true}。
 * 门禁无条件运行（不依赖 ctx != null），故以 null ctx 直调 checkPermissions 验证生产接线点
 * 真存在（非 stub）。合法只读命令（ls / echo 简单形式 / git status）不得被门禁误伤。
 */
@DisplayName("[RV-D-01] BashSecurity 门禁接线")
class BashToolSecurityGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static PermissionResult check(BashTool tool, String command) {
        ObjectNode input = JSON.createObjectNode();
        input.put("command", command);
        return tool.checkPermissions(input, null);
    }

    @Test
    @DisplayName("反斜杠转义操作符注入 → Ask + misparsing flag（原 passthrough）")
    void backslashOperator_injectionReturnsMisparsingAsk() {
        BashTool tool = new BashTool();
        PermissionResult r = check(tool, "cat safe.txt \\; echo /etc/passwd");

        assertThat(r).isInstanceOf(PermissionResult.Ask.class);
        PermissionResult.Ask ask = (PermissionResult.Ask) r;
        assertThat(ask.isBashSecurityCheckForMisparsing()).isTrue();
        assertThat(ask.message()).contains("backslash");
    }

    @Test
    @DisplayName("$() 命令替换注入 → Ask + misparsing flag")
    void commandSubstitution_injectionReturnsMisparsingAsk() {
        BashTool tool = new BashTool();
        PermissionResult r = check(tool, "echo $(id)");

        assertThat(r).isInstanceOf(PermissionResult.Ask.class);
        assertThat(((PermissionResult.Ask) r).isBashSecurityCheckForMisparsing()).isTrue();
    }

    @Test
    @DisplayName("合法命令不误伤：ls -la / git status → 非 Ask；echo hi → Allow")
    void legalCommands_notBlocked() {
        BashTool tool = new BashTool();
        assertThat(check(tool, "ls -la")).isNotInstanceOf(PermissionResult.Ask.class);
        assertThat(check(tool, "git status")).isNotInstanceOf(PermissionResult.Ask.class);
        assertThat(check(tool, "echo hi")).isInstanceOf(PermissionResult.Allow.class);
    }
}
