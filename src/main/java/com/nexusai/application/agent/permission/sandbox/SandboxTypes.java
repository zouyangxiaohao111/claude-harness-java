package com.nexusai.application.agent.permission.sandbox;

import java.util.List;
import java.util.Map;

/**
 * SandboxTypes · 对齐 CC entrypoints/sandboxTypes.ts:11-143 三个 zod schema 的值对象。
 *
 * <p>L1 语义: 沙箱配置的单一事实源 — 网络 / 文件系统 / 总设置。SDK 与 settings 校验共用。
 * 所有字段可选 (CC zod .optional()), Java 用 nullable 包装类型表达。
 *
 * <p>IMP-5（OPD-WF4-BC-02 拍板：归沙箱专项补字段）：SandboxSettings 由 6 字段补齐为
 * CC SandboxSettingsSchema（sandboxTypes.ts:91-144）全部 11 字段：
 * <pre>
 *   enabled / failIfUnavailable / autoAllowBashIfSandboxed / allowUnsandboxedCommands /
 *   network / filesystem / ignoreViolations / enableWeakerNestedSandbox /
 *   enableWeakerNetworkIsolation / excludedCommands / ripgrep
 * </pre>
 * 新增 5 字段：
 * <ul>
 *   <li>{@code ignoreViolations}（:116 z.record(string, array(string))）→ {@code Map&lt;String,List&lt;String&gt;&gt;}</li>
 *   <li>{@code enableWeakerNestedSandbox}（:117）→ {@code Boolean}</li>
 *   <li>{@code enableWeakerNetworkIsolation}（:118-128，macOS trustd 白名单）→ {@code Boolean}</li>
 *   <li>{@code excludedCommands}（:129）→ {@code List&lt;String&gt;}</li>
 *   <li>{@code ripgrep}（:130-136 {command, args?}）→ {@link RipgrepConfig}</li>
 * </ul>
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: NetworkConfig(7 字段) / FilesystemConfig(5 字段) / SandboxSettings(11 字段) record, 字段名 1:1</li>
 *   <li><b>A2 Golden Trace</b>: 构造 → 字段访问器返回原值</li>
 *   <li><b>A3 不可变</b>: record 自动 equals/hashCode; 可选字段 null 合法</li>
 *   <li><b>A4 边界</b>: 全 null 构造合法 (对齐 zod 全 optional); allowManagedDomainsOnly 语义保留</li>
 *   <li><b>A5 业务场景</b>: enterprise managed settings enabled=true + failIfUnavailable=true 硬门禁</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS zod lazySchema + .optional() → Java record + nullable 包装类型 (Boolean/Integer/List/Map)。
 */
public final class SandboxTypes {

    private SandboxTypes() {}

    /** CC SandboxNetworkConfigSchema (sandboxTypes.ts:14-43) */
    public record NetworkConfig(
        List<String> allowedDomains,
        Boolean allowManagedDomainsOnly,
        List<String> allowUnixSockets,
        Boolean allowAllUnixSockets,
        Boolean allowLocalBinding,
        Integer httpProxyPort,
        Integer socksProxyPort) {}

    /** CC SandboxFilesystemConfigSchema (sandboxTypes.ts:48-90) */
    public record FilesystemConfig(
        List<String> allowWrite,
        List<String> denyWrite,
        List<String> denyRead,
        List<String> allowRead,
        Boolean allowManagedReadPathsOnly) {}

    /** CC SandboxSettingsSchema (sandboxTypes.ts:91-144) · IMP-5 补齐 11 字段 */
    public record SandboxSettings(
        Boolean enabled,
        Boolean failIfUnavailable,
        Boolean autoAllowBashIfSandboxed,
        Boolean allowUnsandboxedCommands,
        NetworkConfig network,
        FilesystemConfig filesystem,
        Map<String, List<String>> ignoreViolations,
        Boolean enableWeakerNestedSandbox,
        Boolean enableWeakerNetworkIsolation,
        List<String> excludedCommands,
        RipgrepConfig ripgrep) {}

    /** CC ripgrep（sandboxTypes.ts:130-136）—— custom ripgrep configuration，command 必填 args 可选 */
    public record RipgrepConfig(
        String command,
        List<String> args) {}
}
