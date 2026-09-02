package com.nexusai.application.agent.permission.check;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.permission.BashClassifierFeature;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionResult;
import com.nexusai.application.agent.permission.classifier.AutoModeGate;
import com.nexusai.application.agent.tool.ToolUseContext;
import com.nexusai.application.agent.tool.impl.BashTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [H14 v3 Gap③ + O18] isClassifierPermissionsEnabled 判定语义一致化.
 *
 * <p><b>WHY (规则九 · 验证意图)</b>: v2 对抗复验残留缺口③ "CC 行为分叉（isClassifierPermissionsEnabled
 * 恒 false 但 Java 用 AutoModeGate 配置门控）"。CC {@code buildPendingClassifierCheck}
 * (bashPermissions.ts:1463) 的第一道闸是 {@code isClassifierPermissionsEnabled()} —
 * 即 BASH_CLASSIFIER 特性开关（Java 等价 {@link BashTool#setBashClassifierFeatureBean} /
 * {@code nexusai.feature.bash-classifier} 单一门 BashClassifierFeature.isEnabled()），
 * 与 auto mode 是<b>两个不同维度</b>：
 * auto mode 只影响第二道闸（{@code feature('TRANSCRIPT_CLASSIFIER') && mode==='auto'} 跳过）。
 *
 * <p>O18: Java 启发式 BashClassifier 已删，描述来源恒空（CC 外部构建 stub 恒禁用）——
 * PendingClassifierCheck 恒 null。本测试锁定 feature 闸关闭时的 null 语义
 * （auto-mode 开启也不豁免，两维度独立）。
 */
@DisplayName("[H14 v3 Gap③ + O18] isClassifierPermissionsEnabled 闸：feature 关 → 恒 null")
class H14V3_ClassifierPermissionGateSemanticsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    private static final UUID AGENT = UUID.randomUUID();

    private ToolUseContext ctx(PermissionMode permissionMode) {
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

    private BashTool bashTool(boolean bashClassifierEnabled, boolean autoModeEnabled) {
        BashTool tool = new BashTool();
        // [WF-4 DEC-05] BASH_CLASSIFIER 特性 = isClassifierPermissionsEnabled 单一门
        //   （BashClassifierFeature.isEnabled()；旧 Predicate 双轨已删，测试注入 bean）。
        BashClassifierFeature feature = mock(BashClassifierFeature.class);
        when(feature.isEnabled()).thenReturn(bashClassifierEnabled);
        tool.setBashClassifierFeatureBean(feature);
        inject(tool, "autoModeGate", new AutoModeGate(autoModeEnabled));
        return tool;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("反射注入失败: " + field, e);
        }
    }

    @Test
    @DisplayName("BASH_CLASSIFIER 关 + auto-mode 开 → 不构建 PendingClassifierCheck (CC isClassifierPermissionsEnabled 闸)")
    void bashClassifierDisabled_autoModeEnabled_doesNotBuildCheck() {
        // WHY: CC 第一道闸 isClassifierPermissionsEnabled()=false → buildPendingClassifierCheck
        //      undefined；O18 后描述来源恒空 → 恒 null。auto-mode 开启也不豁免（两维度独立）。
        BashTool tool = bashTool(false, true);  // BASH_CLASSIFIER=off, auto-mode=on

        PermissionResult result = tool.checkPermissions(bashInput("git status"), ctx(PermissionMode.DEFAULT));

        assertThat(result).isInstanceOf(PermissionResult.Passthrough.class);
        assertThat(((PermissionResult.Passthrough) result).pendingClassifierCheck())
            .as("BASH_CLASSIFIER 关闭时第一道闸必须拦截 (isClassifierPermissionsEnabled 语义, auto-mode 不豁免)")
            .isNull();
    }

}
