package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * [P3-5] 技能列表过滤纯静态工具 · 对齐 CC utils/attachments.ts:2651-2659 {@code filterToBundledAndMcp}。
 *
 * <p><b>WHY 存在</b>: CC EXPERIMENTAL_SKILL_SEARCH 启用时，turn-0 skill listing 不再全量注入，
 * 而过滤到 bundled（Anthropic-curated）+ mcp（user-connected）两个小源（CC 注释
 * 「these sources are small, intent-signaled, and won't hit the truncation budget」——
 * user/project/plugin 长尾（200+）走 discovery 而非 listing）。
 *
 * <p><b>回退语义</b>: CC attachments.ts:2654-2658 —— 过滤结果超过 {@link #FILTERED_LISTING_MAX}
 * 时回退 bundled-only（保护 MCP 重载用户（100+ servers）不被截断，同时保住典型场景的 turn-0
 * 保证）。注意 CC 是 {@code filtered.length > FILTERED_LISTING_MAX}（严格大于，=30 时全保留）。
 *
 * <p><b>门控位置</b>: 本过滤器是<b>纯消费侧语义</b>，是否应用由调用点（LlmAgentLoop A8 块，
 * 镜像 CC attachments.ts:2692-2697 双条件）决定；默认 EXPERIMENTAL_SKILL_SEARCH flag 关闭 →
 * 调用点短路，本过滤器不触碰 → 行为零变化（对齐 CC flag-off DCE 折叠）。
 *
 * <p><b>与 CC 的差异说明</b>: CC 过滤键 {@code cmd.loadedFrom === 'bundled' || 'mcp'}
 * （attachments.ts:2653）→ Java {@link CommandLoadedFrom#BUNDLED} / {@link CommandLoadedFrom#MCP}
 * （Command.getLoadedFrom()，P2-21 独立 loadedFrom 字段，loadSkillsDir.ts:72-73 对齐）。
 * null 等价 CC undefined（CommandLoadedFrom null 默认），非 bundled/mcp → 丢弃。
 */
public final class SkillListingFilter {

    private static final Logger log = LoggerFactory.getLogger(SkillListingFilter.class);

    /**
     * 过滤结果最大条数 · CC original: {@code FILTERED_LISTING_MAX = 30}（attachments.ts:2641，
     * 注释「Protects MCP-heavy users (100+ servers) from truncation」）。
     */
    public static final int FILTERED_LISTING_MAX = 30;

    private SkillListingFilter() {
        // 纯静态工具类，禁止实例化
    }

    /**
     * 过滤技能到 bundled + mcp 两源 · CC original: {@code filterToBundledAndMcp}
     * （attachments.ts:2651-2659）。
     *
     * <p>语义（自验 CC 源码）：
     * <ol>
     *   <li>保留 {@code loadedFrom === 'bundled' || loadedFrom === 'mcp'}（:2652-2653）</li>
     *   <li>{@code filtered.length > FILTERED_LISTING_MAX} → 回退 {@code filtered.filter(bundled)}
     *       （:2654-2658）</li>
     *   <li>否则原序返回 filtered（:2659）</li>
     * </ol>
     *
     * <p>纯函数（无副作用、无 IO），保持输入顺序（List 流式过滤保持迭代序，对齐 CC filter 语义）。
     * null 输入 → 空 list（防御性，调用点 getModelInvocableCommandsForListing 可能返回 null）。
     *
     * @param commands 全量技能命令列表（可为 null）
     * @return 过滤后列表（bundled+mcp，超限回退 bundled-only；原序）
     */
    public static List<Command> filterToBundledAndMcp(List<Command> commands) {
        if (commands == null || commands.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("[SkillListingFilter] filterToBundledAndMcp 输入为空 → 返回空 list");
            }
            return List.of();
        }
        // CC attachments.ts:2652-2653 filtered = commands.filter(loadedFrom==='bundled'||'mcp')
        List<Command> filtered = commands.stream()
            .filter(cmd -> cmd != null
                && (CommandLoadedFrom.BUNDLED.equals(cmd.getLoadedFrom())
                    || CommandLoadedFrom.MCP.equals(cmd.getLoadedFrom())))
            .toList();
        // CC attachments.ts:2654-2658 filtered.length > FILTERED_LISTING_MAX → bundled-only 回退
        if (filtered.size() > FILTERED_LISTING_MAX) {
            List<Command> bundledOnly = filtered.stream()
                .filter(cmd -> CommandLoadedFrom.BUNDLED.equals(cmd.getLoadedFrom()))
                .toList();
            if (log.isDebugEnabled()) {
                log.debug("[SkillListingFilter] filtered {} > {} → 回退 bundled-only ({}) · CC attachments.ts:2654-2658",
                    filtered.size(), FILTERED_LISTING_MAX, bundledOnly.size());
            }
            return bundledOnly;
        }
        if (log.isDebugEnabled()) {
            log.debug("[SkillListingFilter] filterToBundledAndMcp: {} → {} (bundled+mcp) · CC attachments.ts:2651-2659",
                commands.size(), filtered.size());
        }
        return filtered;
    }

    /**
     * 格式化命令描述 + 来源标注 · CC original: {@code formatDescriptionWithSource}
     * （commands.ts:728-754）。
     *
     * <p>CC 语义（Read 真源，逐分支）：
     * <ul>
     *   <li>{@code cmd.type !== 'prompt'} → 原样返回 description（:729-731）</li>
     *   <li>{@code cmd.kind === 'workflow'} → {@code `${desc} (workflow)`}（:733-735）</li>
     *   <li>{@code cmd.source === 'plugin'} → 有 {@code pluginInfo.pluginManifest.name} 时
     *       {@code `(${pluginName}) ${desc}`}，否则 {@code `${desc} (plugin)`}（:737-743）——
     *       pluginInfo 读侧消费之一（NEW-GAP-V-CI-1-2 回填）</li>
     *   <li>{@code cmd.source === 'builtin' || 'mcp'} → 原样（:745-747）</li>
     *   <li>{@code cmd.source === 'bundled'} → {@code `${desc} (bundled)`}（:749-751）</li>
     *   <li>其余 SettingSource → {@code `${desc} (${getSettingSourceName(source)})`}（:753）</li>
     * </ul>
     *
     * <p><b>用途边界</b>（CC :721-727 注释）：user-facing UI（typeahead / help 屏）展示用；
     * 模型侧 prompt（SkillTool）直接用 {@code cmd.description}，不走本方法。
     *
     * <p><b>Java CommandSource 逐值映射</b>：P2-19 拆分后 CC SettingSource 5 值
     * （userSettings/projectSettings/localSettings/flagSettings/policySettings）由
     * {@link CommandSource#USER} / {@link CommandSource#PROJECT_SETTINGS} /
     * {@link CommandSource#LOCAL_SETTINGS} / {@link CommandSource#FLAG_SETTINGS} /
     * {@link CommandSource#POLICY_SETTINGS} 逐值表达（M7 旧折叠已拆分）→ 回退分支经
     * {@link #settingSourceDisplayName} 输出 CC {@code getSettingSourceName} 逐值短名
     * （{@code (user)} / {@code (project)} / {@code (project, gitignored)} / {@code (cli flag)} /
     * {@code (managed)}），不再折叠。plugin 分支完整对齐（pluginInfo.pluginManifest.name 前缀）。
     *
     * @param cmd 命令（null → 空串，防御性；CC 无 null 入参契约）
     * @return 带来源标注的描述文本
     */
    public static String formatDescriptionWithSource(Command cmd) {
        if (cmd == null) {
            return "";
        }
        String description = cmd.getDescription() != null ? cmd.getDescription() : "";
        // CC :729-731 cmd.type !== 'prompt' → cmd.description
        if (!"prompt".equals(cmd.getType())) {
            return description;
        }
        // CC :733-735 cmd.kind === 'workflow' → `${desc} (workflow)`
        if ("workflow".equals(cmd.getKind())) {
            return description + " (workflow)";
        }
        CommandSource source = cmd.getSource();
        // CC :737-743 cmd.source === 'plugin'
        if (source == CommandSource.PLUGIN) {
            String pluginName = cmd.getPluginInfo() != null && cmd.getPluginInfo().pluginManifest() != null
                    ? cmd.getPluginInfo().pluginManifest().name()
                    : null;
            // CC :738-741 if (pluginName) → `(${pluginName}) ${desc}`（JS 空串 falsy 等价 null/空）
            if (pluginName != null && !pluginName.isEmpty()) {
                return "(" + pluginName + ") " + description;
            }
            // CC :742 无 pluginName → `${desc} (plugin)`
            return description + " (plugin)";
        }
        // CC :745-747 builtin / mcp → cmd.description
        if (source == CommandSource.BUILTIN || source == CommandSource.MCP) {
            return description;
        }
        // CC :749-751 bundled → `${desc} (bundled)`
        if (source == CommandSource.BUNDLED) {
            return description + " (bundled)";
        }
        // CC :753 其余 SettingSource → `${desc} (${getSettingSourceName(source)})`
        return description + " (" + settingSourceDisplayName(source) + ")";
    }

    /**
     * CC original: {@code getSettingSourceName}（utils/settings/constants.ts:26-33）。
     *
     * <p>CC 5 值映射：userSettings→'user'、projectSettings→'project'、localSettings→
     * 'project, gitignored'、flagSettings→'cli flag'、policySettings→'managed'。P2-19 拆分后
     * {@link CommandSource} 细分各 SettingSource 值 → 展示短名逐值对齐 CC（旧实现折叠为
     * USER 仅能区分 (user)/(managed)）。
     *
     * @param source 命令来源（null → 'user'，默认构造 USER）
     * @return CC getSettingSourceName 短名
     */
    private static String settingSourceDisplayName(CommandSource source) {
        return switch (source) {
            case USER -> "user";
            case PROJECT_SETTINGS -> "project";
            case LOCAL_SETTINGS -> "project, gitignored";
            case FLAG_SETTINGS -> "cli flag";
            case POLICY_SETTINGS -> "managed";
            default -> "user"; // builtin/plugin/mcp/bundled 在调用方已提前 return，不会到这
        };
    }
}
