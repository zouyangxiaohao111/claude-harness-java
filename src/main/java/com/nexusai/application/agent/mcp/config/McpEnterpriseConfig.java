package com.nexusai.application.agent.mcp.config;

import com.nexusai.application.agent.mcp.EnvExpansion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * enterprise MCP 配置独占判定 · 对齐 CC {@code doesEnterpriseMcpConfigExist}
 * （config.ts:1471-1478）+ {@code getEnterpriseMcpFilePath}（config.ts:62-64）+
 * {@code managedPath.ts:8-25}。
 *
 * <p>语义（config !== null）：managed-mcp.json 解析后 config 非 null → 存在（独占）；
 * 文件缺失 / 读失败 / 非法 JSON / schema 不合法 → config null → 不存在（不独占不阻断）。
 * 注意「空文件 ≠ 存在」：零字节文件 {@code JSON.parse("")} 抛错 → config null → 不独占；
 * 而 {@code {"mcpServers":{}}}（合法但无 server）config 非 null → <b>存在即独占</b>。
 *
 * <p>路径按 OS（对齐 managedPath.ts:8-25）：macOS={@code /Library/Application Support/ClaudeCode}，
 * Windows={@code C:\Program Files\ClaudeCode}，其它={@code /etc/claude-code}。
 */
@Component
public class McpEnterpriseConfig {

    private static final Logger log = LoggerFactory.getLogger(McpEnterpriseConfig.class);

    /**
     * managed-mcp.json 路径测试注入缝（null = 走 OS 平台路径）。
     * <p>四态测试（存在/空/缺失/非法）需控制文件路径；生产默认 null → {@link #getEnterpriseMcpFilePath()}
     * 按 OS 解析。Spring 装配走无参构造（managedPathOverride=null），行为与改造前完全一致。
     */
    private final Supplier<String> managedPathOverride;

    /** Spring 装配：默认 OS 平台路径（无参构造，行为不变）。 */
    public McpEnterpriseConfig() {
        this.managedPathOverride = null;
    }

    /** 测试用 package-private 构造：注入 managed-mcp.json 文件路径（默认 = OS 平台路径）。 */
    McpEnterpriseConfig(String managedFilePath) {
        this.managedPathOverride = managedFilePath == null ? null : () -> managedFilePath;
    }

    /** 平台相关 managed 配置目录 + managed-mcp.json（对齐 config.ts:62-64 + managedPath.ts:8-25）。 */
    public String getEnterpriseMcpFilePath() {
        if (managedPathOverride != null) {
            return managedPathOverride.get();
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String dir;
        if (os.contains("mac")) {
            dir = "/Library/Application Support/ClaudeCode";
        } else if (os.contains("win")) {
            dir = "C:\\Program Files\\ClaudeCode";
        } else {
            dir = "/etc/claude-code";
        }
        return dir + "/managed-mcp.json";
    }

    /**
     * managed-mcp.json 是否存在（config !== null，CC config.ts:1471-1478）。
     *
     * <p>解析失败（ENOENT/读失败/非法 JSON/schema 不合法 → fatal error）→ false；
     * 解析通过（含空 {@code mcpServers}）→ true。
     */
    public boolean doesEnterpriseMcpConfigExist() {
        String filePath = getEnterpriseMcpFilePath();
        try {
            McpJsonConfigParser.ParseResult parsed = McpJsonConfigParser.parseMcpConfigFromFilePath(
                filePath, true, "enterprise", new EnvExpansion(), p -> Files.readString(Path.of(p)));
            boolean fatal = parsed.errors().stream()
                .anyMatch(err -> "fatal".equals(err.severity()));
            if (log.isDebugEnabled()) {
                log.debug("[McpEnterpriseConfig] managed-mcp.json 存在判定 file={} fatal={} servers={} → exists={}",
                    filePath, fatal, parsed.servers().size(), !fatal);
            }
            return !fatal;
        } catch (Exception e) {
            // 解析异常兜底按不存在（config:null 语义，不独占不阻断）
            log.warn("[McpEnterpriseConfig] managed-mcp.json 解析异常 file={}: {} → 不独占", filePath, e.getMessage());
            return false;
        }
    }
}
