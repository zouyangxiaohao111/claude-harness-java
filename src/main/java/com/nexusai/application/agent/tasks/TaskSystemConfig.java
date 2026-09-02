package com.nexusai.application.agent.tasks;

import com.nexusai.application.agent.skill.NexusaiPaths;
import com.nexusai.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.function.Supplier;

/**
 * 任务系统配置 · 对齐 CC tasks.ts:133-139 isTodoV2Enabled() + utils/agentSwarmsEnabled.ts + utils/teammate.ts
 *
 * <p>控制使用 TodoWrite (V1) 还是 Task System (V2)。
 *
 * <h2>CC 逻辑映射</h2>
 * <table>
 *   <tr><th>CC 函数</th><th>CC 文件</th><th>Java 方法</th></tr>
 *   <tr><td>{@code isTodoV2Enabled()}</td><td>tasks.ts:133-139</td><td>{@link #isTodoV2Enabled()}</td></tr>
 *   <tr><td>{@code getTaskListId()}</td><td>tasks.ts:199-210</td><td>{@link #getDefaultTaskListId()}</td></tr>
 *   <tr><td>{@code getClaudeConfigHomeDir()}</td><td>envUtils.ts:7-14</td><td>{@link #getClaudeConfigHomeDir()}</td></tr>
 *   <tr><td>{@code isAgentSwarmsEnabled()}</td><td>utils/agentSwarmsEnabled.ts</td><td>{@link #isAgentSwarmsEnabled()}</td></tr>
 *   <tr><td>{@code getAgentName()}</td><td>utils/teammate.ts</td><td>{@link #getAgentName()}</td></tr>
 *   <tr><td>{@code getTeamName()}</td><td>utils/teammate.ts</td><td>{@link #getTeamName()}</td></tr>
 * </table>
 *
 * <h2>V1 vs V2 互斥</h2>
 * <p>对齐 CC：TodoWrite (V1) 和 Task System (V2) 互斥使用。
 * <ul>
 *   <li>{@code isTodoV2Enabled() == true} → TaskCreate/TaskGet/TaskList/TaskUpdate 启用</li>
 *   <li>{@code isTodoV2Enabled() == false} → TodoWrite 启用</li>
 * </ul>
 *
 * <h2>介质与默认值差异（CC vs Java）· isTodoV2Enabled-medium</h2>
 * <p><b>本项仅文档化差异，不改行为（docOnly）。</b>以下差异逐行对齐 CC 实际 TS 源码
 * （tasks.ts:133-139 / state.ts:1057-1059 / state.ts:300 / main.tsx:802-812 / envUtils.ts:32-37），
 * 每一项均以 CC 源码行为为准（CC 注释仅作背景，见 {@link #isTodoV2Enabled()} Javadoc）。</p>
 * <table>
 *   <tr><th>维度</th><th>CC（TypeScript）</th><th>Java（本类）</th></tr>
 *   <tr><td>启用开关介质</td><td>env var {@code CLAUDE_CODE_ENABLE_TASKS}（tasks.ts:135）</td><td>JVM sysprop {@code nexusai.tasks.enabled}（:66）</td></tr>
 *   <tr><td>真值判定</td><td>{@code isEnvTruthy()}：接受 {@code 1/true/yes/on}，lowercase+trim（envUtils.ts:32-37）</td><td>{@link #isEnvTruthy(String)}（:310-317），接受集合一致</td></tr>
 *   <tr><td>交互式判定</td><td>{@code getIsNonInteractiveSession() = !STATE.isInteractive}（state.ts:1057-1059）</td><td>{@link #isInteractive()}（:157-163）</td></tr>
 *   <tr><td>交互式默认值</td><td>{@code STATE.isInteractive = false}（state.ts:300）→ 默认非交互 → V1 TodoWrite</td><td><b>决策 #65</b>：Web 请求路径（RequestContext 有 reqId）默认交互 → V2；cron/后台/无 MDC 默认非交互 → V1（见 {@link #isInteractive()}）</td></tr>
 *   <tr><td>交互式设置时机</td><td>进程启动一次：main.tsx:802-812 按 CLI 参数（{@code -p/--print}、{@code --init-only}、{@code --sdk-url}）与 {@code !process.stdout.isTTY} 计算</td><td>Web 请求路径（ChatService.processUserMessage 设 sessionId+reqId）判定；cron/后台仅设 sessionId</td></tr>
 * </table>
 *
 * <p><b>默认值对齐（决策 #65，2026-08-23 用户拍板）</b>：CC CLI 的 {@code STATE.isInteractive}
 * 在交互式终端默认 true（TTY），非交互（SDK/headless）默认 false；Java Web 后端以「有前端用户」为
 * 交互式判据：ChatService.processUserMessage（Web 请求路径）经 {@code RequestContext.set} 设
 * sessionId + reqId → 交互 → {@code isTodoV2Enabled()==true} → Task V2 默认开；cron/后台
 * （CronIdleExecutor/RemoteAgentTaskService/PartialCompactService 仅 {@code setSession} 设
 * sessionId）→ 非交互 → V1 TodoWrite。默认装配经 ToolRegistry.getTools / TodoWriteTool.isEnabled /
 * AbstractTaskTool.isEnabled 生效（isTodoV2Enabled upstream CRITICAL，d=1 直接调用方 3 个：
 * AbstractTaskTool.isEnabled / TodoWriteTool.isEnabled / ToolRegistrationConfig.todoTaskTools）。
 * 显式设置 {@code nexusai.tasks.enabled=true} 或 {@code nexusai.interactive=true} 时仍可强制 V2。</p>
 *
 * <p><b>跨端共享（待产品决策）</b>：CC env 名 {@code CLAUDE_CODE_ENABLE_TASKS} 与 Java sysprop
 * {@code nexusai.tasks.enabled} 介质不同。若未来要求 CLI 与后端读同一配置源，需主 agent 决策是否在
 * Java 侧额外兼容读取 CC 同名 env 变量（类似既有 {@code CLAUDE_CODE_TASK_LIST_ID} ↔
 * {@code nexusai.taskListId} 双轨，见 TaskService.java:130-144）。本项仅文档化，未落代码。</p>
 *
 * <p><b>交互式判定粒度（决策 #65 已落地）</b>：CC 的 {@code STATE.isInteractive} 是进程级、启动时
 * 一次设定（main.tsx:802-812）；Java {@link #isInteractive()} 以 RequestContext MDC 的
 * {@code reqId} 存在性区分「Web 前端用户消息在途」vs「cron/后台」，满足「Java Web 后端会话
 * （有前端用户）应视为交互 → todoV2 默认开」的用户拍板决策。</p>
 */
