package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.PermissionRuleSource;
import com.nexusai.application.agent.permission.ToolPermissionContext;
import com.nexusai.application.agent.skill.SkillRegistry;
import com.nexusai.application.agent.tool.AbortController;
import com.nexusai.application.agent.tool.AgentToolResult;
import com.nexusai.application.agent.tool.ToolResult;
import com.nexusai.application.agent.tool.ToolUseBlock;
import com.nexusai.application.agent.tool.ToolUseContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [P0-2] SkillTool inline contextModifier 三件套对齐 CC 测试 ·
 * 对齐 CC {@code Open-ClaudeCode/src/tools/SkillTool/SkillTool.ts:775-839} call() 返回的
 * contextModifier（allowedTools / model / effort 三件套）。
 *
 * <p>规则九（验证意图）: inline 技能带 allowedTools/model/effort frontmatter 时, 返回的
 * ToolResult 必须携带 contextModifier（RED 前提: 旧实现 SkillToolImpl.java:414-417 恒传
 * null, 注释明示 "暂传 null"）; apply 后把三件套经 setAppState 写入会话 appStateRef ——
 * 后续轮次 toolExecContext 合并 command 授权（工具不再被权限层阻断）+ getModelForCall
 * 走 skill-model 优先级层（LLM 请求用覆盖模型）。若 contextModifier 缺失, 技能声明的
 * 授权 / 模型 / effort 全部无效（LLM 下一轮仍被权限层阻断 + 用原模型）。
 *
 * <p>RED 依据: 实施前 tr.contextModifier() == null（SkillToolImpl.java:414-416 "暂传 null"）。
 * 实施后转 GREEN。
 */
