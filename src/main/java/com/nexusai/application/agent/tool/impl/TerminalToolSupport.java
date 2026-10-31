package com.nexusai.application.agent.tool.impl;

import com.nexusai.application.terminal.TerminalInfo;
import com.nexusai.common.SessionKeys;

import java.util.List;
import java.util.Locale;

/**
 * 终端只读工具（{@link TerminalListTool} / {@link TerminalReadTool}）的共享口径与文本渲染。
 *
 * <p><b>WHY 单独一个包内可见类</b>：两个工具的输出文案必须<b>逐字一致</b>
 * （用户裁定：「会话无终端 → 同 terminal_list 的文案」），若各自写一份字符串，
 * 后续改一处必漂一处。渲染/裁剪/会话判定也同源，避免两处各判一套。
 *
 * <p><b>产品口径（用户已裁定，照做）</b>：终端<b>只由用户手动创建</b>，Agent 不创建、不写入；
 * Agent 只能<b>读</b>已开终端的输出（对齐 Codex 桌面版 {@code read_thread_terminal}）。
 * 执行命令仍走 Bash 工具，<b>不进</b>集成终端（两条通道分离）。
 */
final class TerminalToolSupport {

    /** {@code terminal_read} 未指定 {@code lines} 时的默认行数。 */
    static final int DEFAULT_LINES = 100;

    /** {@code terminal_read} 的可读行数上限（超出钳到本值，防止把整个缓冲塞进上下文）。 */
    static final int MAX_LINES = 500;

    /** 会话无终端时的固定文案（只读两工具共用；⛔ 不要各写一份）。 */
    static final String NO_TERMINALS_MESSAGE =
        "本会话还没有终端（终端由用户手动创建，Agent 不能创建）";

    /**
     * 写工具（{@code terminal_write}）在会话无终端时的文案 —— <b>刻意与只读文案不同</b>：
     * 上一批的产品口径是「Agent 不能创建终端」，本批用户已放行 {@code terminal_create}，
     * 故这里必须给出两条可执行的出路，否则模型只能干瞪眼。
     */
    static final String NO_TERMINALS_FOR_WRITE_MESSAGE =
        "本会话还没有终端：请用户先手动创建终端，或先调用 terminal_create 工具创建（terminal_write 自身不会创建终端）";

    /** 无会话上下文（ctx 缺失 / {@link SessionKeys#NO_SESSION} 哨兵）时的固定文案。 */
    static final String NO_SESSION_MESSAGE =
        "当前没有会话上下文，无法读取终端（终端按会话隔离，请在会话内调用）";

    /** 指定 terminalId 不存在时的固定前缀（后接原样 id）。 */
    static final String NOT_FOUND_PREFIX = "未找到该终端：";

    private TerminalToolSupport() {
    }

    /**
     * 会话标识是否可用于查询终端。
     *
     * <p>{@link SessionKeys#NO_SESSION} 是「显式确无会话」哨兵 —— 它<b>不是</b>真实会话键，
     * 拿它查终端只会得到空列表，那会让调用方误以为「用户确实没开终端」，故单独判出来。
     *
     * @param sessionId 会话 ID（可空）
     * @return true = 可用于 {@code TerminalService.list}
     */
    static boolean hasSession(String sessionId) {
        return sessionId != null && !sessionId.isBlank() && !SessionKeys.isNoSession(sessionId);
    }

    /**
     * shell 短名（{@code C:\…\powershell.exe} → {@code powershell}）。
     *
     * <p>只做展示：元信息里的全路径会挤占上下文，而 Agent 判断 dev server / 构建状态
     * 只需要知道是哪种 shell。解析失败回落 {@code unknown}（不抛）。
     *
     * @param shell {@link TerminalInfo#shell()} 原值（可能是全路径，也可能是 PATH 名）
     * @return 短名（非空）
     */
    static String shellShortName(String shell) {
        if (shell == null || shell.isBlank()) {
            return "unknown";
        }
        String name = shell.trim();
        int sep = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (sep >= 0 && sep + 1 < name.length()) {
            name = name.substring(sep + 1);
        }
        if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name.isBlank() ? "unknown" : name;
    }

    /**
     * {@code terminal_list} 的单条输出行：
     * {@code <id> | <shell 短名> | 运行中|已退出 | cwd=<cwd> | 启动于 <时间>}。
     *
     * @param info 终端描述（非 null）
     * @return 单行文本（不含换行）
     */
    static String listLine(TerminalInfo info) {
        return info.id() + " | " + shellShortName(info.shell()) + " | "
            + (info.alive() ? "运行中" : "已退出")
            + " | cwd=" + info.cwd()
            + " | 启动于 " + info.startedAt();
    }

    /**
     * {@code terminal_read} 的首行元信息：
     * {@code terminal=<id> shell=<shell 短名> alive=<true|false> cwd=<cwd>}。
     *
     * @param info 终端描述（非 null）
     * @return 单行文本（不含换行）
     */
    static String metaLine(TerminalInfo info) {
        return "terminal=" + info.id()
            + " shell=" + shellShortName(info.shell())
            + " alive=" + info.alive()
            + " cwd=" + info.cwd();
    }

    /**
     * 行数参数钳制：{@code null} / {@code <=0} ⇒ {@link #DEFAULT_LINES}；超出上限 ⇒ {@link #MAX_LINES}。
     *
     * @param requested LLM 传入的 {@code lines}（可空）
     * @return 实际请求给 {@link com.nexusai.application.terminal.TerminalService#snapshot} 的行数
     */
    static int clampLines(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LINES;
        }
        return Math.min(requested, MAX_LINES);
    }

    /**
     * 按 id 精确找终端。
     *
     * @param terminals {@link com.nexusai.application.terminal.TerminalService#list} 结果（非 null）
     * @param id        终端 ID
     * @return 命中的终端；不存在 ⇒ {@code null}
     */
    static TerminalInfo findById(List<TerminalInfo> terminals, String id) {
        if (terminals == null || id == null || id.isBlank()) {
            return null;
        }
        for (TerminalInfo info : terminals) {
            if (id.equals(info.id())) {
                return info;
            }
        }
        return null;
    }

    /**
     * 最近启动的终端。
     *
     * <p>{@link com.nexusai.application.terminal.TerminalService#list} 按 {@code startedAt}
     * <b>升序</b>返回，故最后一条 = 最近启动。⛔ 不看 alive：用户口径是「最近启动」，
     * 已退出终端仍按启动时间参与比较（其缓冲仍可读）。
     *
     * @param terminals 非空列表
     * @return 最后一条（最近启动）
     */
    static TerminalInfo mostRecent(List<TerminalInfo> terminals) {
        return terminals.get(terminals.size() - 1);
    }

    /**
     * 日志用数据预览（对齐 BashTool {@code log.info("BashTool: $ {} ...", abbreviate(command, 200))}
     * 的既有惯例：命令原文进日志但截断）。换行转义，避免一条日志被命令里的换行打散
     * （本仓铁律：含可内嵌换行的字段会打穿逐行 grep）。
     *
     * @param text    待记录文本（可空）
     * @param maxChar 最大字符数
     * @return 单行预览
     */
    static String previewForLog(String text, int maxChar) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        // 用 ASCII 转义记号（⛔ 不用 ⏎ 之类 Unicode 符号：控制台按 GBK 解码时会被打成 '?'，
        // 反而看不出是换行 —— 与「遥测 message= 是 mojibake」同类坑）
        String one = text.replace("\\", "\\\\")
            .replace("\r\n", "\\n")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
        return one.length() <= maxChar ? one : one.substring(0, maxChar) + "…（截断）";
    }
}