public class TaskSystemConfig {

    // ════════════════════════════════════════════════════════════════════════
    // 系统属性常量
    // ════════════════════════════════════════════════════════════════════════

    private static final String ENABLE_TASKS_PROPERTY = "nexusai.tasks.enabled";
    private static final String INTERACTIVE_PROPERTY = "nexusai.interactive";
    /** agent-swarms opt-in env 的 sysprop-override seam · CC 原名: CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS (agentSwarmsEnabled.ts:32) */
    private static final String AGENT_TEAMS_OPTIN_PROPERTY = "nexusai.experimental.agent-teams";
    /** --agent-teams flag 的 sysprop 映射 · CC 原名: process.argv 含 '--agent-teams' (agentSwarmsEnabled.ts:10-11)，Java 无 argv 解析，部署侧约定以该 sysprop 映射 */
    private static final String AGENT_TEAMS_FLAG_PROPERTY = "nexusai.agent-teams";
    /** swarms killswitch sysprop 等价项 · CC 原名: GrowthBook getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_flint', true) (agentSwarmsEnabled.ts:39)；Java 无 GrowthBook 基础设施，置 true 模拟 killswitch 关闭（外部用户禁用 swarms），缺省 false=通过 */
    private static final String SWARMS_KILLSWITCH_PROPERTY = "nexusai.swarms.killswitch";

    /** settings.agentSwarmsEnabled 静态覆盖（前端「环境配置」开关 → SettingsService.get/update 同步）。
     *  默认 null = 未配置不覆盖（维持 CC 原 opt-in/killswitch 链）；true = 额外 opt-in 源；false = 不额外放行。
     *  [agent-swarms-global] 生产已由 {@link #agentSwarmsSettingsSource} 实时 DB 读源替代权威判定，
     *  本标志降级为 POJO/兼容测试 seam（source 未安装时回落）。 */
    private static volatile Boolean agentSwarmsSettingsOverride;

    /**
     * [agent-swarms-global] settings.agentSwarmsEnabled 实时 DB 读源（全局单例，每次调用实时读 DB）。
     * SettingsService @PostConstruct 注入（对齐 ModelNameResolver.installTierSources 静态 volatile Supplier）。
     * 默认 null（未安装）= 维持 CC 原 opt-in/killswitch 链；安装后为生产权威源（覆盖 override 测试 seam）。
     */
    private static volatile Supplier<Boolean> agentSwarmsSettingsSource;

