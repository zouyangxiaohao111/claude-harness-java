package com.nexusai.application.agent.permission.hook;

import com.nexusai.application.agent.skill.NexusaiPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Hooks Settings · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts} (271 行).
 *
 * <p>WHY: 本类承载 hook 配置的加载 / 合并 / 查询 / 禁用判定. 原实现仅 4-String HookConfig
 * + shouldDisableAll 永远 false (Pattern #11 门禁绕过). 本轮 H3 重构为:
 * <ul>
 *   <li>{@link IndividualHookConfig} 替代旧 4-String HookConfig (对齐 CC 5 字段)</li>
 *   <li>{@link #getAllHooks()} 合并多 source (对齐 hooksSettings.ts:92-161 + 快照分支 5
 *       merged hooks, IMPL-08 起含 policy + 同内容折叠 last-wins)</li>
 *   <li>{@link #shouldDisableAll()} 读 policySettings.disableAllHooks (对齐
 *       hooksConfigSnapshot.ts:83-88 shouldDisableAllHooksIncludingManaged, 不再永远 false)</li>
 * </ul>
 *
 * <p><b>DEL-CFG-02..05 (IMPL-08) + 5-W1-2 恢复公开</b>: isHookEqual / getHookDisplayText 曾删除
 * (全库 0 消费者, EV-CFG-020), 2026-08-15 用户拍板恢复为 public (open-decisions.md 5-W1-2);
 * getHooksForEvent / sortMatchersByPriority + highestPriority 仍在 DEL-CFG-03..05 删除状态
 * (不在 5-W1-2 恢复范围).
 *
 * <p><b>policySettings 注入</b> (IMPL-01 D1-4): 参考 {@code ChannelNotificationGate} 的
 * {@code Function<String, Object> policySettingsSupplier} 模式. Spring 无参构造默认
 * {@code key -> null}; 生产由 {@link #setManagedPolicySettingsSupplier(ManagedPolicySettingsSupplier)}
 * 注入真实 policy 文件读取器 (替换恒 false 路径), 测试用 {@link #HooksSettings(Function)}
 * 注入 mock policy.
 *
 * <p><b>merged 视图注入</b> (EX_G_DisableAllHooks R1 + IMP-HOOKS-S1 H1): 由
 * {@link MultiSourceHooksConfigLoader} 加载完成后经 {@link #setDisableAllHooksMerged(boolean)}
 * 注入 merged disableAllHooks (对齐 CC {@code getSettings_DEPRECATED().disableAllHooks}
 * 全源合并语义, hooksConfigSnapshot.ts:47-49/:69-74), 并经
 * {@link #setMergedHttpHookPolicy(List, List)} 注入 merged HTTP hook allowlist
 * (对齐 CC {@code getInitialSettings().allowedHttpHookUrls/httpHookAllowedEnvVars},
 * execHttpHook.ts:53-57). 原 {@code setConfigStorage(FileConfigStorage)} 通道
 * 已删除 —— 读单文件与 CC 全源 mergeWith (settings.ts:674-729) 语义不符且生产无注入 (恒 false).
 *
 * <p><b>DEL-CFG-A (IMP-HOOKS-S1)</b>: getAllHooks 的 SESSION_HOOK 追加段已删除 ——
 * bySource[SESSION_HOOK] 全仓主代码 0 写入方 (死读分支); session hook 执行走
 * SessionHookStore 独立链.
 *
 * <p><b>local-only 约束</b>: 本类不向外发送任何数据, 仅本地 hook 配置查询.
 *
 */
@Component
public class HooksSettings {

    private static final Logger log = LoggerFactory.getLogger(HooksSettings.class);

    /**
     * policySettings supplier · key → value, 参考 ChannelNotificationGate 模式.
     *
     * <p>[IMPL-01 D1-4] 注入链修复: 原 {@code final} 字段仅无参构造 {@code key -> null}
     * 且无 setter → 生产恒 false (EV-CFG-019). 现为 {@code volatile}, 生产经
     * {@link #setManagedPolicySettingsSupplier(ManagedPolicySettingsSupplier)} 注入真实
     * policy 文件读取器 (CC {@code getSettingsForSource('policySettings')} 等价).
     */
    private volatile Function<String, Object> policySettingsSupplier;

    /** 生产 policy 文件读取器 (Spring 注入); null = 测试/手动构造 (无企业管控). */
    private volatile ManagedPolicySettingsSupplier managedPolicySettingsSupplier;

    /**
     * merged settings 顶层 disableAllHooks · CC {@code getSettings_DEPRECATED().disableAllHooks}
     * (hooksConfigSnapshot.ts:47-49/:69-74) 的 Java 等价.
     *
     * <p>[EX_G_DisableAllHooks R1 残留修复] 原实现经 {@code FileConfigStorage} 读单文件
     * (生产无注入 → 恒 false); 现由 {@link MultiSourceHooksConfigLoader#reloadFromDisk()}
     * 加载完成后注入 (标量 last-wins: user→project→local→policy, 对齐 CC getInitialSettings
     * 全源 mergeWith, settings.ts:674-729). 默认 false = 未加载/缺省.
     */
    private volatile boolean disableAllHooksMerged;

    /**
     * merged settings 顶层 HTTP hook allowlist · CC {@code getInitialSettings().allowedHttpHookUrls}
     * / {@code httpHookAllowedEnvVars}（execHttpHook.ts:53-57）的 Java 等价.
     *
     * <p>[IMP-HOOKS-S1 H1 / DIF-CFG-01] 由 {@link MultiSourceHooksConfigLoader#reloadFromDisk()}
     * 加载完成后经 {@link #setMergedHttpHookPolicy(List, List)} 注入（跨源 concat 保序去重,
     * 对齐 CC settings.ts:529-531 mergeArrays=uniq([...target,...source])）.
     * 默认 null = undefined = 不限制（与 disableAllHooksMerged 同注入模式）.
     */
    private volatile List<String> mergedHttpHookUrls;
    private volatile List<String> mergedHttpHookEnvVars;

    /**
     * session hooks 读取器 · CC {@code getAllHooks} 的 session 合并段（hooksSettings.ts:144-158）
     * 依赖 {@code getSessionHooks(appState, sessionId)} —— Java 端 session hooks 存于
     * {@link SessionHookStore}（HookRegistry 内部持有），本字段以 {@code Function<String, ...>}
     * 承接（输入 sessionId → 输出 event→derived matcher 列表）。
     *
     * <p><b>[4-3 决策, open-decisions.md]</b>: 用户拍板「后端补 getAllHooks 合并 session hooks」。
     * 旧实现删 session 段（DEL-CFG-A）因 bySource[SESSION_HOOK] 0 写入方；现改走 SessionHookStore
     * 读取器，对齐 CC hooksSettings.ts:144-158。
     *
     * <p><b>接线（合并阶段）</b>: 由 {@link HookRegistry}（持 {@code SessionHookStore}）注入
     * {@code sessionId -> sessionHookStore.getSessionHooks(sessionId, null)} —— 见
     * {@code 探查/hooks_v3/implementation/H-WF1-patch-note.md}（HookRegistry 属共享文件，合并阶段统一应用）。
     * 缺省 null = 不合并 session hooks（保持现状，非 UI 展示调用不受影响）。
     */
    private volatile Function<String, Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>>> sessionHooksProvider;

    /** 按 source 分组的 hook 配置 (source 名 → IndividualHookConfig 列表). */
    private final Map<String, List<IndividualHookConfig>> bySource = new ConcurrentHashMap<>();

    /** Spring 无参构造: policySettings 无注入时返回 null (无企业管控). */
    public HooksSettings() {
        this(key -> null);
    }

    /**
     * 测试 / 手动构造: 注入 policySettings supplier.
     *
     * @param policySettingsSupplier key → value; null 视为永远返回 null
     */
    public HooksSettings(Function<String, Object> policySettingsSupplier) {
        this.policySettingsSupplier = policySettingsSupplier != null ? policySettingsSupplier : key -> null;
    }

    /**
     * [IMPL-01 D1-4] 生产注入真实 policy 读取器 · 无参构造的 {@code key -> null}
     * 恒 false 路径在生产被本 setter 替换 (旧"无 setter"缺陷修复).
     *
     * <p><b>必注 (required)</b>: 政策门控是安全闸, 若 supplier 未注入将静默恒 false
     * (企业策略不可观察) — 规则十二 显式失败: 缺 bean 时启动期报错, 而不是运行时静默放行.
     * ManagedPolicySettingsSupplier 与 HooksSettings 同包 @Component, 任何含本 bean 的
     * Spring 上下文必含 supplier bean.
     *
     * @param supplier 真实 policy 文件读取器; null 忽略 (保持现状, 防御)
     */
    @org.springframework.beans.factory.annotation.Autowired
    public synchronized void setManagedPolicySettingsSupplier(ManagedPolicySettingsSupplier supplier) {
        if (supplier != null) {
            this.managedPolicySettingsSupplier = supplier;
            this.policySettingsSupplier = supplier::get;
            if (log.isDebugEnabled()) {
                log.debug("HooksSettings: 生产 policySettingsSupplier 已注入 (managed policy 文件读取器)");
            }
        }
    }

    /**
     * [EX_G_DisableAllHooks R1 残留修复] 注入 merged settings 顶层 disableAllHooks.
     *
     * <p>生产唯一注入点: {@link MultiSourceHooksConfigLoader#reloadFromDisk()} 加载完成后
     * 调用 (标量 last-wins 全源合并结果); 测试可直接注入布尔值.
     *
     * @param merged merged settings 顶层 disableAllHooks (true/false)
     */
    public void setDisableAllHooksMerged(boolean merged) {
        this.disableAllHooksMerged = merged;
        if (log.isDebugEnabled()) {
            log.debug("HooksSettings: merged disableAllHooks 已注入: {}", merged);
        }
    }

    /**
     * [IMP-HOOKS-S1 H1 / DIF-CFG-01] 注入 merged settings 顶层 HTTP hook allowlist.
     *
     * <p>生产唯一注入点: {@link MultiSourceHooksConfigLoader#reloadFromDisk()} 加载完成后
     * 调用（跨源 concat 保序去重结果, 对齐 CC execHttpHook.ts:49-58 getInitialSettings
     * 全源合并 + settings.ts:529-531 mergeArrays）；测试可直接注入.
     *
     * <p><b>三态语义（对齐 CC）</b>: null = undefined = 不限制; 空 list = 全拦;
     * 非空 = 须匹配. 任一来源配置（含显式空数组）即整体生效 —— allowlist 是<b>全局</b>
     * 设置（非单 hook 字段）, 任意源可贡献条目（CC 可观测行为, 见 execHttpHook.ts:43-48 JSDoc）.
     *
     * @param urls    跨源合并后的 allowedHttpHookUrls; null = 全部源未配置（不限制）
     * @param envVars 跨源合并后的 httpHookAllowedEnvVars; null = 全部源未配置（不限制）
     */
    public void setMergedHttpHookPolicy(List<String> urls, List<String> envVars) {
        this.mergedHttpHookUrls = urls != null ? List.copyOf(urls) : null;
        this.mergedHttpHookEnvVars = envVars != null ? List.copyOf(envVars) : null;
        if (log.isDebugEnabled()) {
            log.debug("HooksSettings: merged HTTP hook allowlist 已注入: urls={} envVars={}",
                mergedHttpHookUrls != null ? mergedHttpHookUrls : "undefined",
                mergedHttpHookEnvVars != null ? mergedHttpHookEnvVars : "undefined");
        }
    }

    /**
     * [4-3 决策] 注入 session hooks 读取器 · 对齐 CC {@code getAllHooks} session 合并段
     * （hooksSettings.ts:144-158 {@code getSessionHooks(appState, sessionId)}）。
     *
     * <p><b>接线（合并阶段）</b>: 生产由 {@link HookRegistry}（持 {@code SessionHookStore}）注入
     * {@code sessionId -> sessionHookStore.getSessionHooks(sessionId, null)} —— 见
     * {@code 探查/hooks_v3/implementation/H-WF1-patch-note.md}（HookRegistry 属共享文件）。
     * 测试可直接注入 mock 读取器。
     *
     * @param provider sessionId → event→derived matcher 列表; null 忽略 (保持现状)
     */
    public void setSessionHooksProvider(
            Function<String, Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>>> provider) {
        if (provider != null) {
            this.sessionHooksProvider = provider;
            if (log.isDebugEnabled()) {
                log.debug("HooksSettings: session hooks 读取器已注入 (getAllHooks session 合并启用)");
            }
        }
    }

    /**
     * 读取 policySettings 指定键的值 · 等价 CC {@code getSettingsForSource('policySettings')?.[key]}.
     *
     * @param key 策略键 (如 "hooks" / "strictPluginOnlyCustomization")
     * @return 值; 无 policy → null
     */
    public Object policySettingsValue(String key) {
        return policySettingsSupplier.apply(key);
    }

    /**
     * 读取整个 policySettings 为 Map · 供 PluginOnlyPolicy 整表检查
     * (CC getSettingsForSource 返回整个 settings 对象).
     *
     * @return policy 全量 Map; 无 policy → 空 Map
     */
    public Map<String, Object> policySettingsMap() {
        ManagedPolicySettingsSupplier supplier = this.managedPolicySettingsSupplier;
        return supplier != null ? supplier.all() : Map.of();
    }

    /**
     * 读取 merged settings 的 disableAllHooks · 等价 CC
     * {@code getSettings_DEPRECATED().disableAllHooks} (hooksConfigSnapshot.ts:47).
     *
     * @return true = merged settings 顶层 disableAllHooks==true (由 loader 注入, 缺省 false)
     */
    public boolean shouldDisableAllMerged() {
        return disableAllHooksMerged;
    }

    /**
     * 从指定 source 加载 hook 配置列表 (供 settings loader 调用).
     *
     * <p>H1 接线: {@link #loadFromSource(String, List)} 是 bySource 唯一写入入口,
     * 由 {@link MultiSourceHooksConfigLoader} 多来源加载时调用 (打通配置驱动主链路).
     *
     * @param source source 名 (对应 {@link HookSource} name)
     * @param hooks  IndividualHookConfig 列表
     */
    public void loadFromSource(String source, List<IndividualHookConfig> hooks) {
        bySource.put(source, hooks != null ? List.copyOf(hooks) : List.of());
    }

    /**
     * 获取指定 source 的 hook 配置列表.
     *
     * @param source source 名
     * @return IndividualHookConfig 列表 (可能为空, 永不 null)
     */
    public List<IndividualHookConfig> getFor(String source) {
        return bySource.getOrDefault(source, List.of());
    }

    /**
     * 是否禁用所有 hook (含 managed) · 对齐 CC hooksConfigSnapshot.ts:83-88
     * {@code shouldDisableAllHooksIncludingManaged}: policySettings.disableAllHooks==true.
     *
     * <p>WHY (Pattern #11 门禁绕过关闭): 原实现永远 false, 导致企业 policy 禁用 hook
     * 时 Java 端仍执行. 改为读 policySettings.disableAllHooks.
     *
     * @return true = policySettings.disableAllHooks==true
     */
    public boolean shouldDisableAll() {
        Object v = policySettingsSupplier.apply("disableAllHooks");
        return Boolean.TRUE.equals(v);
    }

    /**
     * 是否仅允许 managed hooks · 全量对齐 hooksConfigSnapshot.ts:62-76
     * {@code shouldAllowManagedHooksOnly}.
     *
     * <p>[IMPL-01 D1-1/OD-10] CC 双条件:
     * <ol>
     *   <li>policySettings.allowManagedHooksOnly==true (:64-66)</li>
     *   <li>merged(user settings).disableAllHooks==true 且 policySettings.disableAllHooks!=true
     *       (:69-74) — 非 managed 想禁全部但管不了 managed → 等效 managed-only</li>
     * </ol>
     * 旧实现仅前半部分 (后半分支注释保留未实现), 本次补 {@link #shouldDisableAllMerged()}.
     *
     * @return true = 仅允许 managed hooks
     */
    public boolean shouldAllowManagedHooksOnly() {
        Object v = policySettingsSupplier.apply("allowManagedHooksOnly");
        if (Boolean.TRUE.equals(v)) {
            return true;
        }
        // 对齐 :69-74: 非 managed disableAllHooks==true 且 policy disableAllHooks!=true
        return shouldDisableAllMerged() && !shouldDisableAll();
    }

    /**
     * HTTP hook 全局策略 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/execHttpHook.ts:49-58}
     * {@code getHttpHookPolicy()}: 从 merged settings 读 {@code allowedHttpHookUrls} +
     * {@code httpHookAllowedEnvVars}, 供 {@link ExecHttpHook} 做 URL allowlist 校验 +
     * env var 双重白名单交集.
     *
     * <p><b>读取源（IMP-HOOKS-S1 H1 / DIF-CFG-01 修复）</b>: 读 {@link #mergedHttpHookUrls}
     * / {@link #mergedHttpHookEnvVars} —— 由 {@link MultiSourceHooksConfigLoader#reloadFromDisk()}
     * 注入的全源合并视图（user→project→local→policy 保序去重 concat, 对齐 CC
     * {@code getInitialSettings()} settings.ts:812-815 全源 mergeWith + settings.ts:529-531
     * mergeArrays=uniq([...target,...source])）。旧实现经 {@link #policySettingsSupplier}
     * 读 policy 单源 —— user/project/local 的 allowlist 静默失效, 与 CC 偏离（DIF-CFG-01 high）。
     *
     * <p><b>merged 语义登记（安全）</b>: allowlist 是<b>全局收紧</b>机制, 任意源配置即整体生效
     * 且跨源 concat —— 后源可贡献条目（CC 可观测行为, execHttpHook.ts:43-48 JSDoc "arrays
     * concatenate across sources"）。合并可能放宽 policy 的收紧配置, 这是 CC 语义, 非缺口。
     *
     * <p>三态语义: 返回的 {@link HttpHookPolicy} 字段为 {@code null} 表示 undefined (不限制),
     * 空 list 表示全拦, 非空表示须匹配 (对齐 CC :138 undefined/[]/非空 三态).
     *
     * @return {@link HttpHookPolicy} (字段可能为 null = undefined = 不限制)
     */
    public HttpHookPolicy getHttpHookPolicy() {
        List<String> urls = mergedHttpHookUrls;
        List<String> vars = mergedHttpHookEnvVars;
        if (log.isDebugEnabled()) {
            log.debug("getHttpHookPolicy: allowedUrls={} allowedEnvVars={}",
                urls != null ? urls : "undefined",
                vars != null ? vars : "undefined");
        }
        return new HttpHookPolicy(urls, vars);
    }


    /**
     * 获取所有 hook (合并多 source) · 对齐 CC hooksSettings.ts:92-161 {@code getAllHooks}
     * + hooksConfigSnapshot.ts:43-52 分支 5（mergedSettings.hooks，含 policy）。
     *
     * <p>[IMPL-08 D7-1] WHY: CC loadSettingsFromDisk (settings.ts:645-796) 把
     * user/project/local/policy 逐源 mergeWith 进 mergedSettings（后合并覆盖先合并），
     * 快照分支 5 返回 mergedSettings.hooks —— 即<b>含 policy hooks</b> 的合并结果。
     * 旧实现仅合并 user/project/local（无 policy）且不折叠重复（EV-CFG-011/016）。
     * 本方法对齐:
     * <ul>
     *   <li>合并顺序 user → project → local → policy（对齐 SETTING_SOURCES 顺序，
     *       constants.ts:7-22；后源覆盖先源）</li>
     *   <li>同 (event, matcher, hook 内容) 折叠为 1 条，last-wins 保留高优先级源
     *       （等价 CC 匹配层 getMatchingHooks dedup 4 Map last-wins，hooks.ts:1603-1874；
     *       settings 源同 '' 前缀 → 同 payload 折叠）</li>
     * </ul>
     *
     * <p><b>守卫单条件化（IMP-HOOKS-S1 / DIF-CFG-04 修复）</b>: 对齐 hooksSettings.ts:96-101,
     * CC getAllHooks 仅在 {@code policySettings.allowManagedHooksOnly === true}（policy 单源）
     * 时隐藏非 managed hook —— 不含 merged.disableAllHooks 双条件（旧实现调用
     * {@link #shouldAllowManagedHooksOnly()} 双条件, 与 CC 直调语义偏离）。
     *
     * <p><b>等价链登记</b>: 生产可观察行为不变 —— 本方法唯一生产调用方
     * {@link HooksConfigSnapshot} 分支 5（getHooksFromAllowedSources :207）仅在
     * policyOnly=false 时可达, 而 shouldAllowManagedHooksOnly()（含 merged.disableAllHooks
     * 分支）已被快照分支 2/3/4 前置拦截（hooksConfigSnapshot.ts:47-49 分支 4 在分支 5 之前
     * 判定 merged.disableAllHooks===true → 仅 policy hooks）→ 双条件守卫在分支 5 恒 false。
     * 若未来新增 getAllHooks 直接消费方, 需保持 CC 单条件语义（DIF-CFG-04 关联登记）。
     *
     * <p><b>DEL-CFG-A（IMP-HOOKS-S1）</b>: session hooks 追加段已删除 —— CC getAllHooks
     * :144-158 的 session 合并读运行时会话存储（getSessionHooks, UI 展示语义）; Java 旧实现
     * 读 bySource[SESSION_HOOK], 全仓主代码 0 写入方（grep 自验: 仅 HookSource.java:39 枚举
     * 定义 + 本读取）→ 死读分支。session hook 执行走 SessionHookStore 独立链（T3）。
     *
     * @return IndividualHookConfig 列表 (可能为空)
     */
    public List<IndividualHookConfig> getAllHooks() {
        // 对齐 hooksSettings.ts:96-101: 单条件 —— 仅 policySettings.allowManagedHooksOnly===true
        // 时隐藏非 managed hook（CC 不含 merged.disableAllHooks 分支, 等价链见类 Javadoc）
        if (Boolean.TRUE.equals(policySettingsSupplier.apply("allowManagedHooksOnly"))) {
            if (log.isDebugEnabled()) {
                log.debug("getAllHooks: policy.allowManagedHooksOnly=true, 返回空列表 (UI 隐藏非 managed hook)");
            }
            return List.of();
        }

        // 对齐 hooksSettings.ts:103-141 + settings.ts mergeWith 链: 合并 user/project/local/policy
        // （policy 最后合并 = 最高优先级；同内容 hook 折叠 last-wins 保留后源）
        Map<HookIdentity, IndividualHookConfig> merged = new LinkedHashMap<>();
        for (HookSource src : new HookSource[]{
            HookSource.USER_SETTINGS, HookSource.PROJECT_SETTINGS,
            HookSource.LOCAL_SETTINGS, HookSource.POLICY_SETTINGS
        }) {
            List<IndividualHookConfig> hooks = bySource.get(src.name());
            if (hooks != null) {
                for (IndividualHookConfig h : hooks) {
                    // last-wins: 同内容 hook 后源覆盖先源 (CC 匹配层 dedup 等价)
                    merged.put(new HookIdentity(h.event(), h.matcher(), h.config()), h);
                }
            }
        }
        List<IndividualHookConfig> result = new ArrayList<>(merged.values());

        if (log.isDebugEnabled()) {
            log.debug("getAllHooks: 合并 {} 个 hook (user/project/local/policy)", result.size());
        }
        return List.copyOf(result);
    }

    /**
     * 获取所有 hook（含 session hooks 合并）· 对齐 CC {@code hooksSettings.ts:92-161}
     * {@code getAllHooks}（settings 段 :96-141 + session 段 :144-158）。
     *
     * <p><b>[4-3 决策, open-decisions.md]</b>: 用户拍板「后端补 getAllHooks 合并 session hooks」——
     * 旧实现删 session 段（DEL-CFG-A），现改走 {@link #sessionHooksProvider}（SessionHookStore
     * 读取器, 见 {@link #setSessionHooksProvider}）合并运行时 session hooks（source=sessionHook,
     * UI 展示语义）。
     *
     * <p><b>CC 语义 (hooksSettings.ts:96-101/:144-158)</b>:
     * <ol>
     *   <li>settings 段受 {@code allowManagedHooksOnly===true} 守卫（:96-101）—— 隐藏非 managed</li>
     *   <li>session 段<b>无条件</b>追加（:144-158 在守卫块外）—— 即使 managedOnly 也展示 session hooks</li>
     *   <li>session hooks 遍历 {@code sessionHooks.entries()}（event→matcher[]）→ 每个 matcher 的
     *       hooks[] → push {@code {event, config: hookCommand, matcher: matcher.matcher, source:'sessionHook'}}</li>
     * </ol>
     *
     * <p><b>缺省无 provider</b>: 返回 settings-only（等价 {@link #getAllHooks()}），
     * 非 UI 展示调用（快照分支 5）行为不变。
     *
     * @param sessionId 会话 ID（null/blank → 仅 settings, 不合并 session）
     * @return IndividualHookConfig 列表 (settings + session; 可能为空)
     */
    public List<IndividualHookConfig> getAllHooks(String sessionId) {
        // settings 段（守卫 + 折叠 last-wins）—— 复用本类语义
        List<IndividualHookConfig> result = new ArrayList<>(getAllHooks());
        // session 段（无条件, CC :144-158）
        Function<String, Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>>> provider =
            this.sessionHooksProvider;
        if (provider != null && sessionId != null && !sessionId.isBlank()) {
            Map<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> sessionHooks;
            try {
                sessionHooks = provider.apply(sessionId);
            } catch (Exception e) {
                // 读取器异常不中断 settings 结果（对齐 CC 宽容加载）
                if (log.isDebugEnabled()) {
                    log.debug("getAllHooks(sessionId): session hooks 读取失败, 忽略: {}", e.toString());
                }
                sessionHooks = null;
            }
            if (sessionHooks != null) {
                for (Map.Entry<HookEventType, List<SessionHookStore.SessionDerivedHookMatcher>> e
                        : sessionHooks.entrySet()) {
                    List<SessionHookStore.SessionDerivedHookMatcher> matchers = e.getValue();
                    if (matchers == null) {
                        continue;
                    }
                    for (SessionHookStore.SessionDerivedHookMatcher matcher : matchers) {
                        if (matcher.hooks() == null) {
                            continue;
                        }
                        for (HookCommand hook : matcher.hooks()) {
                            result.add(new IndividualHookConfig(
                                e.getKey(), hook, matcher.matcher(), HookSource.SESSION_HOOK, null));
                        }
                    }
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("getAllHooks(sessionId={}): 合并后 {} 个 hook (含 session)", sessionId, result.size());
        }
        return List.copyOf(result);
    }

    /**
     * 获取指定事件的所有 hook · 对齐 CC {@code hooksSettings.ts:163-168}
     * {@code getHooksForEvent} —— {@code getAllHooks().filter(hook => hook.event === event)}。
     *
     * @param sessionId 会话 ID（null → 仅 settings, 不合并 session）
     * @param event     hook 事件类型
     * @return 该事件的 hooks 列表（可能为空, 永不 null）
     */
    public List<IndividualHookConfig> getHooksForEvent(String sessionId, HookEventType event) {
        return getAllHooks(sessionId).stream()
            .filter(hook -> hook.event() == event)
            .toList();
    }

    // ════════════════════════════════════════════════════════════════════════
    // [4-2 决策] UI 展示辅助 · 对齐 CC hooksSettings.ts:170-271
    //   hookSourceDescriptionDisplayString / hookSourceHeaderDisplayString /
    //   hookSourceInlineDisplayString (:170-228) + sortMatchersByPriority (:230-271)。
    //   DEL-CFG-03..05 曾删除（全库 0 消费者, EV-CFG-020），用户拍板"后端实现展示函数"
    //   （open-decisions.md 4-2）恢复。
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 来源描述展示串 · 对齐 CC {@code hooksSettings.ts:170-190}
     * {@code hookSourceDescriptionDisplayString(source)}（7 分支）。
     *
     * @param source hook 来源
     * @return 描述串（CC default 分支返回 source 字符串）
     */
    public static String hookSourceDescriptionDisplayString(HookSource source) {
        // [T3/#21] 展示串 .nexusai → 动态 appName（决策 D1/D6）：hook 来源描述随 appName 联动
        String projDir = NexusaiPaths.getProjectDirName();
        return switch (source) {
            // R3-6: claude 路径文案 → nexusai 自有根文案（决策 D1/D2，~/.claude 已不读）
            case USER_SETTINGS -> "User settings (~/" + projDir + "/settings.json)";
            case PROJECT_SETTINGS -> "Project settings (~/" + projDir + "/settings.json)";
            case LOCAL_SETTINGS -> "Local settings (~/" + projDir + "/settings.local.json)";
            case PLUGIN_HOOK -> "Plugin hooks (~/" + projDir + "/plugins/*/hooks/hooks.json)";
            case SESSION_HOOK -> "Session hooks (in-memory, temporary)";
            case BUILTIN_HOOK -> "Built-in hooks (registered internally by Claude Code)";
            // CC :188-189 default: return source as string
            default -> source.name();
        };
    }

    /**
     * 来源表头展示串 · 对齐 CC {@code hooksSettings.ts:192-209}
     * {@code hookSourceHeaderDisplayString(source)}（7 分支）。
     *
     * @param source hook 来源
     * @return 表头串（CC default 分支返回 source 字符串）
     */
    public static String hookSourceHeaderDisplayString(HookSource source) {
        return switch (source) {
            case USER_SETTINGS -> "User Settings";
            case PROJECT_SETTINGS -> "Project Settings";
            case LOCAL_SETTINGS -> "Local Settings";
            case PLUGIN_HOOK -> "Plugin Hooks";
            case SESSION_HOOK -> "Session Hooks";
            case BUILTIN_HOOK -> "Built-in Hooks";
            default -> source.name();
        };
    }

    /**
     * 来源内联展示串 · 对齐 CC {@code hooksSettings.ts:211-228}
     * {@code hookSourceInlineDisplayString(source)}（7 分支）。
     *
     * @param source hook 来源
     * @return 内联串（CC default 分支返回 source 字符串）
     */
    public static String hookSourceInlineDisplayString(HookSource source) {
        return switch (source) {
            case USER_SETTINGS -> "User";
            case PROJECT_SETTINGS -> "Project";
            case LOCAL_SETTINGS -> "Local";
            case PLUGIN_HOOK -> "Plugin";
            case SESSION_HOOK -> "Session";
            case BUILTIN_HOOK -> "Built-in";
            default -> source.name();
        };
    }

    /**
     * 按来源优先级排序 matcher 键 · 对齐 CC {@code hooksSettings.ts:230-271}
     * {@code sortMatchersByPriority(matchers, hooksByEventAndMatcher, selectedEvent)}。
     *
     * <p><b>CC 语义 (:238-245)</b>: 优先级表由 {@code SOURCES}（constants.ts:191-195,
     * {@code ['localSettings','projectSettings','userSettings']}）reduce 而来 —— 低索引 = 高优先级。
     *
     * <p><b>比较器 (:247-270)</b>:
     * <ol>
     *   <li>取每个 matcher 对应 hooks 的 source 集合（Set 去重, :251-252）</li>
     *   <li>{@code getSourcePriority}: pluginHook/builtinHook → 999（最低, :256-259）；
     *       editable 源 → {@code sourcePriority[source]}</li>
     *   <li>{@code Math.min} 取最高优先级（最小索引, :261-262）；不等 → 索引差（高优先级先）</li>
     *   <li>同优先级 → {@code localeCompare} 按名称排序（:269）</li>
     * </ol>
     *
     * <p><b>Java 适配</b>: CC {@code SOURCES} 仅 editable 3 源；policy/session 不在表内（undefined
     * 优先级），Java 端映射 policy → 3、session → 4（置于 editable 之后, plugin/builtin 之前），
     * 保持「editable 高优先 / 插件最低」的相对序（Java 独有能力排序, 见类 Javadoc）。
     *
     * @param matchers              待排序的 matcher 键数组（null → 空结果）
     * @param hooksByEventAndMatcher event→(matcherKey→hooks) 分组（getSortedMatchersForEvent 入参）
     * @param selectedEvent         目标事件
     * @return 按优先级排序的 matcher 键数组
     */
    public static List<String> sortMatchersByPriority(
            List<String> matchers,
            Map<HookEventType, Map<String, List<IndividualHookConfig>>> hooksByEventAndMatcher,
            HookEventType selectedEvent) {
        if (matchers == null || matchers.isEmpty()) {
            return List.of();
        }
        // CC :239-245: sourcePriority = SOURCES.reduce(...) —— local=0/project=1/user=2
        Map<HookSource, Integer> sourcePriority = new LinkedHashMap<>();
        sourcePriority.put(HookSource.LOCAL_SETTINGS, 0);
        sourcePriority.put(HookSource.PROJECT_SETTINGS, 1);
        sourcePriority.put(HookSource.USER_SETTINGS, 2);
        // Java 适配: policy=3 / session=4（editable 之后, plugin 之前）; plugin/builtin → 999
        sourcePriority.put(HookSource.POLICY_SETTINGS, 3);
        sourcePriority.put(HookSource.SESSION_HOOK, 4);

        List<String> sorted = new ArrayList<>(matchers);
        sorted.sort((a, b) -> {
            Map<String, List<IndividualHookConfig>> eventGroup =
                hooksByEventAndMatcher.getOrDefault(selectedEvent, Map.of());
            int aPri = highestSourcePriority(eventGroup.getOrDefault(a, List.of()), sourcePriority);
            int bPri = highestSourcePriority(eventGroup.getOrDefault(b, List.of()), sourcePriority);
            if (aPri != bPri) {
                return aPri - bPri; // CC :264-266: 高优先级（小索引）先
            }
            return a.compareTo(b); // CC :269: 同优先级按名称排序
        });
        return List.copyOf(sorted);
    }

    /** 取 hooks 的最高来源优先级（最小索引）· 等价 CC :251-262 Math.min(getSourcePriority). */
    private static int highestSourcePriority(
            List<IndividualHookConfig> hooks, Map<HookSource, Integer> sourcePriority) {
        if (hooks == null || hooks.isEmpty()) {
            // 空 hooks → 无穷大（等价 CC Math.min(...[]) = Infinity → 排在最后）
            return Integer.MAX_VALUE;
        }
        int min = Integer.MAX_VALUE;
        for (IndividualHookConfig h : hooks) {
            int p = sourcePriority.getOrDefault(h.source(), 999); // CC :256-259: 未知源 → 999
            if (p < min) {
                min = p;
            }
        }
        return min;
    }

    /**
     * hook 身份键 · 同 (event, matcher, config 内容) 视为同一 hook (对齐 CC 匹配层
     * hookDedupKey = 源前缀 + payload 的 settings 源折叠语义; source 不参与身份).
     */
    private record HookIdentity(HookEventType event, String matcher, HookCommand config) {
    }

    // ════════════════════════════════════════════════════════════════════════
    // 静态工具方法 · 对齐 CC hooksSettings.ts:33-90
    //   [5-W1-2 恢复公开] getHookDisplayText / isHookEqual —— DEL-CFG-02 曾删除
    //   (生产 0 消费者, EV-CFG-020), 用户拍板"恢复公开"(open-decisions.md 5-W1-2:
    //   "改回 public（DEL-CFG-02 撤销）"). 其余 DEL-CFG-03..05 删除项
    //   (getHooksForEvent / sortMatchersByPriority / highestPriority) 不在 5-W1-2
    //   范围内, 不恢复. SessionHookStore 私有 isHookEqual (:507) 保留 (表达差异,
    //   本任务不删).
    // ════════════════════════════════════════════════════════════════════════

    /**
     * 判断两个 hook 是否相等 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts:33-65}
     * {@code isHookEqual(a, b)}.
     *
     * <p><b>[5-W1-2 恢复公开]</b>: DEL-CFG-02 (IMPL-08) 曾删除 (生产 0 消费者),
     * 用户拍板恢复 public (open-decisions.md 5-W1-2 "改回 public（DEL-CFG-02 撤销）").
     * 与 SessionHookStore 私有实现 (:507) 语义一致 —— 均对齐 CC 真源.
     *
     * <p><b>CC 语义 (hooksSettings.ts:33-64)</b>:
     * <ul>
     *   <li>只比较 command/prompt 内容, 不比较 timeout (CC L40-42 注释 "We only compare
     *       command/prompt content, not timeout")</li>
     *   <li>{@code if} 是身份一部分 (CC L41-43): 同 command 不同 if 条件是不同 hook
     *       (如 setup.sh if=Bash(git *) vs if=Bash(npm *))</li>
     *   <li>shell 也是身份一部分, 缺省 'bash' (CC L48-52, DEFAULT_HOOK_SHELL)</li>
     *   <li>function hook 无稳定标识, 永远不等 (CC L61-63)</li>
     * </ul>
     *
     * @param a 左侧 hook (SessionHook = HookCommand|FunctionHook union, 对齐 CC
     *          {@code HookCommand | { type: 'function' }})
     * @param b 右侧 hook
     * @return 相等与否
     */
    public static boolean isHookEqual(SessionHook a, SessionHook b) {
        if (a == null || b == null) {
            return false;
        }
        if (!a.type().equals(b.type())) {
            return false; // CC L37: type 不同 → false
        }
        // CC L61-63: function hook 无稳定标识 → 永远不等
        if (a instanceof FunctionHook) {
            return false;
        }
        if (a instanceof CommandHook ca && b instanceof CommandHook cb) {
            // CC L46-54: command + shell(缺省 bash) + sameIf
            return Objects.equals(ca.command(), cb.command())
                    && defaultShell(ca.shell()).equals(defaultShell(cb.shell()))
                    && sameIf(ca.ifCondition(), cb.ifCondition());
        }
        if (a instanceof PromptHook pa && b instanceof PromptHook pb) {
            // CC L55-56: prompt + sameIf
            return Objects.equals(pa.prompt(), pb.prompt()) && sameIf(pa.ifCondition(), pb.ifCondition());
        }
        if (a instanceof AgentHook aa && b instanceof AgentHook ab) {
            // CC L57-58: prompt + sameIf
            return Objects.equals(aa.prompt(), ab.prompt()) && sameIf(aa.ifCondition(), ab.ifCondition());
        }
        if (a instanceof HttpHook ha && b instanceof HttpHook hb) {
            // CC L59-60: url + sameIf
            return Objects.equals(ha.url(), hb.url()) && sameIf(ha.ifCondition(), hb.ifCondition());
        }
        return false;
    }

    /**
     * 获取 hook 展示文本 · 对齐 CC {@code Open-ClaudeCode/src/utils/hooks/hooksSettings.ts:68-90}
     * {@code getHookDisplayText(hook)}.
     *
     * <p><b>[5-W1-2 恢复公开]</b>: DEL-CFG-02 (IMPL-08) 曾删除 (生产 0 消费者),
     * 用户拍板恢复 public (open-decisions.md 5-W1-2 "改回 public（DEL-CFG-02 撤销）").
     *
     * <p><b>CC 语义 (hooksSettings.ts:71-89)</b>:
     * <ul>
     *   <li>statusMessage 优先 (CC L72-74: {@code 'statusMessage' in hook && hook.statusMessage} —
     *       空串/空白为 falsy, 不返回, 落回类型分支)</li>
     *   <li>command → command 串 (CC L77-78)</li>
     *   <li>prompt → prompt 串 (CC L79-80)</li>
     *   <li>agent → prompt 串 (CC L81-82)</li>
     *   <li>http → url 串 (CC L83-84)</li>
     *   <li>function → "function" (CC L87-88); Java SessionHook union 无 'callback' 成员,
     *       CC L85-86 callback → "callback" 在 Java 端不适用</li>
     * </ul>
     *
     * @param hook 待展示的 hook (SessionHook = HookCommand|FunctionHook union)
     * @return 展示文本
     */
    public static String getHookDisplayText(SessionHook hook) {
        if (hook == null) {
            return "";
        }
        String statusMessage = hook.statusMessage();
        if (statusMessage != null && !statusMessage.isBlank()) {
            return statusMessage; // CC L72-74: statusMessage 优先 (空串/空白 falsy, 不返回)
        }
        if (hook instanceof CommandHook c) {
            return c.command();
        }
        if (hook instanceof PromptHook p) {
            return p.prompt();
        }
        if (hook instanceof AgentHook a) {
            return a.prompt();
        }
        if (hook instanceof HttpHook h) {
            return h.url();
        }
        if (hook instanceof FunctionHook) {
            return "function"; // CC L87-88
        }
        return hook.type();
    }

    /** CC DEFAULT_HOOK_SHELL='bash' (shellProvider.ts:2 / CommandHook.DEFAULT_SHELL) — shell 缺省值归一. */
    private static String defaultShell(String shell) {
        return (shell == null || shell.isBlank()) ? CommandHook.DEFAULT_SHELL : shell;
    }

    /** CC hooksSettings.ts:43-44 sameIf: {@code (x.if ?? '') === (y.if ?? '')} — if 缺省 '' 参与身份比较. */
    private static boolean sameIf(String a, String b) {
        return Objects.equals(a == null ? "" : a, b == null ? "" : b);
    }

}