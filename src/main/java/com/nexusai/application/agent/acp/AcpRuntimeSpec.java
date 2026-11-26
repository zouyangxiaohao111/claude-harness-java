package com.nexusai.application.agent.acp;

import com.nexusai.application.agent.subagent.AgentDefinition;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeEnvStore;
import com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * ACP 运行时的启动规格 · 与 {@code ExternalRuntimeSpec}（8 分量）<b>无继承无复用</b>。
 *
 * <p>为什么不能复用：{@code ExternalRuntimeSpec} 的 {@code argv} 是「一次性 CLI 的参数模板」，
 * {@code promptVia}/{@code streamFormat} 分别假设「任务从 argv/stdin 进、stdout 是 JSONL 事件」；
 * ACP 是<b>长连接 JSON-RPC</b>，任务走 {@code session/prompt}，三个字段全部不适用。
 *
 * <p>配置来源：{@code cli_runtime_config}（V261/V262）里 {@code runtime='acp'} 一行的
 * {@code bin / secrets}，由 {@link ExternalRuntimeEnvStore} 在启动期投影成内存快照。
 *
 * @param runtime         运行时 id（恒 {@code "acp"}）
 * @param bin             可执行文件绝对路径（已解析）
 * @param argv            除 bin 之外的参数（dsh 默认 {@code --profile acp}）
 * @param env             追加环境变量（secrets 原值转发；只进子进程，不写日志）
 * @param model           模型覆盖（可空；MVP 不经 session/set_config_option 下发）
 * @param cwd             工作目录（绝对）
 * @param timeoutMs       单次 prompt 等待上限
 * @param permissionPolicy ask / allow / reject
 */