    /**
     * [agent-swarms-setting V42] 同步 settings.agentSwarmsEnabled 静态覆盖标志（前端设置页「环境配置」
     * Agent Swarms 开关 → DB 列 V42 → get/update 写库后同步）。null = 未配置不覆盖（维持 CC 原链）。
     * [agent-swarms-global] 生产权威源为 {@link #installAgentSwarmsSettingsSource} 实时 DB 读源（source
     * 优先），本标志仅在 source 未安装（POJO 测试）时回落生效。
     */
    public static void setAgentSwarmsSettingsOverride(Boolean enabled) {
        agentSwarmsSettingsOverride = enabled;
        if (log.isDebugEnabled()) {
            log.debug("setAgentSwarmsSettingsOverride：settings.agentSwarmsEnabled={}（null=未配置不覆盖；前端设置页写入/读取时同步）", enabled);
        }
    }

    /**
     * [agent-swarms-global] 安装 settings.agentSwarmsEnabled 实时 DB 读源 · 对齐 ModelNameResolver.installTierSources
     * （ModelNameResolver.java:80-84 静态 volatile Supplier 注入）。每次 source.get() 实时读 DB 全局单例，
     * 换会话/换请求不依赖 get/update 触发刷新。null 参数不覆盖既有 source（与 installTierSources null-guard 一致）。
     *
     * @param source 每次调用返回 settings.agentSwarmsEnabled（Boolean；null → 未配置不覆盖）
     */
    public static void installAgentSwarmsSettingsSource(Supplier<Boolean> source) {
        if (source != null) {
            agentSwarmsSettingsSource = source;
        }
    }
    /** 用户类型 sysprop-override seam · CC 原名: process.env.USER_TYPE (agentSwarmsEnabled.ts:26)，缺省回退 env USER_TYPE */
    private static final String USER_TYPE_PROPERTY = "nexusai.user.type";
    private static final String AGENT_NAME_PROPERTY = "nexusai.agent.name";
    private static final String TEAM_NAME_PROPERTY = "nexusai.team.name";
    private static final String AGENT_COLOR_PROPERTY = "nexusai.agent.color";
    private static final String VERIFICATION_AGENT_PROPERTY = "nexusai.feature.verification_agent";
    private static final String TENGU_HIVE_EVIDENCE_PROPERTY = "nexusai.feature.tengu_hive_evidence";

    /**
     * 任务配置根目录覆盖 · nexusai.* 约定
     *
     * <p>CC 原语义（envUtils.ts:7-14）：任务根 = CLAUDE_CONFIG_DIR 环境变量，否则 ~/.claude。
     * <b>决策 D1（nexusai 复刻版 .claude 改造，2026-08-30）</b> 后：写根默认已是 nexusai 自有根
     * {@code {user.home}/.{appName}}（见 {@link #getClaudeConfigHomeDir()}），本 sysprop 仅作
     * 部署/测试隔离的显式覆盖 seam（优先级最高，保留）。
     *
     * <p><b>迁移提示（tasks/teams 既有数据）</b>：弃 CLAUDE_CONFIG_DIR/~/.claude 后，存量
     * {@code ~/.claude/tasks}、{@code ~/.claude/teams} 不再自动读取。迁移方式二选一：
     * ① 一次性把旧目录复制到 nexusai 根；② 过渡期临时设本 sysprop={@code ~/.claude} 读旧数据
     * （详见 {@link #getClaudeConfigHomeDir()} Javadoc）。
     */
    private static final String TASKS_CONFIG_DIR_PROPERTY = "nexusai.task.config-dir";

    private static final Logger log = LoggerFactory.getLogger(TaskSystemConfig.class);

