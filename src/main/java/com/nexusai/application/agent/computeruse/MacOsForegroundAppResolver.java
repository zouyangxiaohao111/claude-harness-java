package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * macOS 前台应用解析器（JXA + NSWorkspace）· computer-use 的档位门控数据源。
 *
 * <h2>CCB 对照</h2>
 * <ul>
 *   <li>CCB 的 macOS 前台应用：{@code packages/@ant/computer-use-input/src/backends/darwin.ts:186-209}
 *       的 {@code getFrontmostAppInfo()} —— <b>AppleScript System Events</b>
 *       （{@code tell application "System Events" to … bundle identifier & "|" & name}）；</li>
 *   <li>CCB 的 {@code NSWorkspace} 版本：{@code packages/@ant/computer-use-swift/src/backends/darwin.ts:172-185}
 *       的 {@code appUnderPoint()}（{@code NSWorkspace.sharedWorkspace.frontmostApplication}
 *       ⇒ {@code bundleIdentifier} + {@code localizedName}）。</li>
 * </ul>
 * ⇒ 本仓走 <b>NSWorkspace</b> 那条（JXA，{@link MacOsJxa#frontmostApplication()}）：
 * 它<b>不依赖 System Events 的辅助功能脚本</b>（更稳），且一次调用同时给出
 * bundleId 与显示名。
 *
 * <h2>授权键归一</h2>
 * <p>本仓的授权键命名空间 = {@link ComputerUseAppPolicy#normalizeAppKey(String)}（小写、去路径）
 * ⇒ 本实现返回<b>前台应用的显示名</b>归一后的结果（如 {@code "Google Chrome"} ⇒ {@code "google chrome"}）。
 * <br>⚠️ 与 Windows 侧同款约束：门控（{@link ComputerUseInputGate#check}）是<b>精确匹配</b>
 * ⇒ 模型申请授权时用的名字必须与前台应用的显示名一致（CCB 也有同款约束）。
 */
public final class MacOsForegroundAppResolver implements ComputerUseForegroundAppResolver {

    private static final Logger log = LoggerFactory.getLogger(MacOsForegroundAppResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ComputerUseProcessRunner runner;

    /** 生产构造器（真起进程）。 */
    public MacOsForegroundAppResolver() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器
     */
    public MacOsForegroundAppResolver(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsForegroundAppResolver 依赖不能为空");
        }
        this.runner = runner;
    }

    @Override
    public String currentAppId() {
        List<String> aliases = currentAppAliases();
        return aliases.isEmpty() ? null : aliases.get(0);
    }

    /**
     * 前台应用的<b>全部授权键别名</b>：{@code [bundleId 归一, displayName 归一]}（去重、去空）。
     *
     * <h2>WHY 必须两个都给</h2>
     * <p>macOS 的显示名<b>随系统语言变化</b>（中文系统 {@code Finder} ⇒ 「访达」），而 bundle id
     * 是稳定的。授权时模型可能给英文名（{@code Finder}）、bundle id、或本地化名，三种都该认。
     * <br>只按 displayName 匹配是本仓旧实现的缺陷：实测授权 {@code Finder} 后，中文系统前台
     * 报「访达」⇒ 精确匹配必然 miss ⇒ 所有输入动作被误拦。
     * <p>⇒ 门控（{@link ComputerUseInputGate}）按「命中任一别名即授权」判定。
     *
     * @return 别名列表；取不到前台应用 ⇒ 空列表（调用方放行）
     */
    @Override
    public List<String> currentAppAliases() {
        try {
            String raw = MacOsExec.jxa(runner, MacOsJxa.frontmostApplication());
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) {
                return List.of();
            }
            // LinkedHashSet：保序（bundleId 在前 = currentAppId() 返回稳定键）+ 去重
            java.util.Set<String> aliases = new java.util.LinkedHashSet<>();
            String bundleId = ComputerUseAppPolicy.normalizeAppKey(node.path("bundleId").asText(""));
            if (!bundleId.isBlank()) {
                aliases.add(bundleId);
            }
            String displayName = ComputerUseAppPolicy.normalizeAppKey(node.path("displayName").asText(""));
            if (!displayName.isBlank()) {
                aliases.add(displayName);
            }
            return List.copyOf(aliases);
        } catch (Throwable t) {
            // 取不到前台应用 ⇒ 按 CCB toolCalls.ts:502-506 的语义放行（返回空）
            log.warn("[computer-use] 取 macOS 前台应用失败（按 CCB 语义放行）: {}", t.toString());
            return List.of();
        }
    }
}
