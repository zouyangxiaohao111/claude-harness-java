package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.hook.CommandHookExecutor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 权限更新辅助函数 · 对齐 CC 权限模块的自由函数：
 * <ul>
 *   <li>{@code extractRules} / {@code createReadRuleSuggestion}
 *       （{@code utils/permissions/PermissionUpdate.ts}）；</li>
 *   <li>{@code generateSuggestions}（{@code utils/permissions/filesystem.ts:1414-1478}，
 *       read/write/create 三分支）；</li>
 *   <li>{@code getDirectoryForPath}（{@code utils/path.ts:109-125}，目录展开辅助）。</li>
 * </ul>
 *
 * <p>CC 中这些函数是模块导出（非类型方法），Java 端沿用 {@link PermissionUpdateApplier} /
 * {@link PermissionUpdatePersister} 的既有惯例，收拢为静态工具类。
 *
 * <p>{@code hasRules}（CC {@code PermissionUpdate.ts:45-47}）仅前端 React 遥测
 * （hooks.ts:147）调用，Java 后端无对应需求 → 后端 N/A，不补（P2-G2-2 判定）。
 */
public final class PermissionUpdates {

    private static final Logger log = LoggerFactory.getLogger(PermissionUpdates.class);

    private PermissionUpdates() {
        // 工具类，禁止实例化
    }

    /**
     * 权限操作类型 · 对齐 CC {@code generateSuggestions} 的
     * {@code operationType: 'read' | 'write' | 'create'} 联合类型
     * （filesystem.ts:1416）。
     *
     * <p>Java 无字面量联合类型 → 以枚举表达三态。{@code create} 与 {@code write}
     * 在 CC generateSuggestions 中走同一分支（filesystem.ts:1448 {@code write || create}），
     * 故枚举仅作入参区分，不影响分支语义。
     */
    public enum OperationType {
        READ,
        WRITE,
        CREATE
    }