    // ════════════════════════════════════════════════════════════════════════
    // V1 vs V2 互斥
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否启用 Task V2 系统 · 对齐 CC tasks.ts:133-139 isTodoV2Enabled()
     *
     * <p>CC 逻辑（tasks.ts:133-139）——<b>以实际 TS 源码行为为准，CC 注释（:134）仅作背景</b>：
     * <pre>
     * export function isTodoV2Enabled(): boolean {
     *   // Force-enable tasks in non-interactive mode (e.g. SDK users who want Task tools over TodoWrite)
     *   if (isEnvTruthy(process.env.CLAUDE_CODE_ENABLE_TASKS)) {
     *     return true
     *   }
     *   return !getIsNonInteractiveSession()
     * }
     * </pre>
     *
     * <p>CC 判定链（逐行实证）：
     * <ol>
     *   <li>env {@code CLAUDE_CODE_ENABLE_TASKS} 为真（isEnvTruthy：{@code 1/true/yes/on}，
     *       lowercase+trim，envUtils.ts:32-37）→ 无条件返回 true（tasks.ts:135-136）。
     *       CC 注释（tasks.ts:134）仅描述 SDK 使用场景，实际代码在交互式模式下同样强制启用——文档以行为为准。</li>
     *   <li>否则返回 {@code !getIsNonInteractiveSession()}（tasks.ts:138）
     *       = {@code !(!STATE.isInteractive)} = {@code STATE.isInteractive}（state.ts:1057-1059）。</li>
     *   <li>{@code STATE.isInteractive} 默认 {@code false}（state.ts:300）；运行时唯一设置点
     *       main.tsx:802-812：{@code isNonInteractive = hasPrintFlag(-p/--print) || hasInitOnlyFlag(--init-only)
     *       || hasSdkUrl(--sdk-url) || !process.stdout.isTTY}，{@code isInteractive = !isNonInteractive}。
     *       即仅当「无 -p/--print、无 --init-only、无 --sdk-url 且 stdout 为 TTY」时才为 true。</li>
     * </ol>
     *
     * <p><b>介质差异</b>：CC 读 env var {@code CLAUDE_CODE_ENABLE_TASKS}（tasks.ts:135）；
     * Java 读 JVM sysprop {@code nexusai.tasks.enabled}（:66）。二者 isEnvTruthy 接受集合一致
     * （{@code 1/true/yes/on}，不区分大小写）。</p>
     *
     * <p><b>默认值对齐（决策 #65 已拍板实施）</b>：Java Web 后端会话（有前端用户）默认交互 →
     * {@link #isInteractive()} 在 Web 请求路径（RequestContext 有 reqId）默认 {@code true} →
     * {@code isTodoV2Enabled()==true} → Task V2 默认开；cron/后台（仅 sessionId）→ 非交互 →
     * V1 TodoWrite。显式设置 {@code nexusai.tasks.enabled=true} 或 {@code nexusai.interactive=true}
     * 仍可强制 V2（对齐 CC tasks.ts:135-136 env 强制分支与 STATE.isInteractive 语义）。</p>
     *
     * @return true → 使用 TaskCreate/TaskGet/TaskUpdate/TaskList (V2)
     *         false → 使用 TodoWrite (V1)
     */
    public static boolean isTodoV2Enabled() {
        // 对齐 CC tasks.ts:135-137：环境变量强制启用
        String envValue = System.getProperty(ENABLE_TASKS_PROPERTY);
        if (isEnvTruthy(envValue)) {
            if (log.isDebugEnabled()) {
                log.debug("isTodoV2Enabled：sysprop {}={} 为真值，强制启用 V2（对齐 CC tasks.ts:135-136 env CLAUDE_CODE_ENABLE_TASKS 强制分支）", ENABLE_TASKS_PROPERTY, envValue);
            }
            return true;
        }

        // 对齐 CC tasks.ts:138：!getIsNonInteractiveSession() = STATE.isInteractive（state.ts:1057-1059）
        boolean interactive = isInteractive();
        if (log.isDebugEnabled()) {
            log.debug("isTodoV2Enabled：无 {} 注入，按交互式判定返回 {}（CC 默认 STATE.isInteractive=false → V1 TodoWrite）", ENABLE_TASKS_PROPERTY, interactive);
        }
        return interactive;
    }

    /**
     * 是否为交互式会话 · 对齐 CC STATE.isInteractive（bootstrap/state.ts）
     *
     * <p>CC 等价语义：{@code isTodoV2Enabled() = !getIsNonInteractiveSession() = STATE.isInteractive}
     * （tasks.ts:138 / state.ts:1057-1059）。CC 的交互式判定是进程级、启动时一次设定
     * （main.tsx:802-812：{@code -p/--print}、{@code --init-only}、{@code --sdk-url} 或
     * {@code !process.stdout.isTTY} → 非交互）。</p>
     *
     * <p><b>Java Web 后端决策 #65（tool-v4 覆盖度对齐 CC，2026-08-23 用户拍板）</b>：
     * Web 后端会话（有前端用户）应视为<b>交互</b> → todoV2 默认开。判定源 =
     * {@link RequestContext#requestId()}：ChatService.processUserMessage 经
     * {@code RequestContext.set(sessionId, userMessageId)} 同时设 sessionId + reqId ——
     * 有 reqId = 前端用户消息在途（交互）；cron/后台仅经
     * {@code RequestContext.setSession(sessionId)}（CronIdleExecutor/RemoteAgentTaskService/
     * PartialCompactService）→ 无 reqId → 非交互 → V1 TodoWrite。无 MDC（启动期/测试）
     * → 默认非交互（对齐 CC state.ts:300 STATE.isInteractive=false）。</p>
     *
     * <p><b>sysprop 覆盖优先</b>：显式设置 {@code nexusai.interactive} 时按 isEnvTruthy 判定
     * （对齐 CC 进程级显式设定）；未注入时按上述 Web 请求路径判定。显式设置
     * {@code nexusai.interactive=true} 或 {@code nexusai.tasks.enabled=true} 仍可强制 V2
     * （对齐 CC tasks.ts:135-136 env 强制分支）。</p>
     */
    public static boolean isInteractive() {
        String interactive = System.getProperty(INTERACTIVE_PROPERTY);
        if (interactive == null) {
            // 决策 #65：Web 请求路径（有前端用户，ChatService 设 sessionId + reqId）→ 交互 → todoV2 默认开；
            // cron/后台（仅 setSession sessionId，无 reqId）→ 非交互 → V1 TodoWrite；无 MDC → 非交互。
            boolean webInteractive = RequestContext.requestId() != null;
            if (log.isDebugEnabled()) {
                log.debug("isInteractive：无 {} 注入，按 Web 请求路径判定 requestId={} → interactive={}（决策#65：Web 前端用户=交互→V2；cron/后台=非交互→V1）",
                    INTERACTIVE_PROPERTY, RequestContext.requestId(), webInteractive);
            }
            return webInteractive;
        }
        if (log.isDebugEnabled()) {
            log.debug("isInteractive：sysprop {}={}，判定为 {}", INTERACTIVE_PROPERTY, interactive, isEnvTruthy(interactive));
        }
        return isEnvTruthy(interactive);
    }

    // ════════════════════════════════════════════════════════════════════════
    // Agent Swarms 功能开关 · 对齐 CC utils/agentSwarmsEnabled.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否启用 Agent Swarms · 对齐 CC utils/agentSwarmsEnabled.ts:24-44
     *
     * <p>启用时，TaskUpdateTool 在状态变为 in_progress 时会自动设置 owner，
     * 且任务完成时显示"检查下一个任务"提示。
     *
     * <p>判定语义（逐分支对齐 CC 真源）：
     * <ul>
     *   <li>ant（USER_TYPE == "ant"，agentSwarmsEnabled.ts:26-28）→ 恒 true，不经 opt-in/killswitch；</li>
     *   <li>外部需 opt-in：CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS env 为真 或 --agent-teams flag
     *       （agentSwarmsEnabled.ts:30-33），否则 false；
     *       <b>[agent-swarms-setting V42 + agent-swarms-global]</b> 第三个 opt-in 源：
     *       settings.agentSwarmsEnabled（前端「环境配置」开关，DB V42 列）。生产经
     *       {@link #installAgentSwarmsSettingsSource} 安装的<b>实时 DB 读源</b>（SettingsService
     *       @PostConstruct 注入，每次调用实时读全局单例行 → 换会话无需 get/update 触发），
     *       OR 进外部 opt-in 判定；未安装（POJO 测试无 Spring）→ 回落
     *       {@link #setAgentSwarmsSettingsOverride} 静态覆盖标志测试 seam（get/update 同步镜像）。</li>
     *   <li>killswitch（GrowthBook 'tengu_amber_flint'，agentSwarmsEnabled.ts:37-41）→ Java 无
     *       GrowthBook 基础设施，以 sysprop {@code nexusai.swarms.killswitch} 等价模拟：置 true
     *       （isEnvTruthy 真值集合）模拟 killswitch 关闭场景（外部用户禁用 swarms），缺省 false
     *       = killswitch 未关闭（对齐 CC getFeatureValue_CACHED_MAY_BE_STALE('tengu_amber_flint', true)
     *       缺省通过）。killswitch 仍末位约束（优先级不变，覆盖 opt-in + settings 放行）。</li>
     * </ul>
     *
     * <p>Java env 测试不可设 → sysprop-override seam（镜像既有 TASKS_CONFIG_DIR 模式）：
     * sysprop 优先，缺省回退对应 env。
     *
     * @return true 如果 agent swarms 功能已启用
     */
    public static boolean isAgentSwarmsEnabled() {
        // CC agentSwarmsEnabled.ts:26-28 · ant：恒 true
        if (isAntUser()) {
            if (log.isDebugEnabled()) {
                log.debug("isAgentSwarmsEnabled：USER_TYPE=ant → 恒 true");
            }
            return true;
        }
        // CC agentSwarmsEnabled.ts:30-33 · 外部：需 opt-in（env 或 --agent-teams flag）
        // [agent-swarms-setting V42 + agent-swarms-global] 第三个 opt-in 源：settings.agentSwarmsEnabled
        //   （前端开关）。生产经 @PostConstruct 安装的实时 DB 读源（每次调用实时读，换会话即生效，
        //   不依赖 get/update 触发刷新）；未安装（POJO 测试）→ 回落静态覆盖标志（get/update 同步镜像，
        //   测试 seam）。source 安装后为权威（防「旧 override 残留」覆盖「实时 DB false」，全局关=所有会话关）。
        boolean optIn = isEnvTruthy(readOptInValue()) || isAgentTeamsFlagSet();
        Boolean liveSetting = agentSwarmsSettingsSource != null ? agentSwarmsSettingsSource.get() : null;
        boolean setting = liveSetting != null ? liveSetting : Boolean.TRUE.equals(agentSwarmsSettingsOverride);
        if (!optIn && !setting) {
            if (log.isDebugEnabled()) {
                log.debug("isAgentSwarmsEnabled：未 opt-in（CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS 与 --agent-teams 均缺省）且 settings.agentSwarmsEnabled={} 未开启 → false", setting);
            }
            return false;
        }
        // CC agentSwarmsEnabled.ts:37-41 · killswitch：Java 无 GrowthBook → sysprop
        // nexusai.swarms.killswitch 等价模拟（true=关闭，缺省 false=通过，对齐 CC 缺省 true）
        if (isEnvTruthy(System.getProperty(SWARMS_KILLSWITCH_PROPERTY))) {
            if (log.isDebugEnabled()) {
                log.debug("isAgentSwarmsEnabled：killswitch 关闭（nexusai.swarms.killswitch=true）→ false（仍末位优先，覆盖 opt-in/settings 放行）");
            }
            return false;
        }
        return true;
    }

    /** ant 用户判定 · CC 原名: process.env.USER_TYPE === 'ant' (agentSwarmsEnabled.ts:26)，sysprop nexusai.user.type 优先，缺省回退 env USER_TYPE */
    private static boolean isAntUser() {
        String userType = System.getProperty(USER_TYPE_PROPERTY);
        if (userType == null) {
            userType = System.getenv("USER_TYPE");
        }
        return "ant".equals(userType);
    }

    /** opt-in env 读取 · CC 原名: process.env.CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS (agentSwarmsEnabled.ts:32)，sysprop nexusai.experimental.agent-teams 优先，缺省回退 env */
    private static String readOptInValue() {
        String value = System.getProperty(AGENT_TEAMS_OPTIN_PROPERTY);
        if (value == null) {
            value = System.getenv("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS");
        }
        return value;
    }

    /** --agent-teams flag 判定 · CC 原名: process.argv.includes('--agent-teams') (agentSwarmsEnabled.ts:10-11)；Java 无 argv 解析 → sysprop nexusai.agent-teams 映射（部署侧约定） */
    private static boolean isAgentTeamsFlagSet() {
        return isEnvTruthy(System.getProperty(AGENT_TEAMS_FLAG_PROPERTY));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Verification Agent 功能开关 · 对齐 CC feature('VERIFICATION_AGENT')
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 是否启用 Verification Agent · 对齐 CC TodoWriteTool.ts:78 feature('VERIFICATION_AGENT')
     *
     * <p>启用时，关闭 3+ 个任务且无 verification 步骤时触发 verification nudge。
     */
    public static boolean isVerificationAgentEnabled() {
        return isEnvTruthy(System.getProperty(VERIFICATION_AGENT_PROPERTY));
    }

    /**
     * 是否启用 Tengu Hive Evidence · 对齐 CC TodoWriteTool.ts:79
     * getFeatureValue_CACHED_MAY_BE_STALE('tengu_hive_evidence', false)
     *
     * <p>verification nudge 的第二个条件。
     */
    public static boolean isTenguHiveEvidenceEnabled() {
        return isEnvTruthy(System.getProperty(TENGU_HIVE_EVIDENCE_PROPERTY));
    }

    // ════════════════════════════════════════════════════════════════════════
    // Agent/Team 名称 · 对齐 CC utils/teammate.ts
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前 agent 名称 · 对齐 CC utils/teammate.ts getAgentName()
     *
     * <p>用于 hook 调用的 agent_name 参数 + task owner 自动设置。
     *
     * @return agent 名称，null 表示未设置
     */
    public static String getAgentName() {
        return System.getProperty(AGENT_NAME_PROPERTY);
    }

    /**
     * 获取当前 team 名称 · 对齐 CC utils/teammate.ts getTeamName()
     *
     * <p>用于 hook 调用的 team_name 参数 + taskListId 解析。
     * 优先系统属性，其次 TaskService 的 leaderTeamName。
     *
     * @return team 名称，null 表示未设置
     */
    public static String getTeamName() {
        String teamName = System.getProperty(TEAM_NAME_PROPERTY);
        if (teamName != null && !teamName.isBlank()) {
            return teamName;
        }
        return null;
    }

    /**
     * 获取当前 agent 颜色 · 对齐 CC utils/teammate.ts getTeammateColor()（:138-145）
     *
     * <p>CC 语义：仅 in-process/dynamicTeamContext 上下文返回颜色，主线程 undefined
     * （teammate.ts:138-145 grep 自验）。Java 无 teammate 上下文子系统（OD-5），按
     * OD-TU-3 登记的「sysprop 代理暂可接受」以 sysprop {@code nexusai.agent.color} 代理；
     * 未设置返回 null（等价 CC 主线程 undefined）→ 信封省略 color 键
     * （JSON.stringify 省略 undefined）。
     *
     * @return agent 颜色，null 表示未设置（等价 CC undefined）
     */
    public static String getTeammateColor() {
        return System.getProperty(AGENT_COLOR_PROPERTY);
    }

    // ════════════════════════════════════════════════════════════════════════
    // TaskListId 委托 · 对齐 CC tasks.ts:199-210 getTaskListId()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 获取默认 TaskListId · 对齐 CC tasks.ts:199-210 getTaskListId()
     *
     * <p>委托给 {@link TaskService#getTaskListId()}。
     *
     * @return 任务列表 ID
     */
    public static String getDefaultTaskListId() {
        return TaskService.getTaskListId();
    }

    // ════════════════════════════════════════════════════════════════════════
    // 配置根目录 · 对齐 CC envUtils.ts:7-14 getClaudeConfigHomeDir()
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 获取任务配置根目录 · 对齐 CC getClaudeConfigHomeDir()
     * （Open-ClaudeCode/src/utils/envUtils.ts:7-14）
     *
     * <p>CC 源码：
     * <pre>
     * export const getClaudeConfigHomeDir = memoize(
     *   (): string =&gt; {
     *     return (
     *       process.env.CLAUDE_CONFIG_DIR ?? join(homedir(), '.claude')
     *     ).normalize('NFC')
     *   },
     *   () =&gt; process.env.CLAUDE_CONFIG_DIR,
     * )
     * </pre>
     *
     * <p><b>决策 D1（nexusai 复刻版 .claude 改造，2026-08-30）</b>：写根统一切 nexusai 自有根
     * {@code {user.home}/.{appName}}（appName=spring.application.name，默认 nexusai，见
     * {@link NexusaiPaths#getAppConfigHomeDir()}）。<b>弃用</b> CC 原生 {@code CLAUDE_CONFIG_DIR}
     * env 与 {@code ~/.claude} 默认（决策 D1/D3：nexusai 用独立配置结构，不复刻 claude 目录）。
     * Java {@link Path} 不做 normalize('NFC')（CC 在 JS 侧做，低影响，见计划 concerns）。
     *
     * <p>Java 映射（优先级从高到低）：
     * <ol>
     *   <li><b>nexusai.task.config-dir sysprop</b>（部署/测试隔离覆盖，保留，CC 原语义的
     *       Java 侧 sysprop-override seam）</li>
     *   <li><b>NexusaiPaths 自有根</b> {@code {user.home}/.{appName}}（决策 D1 新默认）</li>
     * </ol>
     *
     * <p><b>迁移提示（tasks/teams 既有数据）</b>：弃 CLAUDE_CONFIG_DIR/~/.claude 后，存量
     * {@code ~/.claude/tasks}、{@code ~/.claude/teams} 不再自动读取。迁移方式二选一：
     * <ul>
     *   <li>一次性复制：把 {@code ~/.claude/tasks}、{@code ~/.claude/teams} 拷到
     *       {@code {user.home}/.{appName}/tasks}、{@code {user.home}/.{appName}/teams}；或</li>
     *   <li>过渡期：临时设 {@code nexusai.task.config-dir}={@code ~/.claude} 读旧数据，
     *       迁移完成后移除该 sysprop 即回 nexusai 根。</li>
     * </ul>
     *
     * @return 任务配置根目录（任务存储根 = {configHome}/tasks/{taskListId}）
     */
    public static Path getClaudeConfigHomeDir() {
        // 优先级 1：nexusai.task.config-dir sysprop（部署/测试隔离覆盖，保留）
        String sysprop = System.getProperty(TASKS_CONFIG_DIR_PROPERTY);
        if (sysprop != null && !sysprop.isBlank()) {
            if (log.isDebugEnabled()) {
                log.debug("任务配置根目录（nexusai.task.config-dir 覆盖）：{}", sysprop);
            }
            return Path.of(sysprop);
        }

        // 优先级 2：NexusaiPaths 自有根（决策 D1）· 弃用 CLAUDE_CONFIG_DIR env 与 ~/.claude 默认
        Path root = NexusaiPaths.getAppConfigHomePath();
        if (log.isDebugEnabled()) {
            log.debug("任务配置根目录（决策 D1 nexusai 自有根）：{}", root);
        }
        return root;
    }

    // ════════════════════════════════════════════════════════════════════════
    // 环境变量判断 · 对齐 CC isEnvTruthy
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 判断环境变量值是否为真 · 对齐 CC isEnvTruthy
     *
     * <p>接受：true, 1, yes, on（不区分大小写）
     */
    public static boolean isEnvTruthy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "true".equals(lower) || "1".equals(lower) ||
               "yes".equals(lower) || "on".equals(lower);
    }

    /**
     * 判断环境变量值是否为「显式假」· 对齐 CC isEnvDefinedFalsy（utils/envUtils.ts:39-47）
     *
     * <pre>{@code
     * export function isEnvDefinedFalsy(envVar) {
     *   if (envVar === undefined) return false
     *   if (typeof envVar === 'boolean') return !envVar
     *   if (!envVar) return false
     *   const normalizedValue = envVar.toLowerCase().trim()
     *   return ['0', 'false', 'no', 'off'].includes(normalizedValue)
     * }
     * }</pre>
     *
     * <p>接受：0, false, no, off（不区分大小写）。未设置（null/空）→ false
     * （区别于「显式置假」：CC undefined/空串都是「未定义」而非「显式假」）。
     * 用于 paths.ts:35-37 {@code isAutoMemoryEnabled} 的短路 true 分支
     * （{@code CLAUDE_CODE_DISABLE_AUTO_MEMORY=0/false/no/off} → auto-memory 显式开启）。
     */
    public static boolean isEnvDefinedFalsy(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.trim().toLowerCase();
        return "0".equals(lower) || "false".equals(lower) ||
               "no".equals(lower) || "off".equals(lower);
    }

    // ════════════════════════════════════════════════════════════════════════
    // 测试辅助方法
    // ════════════════════════════════════════════════════════════════════════

    /** 强制启用 Task V2（测试辅助）：仅写入 sysprop {@code nexusai.tasks.enabled=true}，不属于 CC 等价行为（CC 无此 API） */
    public static void enableTaskV2() {
        System.setProperty(ENABLE_TASKS_PROPERTY, "true");
    }

    /** 强制禁用 Task V2（测试辅助）：写入 {@code nexusai.tasks.enabled=false} + {@code nexusai.interactive=false}，不属于 CC 等价行为 */
    public static void disableTaskV2() {
        System.setProperty(ENABLE_TASKS_PROPERTY, "false");
        System.setProperty(INTERACTIVE_PROPERTY, "false");
    }

    /** 清除测试设置（测试辅助）：清空本类全部 sysprop，不属于 CC 等价行为 */
    public static void clearForTest() {
        System.clearProperty(ENABLE_TASKS_PROPERTY);
        System.clearProperty(INTERACTIVE_PROPERTY);
        System.clearProperty(AGENT_TEAMS_OPTIN_PROPERTY);
        System.clearProperty(AGENT_TEAMS_FLAG_PROPERTY);
        System.clearProperty(SWARMS_KILLSWITCH_PROPERTY);
        System.clearProperty(USER_TYPE_PROPERTY);
        System.clearProperty(AGENT_NAME_PROPERTY);
        System.clearProperty(TEAM_NAME_PROPERTY);
        System.clearProperty(VERIFICATION_AGENT_PROPERTY);
        System.clearProperty(TENGU_HIVE_EVIDENCE_PROPERTY);
        System.clearProperty(TASKS_CONFIG_DIR_PROPERTY);
        System.clearProperty(AGENT_COLOR_PROPERTY);
        // [agent-swarms-global] 清实时 DB 读源：否则测试间安装的 source 串状态（下一测试误读上一测试 DB 值）
        agentSwarmsSettingsSource = null;
        // [agent-swarms-setting V42] 清 settings 静态覆盖标志：否则既有 swarms 门控测试（SendMessageTool 等）串状态
        agentSwarmsSettingsOverride = null;
    }
}
