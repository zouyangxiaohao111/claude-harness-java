package com.nexusai.application.agent.subagent;

import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.prompt.GitStatusProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 子代理 env 详情单实现 · 对齐 CC {@code computeEnvInfo}
 * （CC original: {@code computeEnvInfo(modelId, additionalWorkingDirectories?)}
 * (Open-ClaudeCode/src/constants/prompts.ts:606-649)）。
 *
 * <p><b>目标（OPD-SP-18 / RES-SP18）</b>: 收敛 subagent 模块三处发散 env 渲染
 * （BuiltInAgents.buildSystemPrompt 内联旧 env 块 / 已删的
 * SubagentExecutor 自建 env 块 / 已删的 SubagentSystemPrompt）为<b>单实现</b>。
 * 本类是唯一 env 渲染点，BuiltInAgents 收敛调用本类。
 *
 * <p><b>CC 输出结构（prompts.ts:644-649）</b>:
 * <pre>
 * Here is useful information about the environment you are running in:
 * &lt;env&gt;
 * Working directory: {cwd}
 * Is directory a git repo: Yes|No
 * [Additional working directories: {a}, {b}]
 * Platform: {win32|darwin|linux|...}
 * Shell: {shell} [(use Unix shell syntax, not Windows ...)]
 * OS Version: {osName} {osVersion}
 * &lt;/env&gt;
 * {modelDescription}{knowledgeCutoffMessage}
 * </pre>
 * modelDescription（:619-628）= marketing 名存在 → {@code You are powered by the model named
 * {marketing}. The exact model ID is {modelId}.}，否则 → {@code You are powered by the model
 * {modelId}.}；knowledgeCutoffMessage（:636-638）= cutoff 存在 →
 * {@code \n\nAssistant knowledge cutoff is {cutoff}.}。
 *
 * <p><b>Java 偏差登记</b>:
 * <ul>
 *   <li><b>modelId 可空</b>: CC 的 {@code USER_TYPE==='ant' && isUndercover()} 分支（:621-623）
 *       抑制 modelDescription；Java 无 USER_TYPE 概念，当调用方无法提供 resolved model 时
 *       （resolved model 沿调用链 {@code getSystemPrompt(modelId)} → {@code buildSystemPrompt} →
 *       {@code computeEnvInfo(modelId, ...)} 逐调用显式传递，与 CC 一致无静态槽），
 *       modelId 为 null → 抑制 modelDescription + knowledgeCutoff（对齐 CC undercover 语义，
 *       不编造模型名）。</li>
 *   <li><b>cwd</b>: CC {@code getCwd()}（cwd.ts:26-32，per-async-context override）；Java 取
 *       {@code CwdResolution.getCwd(sessionId)}（对齐 CC 三层回落：override ?? sessionCwd ??
 *       boundProject ?? user.dir；sessionId 经 {@code computeEnvInfo(sessionId, ...)} 显式传参，
 *       无会话回落 user.dir 零行为变化）。</li>
 *   <li><b>isGit</b>: CC {@code getIsGit}（git.ts:218-229 → findGitRoot 沿 cwd 上溯找 .git）；
 *       Java 复用既有 {@link GitStatusProvider#isGit()}（同一 walk-up 语义，不新建通道）。</li>
 * </ul>
 */
public final class SubagentEnvInfo {

    private static final Logger log = LoggerFactory.getLogger(SubagentEnvInfo.class);

    private SubagentEnvInfo() {
    }

    /**
     * computeEnvInfo Java 等价 · 对齐 CC {@code computeEnvInfo}
     * （CC original: prompts.ts:606-649，最终 return :644-649）。
     *
     * <p><b>sessionId 显式传参（cwd-align-extended 方案2）</b>：CC {@code getCwd()}（prompts.ts:642）
     * 读 per-async-context override；Java 以 {@code CwdResolution.getCwd(sessionId)}（override ??
     * sessionCwd ?? boundProject ?? user.dir）对齐，sessionId 由调用方（BuiltInAgents 等）传
     * {@code RequestContext.sessionId()}。无会话（null）回落 user.dir，零行为变化。
     *
     * @param sessionId                  会话 ID（可 null；null → 回落 user.dir，对齐 CC getOriginalCwd 末端兜底）
     * @param modelId                    完整 model id（可 null；null → 抑制模型描述行）
     * @param additionalWorkingDirectories 附加工作目录路径列表（CC original: string[]，可 null）
     * @return env 详情块（含 {@code <env>} 结构 + modelDescription + knowledgeCutoff）
     */
    public static String computeEnvInfo(String sessionId, String modelId, List<String> additionalWorkingDirectories) {
        String cwd = currentWorkingDirectory(sessionId);
        boolean isGit = isGitRepository(cwd);
        String unameSR = unameSR();

        // CC :619-628 modelDescription（USER_TYPE='ant' && isUndercover 分支 Java 不适用 → 恒走 else）
        String modelDescription = "";
        if (modelId != null && !modelId.isBlank()) {
            String marketingName = marketingNameForModel(modelId);
            modelDescription = marketingName != null
                    ? "You are powered by the model named " + marketingName
                        + ". The exact model ID is " + modelId + "."
                    : "You are powered by the model " + modelId + ".";
        }

        // CC :631-634 additionalDirsInfo（末行带 \n，插在 Platform 行前）
        String additionalDirsInfo = "";
        if (additionalWorkingDirectories != null && !additionalWorkingDirectories.isEmpty()) {
            additionalDirsInfo = "Additional working directories: "
                    + String.join(", ", additionalWorkingDirectories) + "\n";
        }

        // CC :636-638 knowledgeCutoffMessage
        String knowledgeCutoffMessage = "";
        String cutoff = knowledgeCutoff(modelId);
        if (cutoff != null) {
            knowledgeCutoffMessage = "\n\nAssistant knowledge cutoff is " + cutoff + ".";
        }

        // CC :644-649 return
        StringBuilder sb = new StringBuilder();
        sb.append("Here is useful information about the environment you are running in:\n");
        sb.append("<env>\n");
        sb.append("Working directory: ").append(cwd).append("\n");
        sb.append("Is directory a git repo: ").append(isGit ? "Yes" : "No").append("\n");
        sb.append(additionalDirsInfo);
        sb.append("Platform: ").append(ccPlatform()).append("\n");
        sb.append(shellInfoLine()).append("\n");
        sb.append("OS Version: ").append(unameSR).append("\n");
        sb.append("</env>\n");
        sb.append(modelDescription);
        sb.append(knowledgeCutoffMessage);
        return sb.toString();
    }

    /**
     * CC {@code getCwd()} 等价（CC original: cwd.ts:26-32，prompts.ts:642）·
     * 对齐 {@link CwdResolution#getCwd(String)}（override ?? sessionCwd ?? boundProject ?? user.dir，反斜杠归一）。
     *
     * @param sessionId 会话 ID（null → 回落 user.dir，零行为变化）
     */
    private static String currentWorkingDirectory(String sessionId) {
        return Path.of(CwdResolution.getCwd(sessionId)).toString().replace('\\', '/');
    }

    /** CC {@code getIsGit()} 等价 · 复用既有通道 GitStatusProvider.isGit()（walk-up .git 目录/文件）。 */
    private static boolean isGitRepository(String cwd) {
        try {
            return new GitStatusProvider(Path.of(cwd)).isGit();
        } catch (RuntimeException e) {
            // CC getIsGit 不抛异常（findGitRoot 内部吞掉）；故障按非 git 处理，不阻断 env 块
            log.warn("[SubagentEnvInfo] isGit 判定失败，按非 git 处理: cwd={} err={}", cwd, e.getMessage());
            return false;
        }
    }

    /** CC {@code env.platform} 等价 · 对齐 SystemPromptSections.ccPlatform（prompts.ts:702）。 */
    private static String ccPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase();
        if (os.contains("win")) {
            return "win32";
        }
        if (os.contains("mac")) {
            return "darwin";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return os;
    }

    /** CC {@code getShellInfoLine()} 等价（CC original: prompts.ts:732-743）。 */
    private static String shellInfoLine() {
        String shell = System.getenv("SHELL");
        if (shell == null || shell.isBlank()) {
            shell = "unknown";
        }
        String shellName = shell.contains("zsh") ? "zsh"
                : shell.contains("bash") ? "bash" : shell;
        if ("win32".equals(ccPlatform())) {
            return "Shell: " + shellName
                    + " (use Unix shell syntax, not Windows — e.g., /dev/null not NUL, forward slashes in paths)";
        }
        return "Shell: " + shellName;
    }

    /**
     * CC {@code getUnameSR()} 等价（CC original: getUnameSR()，Open-ClaudeCode/src/constants/prompts.ts:745-756）。
     *
     * <p><b>CC 真实差异（:746-749）</b>:
     * <ul>
     *   <li><b>win32</b>（:746-747）: {@code os.version() + ' ' + os.release()} —— 第一 token 为<b>友好名</b>
     *       （{@code os.version()} 经 GetVersionExW/RtlGetVersion 返回 "Windows 11 Pro"），第二 token 为版本号；</li>
     *   <li><b>POSIX</b>（:748-749）: {@code os.type() + ' ' + os.release()} —— 第一 token 为<b>内核类型</b>
     *       （uname(3) 的 "Darwin"/"Linux"），第二 token 为内核版本。</li>
     * </ul>
     *
     * <p><b>Java 单实现理由（RES-SP18-2）</b>: Java 无 {@code os.type()} 直接等价，两个第一 token 均由
     * {@code System.getProperty("os.name")} 承载（Windows 上 {@code os.name}="Windows 11" = 友好名代理；
     * POSIX 上 {@code os.name}="Linux"/"Mac OS X" = 类型名代理）；{@code os.version} 代理 {@code os.release()}。
     * 因此 win32/POSIX 两分支在 Java 产出字节一致 —— 旧实现为<b>死条件分支</b>（误导读者以为存在平台差异）。
     * 本方法统一为<b>单表达式</b>渲染（与 {@code SystemPromptSections.ccOsVersion()} 同一形式），语义由
     * JVM 按平台设置 {@code os.name} 自动区分，不编造任何系统信息。空版本 → 仅输出名称（避免尾随空格）。
     *
     * @return OS 名称 + 版本（如 Windows "Windows 11 10.0" / Linux "Linux 6.6.4" / macOS "Mac OS X 15.0"）
     */
    private static String unameSR() {
        String osName = System.getProperty("os.name", "unknown");
        String osVersion = System.getProperty("os.version", "");
        return (osVersion == null || osVersion.isEmpty()) ? osName : osName + " " + osVersion;
    }

    /**
     * CC {@code getMarketingNameForModel} 等价（CC original: model.ts:570-614）。
     *
     * <p><b>双消费者（同源复用）</b>：本类（子代理通道 {@code computeEnvInfo}）+ 主循环
     * {@code SystemPromptSections.envInfoSimpleCompute}（主 env 通道）共用同一映射，
     * 消除两通道 marketing 名不一致（G9 关闭，探查 EVD-ENV-08 逐档核验）。
     *
     * @param modelId 完整 model id
     * @return marketing 名；无映射 → null（调用方走 {@code You are powered by the model {modelId}.} 兜底）
     */
    public static String marketingNameForModel(String modelId) {
        String lower = modelId.toLowerCase();
        boolean has1m = lower.contains("[1m]");
        String canonical = canonicalName(modelId);

        if (canonical.contains("claude-opus-4-6")) {
            return has1m ? "Opus 4.6 (with 1M context)" : "Opus 4.6";
        }
        if (canonical.contains("claude-opus-4-5")) {
            return "Opus 4.5";
        }
        if (canonical.contains("claude-opus-4-1")) {
            return "Opus 4.1";
        }
        if (canonical.contains("claude-opus-4")) {
            return "Opus 4";
        }
        if (canonical.contains("claude-sonnet-4-6")) {
            return has1m ? "Sonnet 4.6 (with 1M context)" : "Sonnet 4.6";
        }
        if (canonical.contains("claude-sonnet-4-5")) {
            return has1m ? "Sonnet 4.5 (with 1M context)" : "Sonnet 4.5";
        }
        if (canonical.contains("claude-sonnet-4")) {
            return has1m ? "Sonnet 4 (with 1M context)" : "Sonnet 4";
        }
        if (canonical.contains("claude-3-7-sonnet")) {
            return "Claude 3.7 Sonnet";
        }
        if (canonical.contains("claude-3-5-sonnet")) {
            return "Claude 3.5 Sonnet";
        }
        if (canonical.contains("claude-haiku-4-5")) {
            return "Haiku 4.5";
        }
        if (canonical.contains("claude-3-5-haiku")) {
            return "Claude 3.5 Haiku";
        }
        return null;
    }

    /**
     * CC {@code getCanonicalName} / {@code firstPartyNameToCanonical} 等价
     * （CC original: model.ts:217-277，有序 contains 检查 + 正则兜底）。
     *
     * <p><b>双消费者（同源复用）</b>：子代理通道 + 主循环 {@code SystemPromptSections}
     * （marketing 名/knowledgeCutoff 判定前置 canonical 化）共用，两通道一致。
     *
     * @param modelId 完整 model id
     * @return canonical 短名；无匹配 → 原 modelId
     */
    public static String canonicalName(String modelId) {
        String name = modelId.toLowerCase();
        if (name.contains("claude-opus-4-6")) {
            return "claude-opus-4-6";
        }
        if (name.contains("claude-opus-4-5")) {
            return "claude-opus-4-5";
        }
        if (name.contains("claude-opus-4-1")) {
            return "claude-opus-4-1";
        }
        if (name.contains("claude-opus-4")) {
            return "claude-opus-4";
        }
        if (name.contains("claude-sonnet-4-6")) {
            return "claude-sonnet-4-6";
        }
        if (name.contains("claude-sonnet-4-5")) {
            return "claude-sonnet-4-5";
        }
        if (name.contains("claude-sonnet-4")) {
            return "claude-sonnet-4";
        }
        if (name.contains("claude-haiku-4-5")) {
            return "claude-haiku-4-5";
        }
        if (name.contains("claude-3-7-sonnet")) {
            return "claude-3-7-sonnet";
        }
        if (name.contains("claude-3-5-sonnet")) {
            return "claude-3-5-sonnet";
        }
        if (name.contains("claude-3-5-haiku")) {
            return "claude-3-5-haiku";
        }
        if (name.contains("claude-3-opus")) {
            return "claude-3-opus";
        }
        if (name.contains("claude-3-sonnet")) {
            return "claude-3-sonnet";
        }
        if (name.contains("claude-3-haiku")) {
            return "claude-3-haiku";
        }
        Matcher m = CLAUDE_PREFIX_PATTERN.matcher(name);
        if (m.find() && m.group(0) != null) {
            return m.group(0);
        }
        return modelId;
    }

    /** CC {@code getKnowledgeCutoff} 等价（CC original: prompts.ts:713-730）。 */
    public static String knowledgeCutoff(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return null;
        }
        String canonical = canonicalName(modelId);
        if (canonical.contains("claude-sonnet-4-6")) {
            return "August 2025";
        }
        if (canonical.contains("claude-opus-4-6")) {
            return "May 2025";
        }
        if (canonical.contains("claude-opus-4-5")) {
            return "May 2025";
        }
        if (canonical.contains("claude-haiku-4")) {
            return "February 2025";
        }
        if (canonical.contains("claude-opus-4") || canonical.contains("claude-sonnet-4")) {
            return "January 2025";
        }
        return null;
    }

    /** CC firstPartyNameToCanonical 正则兜底（model.ts:275）· {@code claude-(\d+-\d+-)?\w+}。 */
    private static final Pattern CLAUDE_PREFIX_PATTERN = Pattern.compile("claude-(\\d+-\\d+-)?\\w+");
}
