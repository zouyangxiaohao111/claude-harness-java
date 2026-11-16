package com.nexusai.application.agent.tool;

import com.nexusai.application.agent.mcp.McpStringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 会话工具分类 · 前端工具管理 UI 的 5 类分组（[tool-categories]）。
 *
 * <h2>为什么单独一个类</h2>
 * <p>分类归属必须<b>只判一次</b>：前端面板按 category 分组渲染，默认禁用集按 category 取值。
 * 若把「哪些工具属于电脑操作」的规则散在前端与后端多处，工具改名/新增时必漏改一处
 * （本仓已多次踩过「同一语义两处各写一遍」的坑）。故判定唯一来源 = {@link #of(Tool)}。
 *
 * <h2>分类判据（按优先级）</h2>
 * <ol>
 *   <li>{@code mcp__computer-use__*} → {@link #CU} —— computer-use 的 in-process MCP server
 *       工具，server 名见 {@code ComputerUseMcpServer.SERVER_NAME = "computer-use"}</li>
 *   <li>{@code mcp__nexusai-in-chrome__*} → {@link #BR} —— 浏览器扩展 MCP 工具
 *       （前缀见 {@code BrowserToolRegistry.TOOL_PREFIX}）</li>
 *   <li>其余 {@code mcp__*} → {@link #MCP} —— 第三方 MCP server 的工具（如 rea）。
 *       ⚠️ 必须在 CU / BR 之后判，二者是专属前缀</li>
 *   <li>实现类在 {@code com.nexusai.kb.} 包下 → {@link #KB} —— 知识库专属工具
 *       （{@code kb/agent/tool/} 下 15 个，均标 {@code @ExclusiveTool}）</li>
 *   <li>其余 → {@link #SYS}</li>
 * </ol>
 *
 * <p><b>为什么 KB 按包名而不是工具名前缀</b>：知识库工具名是 snake_case（{@code search_knowledge} /
 * {@code wiki_read_page} ...），与系统工具的 PascalCase 无统一前缀可分；而它们的实现类
 * 同处 {@code com.nexusai.kb} 包，按包名判定是稳定的（改名不影响）。
 *
 * <p><b>[tool-categories] MCP 三级联动（2026-11-16）</b>：MCP 工具名形如
 * {@code mcp__<server>__<tool>}，一个 server 下可有上百个工具（rea 有 255 个）。故前端面板提供
 * 三级开关：①{@link #MCP} 整类 ②单个 server（{@link #mcpServerOf(String)}） ③单个工具。
 *
 * <h2>默认禁用（[tool-categories] 用户 2026-11-12 裁定）</h2>
 * <p>只有 {@link #CU} 与 {@link #KB} 两类默认禁用；{@link #BR} <b>不</b>默认禁用
 * （未装扩展时该组天然 0 项，装上后立即生效，无需用户再手动开）；
 * {@link #MCP} 也<b>不</b>默认禁用（第三方能力由用户在会话内按需关闭）。
 *
 * <p><b>静态桥接</b>：{@link #defaultDisabledNamesStatic()} 为静态 —— 消费方
 * {@code SessionToolDisableConfig.getDisabledTools} 在 {@code LlmAgentLoop} 的 static
 * 上下文被调用。POJO {@code new} 场景（单测）不触发 Spring → 桥接为 null → 返回空集；
 * 测试用 {@link #reset()} 防跨测试污染（对齐 {@code SessionToolDisableConfig} 同款惯例）。
 */
@Component
public class SessionToolCategory {

    private static final Logger log = LoggerFactory.getLogger(SessionToolCategory.class);

    /** 电脑操作 · computer-use MCP 工具名前缀（{@code ComputerUseMcpServer.SERVER_NAME} + MCP 分隔符）。 */
    public static final String CU_PREFIX = "mcp__computer-use__";

    /** 浏览器控制 · nexusai-in-chrome MCP 工具名前缀。 */
    public static final String BR_PREFIX = "mcp__nexusai-in-chrome__";

    /** 知识库 · 工具实现类所在包前缀。 */
    public static final String KB_PACKAGE_PREFIX = "com.nexusai.kb.";

    /** 第三方 MCP 工具 · 工具名前缀（{@code mcp__<server>__<tool>}，见 {@code McpStringUtils.MCP_PREFIX}）。 */
    public static final String MCP_PREFIX = "mcp__";

    public static final String CU = "CU";
    public static final String KB = "KB";
    public static final String BR = "BR";
    public static final String MCP = "MCP";
    public static final String SYS = "SYS";

    /** 合法分类集合（请求体校验用）。 */
    public static final Set<String> ALL = Set.of(CU, KB, BR, MCP, SYS);

    /** 工具注册表静态桥接（null = 未注入，POJO 单测/无 Spring）。 */
    private static volatile ToolRegistry toolRegistry;

    /**
     * Spring 注入桥接（setter 注入 → 静态字段；{@code @Autowired} 不支持静态字段）。
     *
     * @param registry 工具注册表（Spring 注入）
     */
    @Autowired(required = false)
    public void bridgeToolRegistry(ToolRegistry registry) {
        setToolRegistry(registry);
    }

    /** 注册表静态桥接 setter · 测试（跨包）+ Spring 桥接共用。 */
    public static void setToolRegistry(ToolRegistry registry) {
        ToolRegistry previous = toolRegistry;
        toolRegistry = registry;
        if (registry != null && registry != previous) {
            log.info("SessionToolCategory 注入 ToolRegistry（工具分类与默认禁用集可用）");
        }
    }

    /**
     * 判定单个工具的分类。
     *
     * @param tool 工具（null → {@link #SYS}，防御性）
     * @return {@link #CU} / {@link #KB} / {@link #BR} / {@link #MCP} / {@link #SYS}
     */
    public static String of(Tool tool) {
        if (tool == null) {
            return SYS;
        }
        String name = tool.name();
        if (name != null) {
            // ⚠️ 顺序不可换：CU / BR 是各自 server 的专属前缀，必须先判；
            //    否则 mcp__computer-use__* / mcp__nexusai-in-chrome__* 会被下面的 MCP 兜底吃掉。
            if (name.startsWith(CU_PREFIX)) {
                return CU;
            }
            if (name.startsWith(BR_PREFIX)) {
                return BR;
            }
            if (name.startsWith(MCP_PREFIX)) {
                return MCP;
            }
        }
        if (tool.getClass().getName().startsWith(KB_PACKAGE_PREFIX)) {
            return KB;
        }
        return SYS;
    }

    /**
     * 从 {@code mcp__<server>__<tool>} 取 server 名；非 MCP 形态 → {@code null}。
     *
     * <p>解析口径与全仓唯一实现 {@link com.nexusai.application.agent.mcp.McpStringUtils#mcpInfoFromString}
     * 完全一致（server = 前缀后的第一段）：本仓工具名由
     * {@code McpStringUtils.buildMcpToolName} 生成（server 段与 tool 段都经
     * {@code normalizeNameForMCP} 规范化），沿用同一函数才能与 MCP 服务端的
     * {@code getMcpPrefix(server)} 剥离逻辑对齐。
     *
     * <p>⚠️ 不能用「最后一个 {@code __}」来切：工具名段本身可能含 {@code __}
     * （原始名里连续两个非 {@code [a-zA-Z0-9_-]} 字符会被规范化成 {@code __}），
     * 那样会把 tool 段的一部分误当 server，分组凭空多出一个假 server。
     *
     * @param toolName 工具全名（如 {@code mcp__rea__inspect_plist}）
     * @return server 名（如 {@code rea}）；非 MCP / 解析不出 → null
     */
    public static String mcpServerOf(String toolName) {
        McpStringUtils.McpInfo info = McpStringUtils.mcpInfoFromString(toolName);
        return info == null ? null : info.serverName();
    }

    /**
     * 默认禁用的工具名集合 = 注册表里属于 {@link #CU} 或 {@link #KB} 的全部工具名。
     *
     * <p>注册表未注入（POJO 单测 / Spring 尚未就绪）→ 空集（不误禁任何工具）。
     *
     * @return 工具名集合（不可变；无注册表 → 空集）
     */
    public static Set<String> defaultDisabledNamesStatic() {
        ToolRegistry registry = toolRegistry;
        if (registry == null) {
            return Set.of();
        }
        List<Tool> all = registry.all();
        Set<String> names = new LinkedHashSet<>();
        for (Tool t : all) {
            if (t == null || t.name() == null) {
                continue;
            }
            String cat = of(t);
            if (CU.equals(cat) || KB.equals(cat)) {
                names.add(t.name());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("SessionToolCategory.defaultDisabledNames: {} 个（CU + KB 默认禁用；BR 不默认禁用）",
                    names.size());
        }
        return Set.copyOf(names);
    }

    /**
     * 测试辅助：重置静态桥接（防跨测试 / 跨 Spring 上下文污染）。
     */
    public static void reset() {
        toolRegistry = null;
    }
}
