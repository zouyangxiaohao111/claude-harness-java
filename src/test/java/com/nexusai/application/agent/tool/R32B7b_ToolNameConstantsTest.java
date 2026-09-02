package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R32-b7b · ToolNameConstants 新增 2 个常量验证（TUNGSTEN / SUGGEST_BACKGROUND_PR）。
 *
 * <p><b>WHY (意图验证)</b>: 常量值严格对齐 CC 命名（PascalCase），且 stub 的 NAME 字段
 * 引用常量（防止常量/实现漂移）。
 *
 * <p><b>[IMP-C4 DC-A1-03 修订]</b>: 原 4 个 ALL_NAMES 断言已删除 —— {@code ALL_NAMES}
 * 是生产零消费者死代码（EV-A1-064），其「ToolRegistry 按 ALL_NAMES 判断注册覆盖率」前提
 * 与实测不符（ToolRegistry 经 @Autowired List&lt;Tool&gt; + registerAll 注册，不读 ALL_NAMES）。
 * 常量自身值/引用一致性断言保留。
 */
class R32B7b_ToolNameConstantsTest {

    @Test
    @DisplayName("TUNGSTEN_TOOL_NAME = 'Tungsten' (对齐 CC AntConfigTool/TungstenTool 命名)")
    void tungstenConstantValue() {
        // WHY: stub 的 name() 依赖此常量; 常量值必须与 CC 命名一致 (PascalCase),
        // 不能写成小写下划线 (e.g. "tungsten"), 否则 ToolRegistry 查不到.
        assertThat(ToolNameConstants.TUNGSTEN_TOOL_NAME)
            .as("TUNGSTEN_TOOL_NAME 是 TungstenTool stub 的 name() 唯一来源")
            .isEqualTo("Tungsten")
            .isNotBlank();
    }

    @Test
    @DisplayName("SUGGEST_BACKGROUND_PR_TOOL_NAME = 'SuggestBackgroundPR' (对齐 CC 命名)")
    void suggestBackgroundPrConstantValue() {
        // WHY: stub 的 name() 依赖此常量; 常量值必须严格 = CC PascalCase 命名
        assertThat(ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME)
            .as("SUGGEST_BACKGROUND_PR_TOOL_NAME 是 SuggestBackgroundPRTool stub 的 name() 唯一来源")
            .isEqualTo("SuggestBackgroundPR")
            .isNotBlank();
    }

    @Test
    @DisplayName("TUNGSTEN_TOOL_NAME 引用一致性: 常量值与 stub.NAME 字段一致")
    void tungstenConstantMatchesStubField() {
        // WHY: TungstenTool.NAME 字段 = ToolNameConstants.TUNGSTEN_TOOL_NAME,
        // 防止 stub 实现误用硬编码字符串导致常量与 stub 字段漂移
        assertThat(com.nexusai.application.agent.tool.impl.TungstenTool.NAME)
            .as("TungstenTool.NAME 必须 = ToolNameConstants.TUNGSTEN_TOOL_NAME (防止常量/实现漂移)")
            .isEqualTo(ToolNameConstants.TUNGSTEN_TOOL_NAME);
    }

    @Test
    @DisplayName("SUGGEST_BACKGROUND_PR_TOOL_NAME 引用一致性: 常量值与 stub.NAME 字段一致")
    void suggestBackgroundPrConstantMatchesStubField() {
        assertThat(com.nexusai.application.agent.tool.impl.SuggestBackgroundPRTool.NAME)
            .as("SuggestBackgroundPRTool.NAME 必须 = ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME (防止常量/实现漂移)")
            .isEqualTo(ToolNameConstants.SUGGEST_BACKGROUND_PR_TOOL_NAME);
    }
}
