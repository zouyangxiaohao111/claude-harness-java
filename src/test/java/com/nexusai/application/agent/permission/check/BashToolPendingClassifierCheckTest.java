package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.BashClassifierFeature;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.ToolCheckCache;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H14-FIX + O18] BashTool buildPendingClassifierCheck 恒 null 语义 · 对齐 CC
 * buildPendingClassifierCheck (bashPermissions.ts:1459-1476)。
 *
 * <p>WHY (规则九 · 测试验证意图): CC 外部构建 {@code isClassifierPermissionsEnabled()} 恒
 * false（bashClassifier.ts stub）且 {@code getBashPromptAllowDescriptions} 恒 [] —— Java
 * 启发式 BashClassifier 已随 O18 删除，描述来源恒空 → PendingClassifierCheck 恒 null。
 * 本测试锁定恒 null 语义的负向守卫:
 * <ul>
 *   <li><b>负向</b>: classifier 未启用 → 不挂结构体 (对齐 CC undefined)</li>
 *   <li><b>负向</b>: bypassPermissions 模式 → 不挂结构体 (CC :1467-1468)</li>
 *   <li><b>正向</b>: classifier 启用 + DEFAULT 模式 → 恒空描述源仍不挂结构体 (CC :1474 step4)</li>
 * </ul>
 */
@DisplayName("[H14-FIX + O18] BashTool PendingClassifierCheck 恒 null")
class BashToolPendingClassifierCheckTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final UUID AGENT = UUID.randomUUID();

    @AfterEach
    void tearDown() {
        ToolCheckCache.clear();
    }

    private ToolUseContext ctx(PermissionMode permissionMode) {
        // 用全参构造器带 effectiveCwd（PermissionMode 判别用）。
        return new ToolUseContext(AGENT, SESSION, PermissionMode.DEFAULT,
            Map.of(), List.of(), "", com.nexusai.application.agent.tool.AbortController.NOOP,
            List.of(), null, permissionMode, Map.of(), false, "", Path.of("/tmp/project"),
            null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null,
            false, null, null, null, null, null, false, false, null, null, null, null, null, null, null);
    }

    private JsonNode bashInput(String command) {
        return JSON.createObjectNode().put("command", command);
    }

    private BashTool bashToolWithClassifier(boolean classifierEnabled) {
        BashTool tool = new BashTool();
        // [WF-4 DEC-05] classifier 启用判定 = isClassifierPermissionsEnabled 语义（BASH_CLASSIFIER
        //   特性单一门 BashClassifierFeature.isEnabled()；旧 Predicate 双轨已删，测试注入 bean）。
        //   O18: 描述来源已删，启用与否 PendingClassifierCheck 均恒 null（CC 外部构建语义）。
        BashClassifierFeature feature = mock(BashClassifierFeature.class);
        when(feature.isEnabled()).thenReturn(classifierEnabled);
        tool.setBashClassifierFeatureBean(feature);
        return tool;
    }

    @Test
    @DisplayName("classifier 未启用 → Passthrough 不携带结构体 (对齐 CC undefined)")
    void bashTool_classifierDisabled_noPendingClassifierCheck() {
        // WHY: CC isClassifierPermissionsEnabled() false → buildPendingClassifierCheck undefined.
        //      默认未配置 classifier 时结构体必须为 null (不误触发竞速).
        BashTool tool = bashToolWithClassifier(false);

        PermissionResult result = tool.checkPermissions(bashInput("git status"), ctx(PermissionMode.DEFAULT));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(((PermissionResult.Passthrough) result).pendingClassifierCheck()).isNull();
    }

    @Test
    @DisplayName("bypassPermissions 模式 → 不挂结构体 (CC :1467-1468)")
    void bashTool_bypassMode_noPendingClassifierCheck() {
        // WHY: CC buildPendingClassifierCheck 在 bypassPermissions 模式返回 undefined
        //      (bypass 已授权, 无需分类器竞速).
        BashTool tool = bashToolWithClassifier(true);

        PermissionResult result = tool.checkPermissions(bashInput("git status"), ctx(PermissionMode.BYPASS_PERMISSIONS));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(((PermissionResult.Passthrough) result).pendingClassifierCheck()).isNull();
    }

    @Test
    @DisplayName("classifier 启用 + DEFAULT 模式 → 恒空描述源不挂结构体 (CC :1474 step4)")
    void bashTool_classifierEnabled_defaultMode_noPendingClassifierCheck() {
        // WHY: CC buildPendingClassifierCheck step4 — getBashPromptAllowDescriptions 恒 []
        //      (bashClassifier.ts:38-40 stub) → allowDescriptions.length === 0 → return undefined。
        //      Java 已删 BashClassifier 启发式描述源（O18），step4 恒 List.of() → 结构体恒 null。
        //      锁定 step4 恒空守卫：即便 classifier 启用 + DEFAULT（非 auto/bypass），
        //      描述源为空仍不挂结构体（不误触发异步分类器竞速）。此为原 WF2-01 缺口的正向覆盖。
        BashTool tool = bashToolWithClassifier(true);

        PermissionResult result = tool.checkPermissions(bashInput("git status"), ctx(PermissionMode.DEFAULT));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(((PermissionResult.Passthrough) result).pendingClassifierCheck()).isNull();
    }

}
