package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        try {
            String raw = MacOsExec.jxa(runner, MacOsJxa.frontmostApplication());
            JsonNode node = JSON.readTree(raw);
            if (node == null || !node.isObject()) {
                return null;
            }
            String displayName = node.path("displayName").asText("");
            if (displayName.isBlank()) {
                // 显示名取不到 ⇒ 退回 bundle id（同样是稳定标识）
                String bundleId = node.path("bundleId").asText("");
                return bundleId.isBlank() ? null : ComputerUseAppPolicy.normalizeAppKey(bundleId);
            }
            return ComputerUseAppPolicy.normalizeAppKey(displayName);
        } catch (Throwable t) {
            // 取不到前台应用 ⇒ 按 CCB toolCalls.ts:502-506 的语义放行（返回 null）
            log.warn("[computer-use] 取 macOS 前台应用失败（按 CCB 语义放行）: {}", t.toString());
            return null;
        }
    }
}
