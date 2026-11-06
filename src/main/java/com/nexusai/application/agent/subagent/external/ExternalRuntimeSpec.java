package com.nexusai.application.agent.subagent.external;

import com.nexusai.application.agent.subagent.AgentDefinition;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部 CLI 运行时的调用规格 · 由 {@link AgentDefinition.ExternalAgentDefinition} 投影而来。
 *
 * <p>设计参照 GitHub OpenDesign 的 {@code RuntimeAgentDef}（docs/agent-adapters.md
 * "An adapter is a data spec, not a class"）：<b>纯数据 + 纯 arg-builder</b>，
 * 不为每个 CLI 写一个类。
 *
 * <p><b>简化口径（用户 2026-11-04）</b>：md 里**只需写 {@code runtime: claude}** ——
 * bin / argv / 输出协议全部来自本机扫描到的 {@link ExternalRuntimeCatalog} 预设。
 * {@code bin / args / streamFormat / argsPrefix} 都是**高级覆盖项**，不写就用预设。
 *
 * <p><b>连接信息不走 md</b>（用户 2026-11-04）：配置目录 / base URL / API key 全部来自
 * 设置页（{@code cli_runtime_config} 表），由 {@link ExternalRuntimeEnvStore} 在 spawn 前
 * 投影成该 CLI 认的环境变量。md 里写 {@code env} 的旧路径已移除 —— md 会进版本库，
 * 密钥写进去就是明文外泄。
 * 没装该 CLI 时 {@link #tryResolve} 返回人话原因，由调用方回落内置子代理。
 *
 * <p>⛔ 安全边界：argv 以**向量**形式交给 {@link java.lang.ProcessBuilder}，全程不经 shell，
 * 任务文本原样占一个 argv 位 —— 不存在命令注入面。
 */
public record ExternalRuntimeSpec(
        String runtime,
        String bin,
        List<String> argv,
        String promptVia,
        String streamFormat,
        long timeoutMs,
        Map<String, String> env,
        AgentDefinition.ExternalAgentDefinition.Capabilities capabilities
) {

    /** 任务文本占位符（argv 模板里替换）。 */
    public static final String PROMPT_PLACEHOLDER = "{prompt}";
    /** 模型占位符。 */
    public static final String MODEL_PLACEHOLDER = "{model}";
    /** 工作目录占位符。 */
    public static final String CWD_PLACEHOLDER = "{cwd}";

    public static boolean supportsStdin(String promptVia) {
        return "stdin".equalsIgnoreCase(promptVia);
    }

    /**
     * 解析结果 · 未就绪时**不抛**，把原因带回去由调用方决定回落。
     *
     * <p>WHY 不抛：外部运行时「没装」是**常见且可预期**的状态（不是崩溃）。
     * 用户 2026-11-04：「没有运行时会说明并采用默认的配置」⇒ 必须能拿到人话原因
     * 并继续用内置子代理，而不是整个委派失败。
     *
     * @param spec              可用时的调用规格；不可用时为 null
     * @param unavailableReason 不可用原因（人话，可直接展示/写日志）；可用时为 null
     */
    public record Resolution(ExternalRuntimeSpec spec, String unavailableReason) {
        public boolean isAvailable() {
            return spec != null;
        }
    }

    /**
     * 解析调用规格 · **只认 runtime 名字**，其余（bin / argv / 协议）全部来自扫描到的预设。
     *
     * <p>用户 2026-11-04 的简化口径：「选择 claude / opencode 等就行，不用配置 bin —— 扫描就有」。
     * 故 md 里正常只需写 {@code runtime: claude}；{@code bin/args/streamFormat} 仅在
     * 需要覆盖时才写（高级用法）。
     *
     * @return 解析结果；不可用时 {@code spec == null} 且带着人话原因（不抛）
     */
    public static Resolution tryResolve(AgentDefinition.ExternalAgentDefinition def,
                                        String model, Path cwd) {
        String rt = def.runtime() == null ? "" : def.runtime().trim();
        ExternalRuntimeCatalog.RuntimePreset preset = ExternalRuntimeCatalog.find(rt);

        // ① 预设不存在，且用户也没手写 args ⇒ 无法运行（说明原因）
        if (preset == null && def.args().isEmpty()) {
            return new Resolution(null,
                    "未知的外部运行时 '" + rt + "'，且未提供自定义 argv。可选："
                            + ExternalRuntimeCatalog.presets().stream()
                                    .map(ExternalRuntimeCatalog.RuntimePreset::runtime)
                                    .collect(java.util.stream.Collectors.joining(", ")));
        }

        // ② 参数来源：预设优先，用户写了的字段覆盖
        List<String> argvSource = !def.args().isEmpty() ? def.args()
                : (preset != null ? preset.args() : List.of());
        String streamFormat = def.streamFormat() != null && !def.streamFormat().isBlank()
                ? def.streamFormat()
                : (preset != null ? preset.streamFormat() : "plain");
        List<String> fallbackBins = !def.fallbackBins().isEmpty() ? def.fallbackBins()
                : (preset != null ? preset.fallbackBins() : List.of());
        String presetBin = preset != null ? preset.bin() : null;

        // ③ 找可执行文件：用户显式 bin > 设置页配的 bin > 预设 bin/fallback > runtime 同名
        //    设置页配的 bin 排第二：md 是「单个 agent 的覆盖」，设置页是「该运行时的全局覆盖」，
        //    粒度更细的 md 优先。
        String configuredBin = ExternalRuntimeEnvStore.binFor(rt);
        String resolvedBin;
        try {
            resolvedBin = resolveBin(def, configuredBin != null ? configuredBin : presetBin, fallbackBins);
        } catch (IllegalStateException e) {
            // 没装 ⇒ 人话说明（含「怎么装」），由调用方回落内置
            return new Resolution(null, e.getMessage());
        }

        // ④ argsPrefix 插在最前（CLI 前缀开关必须在位置参数之前）
        List<String> finalArgs = new ArrayList<>(def.argsPrefix());
        finalArgs.addAll(argvSource);

        // ⑤ 模型：优先级 = 本次调用传的 model > 设置页选的 > md 里写的
        String effectiveModel = (model != null && !model.isBlank()) ? model.trim()
                : (ExternalRuntimeEnvStore.modelFor(rt) != null
                        ? ExternalRuntimeEnvStore.modelFor(rt)
                        : def.model().orElse(null));
        String cwdStr = cwd != null ? cwd.toAbsolutePath().toString() : System.getProperty("user.dir", ".");

        List<String> argv = new ArrayList<>(finalArgs.size() + 4);
        for (String tpl : finalArgs) {
            if (tpl == null) continue;
            // {prompt} 保留给 runner 在真正 spawn 时替换（任务可能很大/含特殊字符）
            argv.add(tpl
                    .replace(MODEL_PLACEHOLDER, effectiveModel == null ? "" : effectiveModel)
                    .replace(CWD_PLACEHOLDER, cwdStr));
        }
        // ⑥ 模型 / 推理强度：只有预设的 args 里没手写对应标志时才追加
        //    （用户手写 args 时完全以他的为准，⛔ 不要再叠一份造成重复参数）
        boolean userWroteArgs = !def.args().isEmpty();
        if (preset != null && !userWroteArgs) {
            if (effectiveModel != null && argv.stream().noneMatch(a -> a.contains(effectiveModel))) {
                argv.addAll(ExternalRuntimeCatalog.modelArgs(preset, effectiveModel));
            }
            argv.addAll(ExternalRuntimeCatalog.reasoningArgs(preset,
                    ExternalRuntimeEnvStore.reasoningFor(rt)));
        }

        // 连接信息唯一来源：设置页（未配置 ⇒ 空 map ⇒ CLI 用自己的默认配置/登录态）
        Map<String, String> runtimeEnv = ExternalRuntimeEnvStore.envFor(preset);
        return new Resolution(new ExternalRuntimeSpec(rt, resolvedBin, List.copyOf(argv),
                def.promptVia(), streamFormat, def.timeoutMs(), runtimeEnv, def.capabilities()), null);
    }

    /**
     * 旧入口 · 不可用时抛 {@link IllegalStateException}。
     *
     * <p>⛔ 新代码用 {@link #tryResolve}（要能回落）；本方法只保留给「必须成功」的场景与既有单测。
     */
    public static ExternalRuntimeSpec of(AgentDefinition.ExternalAgentDefinition def,
                                         String model, Path cwd) {
        Resolution r = tryResolve(def, model, cwd);
        if (!r.isAvailable()) {
            throw new IllegalStateException(r.unavailableReason());
        }
        return r.spec();
    }

    /**
     * 生成最终 argv（替换 {@code {prompt}}）。
     *
     * @param prompt 任务文本
     * @return 可直接交给 ProcessBuilder 的向量
     */
    public List<String> buildArgv(String prompt) {
        String p = prompt == null ? "" : prompt;
        List<String> out = new ArrayList<>(argv.size());
        for (String a : argv) {
            out.add(a.replace(PROMPT_PLACEHOLDER, p));
        }
        return out;
    }

    /**
     * 找到可执行文件：定义里的 bin → 预设的 bin/fallbackBins → runtime 同名。
     *
     * <p>探测口径与 dsh-agent-conductor 的 {@code doctor} 同构：只解析 PATH / 绝对路径存在性，
     * ⛔ 不执行发现的二进制（避免「探一下」就触发登录/副作用）。
     *
     * <p><b>返回解析后的绝对路径，不是裸名字</b>（2026-11-04 真机踩到）：
     * Windows 上 npm 装的 {@code claude} 是**无扩展名的 bash 脚本**，真正可执行的是
     * {@code claude.cmd}。若只判存在却返回裸名，{@code ProcessBuilder} 会
     * {@code CreateProcess error=2, 系统找不到指定的文件}。
     */
    public static String resolveBin(AgentDefinition.ExternalAgentDefinition def,
                                    String baseBin, List<String> baseFallbackBins) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        def.bin().ifPresent(candidates::add);
        candidates.addAll(def.fallbackBins());
        if (baseBin != null && !baseBin.isBlank()) candidates.add(baseBin);
        if (baseFallbackBins != null) candidates.addAll(baseFallbackBins);
        candidates.add(def.runtime());
        for (String c : candidates) {
            if (c == null || c.isBlank()) continue;
            String resolved = resolveExecutable(c.trim());
            if (resolved != null) return resolved;
        }
        throw new IllegalStateException("外部运行时 '" + def.runtime() + "' 的可执行文件未找到（已尝试: "
                + String.join(", ", candidates) + "）。请先安装对应 CLI，或在 frontmatter 里用 bin 指定绝对路径。");
    }

    /** 兼容入口：等价于 baseBin=null（不查预设 bin）。 */
    public static String resolveBin(AgentDefinition.ExternalAgentDefinition def) {
        return resolveBin(def, null, List.of());
    }

    /**
     * 把一个候选名解析成**可直接 spawn 的绝对路径**。
     *
     * @return 绝对路径；找不到 → null
     */
    public static String resolveExecutable(String bin) {
        Path asPath = Path.of(bin);
        // 绝对路径 / 含分隔符的相对路径：按给定形态直判（用户点名了具体文件，尊重其选择）
        boolean hasSep = bin.contains("/") || bin.contains("\\");
        if (asPath.isAbsolute() || hasSep) {
            Path p = asPath.isAbsolute() ? asPath : asPath.toAbsolutePath();
            return Files.isRegularFile(p) ? p.toString() : null;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return null;
        String[] exts = executableExtensions();
        for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
            if (dir == null || dir.isBlank()) continue;
            for (String ext : exts) {
                Path candidate = Path.of(dir, bin + ext);
                if (Files.isRegularFile(candidate)) return candidate.toString();
            }
        }
        return null;
    }

    /**
     * 可执行扩展名优先级。
     *
     * <p>Windows 必须把 {@code .exe/.cmd/.bat} 排在**无扩展名之前**：npm 的全局 bin 目录里
     * 同时存在无扩展名的 POSIX sh 脚本和 {@code .cmd} 包装器，先命中前者会得到一个
     * 「存在但 Windows 跑不了」的路径。
     */
    private static String[] executableExtensions() {
        return isWindows()
                ? new String[]{".exe", ".cmd", ".bat", "", ".ps1"}
                : new String[]{""};
    }

    /** 探测显示名（诊断用）：只返回解析结果，不抛。 */
    public static Optional<String> tryResolveBin(AgentDefinition.ExternalAgentDefinition def) {
        try {
            return Optional.of(resolveBin(def));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** 便于日志的 env 摘要（值脱敏：只留 key）。 */
    public Map<String, String> envKeysForLog() {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : env.keySet()) m.put(k, "***");
        return m;
    }
}
