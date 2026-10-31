package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * macOS 应用枚举（JXA + {@code mdls}）· computer-use macOS 执行端。
 *
 * <h2>CCB 对照（{@code packages/@ant/computer-use-swift/src/backends/darwin.ts}）</h2>
 * <ul>
 *   <li><b>运行中应用</b>：{@code :232-249} {@code listRunning()}
 *       ⇒ {@code Application("System Events").applicationProcesses.whose({backgroundOnly:false})}
 *       ⇒ 每项取 {@code bundleIdentifier()} + {@code name()}；</li>
 *   <li><b>已安装应用</b>：{@code :187-226} {@code listInstalled()}
 *       ⇒ 扫 {@code /Applications} / {@code ~/Applications} / {@code /System/Applications}
 *       下的 {@code *.app}，用 {@code mdls -name kMDItemCFBundleIdentifier} 取<b>真实 bundle id</b>
 *       （CCB 注释原文：早前的 AppleScript 方案会造出假 bundle id，导致 request_access 匹配不到）。</li>
 * </ul>
 *
 * <h2>⚠️ 当前<b>没有消费者</b>（如实标注）</h2>
 * <p>本仓 Phase 1a 的 {@code request_access} <b>不做应用枚举</b>
 * （见 {@link ComputerUseRequestAccessTool} 的类注释：「CCB 的 {@code apps} 描述末尾会追加一个运行时字段
 * {@code installedAppsHint}（已安装应用名清单）—— 本仓 Phase 1a <b>无应用枚举能力</b> ⇒ 该运行时后缀省略」）。
 * <br>⇒ 本类先把 CCB 的 macOS 枚举能力<b>原样落地</b>（执行端对齐的组成部分），
 * 供后续把 {@code installedAppsHint} 补回时直接使用。
 * <br>⇒ <b>不影响任何既有工具的行为</b>（无调用方 ⇒ 零副作用）。
 */
public final class MacOsAppCatalog {

    private static final Logger log = LoggerFactory.getLogger(MacOsAppCatalog.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 一个应用条目。
     *
     * @param bundleId    真实 bundle id（如 {@code com.google.Chrome}）
     * @param displayName 显示名（如 {@code Google Chrome}）
     * @param path        应用路径（已安装应用才有；运行中应用为空串）
     */
    public record AppInfo(String bundleId, String displayName, String path) {
    }

    private final ComputerUseProcessRunner runner;

    /** 生产构造器（真起进程）。 */
    public MacOsAppCatalog() {
        this(ComputerUseProcessRunner.REAL);
    }

    /**
     * @param runner 外部命令执行器
     */
    public MacOsAppCatalog(ComputerUseProcessRunner runner) {
        if (runner == null) {
            throw new IllegalArgumentException("[computer-use] MacOsAppCatalog 依赖不能为空");
        }
        this.runner = runner;
    }

    /**
     * 运行中的应用（CCB {@code listRunning}）。
     *
     * @return 应用列表（失败 ⇒ 空列表）
     */
    public List<AppInfo> listRunning() {
        try {
            ComputerUseProcessRunner.Result result = runner.run(List.of(
                    "osascript", "-l", "JavaScript", "-e", MacOsJxa.runningApplications()));
            if (!result.ok()) {
                log.warn("[computer-use] macOS 运行中应用枚举失败（退出码 {}）: {}",
                        result.exitCode(), result.stderr());
                return List.of();
            }
            return parseRunning(result.stdout());
        } catch (Exception e) {
            log.warn("[computer-use] macOS 运行中应用枚举异常: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 已安装的应用（CCB {@code listInstalled}）。
     *
     * @return 应用列表（按 bundle id 去重，保留首次出现者 —— 与 CCB 同款）
     */
    public List<AppInfo> listInstalled() {
        try {
            ComputerUseProcessRunner.Result result = runner.run(List.of(
                    "bash", "-c", MacOsJxa.installedApplicationsShell()));
            if (!result.ok()) {
                log.warn("[computer-use] macOS 已安装应用枚举失败（退出码 {}）: {}",
                        result.exitCode(), result.stderr());
                return List.of();
            }
            return parseInstalled(result.stdout());
        } catch (Exception e) {
            log.warn("[computer-use] macOS 已安装应用枚举异常: {}", e.toString());
            return List.of();
        }
    }

    /**
     * 解析运行中应用的 JSON 数组（CCB {@code listRunning} 的输出形状）。
     *
     * @param stdout JXA 输出
     * @return 应用列表
     */
    static List<AppInfo> parseRunning(String stdout) {
        List<AppInfo> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return out;
        }
        try {
            JsonNode root = JSON.readTree(stdout.trim());
            if (root == null || !root.isArray()) {
                return out;
            }
            for (JsonNode node : root) {
                String bundleId = node.path("bundleId").asText("");
                String displayName = node.path("displayName").asText("");
                if (bundleId.isBlank() && displayName.isBlank()) {
                    continue;
                }
                out.add(new AppInfo(bundleId, displayName, ""));
            }
        } catch (Exception e) {
            log.warn("[computer-use] macOS 运行中应用 JSON 解析失败: {}", e.toString());
            return List.of();
        }
        return out;
    }

    /**
     * 解析已安装应用的行输出（CCB {@code listInstalled} 的
     * {@code path|displayName|bundleId} 行形状）。
     *
     * @param stdout 命令输出
     * @return 应用列表（按 bundle id 去重）
     */
    static List<AppInfo> parseInstalled(String stdout) {
        List<AppInfo> out = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) {
            return out;
        }
        Map<String, AppInfo> byBundleId = new LinkedHashMap<>();
        for (String line : stdout.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] parts = trimmed.split("\\|", 3);
            if (parts.length < 3) {
                continue;
            }
            String path = parts[0];
            String displayName = parts[1];
            String bundleId = parts[2];
            // CCB 的过滤：bundleId 为空或 "(null)"（mdls 未命中）⇒ 丢弃
            if (bundleId.isBlank() || "(null)".equals(bundleId)) {
                continue;
            }
            byBundleId.putIfAbsent(bundleId, new AppInfo(bundleId, displayName, path));
        }
        out.addAll(byBundleId.values());
        return out;
    }
}