    /**
     * 从更新列表中抽取 addRules 型更新的规则 · 对齐 CC
     * {@code extractRules(updates: PermissionUpdate[] | undefined): PermissionRuleValue[]}
     * （PermissionUpdate.ts:30-43）。
     *
     * <p><b>CC 语义</b>：{@code !updates}（undefined）→ 返回空列表；
     * 否则 {@code flatMap} 中 {@code switch(update.type)} 仅 {@code 'addRules'}
     * 返回 {@code update.rules}，其余所有 type（replaceRules/removeRules/setMode/
     * addDirectories/removeDirectories）→ 空列表。即<b>只从 addRules 型更新抽规则</b>，
     * mode/directory/replace/remove 更新一律不产出规则。
     *
     * <p><b>Java 富类型差异</b>：CC 返回 {@code PermissionRuleValue[]}（toolName +
     * ruleContent 纯值），Java {@link PermissionUpdate.AddRules#rules()} 是
     * {@code List<PermissionRule>}（携带 source + ruleBehavior + ruleValue），
     * 故返回 {@code List<PermissionRule>} 保持富类型。
     *
     * @param updates 权限更新列表（可 null，null = CC undefined → 返回空列表）
     * @return 所有 addRules 型更新携带的规则（保序 flatMap；空列表若 null 或无 addRules）
     */
    public static List<PermissionRule> extractRules(List<PermissionUpdate> updates) {
        if (updates == null) {
            return List.of();
        }
        List<PermissionRule> extracted = new ArrayList<>();
        for (PermissionUpdate update : updates) {
            if (update instanceof PermissionUpdate.AddRules addRules) {
                extracted.addAll(addRules.rules());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("[PermissionUpdates] extractRules 抽取 addRules 规则: 输入更新数={} 抽出规则数={}",
                updates.size(), extracted.size());
        }
        return List.copyOf(extracted);
    }

    /**
     * 为目录构造 Read 规则建议 · 对齐 CC
     * {@code createReadRuleSuggestion(dirPath, destination='session')}
     * （PermissionUpdate.ts:361-389）。
     *
     * <p><b>CC 语义（读源码，非注释）</b>：
     * <ol>
     *   <li>{@code pathForPattern = toPosixPath(dirPath)}——Windows 反斜杠 → 正斜杠
     *       （filesystem.ts:187-192，Java 委托 {@link CommandHookExecutor#windowsPathToPosixPath}）；</li>
     *   <li>若 {@code pathForPattern === '/'}（根目录太宽，不是合理权限目标）→ 返回
     *       {@code undefined}（Java 端 {@link Optional#empty()}）；</li>
     *   <li>{@code ruleContent = posix.isAbsolute(pathForPattern) ? '/'+pathForPattern+'/**'
     *       : pathForPattern+'/**'}——绝对路径再前置一个 {@code /} 形成 {@code //path/**}
     *       （防止 {@code /path/**} 丢失根锚点，CC :374-376）；</li>
     *   <li>返回 {@code {type:'addRules', rules:[{toolName:'Read', ruleContent}],
     *       behavior:'allow', destination}}（CC :378-388）。</li>
     * </ol>
     *
     * <p>规则落地为 Java 富类型：{@code PermissionRule(SESSION, ALLOW,
     * PermissionRuleValue.withContent("Read", ruleContent))} + {@code AddRules(destination,
     * List.of(rule), ALLOW)}（source=SESSION——运行时临时授权建议，destination 默认 session）。
     *
     * @param dirPath     目录路径（CC original: dirPath, PermissionUpdate.ts:362）
     * @param destination 建议写到的 source（CC original: destination, PermissionUpdate.ts:363）
     * @return Read 规则建议（根目录 → {@link Optional#empty()}）
     */
    public static Optional<PermissionUpdate.AddRules> createReadRuleSuggestion(
            String dirPath, PermissionUpdate.Destination destination) {
        if (dirPath == null) {
            return Optional.empty();
        }
        // 1. POSIX 转换（Windows 反斜杠 → 正斜杠，CC toPosixPath filesystem.ts:187-192）
        String pathForPattern = toPosixPath(dirPath);

        // 2. 根目录太宽，不是合理权限目标（CC :369-371）
        if ("/".equals(pathForPattern)) {
            if (log.isDebugEnabled()) {
                log.debug("[PermissionUpdates] createReadRuleSuggestion 跳过根目录: dirPath={}", dirPath);
            }
            return Optional.empty();
        }

        // 3. 绝对路径再前置 / 形成 //path/**；相对路径直接 path/**（CC :373-376）
        boolean absolute = pathForPattern.startsWith("/");
        String ruleContent = absolute
            ? "/" + pathForPattern + "/**"
            : pathForPattern + "/**";

        PermissionRuleValue ruleValue = PermissionRuleValue.withContent("Read", ruleContent);
        PermissionRule rule = new PermissionRule(
            PermissionRuleSource.SESSION, PermissionBehavior.ALLOW, ruleValue);
        PermissionUpdate.AddRules addRules = new PermissionUpdate.AddRules(
            destination, List.of(rule), PermissionBehavior.ALLOW);

        if (log.isDebugEnabled()) {
            log.debug("[PermissionUpdates] createReadRuleSuggestion 产出 Read 规则: dirPath={} pathForPattern={} ruleContent={} destination={}",
                dirPath, pathForPattern, ruleContent, destination);
        }
        return Optional.of(addRules);
    }

    /**
     * 重载：destination 默认 {@link PermissionUpdate.Destination#SESSION}
     * （对齐 CC 缺省参数 {@code destination = 'session'}，PermissionUpdate.ts:363）。
     */
    public static Optional<PermissionUpdate.AddRules> createReadRuleSuggestion(String dirPath) {
        return createReadRuleSuggestion(dirPath, PermissionUpdate.Destination.SESSION);
    }

    /**
     * 取文件/目录路径对应的目录 · 对齐 CC {@code getDirectoryForPath}
     * （path.ts:109-125）：目录 → 返回自身；文件或不存在 → 返回父目录。
     *
     * <p>UNC 路径不做文件系统访问直接返回 dirname（防 NTLM 凭据泄露，CC :112-114）。
     * 入参恒为已展开的绝对路径（调用方 {@code expandPath} 结果），无需二次展开
     * （CC getDirectoryForPath 内部 {@code expandPath(path)} 的结果即本方法入参）。
     *
     * <p>本方法由 {@link ReadPermissionChecker} 私有方法迁出（RETAIN-gap GAP-3），
     * 供 {@link #generateSuggestions} 的 read/write/create 三分支复用目录展开。
     *
     * @param absolutePath 绝对路径（expandPath 结果）
     * @return 对应目录路径；无法确定父目录时回退原路径
     */
    public static String getDirectoryForPath(String absolutePath) {
        if (absolutePath == null) {
            return null;
        }
        // SECURITY: UNC 路径不做 stat（防 NTLM 凭据泄露，CC path.ts:112-114）
        if (absolutePath.startsWith("\\\\") || absolutePath.startsWith("//")) {
            Path p = Paths.get(absolutePath);
            return p.getParent() != null ? p.getParent().toString() : absolutePath;
        }
        try {
            if (Files.isDirectory(Paths.get(absolutePath))) {
                return Paths.get(absolutePath).toAbsolutePath().normalize().toString();
            }
        } catch (Exception e) {
            // 路径不存在或不可访问 → 落到父目录分支（CC path.ts:119-121 catch 忽略）
        }
        Path p = Paths.get(absolutePath).getParent();
        return p != null ? p.toString() : absolutePath;
    }

    /**
     * 构造权限建议 · 对齐 CC {@code generateSuggestions}（filesystem.ts:1414-1478），
     * 完整实现 read / write / create 三分支（RETAIN-gap GAP-3 闭环）。
     *
     * <h2>CC 语义（读源码，非注释）</h2>
     * <ol>
     *   <li>{@code isOutsideWorkingDir = !pathInAllowedWorkingPath(filePath, permCtx,
     *       precomputedPathsToCheck)}——Java 端该判定已在调用方经
     *       {@link ReadPermissionChecker#isInWorkingDir} 完成，故以 {@code isOutsideWorkingDir}
     *       布尔入参透传（避免静态工具类反向依赖 {@code ToolUseContext} 重复解析工作目录）；</li>
     *   <li>{@code read + outside} → 对 {@code getDirectoryForPath(filePath)} 展开目录逐条
     *       {@code createReadRuleSuggestion(dir, 'session')} 并 filter 空（CC :1426-1437）；</li>
     *   <li>{@code shouldSuggestAcceptEdits = mode === 'default' || mode === 'plan'}
     *       （CC :1440-1444 注释：auto/bypass/acceptEdits 时建议会静默降级，不建）；</li>
     *   <li>{@code write/create} → {@code shouldSuggestAcceptEdits} 时先 SetMode(acceptEdits)
     *       + outside 时再 AddDirectories({@code getPathsForPermissionCheck(dirPath)})
     *       （CC :1448-1463）；</li>
     *   <li>{@code read + inside} → 仅 {@code shouldSuggestAcceptEdits} 时 SetMode(acceptEdits)
     *       （CC :1466-1468）。</li>
     * </ol>
     *
     * @param filePath           文件路径（调用方已 expandPath 的绝对路径；可 null → 空列表）
     * @param operationType      操作类型 read/write/create（CC operationType, filesystem.ts:1416）
     * @param mode               当前权限模式（CC {@code toolPermissionContext.mode}, filesystem.ts:1441）
     * @param isOutsideWorkingDir 路径是否在工作目录外（CC pathInAllowedWorkingPath 反义，
     *                            调用方已计算）
     * @return 权限建议列表（空列表若无建议）
     */
    public static List<PermissionUpdate> generateSuggestions(
            String filePath,
            OperationType operationType,
            PermissionMode mode,
            boolean isOutsideWorkingDir) {
        if (filePath == null) {
            return List.of();
        }

        // read + outside → Read rule 建议（CC :1426-1437）
        if (operationType == OperationType.READ && isOutsideWorkingDir) {
            List<PermissionUpdate> suggestions = new ArrayList<>();
            String dirPath = getDirectoryForPath(filePath);
            if (dirPath != null) {
                for (String dir : PermissionPaths.getPathsForPermissionCheck(dirPath)) {
                    createReadRuleSuggestion(dir, PermissionUpdate.Destination.SESSION)
                        .ifPresent(suggestions::add);
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[PermissionUpdates] generateSuggestions(read+outside) 产出 Read 规则建议: filePath={} dirPath={} 建议数={}",
                    filePath, dirPath, suggestions.size());
            }
            return List.copyOf(suggestions);
        }

        // shouldSuggestAcceptEdits：仅 default/plan mode 才建议 acceptEdits
        // （CC :1440-1444 注释：auto/bypass/acceptEdits 时建议会静默降级，不建）
        boolean shouldSuggestAcceptEdits =
            mode == PermissionMode.DEFAULT || mode == PermissionMode.PLAN;

        // write/create → SetMode(acceptEdits) + (outside 时) AddDirectories（CC :1448-1463）
        if (operationType == OperationType.WRITE || operationType == OperationType.CREATE) {
            List<PermissionUpdate> updates = new ArrayList<>();
            if (shouldSuggestAcceptEdits) {
                updates.add(new PermissionUpdate.SetMode(
                    PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS));
            }
            if (isOutsideWorkingDir) {
                String dirPath = getDirectoryForPath(filePath);
                if (dirPath != null) {
                    List<String> dirsToAdd = PermissionPaths.getPathsForPermissionCheck(dirPath);
                    if (!dirsToAdd.isEmpty()) {
                        updates.add(new PermissionUpdate.AddDirectories(
                            PermissionUpdate.Destination.SESSION, dirsToAdd));
                    }
                }
            }
            if (log.isDebugEnabled()) {
                log.debug("[PermissionUpdates] generateSuggestions(write/create) 产出建议: filePath={} outside={} shouldSuggestAcceptEdits={} 建议数={}",
                    filePath, isOutsideWorkingDir, shouldSuggestAcceptEdits, updates.size());
            }
            return List.copyOf(updates);
        }

        // read + inside → 仅 SetMode(acceptEdits)（CC :1466-1468）
        if (log.isDebugEnabled()) {
            log.debug("[PermissionUpdates] generateSuggestions(read+inside) 产出建议: filePath={} shouldSuggestAcceptEdits={}",
                filePath, shouldSuggestAcceptEdits);
        }
        return shouldSuggestAcceptEdits
            ? List.of(new PermissionUpdate.SetMode(
                PermissionUpdate.Destination.SESSION, PermissionMode.ACCEPT_EDITS))
            : List.of();
    }

    /**
     * 路径 → POSIX 格式（供规则模式匹配）· 对齐 CC {@code toPosixPath}
     * （filesystem.ts:187-192）：Windows 走 {@link CommandHookExecutor#windowsPathToPosixPath}
     * （盘符/反斜杠转换），非 Windows 原样返回。
     */
    private static String toPosixPath(String path) {
        return CommandHookExecutor.isWindows()
            ? CommandHookExecutor.windowsPathToPosixPath(path)
            : path;
    }
}
