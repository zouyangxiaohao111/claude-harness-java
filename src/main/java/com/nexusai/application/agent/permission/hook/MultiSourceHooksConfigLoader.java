package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexusai.application.agent.agent.CwdResolution;
import com.nexusai.application.agent.settings.storage.ConfigStorage;
import com.nexusai.application.agent.skill.ClaudePaths;
import com.nexusai.application.agent.skill.NexusaiPaths;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * settings 多来源 hooks 配置加载链 · 对齐 CC {@code Open-ClaudeCode/src/utils/settings/settings.ts:645-796}
 * {@code loadSettingsFromDisk}（lodash mergeWith 深合并 + settingsMergeCustomizer 数组
 * concat+去重）+ {@code Open-ClaudeCode/src/utils/hooks/hooksConfigSnapshot.ts:104-112}
 * {@code updateHooksConfigSnapshot}（快照刷新，运行中配置变更生效，OD-15/16）。
 *
 * <p><b>来源顺序（优先级从低到高，后合并覆盖先合并）</b>：
 * <pre>
 *   userSettings  → ~/.{appName}/settings.json（NexusaiPaths.getAppConfigHomeDir()，决策 D2，
 *                   appName=nexusai → ~/.nexusai/settings.json；CC settings.ts 用户级，最低）
 *   projectSettings → &lt;projectRoot&gt;/.nexusai/settings.json      （项目共享，git 提交，D6 项目内 .nexusai）
 *   localSettings → &lt;projectRoot&gt;/.nexusai/settings.local.json（gitignored，最高 editable，D6 项目内 .nexusai）
 *   policySettings → nexusai.policy.path（企业 managed policy，只读，最高）
 * </pre>
 * 对齐 CC SETTING_SOURCES（constants.ts:7-22 顺序 user→project→local→flag→policy，后覆盖前）；
 * Java 端无 flagSettings 源（CLI flag 场景不存在，DIF-CFG-03 登记缺口），policy 仅
 * managed-file 层（{@link ManagedPolicySettingsSupplier}，与 {@code PolicySettingsLoader}
 * 同一配置键）。
 *
 * <p><b>DIF-CFG-02 部署差异登记（IMP-HOOKS-S1）</b>: CC policySettings 解析链为 4 层
 * first-wins —— remote（API）> MDM（HKLM/plist）> managed-settings.json(+d) > HKCU
 * （settings.ts:677-739, getSettingsForSourceUncached :319-345）。Java 部署模型仅存在
 * managed 文件层: RemoteManagedSettingsService.pollOnce 为 no-op stub 且未接入 hooks
 * policy supplier, MDM/HKCU 无对等基础设施 —— 企业分层 policy 部署时 hooks 门控取值
 * 仅文件层, 属登记差异非实现缺口（跨模块决策 OQ-03, 本 Session 不越界修改 settings 模块;
 * 若未来启用 remote 层需重新对齐）。
 *
 * <p><b>合并语义（settingsMergeCustomizer 等价，settings.ts:529-547）</b>：
 * <ul>
 *   <li>对象深合并（事件级 matcher 列表 concat）</li>
 *   <li>数组 concat + 去重：同一 (event, matcher, hook 内容) 只保留一条，last-wins
 *       （高优先级源覆盖低优先级源；等价 CC 匹配层 getMatchingHooks dedup 4 Map
 *       last-wins，hooks.ts:1603-1874）</li>
 *   <li>seenFiles 去重：同 resolvedPath 只处理一次（CC settings.ts:746-747，
 *       如 user.home==nexusai.home 时 user/project 同文件）</li>
 *   <li>加载失败不中断（CC lenient：解析失败 warn + 跳过，单源失败不影响其它源）</li>
 * </ul>
 *
 * <p><b>快照刷新接线（对齐 CC 3 调用点）</b>：
 * <ol>
 *   <li>{@link #init()} @PostConstruct 启动加载（CC setup.ts:166 capture 时机）</li>
 *   <li>{@link #updateHooksConfigSnapshot()} 供 ExitWorktreeTool / SettingsService
 *       调用（CC ExitWorktreeTool.ts:140 / applySettingsChange.ts:42，运行中变更生效）</li>
 * </ol>
 * 每次调用均重读盘（Java 端无 session 缓存，等价 CC resetSettingsCache + recapture）。
 *
 * <p><b>DEL-CFG-01 替代</b>：本类取代 {@code HooksConfigLoader}（旧单源 USER_SETTINGS
 * @PostConstruct 加载，EV-CFG-011 配置链全断风险）。删除前本类必须 GREEN 接管。
 *
 * <p><b>local-only 约束</b>：本类仅本地文件读取与内存快照，不向外发送任何数据。
 */
@Component
public class MultiSourceHooksConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceHooksConfigLoader.class);

    /** 共享 settings 文件名（user/project 共用）。 */
    private static final String SETTINGS_FILE = "settings.json";
    /** 本地覆盖 settings 文件名（gitignored）。 */
    private static final String LOCAL_SETTINGS_FILE = "settings.local.json";

    private final ObjectMapper objectMapper;
    private final HooksSettings hooksSettings;
    private final HooksConfigSnapshot hooksConfigSnapshot;
    private final ManagedPolicySettingsSupplier policySettingsSupplier;
    private final Supplier<String> projectRootSupplier;
    private final String userHome;

    /** allowlist 键形状非法哨兵（存在但非字符串数组 → 整层无效, DIF-CFG-05）. */
    private static final List<String> INVALID_ARRAY = java.util.Collections.emptyList();

    /**
     * Spring 生产构造器 · 项目根惰性接线 {@code CwdResolution.getOriginalCwdLayer()}
     * （语义 = D6 项目根；无会话回落 {@code user.dir}）；userHome 保留（user settings 已走
     * {@link NexusaiPaths#getAppConfigHomePath()}）。{@code nexusai.home} 已废弃，不再经
     * {@code @Value} 注入。
     *
     * @param objectMapper           Jackson（Spring bean，HookCommand sealed 反序列化）
     * @param hooksSettings          hook 配置存储（bySource 写入入口 loadFromSource）
     * @param hooksConfigSnapshot    快照层（capture/update）
     * @param policySettingsSupplier 企业 managed policy 读取器（policy hooks 源）
     * @param userHome               用户主目录（{@code @Value("${user.home}")}）
     */
    @Autowired
    public MultiSourceHooksConfigLoader(ObjectMapper objectMapper,
                                        HooksSettings hooksSettings,
                                        HooksConfigSnapshot hooksConfigSnapshot,
                                        ManagedPolicySettingsSupplier policySettingsSupplier,
                                        @Value("${user.home}") String userHome) {
        this(objectMapper, hooksSettings, hooksConfigSnapshot, policySettingsSupplier,
            CwdResolution::getOriginalCwdLayer, userHome);
    }

    /**
     * 注入式构造器（测试 / 手动接线）。
     *
     * @param objectMapper           Jackson（Spring bean，HookCommand sealed 反序列化）
     * @param hooksSettings          hook 配置存储（bySource 写入入口 loadFromSource）
     * @param hooksConfigSnapshot    快照层（capture/update）
     * @param policySettingsSupplier 企业 managed policy 读取器（policy hooks 源）
     * @param projectRootSupplier    项目根惰性供应（决策 D6 项目根，project/local 路径基；生产接
     *                               {@code CwdResolution.getOriginalCwdLayer()}，无会话回落
     *                               {@code user.dir}；null 空安全回退 user.dir）
     * @param userHome               保留：历史构造参数 / 测试 API 兼容；user settings 路径已改走
     *                               {@link NexusaiPaths#getAppConfigHomePath()}（userHome 不再参与
     *                               userSettingsPath，决策 D2）
     */
    public MultiSourceHooksConfigLoader(ObjectMapper objectMapper,
                                        HooksSettings hooksSettings,
                                        HooksConfigSnapshot hooksConfigSnapshot,
                                        ManagedPolicySettingsSupplier policySettingsSupplier,
                                        Supplier<String> projectRootSupplier,
                                        String userHome) {
        if (objectMapper == null) throw new IllegalArgumentException("objectMapper is null");
        if (hooksSettings == null) throw new IllegalArgumentException("hooksSettings is null");
        if (hooksConfigSnapshot == null) throw new IllegalArgumentException("hooksConfigSnapshot is null");
        if (userHome == null || userHome.isBlank()) {
            throw new IllegalArgumentException("user.home is blank");
        }
        this.objectMapper = objectMapper;
        this.hooksSettings = hooksSettings;
        this.hooksConfigSnapshot = hooksConfigSnapshot;
        // policy 源允许为空（无企业管控场景 → 跳过 policy 合并）
        this.policySettingsSupplier = policySettingsSupplier;
        this.projectRootSupplier = projectRootSupplier != null
                ? projectRootSupplier
                : () -> System.getProperty("user.dir");
        this.userHome = userHome;
    }

    /**
     * 启动时多来源加载 + 捕获快照 · 对齐 CC setup.ts:166 {@code captureHooksConfigSnapshot}
     * 时机（HooksConfigLoader @PostConstruct 的替代，DEL-CFG-01）。
     *
     * <p>WHY: 启动即按 user→project→local→policy 顺序合并 hooks 配置写入 bySource，
     * 刷新快照（快照为 null 时 update 与 capture 等价——Java 端每次重读盘）。
     */
    @PostConstruct
    public void init() {
        try {
            reloadFromDisk();
            hooksConfigSnapshot.updateHooksConfigSnapshot();
            if (log.isInfoEnabled()) {
                log.info("MultiSourceHooksConfigLoader: 启动多来源加载完成, bySource 事件数={}",
                    hooksSettings.getFor(HookSource.USER_SETTINGS.name()).size()
                        + hooksSettings.getFor(HookSource.PROJECT_SETTINGS.name()).size()
                        + hooksSettings.getFor(HookSource.LOCAL_SETTINGS.name()).size()
                        + hooksSettings.getFor(HookSource.POLICY_SETTINGS.name()).size());
            }
        } catch (Exception e) {
            // 启动加载失败不中断（对齐 CC lenient；单源失败已在源级吞掉，此处兜底）
            log.warn("MultiSourceHooksConfigLoader: 启动加载失败, 快照可能为空: {}", e.toString());
        }
    }

    /**
     * 运行中快照刷新 · 对齐 CC hooksConfigSnapshot.ts:104-112 {@code updateHooksConfigSnapshot}
     * （= resetSettingsCache + 重新 capture）。
     *
     * <p><b>接线点</b>：ExitWorktreeTool（CC ExitWorktreeTool.ts:140 restoreSessionToOriginalCwd
     * 后重读 hooks）/ SettingsService REST 更新（CC applySettingsChange.ts:42）。
     * 运行中改 settings 文件 → 调用本方法 → 新 hook 配置生效（不重启）。
     *
     * <p>Java 端无 session 缓存（每次重读盘），等价 CC 的 resetSettingsCache 语义。
     */
    public synchronized void updateHooksConfigSnapshot() {
        reloadFromDisk();
        hooksConfigSnapshot.updateHooksConfigSnapshot();
        if (log.isDebugEnabled()) {
            log.debug("MultiSourceHooksConfigLoader: 快照已刷新 (运行中配置变更生效)");
        }
    }

    /**
     * [H-WF1-01] 插件相关 settings 快照 · 对齐 CC loadPluginHooks.ts:233-247
     * {@code getPluginAffectingSettingsSnapshot}（loadPluginHooks.ts:255-287 热重载判定的 diff 源）。
     *
     * <p>哈希 4 字段（CC :241-246）：{@code enabledPlugins} + {@code extraKnownMarketplaces}
     * 取自 merged settings（CC {@code getSettings_DEPRECATED} = {@code getInitialSettings}，
     * settings.ts:812/820），{@code strictKnownMarketplaces} + {@code blockedMarketplaces}
     * 仅取自 policySettings（CC {@code getSettingsForSource('policySettings')}）。
     *
     * <p><b>合并语义 = 对象键深合并（非 last-wins 整体替换）</b>：CC getInitialSettings
     * 逐源 {@code mergeWith(merged, settings, settingsMergeCustomizer)} 深合并
     * （settings.ts:663/724/761/776，源序 user→project→local→policy 低→高）；{@code
     * settingsMergeCustomizer}（settings.ts:534-547）仅对数组 concat+去重（settings.ts:529-531
     * {@code uniq([...target, ...source])}），对象字段走 lodash 默认深合并 —— 高源覆盖重叠键、
     * 低源非重叠键保留。Java 等价：{@link #deepMergedSettingsMap(String)} 逐源对象键合并。
     *
     * <p>WHY 4 字段而非仅 enabledPlugins（CC :220-230 注释，#23085/#23152 poisoned-cache）：
     * memoized loadAllPluginsCacheOnly 还读 strictKnownMarketplaces / blockedMarketplaces
     * （pluginLoader.ts:1933 getBlockedMarketplaces）与 extraKnownMarketplaces —— 仅 keyed on
     * enabledPlugins 时 remote managed 只设其中一个字段会漏 diff，listener 跳过 → memoize
     * 保留旧 marketplaces allow/blocklist。
     *
     * <p>键排序（CC sortKeys :239-240）防 Record 字段插入序翻动哈希；数组字段
     * schema-stable 序。返回确定性字符串，仅用于热重载变更检测（loadPluginHooks.ts:266-272）。
     *
     * @return 确定性 JSON 快照串；读取/序列化异常 → 降级确定性串（不抛，watcher 热重载链继续）
     */
    public String pluginAffectingSettingsSnapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("enabledPlugins", sortedMap(deepMergedSettingsMap("enabledPlugins")));
        snapshot.put("extraKnownMarketplaces", sortedMap(deepMergedSettingsMap("extraKnownMarketplaces")));
        snapshot.put("strictKnownMarketplaces", sortedList(readPolicyStringArray("strictKnownMarketplaces")));
        snapshot.put("blockedMarketplaces", sortedList(readPolicyStringArray("blockedMarketplaces")));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (Exception e) {
            // fail-safe：序列化失败回退确定性串（不抛 → watcher 继续, CC jsonStringify 不抛）
            return "err:" + snapshot.hashCode();
        }
    }

    /**
     * [H-WF1-01] 插件相关对象键（enabledPlugins/extraKnownMarketplaces）的 CC 等价深合并视图。
     *
     * <p>对齐 CC getInitialSettings（settings.ts:663/724/761/776）逐源 mergeWith + 对象默认
     * 深合并（settingsMergeCustomizer 仅数组特殊，settings.ts:534-547）：user→project→local→
     * policy 低→高，高源覆盖重叠键、低源非重叠键保留；同一键双方均为对象 → 递归合并，双方
     * 均为数组 → concat+去重（{@link #mergeArraysNode}），其余 → 源值覆盖。
     *
     * @param key settings 顶层对象键（enabledPlugins / extraKnownMarketplaces）
     * @return 深合并后的 {@code Map<String,Object>}（值为 JsonNode；全源缺失 → 空 Map）
     */
    private Map<String, Object> deepMergedSettingsMap(String key) {
        ObjectNode merged = buildMergedTopLevelObjectNode(key);
        return merged.isEmpty()
            ? new LinkedHashMap<>()
            : objectMapper.convertValue(merged, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * [IMP-CF-03] 读取 merged settings 顶层对象键 · 等价 CC {@code getSettings_DEPRECATED()?.[key]}
     * （= {@code getInitialSettings()?.[key]}，settings.ts:812-815）的 Java 等价。
     *
     * <p>供 statusLine/fileSuggestion 后端执行器（{@link HookRegistry#executeStatusLineCommand} /
     * {@link HookRegistry#executeFileSuggestionCommand}）在<b>非 managedOnly</b> 分支读取
     * 合并后的顶层 statusLine/fileSuggestion 配置（CC {@code executeStatusLineCommand}
     * utils/hooks.ts:4608-4610 的 {@code getSettings_DEPRECATED()?.statusLine} 分支）。
     *
     * <p>合并语义 = {@code getInitialSettings} 逐源 mergeWith（settings.ts:663/724/761/776，
     * 源序 user→project→local→policy 低→高，对象键深合并、数组 concat+去重，高源覆盖重叠键）——
     * 与 {@link #deepMergedSettingsMap} 同一合并实现（本方法为其 JsonNode 版出口）。
     *
     * @param key settings 顶层对象键（如 statusLine / fileSuggestion）
     * @return 深合并后的对象节点；全源缺失或非对象 → null（CC undefined 等价）
     */
    public JsonNode mergedTopLevelObject(String key) {
        ObjectNode merged = buildMergedTopLevelObjectNode(key);
        return merged.isEmpty() ? null : merged;
    }

    /** 构建单顶层对象键的跨源深合并 ObjectNode（user→project→local→policy，含 policy 键深合并）。 */
    private ObjectNode buildMergedTopLevelObjectNode(String key) {
        ObjectNode merged = objectMapper.createObjectNode();
        mergeSettingsKeyInto(merged, key, userSettingsPath());
        mergeSettingsKeyInto(merged, key, projectSettingsPath());
        mergeSettingsKeyInto(merged, key, localSettingsPath());
        if (policySettingsSupplier != null) {
            Object policy = policySettingsSupplier.get(key);
            if (policy != null && policy != ConfigStorage.NullMarker) {
                JsonNode pn = (policy instanceof JsonNode node) ? node : objectMapper.valueToTree(policy);
                if (pn.isObject()) {
                    mergeObjectNodes(merged, pn);
                }
            }
        }
        return merged;
    }

    /** 单 settings 文件对象键 → 深合并进累计 ObjectNode（文件缺失/键非对象 → 跳过）。 */
    private void mergeSettingsKeyInto(ObjectNode target, String key, Path path) {
        Object raw = readFileSettingsKey(path, key);
        if (raw instanceof JsonNode node && node.isObject()) {
            mergeObjectNodes(target, node);
        }
    }

    /**
     * lodash mergeWith + settingsMergeCustomizer 的对象合并等价（settings.ts:534-547 + :663-779）：
     * 双方均为对象 → 递归合并；双方均为数组 → concat+去重；其余 → 源值覆盖。
     */
    private static void mergeObjectNodes(ObjectNode target, JsonNode src) {
        if (src == null || !src.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = src.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            String name = e.getKey();
            JsonNode sv = e.getValue();
            JsonNode tv = target.get(name);
            if (tv != null && tv.isObject() && sv.isObject()) {
                mergeObjectNodes((ObjectNode) tv, sv);
            } else if (tv != null && tv.isArray() && sv.isArray()) {
                target.set(name, mergeArraysNode((ArrayNode) tv, (ArrayNode) sv));
            } else {
                target.set(name, sv);
            }
        }
    }

    /**
     * CC {@code mergeArrays} 等价（settings.ts:529-531 {@code uniq([...target, ...source])}，
     * 首现保留）：target 数组 + 追加 source 中未出现元素（JSON 文本等价判重）。
     */
    private static ArrayNode mergeArraysNode(ArrayNode target, ArrayNode src) {
        Set<String> seen = new HashSet<>();
        for (JsonNode n : target) {
            seen.add(n.toString());
        }
        for (JsonNode n : src) {
            if (seen.add(n.toString())) {
                target.add(n);
            }
        }
        return target;
    }

    /**
     * 读取单个 settings 文件顶层键 · 等价 CC parseSettingsFile 后取键（对象/数组 → 原始
     * {@link JsonNode}）。文件缺失/非对象/键缺失 → null（lenient：单文件失败不中断）。
     */
    private Object readFileSettingsKey(Path path, String key) {
        Path resolved = path.toAbsolutePath().normalize();
        if (!Files.exists(resolved)) {
            return null;
        }
        try {
            JsonNode root;
            try (var parser = objectMapper.getFactory().createParser(resolved.toFile())) {
                root = objectMapper.readTree(parser);
            }
            if (root == null || !root.isObject()) {
                return null;
            }
            JsonNode node = root.get(key);
            if (node == null || node.isNull()) {
                return null;
            }
            return node;
        } catch (IOException e) {
            // lenient：单文件读取失败 → null（对齐 CC 源级跳过，不中断其它源）
            return null;
        }
    }

    /** policy 键 → 字符串数组 · 等价 CC {@code getSettingsForSource('policySettings')?.[key] ?? []}。 */
    private List<String> readPolicyStringArray(String key) {
        if (policySettingsSupplier == null) {
            return List.of();
        }
        Object v = policySettingsSupplier.get(key);
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                if (o != null) {
                    out.add(String.valueOf(o));
                }
            }
        } else if (v instanceof JsonNode node && node.isArray()) {
            node.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    /** 键排序 Map（CC sortKeys :239-240 等价；null-safe → 空 TreeMap）。 */
    private static Map<String, Object> sortedMap(Map<String, Object> m) {
        return new TreeMap<>(m);
    }

    /** 排序数组（CC 数组字段 schema-stable 序显式排序）。 */
    private static List<String> sortedList(List<String> list) {
        List<String> sorted = new ArrayList<>(list);
        Collections.sort(sorted);
        return sorted;
    }

    /**
     * 从 4 个来源重读 hooks 配置并写入 HooksSettings.bySource（对齐 CC loadSettingsFromDisk
     * 循环 settings.ts:674-784；seenFiles 去重 settings.ts:746-747）。
     *
     * <p>合并发生在 {@link HooksSettings#getAllHooks()} 消费侧（对象深合并 + 数组
     * concat+去重 last-wins），本方法只负责逐源解析 + 写入（bySource 按源隔离）。
     *
     * <p><b>H1 (IMP-HOOKS-S1 / DIF-CFG-01)</b>: 逐源收集顶层 {@code allowedHttpHookUrls} /
     * {@code httpHookAllowedEnvVars}, 按 user→project→local→policy 顺序
     * {@link LinkedHashSet} 保序去重累加（对齐 CC settings.ts:529-531 mergeArrays=
     * uniq([...target,...source])），每次加载<b>无条件</b>调用
     * {@link HooksSettings#setMergedHttpHookPolicy(List, List)} 刷新 —— 任一源配置
     * （含显式空数组=全拦三态保留）注入合并结果, 全部源未配置注入 null（undefined=
     * 不限制; 无条件刷新避免"先配置后取消配置"保留旧值, 对齐 CC getInitialSettings
     * 每次从盘重建）—— CC execHttpHook.ts:49-58 getHttpHookPolicy 读该全源合并视图。
     */
    private void reloadFromDisk() {
        Set<String> seenFiles = new LinkedHashSet<>();
        // merged HTTP hook allowlist 累加器（跨源 concat 保序去重）
        AllowlistAccumulator allowlist = new AllowlistAccumulator();

        // 1. userSettings（最低优先级，CC ~/.claude/settings.json）
        //    [T3 hook 读兼容] nexusai 优先 + claude 只读回落：claude 在前、nexusai 在后
        //    （loadSourceFileWithClaudeFallback 合并进同一 source，getAllHooks last-wins 保证
        //    nexusai 同名覆盖 claude、claude 独有正常加载，对齐 skills/commands 双目录语义）。
        SourceLoadResult r = loadSourceFileWithClaudeFallback(HookSource.USER_SETTINGS,
            userClaudeSettingsPath(), userSettingsPath(), seenFiles);
        Boolean disableAll = r.disableAllHooks();
        allowlist.add(r);
        // 2. projectSettings（项目共享）
        r = loadSourceFileWithClaudeFallback(HookSource.PROJECT_SETTINGS,
            projectClaudeSettingsPath(), projectSettingsPath(), seenFiles);
        if (r.disableAllHooks() != null) disableAll = r.disableAllHooks();
        allowlist.add(r);
        // 3. localSettings（gitignored，最高 editable 优先级）
        r = loadSourceFileWithClaudeFallback(HookSource.LOCAL_SETTINGS,
            localClaudeSettingsPath(), localSettingsPath(), seenFiles);
        if (r.disableAllHooks() != null) disableAll = r.disableAllHooks();
        allowlist.add(r);
        // 4. policySettings（企业 managed，最高优先级；仅 managed-file 层）
        r = loadPolicySource();
        if (r.disableAllHooks() != null) disableAll = r.disableAllHooks();
        allowlist.add(r);

        // merged 顶层 disableAllHooks → 注入 HooksSettings (CC getInitialSettings 全源合并,
        // 标量 last-wins: settings.ts:674-729 mergeWith; hooksConfigSnapshot.ts:47-49 判定).
        // [EX_G_DisableAllHooks R1 残留修复] 取代已删除的 FileConfigStorage 单文件通道.
        hooksSettings.setDisableAllHooksMerged(Boolean.TRUE.equals(disableAll));

        // merged allowlist → 无条件刷新注入 HooksSettings (H1 / DIF-CFG-01):
        // 本次加载结果即当前视图（任一源配置 → 合并结果; 全部未配置 → null=不限制,
        // 对齐 CC getInitialSettings 每次从盘重建, 无 stale 旧值）
        hooksSettings.setMergedHttpHookPolicy(
            allowlist.urlsConfigured() ? List.copyOf(allowlist.urls()) : null,
            allowlist.envVarsConfigured() ? List.copyOf(allowlist.envVars()) : null);
    }

    /**
     * 单源解析结果 · disableAllHooks 顶层标量 + HTTP hook allowlist.
     *
     * <p>三态语义（对齐 CC）: {@code null} = 未配置（不参与合并）; 空 list = 显式空数组
     * （全拦）; 非空 = 有值. hooks 缺失/整层无效时两 allowlist 均为 null.
     */
    private record SourceLoadResult(Boolean disableAllHooks,
                                    List<String> allowedHttpHookUrls,
                                    List<String> httpHookAllowedEnvVars) {
        /** 无结果常量（文件缺失/跳过/整层无效）: 三个字段均 null. */
        static final SourceLoadResult NONE = new SourceLoadResult(null, null, null);
    }

    /**
     * merged allowlist 累加器 · LinkedHashSet 保序去重（对齐 CC mergeArrays
     * uniq([...target,...source]) 首现保留, settings.ts:529-531）.
     */
    private static final class AllowlistAccumulator {
        private final LinkedHashSet<String> urls = new LinkedHashSet<>();
        private final LinkedHashSet<String> envVars = new LinkedHashSet<>();
        private boolean urlsConfigured;
        private boolean envVarsConfigured;

        List<String> urls() {
            return new ArrayList<>(urls);
        }

        List<String> envVars() {
            return new ArrayList<>(envVars);
        }

        boolean urlsConfigured() {
            return urlsConfigured;
        }

        boolean envVarsConfigured() {
            return envVarsConfigured;
        }

        void add(SourceLoadResult r) {
            if (r.allowedHttpHookUrls() != null) {
                urlsConfigured = true;
                urls.addAll(r.allowedHttpHookUrls());
            }
            if (r.httpHookAllowedEnvVars() != null) {
                envVarsConfigured = true;
                envVars.addAll(r.httpHookAllowedEnvVars());
            }
        }
    }

    /** user 源 settings 路径 · 决策 D2 用户级改走 {@link NexusaiPaths#getAppConfigHomePath()} */
    private Path userSettingsPath() {
        return NexusaiPaths.getAppConfigHomePath().resolve(SETTINGS_FILE);
    }

    private Path projectSettingsPath() {
        // 项目级配置目录名动态化（决策 D1/D6）：NexusaiPaths.getProjectDirName() = "." + appName
        // （生产 appName=nexusai → .nexusai；appName 变则项目级目录名全联动）
        return Paths.get(projectRootSupplier.get(), NexusaiPaths.getProjectDirName(), SETTINGS_FILE);
    }

    private Path localSettingsPath() {
        return Paths.get(projectRootSupplier.get(), NexusaiPaths.getProjectDirName(), LOCAL_SETTINGS_FILE);
    }

    // ── [T3 hook 读兼容] claude 只读回落路径（nexusai 优先 + claude 回落，对齐 skills/commands）──

    /** claude 用户级 hooks settings 回落源 · CC {@code getClaudeConfigHomeDir()/settings.json}
     *  （尊重 CLAUDE_CONFIG_DIR env；D2 只读兼容，不写入）。 */
    private Path userClaudeSettingsPath() {
        return Paths.get(ClaudePaths.getClaudeConfigHomeDir(), SETTINGS_FILE);
    }

    /** claude 项目级 hooks settings 回落源 · {@code <projectRoot>/.claude/settings.json}（D6 只读回落）。 */
    private Path projectClaudeSettingsPath() {
        return Paths.get(projectRootSupplier.get(), ".claude", SETTINGS_FILE);
    }

    /** claude 项目级本地 hooks settings 回落源 · {@code <projectRoot>/.claude/settings.local.json}。 */
    private Path localClaudeSettingsPath() {
        return Paths.get(projectRootSupplier.get(), ".claude", LOCAL_SETTINGS_FILE);
    }

    /**
     * 读取单个 settings 文件并写入对应 source · seenFiles 按绝对路径去重
     * （CC settings.ts:743-747：同 resolvedPath 只处理一次）。
     *
     * <p>lenient：文件缺失/损坏 → 跳过 + debug/warn，不中断其它源。
     *
     * <p><b>DIF-CFG-05（IMP-HOOKS-S1）</b>: 对齐 CC {@code parseSettingsFile}
     * （settings.ts:749-758 经 SettingsSchema() zod 整文件校验, types.ts:435-437/:480-493）
     * —— hooks 存在但非对象 / allowlist 非字符串数组 → 该源<b>整层视为无效</b>
     * （settings=null, 顶层 disableAllHooks 与 allowlist 一并丢弃）; hooks 缺失/NullMarker
     * 仍合法（schema 中 optional）保留 disableAllHooks 与 allowlist.
     *
     * <p><b>H1（IMP-HOOKS-S1 / DIF-CFG-01）</b>: 同时收集顶层 allowlist 键返回给
     * 调用方（merged 累加, 对齐 CC getInitialSettings 全源合并, execHttpHook.ts:53-57）.
     *
     * @return {@link SourceLoadResult}; 文件缺失/跳过/整层无效 → {@link SourceLoadResult#NONE}
     */
    /**
     * [T3 hook 读兼容] 带 claude 只读回落加载：claude 文件（若存在）解析在前、nexusai 主文件在后，
     * 合并进同一 source 单次写入 HooksSettings。
     *
     * <p>合并语义（对齐 skills/commands 双目录 T3）：claude configs 在前、nexusai configs 在后 →
     * {@code getAllHooks} last-wins 折叠保证<b>同名 hook nexusai 覆盖 claude</b>（identity 相同）、
     * claude 独有 hook 正常加载（回落）；allowlist/disableAllHooks 均纳入 claude base（union/标量
     * last-wins，与 nexusai 值合并）。
     *
     * <p><b>不新增 HookSource 枚举</b>：claude 回落进既有 source bucket，getAllHooks 数组/UI/
     * sortMatchersByPriority 均不受影响。
     *
     * @param source       目标 source（USER_SETTINGS / PROJECT_SETTINGS / LOCAL_SETTINGS）
     * @param claudePath   claude 只读回落路径（不存在 → 跳过）
     * @param nexusaiPath  nexusai 主路径（不存在 → 仅 claude 生效；两者都不存在 → NONE）
     * @param seenFiles    seenFiles 去重集
     * @return 合并后的 SourceLoadResult
     */
    private SourceLoadResult loadSourceFileWithClaudeFallback(HookSource source, Path claudePath,
                                                              Path nexusaiPath, Set<String> seenFiles) {
        ParsedFile claude = parseSourceFile(source, claudePath, seenFiles);
        ParsedFile nexusai = parseSourceFile(source, nexusaiPath, seenFiles);
        if (claude == null && nexusai == null) {
            // 两者都不存在/无效 → 清空该 source（保持单文件语义：无配置 = 空列表）
            hooksSettings.loadFromSource(source.name(), List.of());
            return SourceLoadResult.NONE;
        }
        // 合并 configs：claude 在前 + nexusai 在后（getAllHooks last-wins → nexusai 同名覆盖 claude）
        List<IndividualHookConfig> mergedConfigs = new ArrayList<>();
        if (claude != null) {
            mergedConfigs.addAll(claude.configs());
        }
        if (nexusai != null) {
            mergedConfigs.addAll(nexusai.configs());
        }
        hooksSettings.loadFromSource(source.name(), mergedConfigs);
        // allowlist/disableAll 合并：nexusai 优先（有值用 nexusai，否则 claude）
        Boolean disableAll = nexusai != null && nexusai.disableAllHooks() != null
            ? nexusai.disableAllHooks()
            : (claude != null ? claude.disableAllHooks() : null);
        List<String> urls = nexusai != null && nexusai.allowedHttpHookUrls() != null
            ? nexusai.allowedHttpHookUrls()
            : (claude != null ? claude.allowedHttpHookUrls() : null);
        List<String> envVars = nexusai != null && nexusai.httpHookAllowedEnvVars() != null
            ? nexusai.httpHookAllowedEnvVars()
            : (claude != null ? claude.httpHookAllowedEnvVars() : null);
        if (log.isDebugEnabled()) {
            log.debug("MultiSourceHooksConfigLoader: {} 加载 {} 个 hook (claude 回落 {} + nexusai {})",
                source.name(), mergedConfigs.size(),
                claude != null ? claude.configs().size() : 0,
                nexusai != null ? nexusai.configs().size() : 0);
        }
        return new SourceLoadResult(disableAll, urls, envVars);
    }

    /** 单文件解析结果（不写 HooksSettings，供 loadSourceFile / WithClaudeFallback 组合）。 */
    private record ParsedFile(Boolean disableAllHooks,
                              List<String> allowedHttpHookUrls,
                              List<String> httpHookAllowedEnvVars,
                              List<IndividualHookConfig> configs) {
    }

    /**
     * 解析单个 settings 文件为 ParsedFile（不写 HooksSettings）· seenFiles 按绝对路径去重
     * （CC settings.ts:743-747：同 resolvedPath 只处理一次）。
     *
     * <p>lenient：文件缺失/损坏 → 返回 null（调用方按空处理），不中断其它源。
     *
     * <p><b>DIF-CFG-05（IMP-HOOKS-S1）</b>: 对齐 CC {@code parseSettingsFile}
     * （settings.ts:749-758 经 SettingsSchema() zod 整文件校验, types.ts:435-437/:480-493）
     * —— hooks 存在但非对象 / allowlist 非字符串数组 → 该源<b>整层视为无效</b>
     * （settings=null, 顶层 disableAllHooks 与 allowlist 一并丢弃）; hooks 缺失/NullMarker
     * 仍合法（schema 中 optional）保留 disableAllHooks 与 allowlist.
     *
     * @param source   源（用于日志与 expand 的 source 语义）
     * @param path     settings 文件路径
     * @param seenFiles 去重集
     * @return 解析结果；文件缺失/跳过/整层无效 → null
     */
    private ParsedFile parseSourceFile(HookSource source, Path path, Set<String> seenFiles) {
        Path resolved = path.toAbsolutePath().normalize();
        if (!seenFiles.add(resolved.toString())) {
            if (log.isDebugEnabled()) {
                log.debug("MultiSourceHooksConfigLoader: 跳过已处理文件 (seenFiles 去重): {}", resolved);
            }
            return null;
        }
        if (!Files.exists(resolved)) {
            if (log.isDebugEnabled()) {
                log.debug("MultiSourceHooksConfigLoader: {} 文件不存在, 跳过: {}", source.name(), resolved);
            }
            return null;
        }
        try {
            JsonNode root;
            try (var parser = objectMapper.getFactory().createParser(resolved.toFile())) {
                root = objectMapper.readTree(parser);
            }
            if (root == null || !root.isObject()) {
                if (log.isDebugEnabled()) {
                    log.debug("MultiSourceHooksConfigLoader: {} 非 JSON 对象, 跳过: {}", source.name(), resolved);
                }
                return null;
            }
            // DIF-CFG-05: 键形状预校验（zod 整文件校验等价）——任一非法 → 整层无效
            JsonNode hooksNode = root.get("hooks");
            boolean hooksShapeOk = hooksNode == null || hooksNode.isNull() || hooksNode.isObject();
            List<String> urls = readStringArrayOrNull(root, "allowedHttpHookUrls");
            List<String> envVars = readStringArrayOrNull(root, "httpHookAllowedEnvVars");
            if (!hooksShapeOk || urls == INVALID_ARRAY || envVars == INVALID_ARRAY) {
                log.warn("MultiSourceHooksConfigLoader: {} 存在形状非法的顶层键 (hooks 非对象或 allowlist 非字符串数组), 该源整层视为无效: {}",
                    source.name(), resolved);
                return null;
            }
            // 顶层 disableAllHooks 与 hooks 字段相互独立 (CC getInitialSettings 全源合并顶层标量):
            // 无 hooks 字段时仍须返回 disableAllHooks ([EX_G_DisableAllHooks R1] 残留修复).
            Boolean disableAll = readDisableAllHooks(root);
            if (hooksNode == null || hooksNode.isNull()) {
                if (log.isDebugEnabled()) {
                    log.debug("MultiSourceHooksConfigLoader: {} 无 hooks 字段, 跳过: {}", source.name(), resolved);
                }
                return new ParsedFile(disableAll, urls, envVars, List.of());
            }
            Map<String, List<HookMatcher>> parsed = parseHooksConfig(hooksNode);
            List<IndividualHookConfig> configs = expand(parsed, source);
            if (log.isDebugEnabled()) {
                log.debug("MultiSourceHooksConfigLoader: {} 解析 {} 个 hook: {}",
                    source.name(), configs.size(), resolved);
            }
            return new ParsedFile(disableAll, urls, envVars, configs);
        } catch (Exception e) {
            // lenient：单源解析失败 warn + 该源视为空，不中断其它源（CC settings.ts:749-758）
            log.warn("MultiSourceHooksConfigLoader: {} 解析失败, 该源视为空: {} — {}",
                source.name(), resolved, e.toString());
            return null;
        }
    }

    /**
     * 读取 settings 文件顶层字符串数组键 · 对齐 CC zod {@code z.array(z.string()).optional()}
     * （types.ts:480-493）: 缺失/null → null（未配置）; 数组（元素均为字符串）→ List;
     * 存在但非字符串数组 → {@link #INVALID_ARRAY} 哨兵（整层无效, DIF-CFG-05）.
     */
    private static List<String> readStringArrayOrNull(JsonNode root, String key) {
        JsonNode node = root.get(key);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            return INVALID_ARRAY;
        }
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode n : node) {
            if (!n.isTextual()) {
                return INVALID_ARRAY;
            }
            out.add(n.asText());
        }
        return out;
    }

    /**
     * 读取 settings 文件顶层 disableAllHooks · 等价 CC mergedSettings.disableAllHooks
     * (getInitialSettings 全源合并, settings.ts:674-729; hooksConfigSnapshot.ts:47-49 判定).
     *
     * @param root settings.json 根节点
     * @return true/false = 显式配置; null = 缺省 (不覆盖低优先级源的值)
     */
    private static Boolean readDisableAllHooks(JsonNode root) {
        JsonNode node = root.get("disableAllHooks");
        if (node == null || node.isNull()) {
            return null;
        }
        // 非布尔 present → 等价 CC 严格相等 `=== true` 恒 false, 但仍是 present 覆盖
        // (mergeWith 标量覆盖: 后源显式非 true 会盖掉先源 true)
        return node.isBoolean() ? node.booleanValue() : Boolean.FALSE;
    }

    /**
     * 读取 policy hooks 源 · 对齐 CC loadSettingsFromDisk 的 policySettings 分支
     * （settings.ts:677-739，Java 端仅 managed-file 层：ManagedPolicySettingsSupplier）。
     *
     * <p><b>DIF-CFG-02 部署差异登记（IMP-HOOKS-S1）</b>: CC policySettings 解析链为
     * 4 层 first-wins —— remote（getRemoteManagedSettingsSyncFromCache）> MDM
     * （HKLM/plist）> managed-settings.json 文件层 > HKCU（settings.ts:677-739,
     * getSettingsForSourceUncached :319-345）。Java 部署模型仅存在 managed 文件层
     * （{@code nexusai.policy.path}）: RemoteManagedSettingsService.pollOnce 为 no-op
     * stub 且未接入本 supplier, MDM/HKCU 无对等基础设施 —— 属<b>登记差异</b>（非实现
     * 缺口）; 若未来启用 remote 层需重新对齐（OQ-03, 本 Session 不越界修改 settings 模块）。
     *
     * <p><b>DIF-CFG-05（IMP-HOOKS-S1）</b>: 对齐 CC zod 整文件校验 —— policy 层任一
     * 顶层键形状非法 → 该层<b>整层视为无效</b>（settings=null, settings.ts:749-758）:
     * <ul>
     *   <li>hooks 缺失/NullMarker → 合法（schema optional, types.ts:435-437）,
     *       保留 disableAllHooks 与 allowlist</li>
     *   <li>hooks 存在但非对象 / allowlist 非字符串数组 → 整层 null（disableAllHooks
     *       与 allowlist 一并丢弃; 旧实现仅丢 hooks 保留 disableAll, 与 CC 偏离）</li>
     *   <li>解析抛异常 → 整层 null（catch 子情形, 现状保持）</li>
     * </ul>
     *
     * @return {@link SourceLoadResult}; 无 supplier / 整层无效 → {@link SourceLoadResult#NONE}
     */
    private SourceLoadResult loadPolicySource() {
        if (policySettingsSupplier == null) {
            return SourceLoadResult.NONE;
        }
        try {
            // 顶层 disableAllHooks 与 hooks 独立 (R1 残留修复): policy 禁全部时 merged 亦为 true,
            // 但下游 shouldDisableAll() (分支1) 先短路, managed-only 双条件
            // (hooksConfigSnapshot.ts:69-74) 排除 policy 自身 → 无冲突.
            Boolean disableAll = toBooleanValue(policySettingsSupplier.get("disableAllHooks"));
            Object hooksVal = policySettingsSupplier.get("hooks");
            if (hooksVal == null || hooksVal == ConfigStorage.NullMarker) {
                if (log.isDebugEnabled()) {
                    log.debug("MultiSourceHooksConfigLoader: policy 无 hooks 字段, 跳过");
                }
                hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), List.of());
                return new SourceLoadResult(disableAll,
                    toAllowlist(policySettingsSupplier.get("allowedHttpHookUrls")),
                    toAllowlist(policySettingsSupplier.get("httpHookAllowedEnvVars")));
            }
            // DIF-CFG-05 ①: hooks 存在但非对象 → 整层无效（旧实现仅丢 hooks 返回 disableAll,
            // CC zod 整文件校验失败 → 该源 settings=null, disableAllHooks 一并丢弃）
            if (hooksVal instanceof JsonNode node && !node.isObject()) {
                log.warn("MultiSourceHooksConfigLoader: policy hooks 存在但非对象, policy 层整层视为无效 (disableAllHooks 一并丢弃, DIF-CFG-05)");
                hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), List.of());
                return SourceLoadResult.NONE;
            }
            if (hooksVal instanceof String || hooksVal instanceof Number || hooksVal instanceof Boolean) {
                // supplier 值形态兜底: hooks 非对象标量 → 同 ① 整层无效
                log.warn("MultiSourceHooksConfigLoader: policy hooks 存在但非对象, policy 层整层视为无效 (DIF-CFG-05)");
                hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), List.of());
                return SourceLoadResult.NONE;
            }
            List<String> urls = toAllowlist(policySettingsSupplier.get("allowedHttpHookUrls"));
            List<String> envVars = toAllowlist(policySettingsSupplier.get("httpHookAllowedEnvVars"));
            if (urls == INVALID_ARRAY || envVars == INVALID_ARRAY) {
                log.warn("MultiSourceHooksConfigLoader: policy allowlist 非字符串数组, policy 层整层视为无效 (DIF-CFG-05)");
                hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), List.of());
                return SourceLoadResult.NONE;
            }
            Map<String, List<HookMatcher>> parsed = parseHooksConfig(hooksVal);
            List<IndividualHookConfig> configs = expand(parsed, HookSource.POLICY_SETTINGS);
            hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), configs);
            if (log.isDebugEnabled()) {
                log.debug("MultiSourceHooksConfigLoader: policy 加载 {} 个 hook", configs.size());
            }
            return new SourceLoadResult(disableAll, urls, envVars);
        } catch (Exception e) {
            log.warn("MultiSourceHooksConfigLoader: policy hooks 解析失败, 视为空: {}", e.toString());
            hooksSettings.loadFromSource(HookSource.POLICY_SETTINGS.name(), List.of());
            return SourceLoadResult.NONE;
        }
    }

    /**
     * policy supplier 值 → allowlist List · 对象/数组返回原始 JsonNode 时按数组转;
     * null/NullMarker → null（未配置）; 存在但非字符串数组 → {@link #INVALID_ARRAY} 哨兵.
     */
    @SuppressWarnings("unchecked")
    private static List<String> toAllowlist(Object v) {
        if (v == null || v == ConfigStorage.NullMarker) {
            return null;
        }
        if (v instanceof JsonNode node) {
            if (!node.isArray()) {
                return INVALID_ARRAY;
            }
            List<String> out = new ArrayList<>(node.size());
            for (JsonNode n : node) {
                if (!n.isTextual()) {
                    return INVALID_ARRAY;
                }
                out.add(n.asText());
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>(list.size());
            for (Object o : list) {
                out.add(o.toString());
            }
            return out;
        }
        return INVALID_ARRAY;
    }

    /**
     * supplier 值 → Boolean · 等价 CC {@code === true} 严格判定:
     * null/NullMarker → null (缺省, 不覆盖); Boolean → 原值;
     * 其余 present → FALSE (严格相等恒 false, 但仍是 present 覆盖).
     */
    private static Boolean toBooleanValue(Object v) {
        if (v == null || v == ConfigStorage.NullMarker) {
            return null;
        }
        return v instanceof Boolean b ? b : Boolean.FALSE;
    }

    /**
     * 把 hooks JSON 解析为 {@code Map<事件名, List<HookMatcher>>}（对齐旧 HooksConfigLoader
     * parseHooksConfig：HookCommand sealed 按 {@code type} 字段路由）。
     *
     * @param hooksNode hooks 字段（JsonNode 或已解析 Map）
     * @return 事件名（PascalCase）→ matcher 列表；可能为空
     */
    @SuppressWarnings("unchecked")
    private Map<String, List<HookMatcher>> parseHooksConfig(Object hooksNode) {
        if (hooksNode instanceof JsonNode node && !node.isObject()) {
            return Map.of();
        }
        Object parsed = objectMapper.convertValue(hooksNode,
            new TypeReference<Map<String, List<HookMatcher>>>() {});
        return parsed != null ? (Map<String, List<HookMatcher>>) parsed : Map.of();
    }

    /**
     * 展开 {@code Map<事件名, List<HookMatcher>>} 为 {@link IndividualHookConfig} 列表
     * （事件名 PascalCase → {@link HookEventType} UPPER_SNAKE；未知事件名跳过）。
     *
     * @param parsed 事件名 → matcher 列表
     * @param source 来源（写入 bySource 的 source 名）
     * @return IndividualHookConfig 列表
     */
    private List<IndividualHookConfig> expand(Map<String, List<HookMatcher>> parsed, HookSource source) {
        List<IndividualHookConfig> out = new ArrayList<>();
        for (Map.Entry<String, List<HookMatcher>> e : parsed.entrySet()) {
            HookEventType eventType = toEventType(e.getKey());
            if (eventType == null) {
                if (log.isDebugEnabled()) {
                    log.debug("MultiSourceHooksConfigLoader: 跳过未知 hook 事件名: {}", e.getKey());
                }
                continue;
            }
            List<HookMatcher> matchers = e.getValue();
            if (matchers == null) continue;
            for (HookMatcher matcher : matchers) {
                if (matcher == null) continue;
                for (HookCommand hook : matcher.hooks()) {
                    out.add(new IndividualHookConfig(eventType, hook, matcher.matcher(), source, null));
                }
            }
        }
        return out;
    }

    /** PascalCase 事件名 → HookEventType (UPPER_SNAKE); 未知 → null（本包内复制，原方法 private）. */
    private static HookEventType toEventType(String pascalName) {
        if (pascalName == null || pascalName.isEmpty()) return null;
        try {
            return HookEventType.valueOf(normalizeEventName(pascalName));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 事件名归一化：PascalCase "PreToolUse" → "PRE_TOOL_USE"; UPPER_SNAKE 原样. */
    private static String normalizeEventName(String eventName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < eventName.length(); i++) {
            char c = eventName.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && eventName.charAt(i - 1) != '_') {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString().toUpperCase(java.util.Locale.ROOT);
    }
}