public record AcpRuntimeSpec(
        String runtime,
        String bin,
        List<String> argv,
        Map<String, String> env,
        String model,
        Path cwd,
        long timeoutMs,
        String permissionPolicy
) {

    /** 运行时 id（唯一来源：{@link com.nexusai.model.acp.AcpRuntimeConstants#RUNTIME}）。 */
    public static final String RUNTIME = com.nexusai.model.acp.AcpRuntimeConstants.RUNTIME;

    /** 默认单次 prompt 超时（10 分钟，与 {@code ExternalAgentDefinition} 默认一致）。 */
    public static final long DEFAULT_TIMEOUT_MS = 600_000L;

    /** 默认 API key 变量名（唯一来源：{@link com.nexusai.model.acp.AcpRuntimeConstants#DEFAULT_API_KEY_ENV}）。 */
    public static final String DEFAULT_API_KEY_ENV = com.nexusai.model.acp.AcpRuntimeConstants.DEFAULT_API_KEY_ENV;

    /** 解析结果 · 不可用时不抛，带人话原因由调用方回落。 */
    public record Resolution(AcpRuntimeSpec spec, String unavailableReason) {
        public boolean isAvailable() {
            return spec != null && spec.bin() != null;
        }
    }

    /** 完整 argv（bin 打头）。 */
    public List<String> fullArgv() {
        List<String> all = new ArrayList<>(argv.size() + 1);
        all.add(bin);
        all.addAll(argv);
        return all;
    }

    /**
     * 从 agent 定义 + 设置页配置解析启动规格。
     *
     * <p>bin 优先级：md 的 {@code bin} → 设置页配的 bin → md 的 {@code fallbackBins} →
     * {@code dsh} → runtime 同名。⛔ 全部先经
     * {@link ExternalRuntimeSpec#resolveExecutable} 解析成绝对路径（Windows 上裸名找不到文件）。
     */
    public static Resolution tryResolve(AgentDefinition.ExternalAgentDefinition def, String model, Path cwd) {
        String rt = def.runtime() == null ? RUNTIME : def.runtime().trim();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        def.bin().ifPresent(candidates::add);
        String configured = ExternalRuntimeEnvStore.binFor(rt);
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.addAll(def.fallbackBins());
        candidates.add("dsh");
        candidates.add(rt);
        String resolvedBin = null;
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            String resolved = ExternalRuntimeSpec.resolveExecutable(candidate.trim());
            if (resolved != null) {
                resolvedBin = resolved;
                break;
            }
        }
        if (resolvedBin == null) {
            return new Resolution(null, "ACP 运行时可执行文件未找到（已尝试: "
                    + String.join(", ", candidates) + "）。请安装 ACP 适配器（如 dsh），"
                    + "或在设置 → 子代理运行时 → ACP 里填写 bin 绝对路径。");
        }

        List<String> args = new ArrayList<>(def.argsPrefix());
        if (!def.args().isEmpty()) {
            args.addAll(def.args());
        } else {
            args.addAll(defaultArgs(resolvedBin));
        }

        long timeout = def.timeoutMs() > 0 ? def.timeoutMs() : DEFAULT_TIMEOUT_MS;
        Path effectiveCwd = cwd != null ? cwd.toAbsolutePath()
                : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();

        return new Resolution(new AcpRuntimeSpec(
                rt, resolvedBin, List.copyOf(args), buildEnv(rt),
                model, effectiveCwd, timeout,
                AcpPermissionBridge.normalizePolicy(def.permissionMode().orElse(null))), null);
    }

    /**
     * 无 agent 定义时的独立解析（设置页「版本 / 测试连接」用）：
     * 设置页配的 bin → {@code dsh} → {@code acp}，argv/env 与真实派发同源。
     */
    public static Resolution resolveForRuntime(Path cwd) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String configured = ExternalRuntimeEnvStore.binFor(RUNTIME);
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.add("dsh");
        candidates.add(RUNTIME);
        String resolvedBin = null;
        for (String candidate : candidates) {
            String resolved = ExternalRuntimeSpec.resolveExecutable(candidate.trim());
            if (resolved != null) {
                resolvedBin = resolved;
                break;
            }
        }
        if (resolvedBin == null) {
            return new Resolution(null, "ACP 运行时可执行文件未找到（已尝试: "
                    + String.join(", ", candidates) + "）。请安装 ACP 适配器（如 dsh），"
                    + "或在设置 → 子代理运行时 → ACP 里填写 bin 绝对路径。");
        }
        Path effectiveCwd = cwd != null ? cwd.toAbsolutePath()
                : Path.of(System.getProperty("user.dir", ".")).toAbsolutePath();
        return new Resolution(new AcpRuntimeSpec(
                RUNTIME, resolvedBin, List.copyOf(defaultArgs(resolvedBin)), buildEnv(RUNTIME),
                null, effectiveCwd, DEFAULT_TIMEOUT_MS, AcpPermissionBridge.POLICY_ASK), null);
    }

    // ────────────────── ACP 适配器 argv 策略 ──────────────────
    // 为什么策略化（用户 2026-11-26）：原来按 bin 名 if/else；改成有序策略表后，
    // 新增一家适配器 = 加一个实现类 + 挂进 ARGV_STRATEGIES，dispatcher 不感知任何一家。
    // ⛔ 普通 interface + 静态 List，不进 Spring / 无 SPI 扫描。

    /** 一种适配器的「默认 argv」策略（bin 名 → 附加参数）。 */
    interface AcpArgvStrategy {
        /** @param binName 已转小写的可执行文件名（含扩展名） */
        boolean matches(String binName);

        List<String> args();
    }

    /** dsh：需要显式 profile。 */
    private static final class DshAcpArgv implements AcpArgvStrategy {
        @Override public boolean matches(String binName) {
            return binName.startsWith("dsh");
        }
        @Override public List<String> args() {
            return List.of("--profile", "acp");
        }
    }

    /** agy：ACP 入口是子命令 {@code acp}（{@code agy --help} 实测）。 */
    private static final class AgyAcpArgv implements AcpArgvStrategy {
        @Override public boolean matches(String binName) {
            return binName.startsWith("agy");
        }
        @Override public List<String> args() {
            return List.of("acp");
        }
    }

    /** 兜底：其它适配器默认无额外参数（由 md 的 args 自述）。 */
    private static final class DefaultAcpArgv implements AcpArgvStrategy {
        @Override public boolean matches(String binName) {
            return true;
        }
        @Override public List<String> args() {
            return List.of();
        }
    }

    /** 有序策略表：首个 matches 命中即用（Default 恒命中，必须排最后）。 */
    private static final List<AcpArgvStrategy> ARGV_STRATEGIES = List.of(
            new DshAcpArgv(), new AgyAcpArgv(), new DefaultAcpArgv());

    /** 按可执行文件名选默认 argv。 */
    static List<String> defaultArgs(String resolvedBin) {
        String name = Path.of(resolvedBin).getFileName().toString().toLowerCase();
        for (AcpArgvStrategy strategy : ARGV_STRATEGIES) {
            if (strategy.matches(name)) {
                return strategy.args();
            }
        }
        return List.of(); // 不可达（Default 恒命中）
    }

    /**
     * 需要追加给子进程的环境变量。
     *
     * <p>只做两件事：① 转发用户在 ACP 配置里填的 secrets（{@code __default__} 映射到
     * {@value #DEFAULT_API_KEY_ENV}）；② 递归深度声明（与一次性外部 CLI 同语义）。
     * ⛔ 不打印值、不落库。
     */
    static Map<String, String> buildEnv(String runtime) {
        Map<String, String> env = new LinkedHashMap<>();
        ExternalRuntimeEnvStore.Overlay overlay = ExternalRuntimeEnvStore.get(runtime);
        if (overlay != null && !overlay.secrets().isEmpty()) {
            for (Map.Entry<String, String> entry : overlay.secrets().entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null || value.isBlank()) {
                    continue;
                }
                if ("__default__".equals(key)) {
                    env.put(DEFAULT_API_KEY_ENV, value.trim());
                } else {
                    env.put(key, value.trim());
                }
            }
        }
        env.put("NEXUSAI_SUBAGENT_DEPTH", "1");
        env.put("PI_SUBAGENT_DEPTH", "1");
        env.put("NEXUSAI_SUBAGENT_MAX_DEPTH", "0");
        env.put("PI_SUBAGENT_MAX_DEPTH", "0");
        // 出站代理：agy（Google）在本机直连不通，必须继承应用的出站代理配置
        //   （设置页 settings.http_proxy → 环境变量 → 无）。未配置 ⇒ 不注入。
        // ⛔ 与 ExternalRuntimeSpec.outboundProxyEnv 同源，别在这里另写一份解析。
        env.putAll(com.nexusai.application.agent.subagent.external.ExternalRuntimeSpec.outboundProxyEnv());
        return Map.copyOf(env);
    }
}