@DisplayName("[P0-2] SkillTool inline contextModifier 三件套对齐 CC SkillTool.ts:775-839")
class SkillToolContextModifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 在 @TempDir 下创建 context=inline 技能目录 (skills/&lt;name&gt;/SKILL.md) ·
     * 对齐 SkillsLoader 目录布局 (skillsRoot/<skillName>/SKILL.md)。frontmatter 含
     * allowed-tools（kebab, 含重复 Bash 验去重）+ model + effort 三件套。
     */
    private SkillRegistry newInlineRegistry(Path tempDir) throws Exception {
        Path skillDir = tempDir.resolve("inline-skill");
        Files.createDirectories(skillDir);
        String md = "---\n"
                + "name: inline-skill\n"
                + "description: inline contextModifier 技能\n"
                + "allowed-tools: [Bash, Read, Bash]\n"
                + "model: opus\n"
                + "effort: high\n"
                + "---\n"
                + "# Inline Skill\n\n正文\n";
        Files.writeString(skillDir.resolve("SKILL.md"), md);
        return new SkillRegistry(tempDir.toString());
    }

    private ToolUseBlock inlineBlock(String skillName) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("skill", skillName);
        return new ToolUseBlock("tool-use-1", "Skill", input);
    }

    /**
     * 构造 getAppState/setAppState 绑定到可观测 {@code appState} Map 的 TUC ·
     * 对齐 LlmAgentLoop.java:3538-3542 base TUC 绑定（getAppStateSnapshot / setAppState）。
     */
    private ToolUseContext appStateBoundTuc(Map<String, Object> appState) {
        Function<Map<String, Object>, Map<String, Object>> getAppState = prev -> Map.copyOf(appState);
        Consumer<Function<Map<String, Object>, Map<String, Object>>> setAppState = updater -> {
            Map<String, Object> next = updater.apply(Map.copyOf(appState));
            appState.clear();
            if (next != null) {
                appState.putAll(next);
            }
        };
        return new ToolUseContext(
                UUID.randomUUID(), "sess-" + java.util.UUID.randomUUID().toString().substring(0, 8), PermissionMode.DEFAULT,
                Map.of(), List.of(), "", AbortController.NOOP, List.of(), null, PermissionMode.DEFAULT,
                Map.of(), false, "", null, null, Map.of(), null,
                getAppState, setAppState, null, null);
    }

    @Test
    @DisplayName("inline 技能返回携带 contextModifier (RED: 旧实现恒 null)")
    void inlineSkill_returnsContextModifier(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:767-774 call() 返回 {data, newMessages, contextModifier} ——
        //   contextModifier 是把技能声明的 allowedTools/model/effort 生效的唯一通道。
        //   P0-2 前 Java 返回无 modifier（SkillToolImpl.java:414-416 "暂传 null"）→ 三件套全失效。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));
        ToolUseContext tuc = appStateBoundTuc(new ConcurrentHashMap<>());
        AgentToolResult result = tool.execute(inlineBlock("inline-skill"), tuc);
        assertThat(result).isInstanceOf(ToolResult.class);
        ToolResult<?> tr = (ToolResult<?>) result;
        assertThat(tr.contextModifier())
                .as("inline 技能返回必须携带 contextModifier (CC SkillTool.ts:767-774)")
                .isNotNull();
    }

    @Test
    @DisplayName("apply contextModifier → appState 三件套: command 授权去重 / model [1m] / effortValue")
    void contextModifier_appliesTriadToAppState(@TempDir Path tempDir) throws Exception {
        // WHY: CC 三件套 (SkillTool.ts:775-839) —— (a) allowedTools 去重合入
        //   appState.toolPermissionContext.alwaysAllowRules.command (:779-806) → 后续权限检查放行;
        //   (b) model 经 resolveSkillModelOverride 覆盖 mainLoopModel 并顺延 [1m] (:808-821);
        //   (c) effort 写入 appState.effortValue (:823-836)。
        SkillToolImpl tool = new SkillToolImpl(newInlineRegistry(tempDir));
        Map<String, Object> appState = new ConcurrentHashMap<>();
        // 1M 会话 (CC model.ts:527 前提): currentModel 有 [1m], 验证 skill model 顺延 [1m]
        appState.put("mainLoopModel", "opus[1m]");
        ToolUseContext tuc = appStateBoundTuc(appState);

        ToolResult<?> tr = (ToolResult<?>) tool.execute(inlineBlock("inline-skill"), tuc);
        assertThat(tr.contextModifier()).isNotNull();
        ToolUseContext modified = tr.contextModifier().apply(tuc);
        assertThat(modified).isNotNull();

        // (a) command 授权桶: allowed-tools [Bash, Read, Bash] 去重为 {Bash, Read}
        Object tpcObj = appState.get("toolPermissionContext");
        assertThat(tpcObj)
                .as("appState 必须含 toolPermissionContext (CC SkillTool.ts:790-801)")
                .isInstanceOf(ToolPermissionContext.class);
        ToolPermissionContext tpc = (ToolPermissionContext) tpcObj;
        Set<String> toolNames = tpc.alwaysAllowRules()
                .get(PermissionRuleSource.COMMAND).stream()
                .map(r -> r.ruleValue().toolName())
                .collect(Collectors.toSet());
        assertThat(toolNames)
                .as("command 桶含 skill allowed-tools 且去重 (CC [...new Set([...])] SkillTool.ts:794-800)")
                .containsExactlyInAnyOrder("Bash", "Read");

        // (b) model: frontmatter 'opus' 在加载期经 parseUserSpecifiedModel 规范化为 canonical
        //     'claude-opus-4-6'（CC loadSkillsDir.ts:221-226 + model.ts:464-465 别名 opus →
        //     getDefaultOpusModel()），故 contextModifier 收到的 skillModel 已是 canonical；
        //     在 1M 会话（current 'opus[1m]' has1mContext=true）经 resolveSkillModelOverride
        //     modelSupports1M 命中 opus-4-6 家族 → 顺延 [1m] → 'claude-opus-4-6[1m]'
        //     （CC SkillTool.ts:810-821 + model.ts:527-536）。
        assertThat(appState.get("mainLoopModel"))
                .as("model 覆盖经 resolveSkillModelOverride 顺延 [1m] (CC model.ts:527-536)")
                .isEqualTo("claude-opus-4-6[1m]");

        // (c) effort 注入 appState.effortValue (CC SkillTool.ts:823-836)
        assertThat(appState.get("effortValue"))
                .as("effort 注入 appState.effortValue (CC SkillTool.ts:823-836)")
                .isEqualTo("high");
    }

    @Test
    @DisplayName("无三件套的技能返回恒等 contextModifier, 不写 appState")
    void plainInlineSkill_noModifierSideEffects(@TempDir Path tempDir) throws Exception {
        // WHY: CC SkillTool.ts:775-839 contextModifier 对空 allowedTools/model/effort 恒等返回
        //   (不包装 getAppState / options) —— Java 对应: modifier 存在但无 setAppState 副作用。
        Path skillDir = tempDir.resolve("plain-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"),
                "---\n"
                + "name: plain-skill\n"
                + "description: 无三件套\n"
                + "---\n"
                + "# Plain\n\n正文\n");
        SkillToolImpl tool = new SkillToolImpl(new SkillRegistry(tempDir.toString()));
        Map<String, Object> appState = new ConcurrentHashMap<>();
        ToolUseContext tuc = appStateBoundTuc(appState);

        ToolResult<?> tr = (ToolResult<?>) tool.execute(inlineBlock("plain-skill"), tuc);
        assertThat(tr.contextModifier()).isNotNull();
        tr.contextModifier().apply(tuc);
        assertThat(appState)
                .as("无三件套技能 apply 后不写 appState (CC 恒等 modifier)")
                .isEmpty();
    }
}
