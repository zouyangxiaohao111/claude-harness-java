package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.tool.impl.SubagentExecutor;
import com.nexusai.common.SessionProjectRoot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * [RES-SP18] subagent env 渲染单实现测试 · 对齐 CC {@code computeEnvInfo}
 * （prompts.ts:606-649）+ {@code enhanceSystemPromptWithEnvDetails} 组装（:760-791）。
 *
 * <p><b>WHY</b>（CLAUDE.md 规则九 · 测试验证意图）: OPD-SP-18 要求 subagent 模块三处发散 env 渲染
 * （BuiltInAgents 内联 {@code Environment: Java ...} / 已删的 SubagentExecutor 自建块 /
 * 已删的 SubagentSystemPrompt）收敛为<b>单实现</b> {@link SubagentEnvInfo#computeEnvInfo}。
 * env 块是 LLM 对 cwd/git/platform/shell/OS/model 的认知来源，其结构必须严格对齐 CC:
 * <ul>
 *   <li><b>&lt;env&gt; 块结构</b>（:641-648）：Working directory / Is directory a git repo /
 *       [Additional working directories] / Platform / Shell / OS Version；</li>
 *   <li><b>modelDescription</b>（:619-628）：marketing 名存在 → {@code named {marketing}. The exact
 *       model ID is {modelId}.}，否则 {@code You are powered by the model {modelId}.}；</li>
 *   <li><b>knowledgeCutoff</b>（:713-731）：{@code \n\nAssistant knowledge cutoff is {cutoff}.}；</li>
 *   <li><b>收敛无残留</b>：BuiltInAgents.buildSystemPrompt 输出不得含旧 {@code Environment: Java} 发散行
 *       （grep 生产 0 命中）。</li>
 * </ul>
 */
@DisplayName("[RES-SP18] SubagentEnvInfo.computeEnvInfo 单实现（对齐 CC prompts.ts:606-649）")
class SubagentEnvInfoTest {

    @Test
    @DisplayName("computeEnvInfo: <env> 块六行结构 + modelDescription(marketing) + knowledgeCutoff")
    void computeEnvInfo_hasEnvBlockStructure_withMarketingName() {
        // GIVEN: 已知 marketing 名映射 model（CC getMarketingNameForModel model.ts:570-620）
        String text = SubagentEnvInfo.computeEnvInfo(null, "claude-sonnet-4-6", List.of("/extra/a", "/extra/b"));

        assertThat(text).as("引导句（:606）").startsWith("Here is useful information about the environment you are running in:");
        assertThat(text).as("<env> 块开（:641）").contains("\n<env>\n");
        assertThat(text).as("Working directory 行（:642）").contains("Working directory: ");
        assertThat(text).as("Is directory a git repo 行（:643，Yes/No 两值）").containsPattern("Is directory a git repo: (Yes|No)");
        assertThat(text).as("Additional working directories 行（:631-633，路径 join ', '）")
            .contains("Additional working directories: /extra/a, /extra/b");
        assertThat(text).as("Platform 行（:645）").contains("Platform: ");
        assertThat(text).as("Shell 行（:646，getShellInfoLine :732-743）").contains("Shell: ");
        assertThat(text).as("OS Version 行（:647，getUnameSR :745-756）").contains("OS Version: ");
        assertThat(text).as("</env> 闭（:648）").contains("\n</env>\n");
        assertThat(text).as("modelDescription marketing 名（:624-626）")
            .contains("You are powered by the model named Sonnet 4.6. The exact model ID is claude-sonnet-4-6.");
        assertThat(text).as("knowledgeCutoff（:713-731 claude-sonnet-4-6 → August 2025）")
            .contains("\n\nAssistant knowledge cutoff is August 2025.");
    }

    @Test
    @DisplayName("computeEnvInfo: 未知 modelId → 兜底 You are powered by the model {modelId}.")
    void computeEnvInfo_unknownModel_usesFallbackDescription() {
        // WHY: CC :626-627 无 marketing 名 → `You are powered by the model {modelId}.`
        String text = SubagentEnvInfo.computeEnvInfo(null, "gpt-4", null);

        assertThat(text)
            .as("未知 model → 兜底行（:626-627）")
            .contains("You are powered by the model gpt-4.");
        assertThat(text).as("未知 model 无 knowledgeCutoff").doesNotContain("knowledge cutoff is");
    }

    @Test
    @DisplayName("computeEnvInfo: 无附加目录 → 无 Additional working directories 行")
    void computeEnvInfo_noAdditionalDirs_omitsDirsLine() {
        String text = SubagentEnvInfo.computeEnvInfo(null, "claude-sonnet-4-6", List.of());
        assertThat(text).as("空附加目录 → 不产出 Additional 行（:631 条件）")
            .doesNotContain("Additional working directories:");
    }

    @Test
    @DisplayName("computeEnvInfo: modelId null → 抑制模型描述行（CC undercover 语义）")
    void computeEnvInfo_nullModel_suppressesDescription() {
        String text = SubagentEnvInfo.computeEnvInfo(null, null, List.of());
        assertThat(text).as("null model → 无模型描述行（Java 无 resolved model 时诚实抑制）")
            .doesNotContain("You are powered by the model");
    }

    @Test
    @DisplayName("computeEnvInfo: Working directory = CwdResolution.getCwd(sessionId)（CC prompts.ts:642 getCwd），会话绑 P ≠ user.dir 时非恒 user.dir")
    void computeEnvInfo_workingDir_usesSessionCwd(@TempDir Path projectDir) throws Exception {
        // WHY (cwd-align-extended subagent #7 · 对齐 CC computeEnvInfo 内 getCwd()): CC env 块
        // Working directory = getCwd()（prompts.ts:642，per-async-context override 优先）。Java 方案2
        // 以 CwdResolution.getCwd(sessionId) 对齐（override ?? sessionCwd ?? boundProject ?? user.dir）。
        // 旧实现恒 System.getProperty("user.dir") → 会话绑 P ≠ user.dir 时子代理 env 显示进程 cwd
        // 而非会话/worktree cwd（误导子代理 + 权限相对路径基准错位）。测试锁定：sessionId 绑 P →
        // Working directory 行 = CwdResolution.getCwd(sessionId) = P（非 user.dir）。
        SessionProjectRoot.setForSession("sess-cwd-wt1", projectDir.toString());
        try {
            String text = SubagentEnvInfo.computeEnvInfo("sess-cwd-wt1", null, List.of());
            String expected = CwdResolution.getCwd("sess-cwd-wt1").replace('\\', '/');
            assertThat(workingDirLineValue(text))
                .as("Working directory 行 = 会话 cwd（对齐 CC getCwd，非恒 user.dir）")
                .isEqualTo(expected);
        } finally {
            SessionProjectRoot.reset();
        }
    }

    @Test
    @DisplayName("computeEnvInfo: sessionId null → Working directory 回落 user.dir（零行为变化，对齐 CC getCwd catch → getOriginalCwd）")
    void computeEnvInfo_nullSession_fallsBackUserDir() throws Exception {
        // WHY (cwd-align-extended 零行为变化红线 · 对齐 CC cwd.ts:26-32 getCwd catch → getOriginalCwd):
        // 无会话上下文（startup / 无 sessionId 通道 / 测试）时 CwdResolution.getCwd(null) 回落 user.dir
        // （L2/L3 守卫），与旧实现 System.getProperty("user.dir") 等价 → 接线零回归，不破坏既有渲染。
        String text = SubagentEnvInfo.computeEnvInfo(null, null, List.of());
        String expected = Path.of(System.getProperty("user.dir")).toRealPath().toString().replace('\\', '/');
        assertThat(workingDirLineValue(text))
            .as("无会话 → Working directory = user.dir（零行为变化）")
            .isEqualTo(expected);
    }

    @Test
    @DisplayName("BuiltInAgents.buildSystemPrompt 收敛：无旧 'Environment: Java' 发散行，含 <env> 单实现输出")
    void builtInAgentPrompt_converged_noLegacyEnvLine() {
        // WHY（OPD-SP-18 核心验收）: 三处发散收敛为单实现，BuiltInAgents 内联 `Environment: Java ... on ...`
        // 发散块必须消失（grep 'Environment: Java' 生产 0 命中），由 SubagentEnvInfo.computeEnvInfo 替代。
        String prompt = BuiltInAgents.GENERAL_PURPOSE_AGENT.getSystemPrompt(null, List.of());

        assertThat(prompt).as("旧发散行已删（验收 #3/#4）").doesNotContain("Environment: Java");
        assertThat(prompt).as("env 块由单实现注入（含 <env> 结构）").contains("<env>");
        assertThat(prompt).as("组装顺序 [DEFAULT_AGENT_PROMPT, agentSpecific?, notes, envInfo]")
            .contains("You are an agent for Claude Code")
            .contains("Notes:")
            .contains("Here is useful information about the environment you are running in:");
    }

    @Test
    @DisplayName("BuiltInAgents.buildSystemPrompt 输出顺序：notes 在 env 之前（REQ-SP18-2）")
    void builtInAgentPrompt_notesBeforeEnv() {
        String prompt = BuiltInAgents.STATUSLINE_SETUP_AGENT.getSystemPrompt(null, List.of());
        assertThat(prompt.indexOf("Notes:")).as("notes 在 env 之前")
            .isLessThan(prompt.indexOf("Here is useful information"));
    }

    @Test
    @DisplayName("SubagentExecutor.buildAgentSystemPrompt 接线 effectiveModel → env 块 modelDescription（R2-ENVINFO）")
    void executorWiresEffectiveModel_intoEnvBlock() throws Exception {
        // WHY (R2-ENVINFO · 对齐 CC enhanceSystemPromptWithEnvDetails): CC 恒以 resolvedAgentModel
        // （runAgent.ts:340）逐调用显式传参 computeEnvInfo，built-in 子代理 env 块恒含 modelDescription
        // 行（prompts.ts:624-627）。Java 端 effectiveModel 经 getSystemPrompt(modelId) 显式传参（无静态槽），
        // built-in getSystemPrompt 直接读到真实模型名。
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "claude-sonnet-4-6", "fallback");
        Method m = SubagentExecutor.class.getDeclaredMethod("buildAgentSystemPrompt",
            boolean.class, AgentDefinition.class, List.class, String.class, List.class, String.class);
        m.setAccessible(true);
        String prompt = (String) m.invoke(executor,
            false, BuiltInAgents.GENERAL_PURPOSE_AGENT, List.of(), "claude-sonnet-4-6", List.of(), null);

        assertThat(prompt).as("env 块含真实模型名 modelDescription（marketing 名 → named 形式）")
            .contains("You are powered by the model named Sonnet 4.6. The exact model ID is claude-sonnet-4-6.");
        assertThat(prompt).as("内置 agent 基本结构保留（DEFAULT_AGENT_PROMPT 前缀）")
            .startsWith("You are an agent for Claude Code");
    }

    @Test
    @DisplayName("SubagentExecutor.buildAgentSystemPrompt: effectiveModel null/blank → 抑制 modelDescription（对齐 CC undercover，不编造）")
    void executorWiresNullModel_suppressesDescription() throws Exception {
        // WHY (R2-ENVINFO · 对齐 CC :621-623 undercover 抑制): 模型名不可得（null/blank）时
        // 不得编造模型名 → env 块无 modelDescription 行；effectiveModel 显式传参为 null → 抑制。
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, null, "fallback");
        Method m = SubagentExecutor.class.getDeclaredMethod("buildAgentSystemPrompt",
            boolean.class, AgentDefinition.class, List.class, String.class, List.class, String.class);
        m.setAccessible(true);
        String prompt = (String) m.invoke(executor,
            false, BuiltInAgents.GENERAL_PURPOSE_AGENT, List.of(), null, List.of(), null);

        assertThat(prompt).as("null effectiveModel → 无 modelDescription 行")
            .doesNotContain("You are powered by the model");
    }

    @Test
    @DisplayName("SubagentExecutor.buildAgentSystemPrompt 接线 additionalWorkingDirectories → env 块（R32-04）")
    void executorWiresAdditionalWorkingDirectories_intoEnvBlock() throws Exception {
        // WHY (R32-04 · 对齐 CC runAgent.ts:504-518): CC 在 runAgent 内
        //   Array.from(appState.toolPermissionContext.additionalWorkingDirectories.keys())
        //   逐调用下传 getAgentSystemPrompt → enhanceSystemPromptWithEnvDetails →
        //   computeEnvInfo(modelId, additionalWorkingDirectories)，env 块渲染
        //   "Additional working directories: {a}, {b}" 行（prompts.ts:631-633）。
        //   Java 旧实现 BuiltInAgents.buildSystemPrompt 恒 computeEnvInfo(modelId, List.of())
        //   —— dirs 在 Step 7 已从 agentTuc.additionalWorkingDirectories() 取出却从未下传。
        //   本测试（RED→GREEN）锁定：附加目录经 buildAgentSystemPrompt 显式下传，
        //   built-in 子代理 env 块恒含 Additional working directories 行。
        SubagentExecutor executor = new SubagentExecutor(
            null, null, null, null, null, "claude-sonnet-4-6", "fallback");
        Method m = SubagentExecutor.class.getDeclaredMethod("buildAgentSystemPrompt",
            boolean.class, AgentDefinition.class, List.class, String.class, List.class, String.class);
        m.setAccessible(true);
        String prompt = (String) m.invoke(executor,
            false, BuiltInAgents.GENERAL_PURPOSE_AGENT, List.of(), "claude-sonnet-4-6",
            List.of("/extra/a", "/extra/b"), null);

        assertThat(prompt).as("附加目录经显式传参下传 computeEnvInfo，env 块含 Additional working directories 行")
            .contains("Additional working directories: /extra/a, /extra/b");
    }

    @Test
    @DisplayName("unameSR 单路径无平台死分支（RED：实施前含 win32 条件分支）")
    void unameSR_singlePath_noDeadPlatformBranch() throws IOException {
        // WHY (RES-SP18-2 · 对齐 CC getUnameSR prompts.ts:745-756): CC win32 分支输出
        // os.version()+os.release()（友好名 "Windows 11 Pro"），POSIX 分支输出 os.type()+os.release()
        // （内核类型 "Darwin"/"Linux"）——两平台**真实差异**。Java 无 os.type() 直接等价，两个第一 token
        // 均由 System.getProperty("os.name") 承载（Windows 上 os.name=友好名，POSIX 上 os.name=类型名），
        // 故旧实现 win32/POSIX 两分支字节相同 = 误导读者的**死分支**。统一为单表达式后，方法体不得再
        // 含 ccPlatform() 平台条件（AC1/AC5：win32 分支要么消失要么有真实差异）。
        // 结构守卫：读取源码提取 unameSR 方法体（模式同 LlmAgentLoopWiringOrderTest:140）。
        String body = unameSRMethodBody();
        assertThat(body).as("unameSR 单实现：不得再按平台分支（无 ccPlatform 调用）").doesNotContain("ccPlatform");
        assertThat(body).as("unameSR 单实现：不得含 if 条件分支").doesNotContain("if (");
    }

    @Test
    @DisplayName("unameSR 输出 = os.name + os.version（CC 真实差异的 Java 代理契约，空版本无尾随空格）")
    void unameSR_output_isExactOsNamePlusVersion() {
        // WHY (RES-SP18-2 · 对齐 CC getUnameSR): CC 名称 token 恒为平台真实值（win32 友好名 / POSIX 内核类型），
        // 绝无编造。Java 代理 = os.name（两平台真实值）+ os.version（≈ os.release）。测试锁定：
        // 名称 token 恰为 System.getProperty("os.name")（不允许 toLowerCase/硬编码映射），
        // 版本 token 恰为 os.version，空版本 → 仅名称无尾随空格。
        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "");
        String expected = (osVersion == null || osVersion.isEmpty()) ? osName : osName + " " + osVersion;

        String value = osVersionLineValue(SubagentEnvInfo.computeEnvInfo(null, null, List.of()));
        assertThat(value).as("OS Version 值 = os.name + ' ' + os.version（名称 token 恰为 os.name，无编造）")
            .isEqualTo(expected);
    }

    /** 从 <env> 块提取 {@code OS Version: } 行的值（CC prompts.ts:647，unameSR :745-756）。 */
    private static String osVersionLineValue(String envText) {
        for (String line : envText.split("\n")) {
            if (line.startsWith("OS Version: ")) {
                return line.substring("OS Version: ".length());
            }
        }
        throw new AssertionError("env 块缺失 OS Version 行: " + envText);
    }

    /** 从 <env> 块提取 {@code Working directory: } 行的值（CC prompts.ts:642 getCwd）。 */
    private static String workingDirLineValue(String envText) {
        for (String line : envText.split("\n")) {
            if (line.startsWith("Working directory: ")) {
                return line.substring("Working directory: ".length());
            }
        }
        throw new AssertionError("env 块缺失 Working directory 行: " + envText);
    }

    @Test
    @DisplayName("SubagentEnvInfo 无静态模型槽：model 经 computeEnvInfo 参数显式传递（R2-ENVINFO）")
    void subagentEnvInfo_hasNoStaticModelSlot() throws IOException {
        // WHY (R2-ENVINFO · 对齐 CC 模型逐调用传递): CC 无进程级/线程级静态模型槽
        //   （runAgent.ts:340 resolvedAgentModel 为局部变量，经 getAgentSystemPrompt →
        //   enhanceSystemPromptWithEnvDetails → computeEnvInfo(modelId, ...) 参数显式传递）。
        //   Java 旧实现 DEFAULT_MODEL_ID 为 ThreadLocal 静态槽（RES-C1 曾由 static volatile 改
        //   ThreadLocal，但仍是静态槽，跨会话串台窗口只是缩小、未从根源消除）——与本 session
        //   对齐目标相悖。本测试为 RED→GREEN 结构守卫：实施前源码含 ThreadLocal/setDefaultModelId
        //   → 断言失败（RED）；实施后删除静态槽 → 通过（GREEN）。
        String source = readSubagentEnvInfoSource();
        assertThat(source).as("ThreadLocal 静态模型槽已删（CC 无进程级静态槽，模型逐调用传参）")
            .doesNotContain("ThreadLocal");
        assertThat(source).as("setDefaultModelId 静态注入槽已删").doesNotContain("setDefaultModelId");
        assertThat(source).as("defaultModelId 静态读槽已删").doesNotContain("defaultModelId");
    }

    /** 读取 {@code SubagentEnvInfo.java} 源码全文（结构守卫，模式同 unameSRMethodBody）。 */
    private static String readSubagentEnvInfoSource() throws IOException {
        Path src = Path.of("src/main/java/com/nexusai/application/agent/subagent/SubagentEnvInfo.java");
        return Files.readString(src);
    }

    /** 提取 {@code unameSR()} 方法体（含大括号）· 结构守卫读取源码（模式同 LlmAgentLoopWiringOrderTest）。 */
    private static String unameSRMethodBody() throws IOException {
        Path src = Path.of("src/main/java/com/nexusai/application/agent/subagent/SubagentEnvInfo.java");
        String source = Files.readString(src);
        int idx = source.indexOf("private static String unameSR()");
        if (idx < 0) {
            throw new AssertionError("未找到 unameSR 方法");
        }
        int open = source.indexOf('{', idx);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unameSR 方法体未闭合");
    }
}
