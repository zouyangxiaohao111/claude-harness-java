package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Shell command hook · 对齐 claude.exe v2.1.325 {@code BashCommandHookSchema}
 * (type='command', {@code pg()} off≈309,296,321；旧快照
 * {@code Open-ClaudeCode/src/schemas/hooks.ts:32-65}).
 *
 * <p>WHY: CC command hook 是最常用的 hook 类型, 执行 shell 命令并按 exit code 分流
 * (0=success, 2=blocking, 其他=warning). 字段语义对齐 CC Zod schema, 每个字段
 * JavaDoc 标注 CC 原名 + 行号, 便于未来审计无需重跑 CC 源码.
 *
 * <p><b>CC 真源字段 (v2.1.325 {@code pg()} 命令子 schema)</b>:
 * <ul>
 *   <li>{@code command} — Shell 命令字符串 (必填)</li>
 *   <li>{@code args} — [Wave1-C] exec 形式参数列表：存在时 {@code command} 按可执行文件直接
 *       spawn、<b>不经 shell</b>（路径里的引号/空格/{@code $}/反引号不会被 shell 解释）；
 *       缺省时走 shell（POSIX bash / Windows 无 Git Bash 用 PowerShell）</li>
 *   <li>{@code if} — 权限规则语法过滤条件, 如 "Bash(git *)"; Java 关键字 {@code if}
 *       避让为 {@code ifCondition}, Jackson 通过 {@link JsonProperty} 映射回 "if"</li>
 *   <li>{@code shell} — Shell 解释器, enum {bash, powershell}, 缺省 bash
 *       (CC shellProvider.ts:2 DEFAULT_HOOK_SHELL='bash')</li>
 *   <li>{@code timeout} — 超时秒数 (正整数)</li>
 *   <li>{@code onFailure} — [Wave1-C] 失败语义 {@code continue|block}：hook 起不来 / 超时 /
 *       以 0、2 之外的码退出 / 输出非法 JSON 时，{@code block} 视同 exit 2 拦住该事件守护的
 *       动作；缺省 {@code continue}。CC 原文 "Ignored for async hooks and on Stop,
 *       SubagentStop, TaskCompleted and TeammateIdle"</li>
 *   <li>{@code statusMessage} — 自定义 spinner 状态文案</li>
 *   <li>{@code once} — true=执行一次后移除</li>
 *   <li>{@code async} — true=后台运行不阻塞</li>
 *   <li>{@code asyncRewake} — true=后台运行, exit 2 唤醒模型 (隐含 async)</li>
 *   <li>{@code rewakeMessage} — [Wave1-C] asyncRewake exit 2 时注入模型的 system-reminder
 *       前缀（hook 输出追加其后）；CC 标 {@code @internal}</li>
 *   <li>{@code rewakeSummary} — [Wave1-C] asyncRewake exit 2 时给用户的一行摘要，
 *       缺省 "Stop hook feedback"</li>
 *   <li>{@code cloud} — [Wave1-C] 云会话由本机驱动时该 hook 能否在本机跑
 *       {@code device|skip}（未识别值按 {@code skip}）；CC 标 {@code @internal}</li>
 * </ul>
 *
 * <p><b>UI 集成预留</b>: 前端 command hook 表单字段对齐本 record.
 *
 * @param command       CC original: command
 * @param ifCondition   CC original: if; 权限规则过滤条件
 * @param shell         CC original: shell; null → 默认 bash
 * @param timeout       CC original: timeout; 秒
 * @param statusMessage CC original: statusMessage
 * @param once          CC original: once
 * @param asyncFlag     CC original: async; Java 关键字避让
 * @param asyncRewake   CC original: asyncRewake
 * @param args          CC original: args（[Wave1-C] exec 形式参数，存在即不经 shell）
 * @param onFailure     CC original: onFailure（continue|block，缺省 continue）
 * @param rewakeMessage CC original: rewakeMessage（asyncRewake exit 2 的模型侧前缀）
 * @param rewakeSummary CC original: rewakeSummary（asyncRewake exit 2 的用户侧摘要）
 * @param cloud         CC original: cloud（device|skip，云会话是否可在此机器运行）
 */
public record CommandHook(
    String command,
    @JsonProperty("if") String ifCondition,
    String shell,
    Integer timeout,
    String statusMessage,
    Boolean once,
    @JsonProperty("async") Boolean asyncFlag,
    Boolean asyncRewake,
    List<String> args,
    String onFailure,
    String rewakeMessage,
    String rewakeSummary,
    String cloud
) implements HookCommand, SessionHook {

    /** CC DEFAULT_HOOK_SHELL='bash' (shellProvider.ts:2) — shell 缺省值. */
    public static final String DEFAULT_SHELL = "bash";

    /** [Wave1-C] {@code onFailure} 缺省值 · CC 原文 "'continue' (default)". */
    public static final String DEFAULT_ON_FAILURE = "continue";

    public CommandHook {
        if (args != null) {
            args = List.copyOf(args);
        }
    }

    /**
     * [Wave1-C] 8 参转发构造器 · 新增 5 字段缺失（{@code args}/{@code onFailure}/
     * {@code rewakeMessage}/{@code rewakeSummary}/{@code cloud} = null，即 CC 缺省语义）。
     *
     * <p>WHY: 加字段前本 record 为 8 字段，生产代码（HookRegistry / PluginLoader）与测试
     * 共 129 处按 8 参构造 ⇒ 由本构造器承接（{@code args=null} 即"无 exec 形式"）。
     *
     * <p><b>{@code @JsonCreator(DISABLED)}</b>：本模块编译带 {@code -parameters} 且注册
     * ParameterNamesModule，任一多参构造器都会被视作隐式 property-based creator；不显式
     * 禁掉会与 record 规范构造器冲突（Conflicting property-based creators）。
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public CommandHook(String command, String ifCondition, String shell, Integer timeout,
                       String statusMessage, Boolean once, Boolean asyncFlag, Boolean asyncRewake) {
        this(command, ifCondition, shell, timeout, statusMessage, once, asyncFlag, asyncRewake,
            null, null, null, null, null);
    }

    @Override
    public HookType hookType() {
        return HookType.COMMAND;
    }

    /**
     * CC original: {@code type} = 'command' · 对齐 schemas/hooks.ts discriminatedUnion 字面量.
     *
     * <p>[3-1 拆开] 显式实现 — 本类同时 implements {@link SessionHook} (抽象 type()) 与
     * {@link HookCommand} (default type()), Java 要求显式声明以同时满足两接口契约.
     */
    @Override
    public String type() {
        return "command";
    }
}