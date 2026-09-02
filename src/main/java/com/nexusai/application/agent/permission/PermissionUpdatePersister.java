package com.nexusai.application.agent.permission;

import com.nexusai.application.agent.permission.source.LocalSettingsLoader;
import com.nexusai.application.agent.permission.source.PermissionRuleValueParser;
import com.nexusai.application.agent.permission.source.PermissionSourceLoader;
import com.nexusai.application.agent.permission.source.ProjectSettingsLoader;
import com.nexusai.application.agent.permission.source.UserSettingsLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 权限更新持久化器 · 对齐 CC {@code utils/permissions/PermissionUpdate.ts:222-342}
 * {@code persistPermissionUpdate}（增量 per-type 写盘）。
 *
 * <h2>增量语义（对齐 CC persistPermissionUpdate 6 case）</h2>
 * <p>按 {@code update.type} 分发，仅影响对应桶，<b>不</b>整源收集重写：
 * <ol>
 *   <li>{@code addRules} → 追加到单 {@code behavior} 桶（去重，roundtrip 归一化）——
 *       CC {@code addPermissionRulesToSettings}（permissionsLoader.ts:229-296）</li>
 *   <li>{@code removeRules} → 从单 {@code behavior} 桶过滤删除（roundtrip 归一化匹配）——
 *       CC PermissionUpdate.ts:268-295</li>
 *   <li>{@code replaceRules} → 单 {@code behavior} 桶整体替换——CC PermissionUpdate.ts:329-340</li>
 *   <li>{@code setMode} → 写 {@code permissions.defaultMode}——CC PermissionUpdate.ts:317-327</li>
 *   <li>{@code addDirectories} → 追加 {@code permissions.additionalDirectories}（去重）——
 *       CC PermissionUpdate.ts:244-266</li>
 *   <li>{@code removeDirectories} → 过滤删除 {@code permissions.additionalDirectories}——
 *       CC PermissionUpdate.ts:297-315</li>
 * </ol>
 *
 * <p>写盘通道为 {@link SettingsJsonParser} 的读-改-写合并（对齐 CC
 * {@code updateSettingsForSource} mergeWith 数组整体替换语义，settings.ts:416-524），
 * 未知 key / hooks / env 一律保留。
 *
 * <p><b>旧架构删除说明</b>：原 {@code collectRules()}（整源收集）+ 各 loader 的
 * {@code save(List<PermissionRule>)}（整源重写）已删除——CC 无对应能力，
 * 增量写盘是唯一正确语义（避免"改一个桶抹掉其它桶"）。
 */
@Component
public class PermissionUpdatePersister {

    private static final Logger log = LoggerFactory.getLogger(PermissionUpdatePersister.class);

    private final UserSettingsLoader userSettingsLoader;
    private final ProjectSettingsLoader projectSettingsLoader;
    private final LocalSettingsLoader localSettingsLoader;
    private final PermissionRuleValueParser ruleValueParser;
    /** [IMP-3 G1] 企业管控 managed-only 判定器；null = 未注入（无企业管控，不门控）。 */
    private PermissionManagedPolicy managedPolicy;

    /**
     * Spring 注入构造器。
     *
     * @param userSettingsLoader    userSettings 写盘 loader
     * @param projectSettingsLoader projectSettings 写盘 loader
     * @param localSettingsLoader   localSettings 写盘 loader
     * @param ruleValueParser       rule 字符串解析器（roundtrip 归一化用，对齐 CC
     *                              {@code permissionRuleValueFromString/ToString}）
     */
    public PermissionUpdatePersister(
            UserSettingsLoader userSettingsLoader,
            ProjectSettingsLoader projectSettingsLoader,
            LocalSettingsLoader localSettingsLoader,
            PermissionRuleValueParser ruleValueParser) {
        this.userSettingsLoader = userSettingsLoader;
        this.projectSettingsLoader = projectSettingsLoader;
        this.localSettingsLoader = localSettingsLoader;
        this.ruleValueParser = ruleValueParser;
    }

    /**
     * [IMP-3 G1] 注入 managed-only 判定器 · 对齐 CC {@code addPermissionRulesToSettings}
     * （permissionsLoader.ts:239-242）写规则前的 managed-only 早退。
     *
     * <p>{@code @Autowired(required = false)}：生产 Spring 上下文必含
     * {@link PermissionManagedPolicy} bean（与 hooks 侧 managed policy supplier 同源）；
     * 非 Spring 单元测试可经本 setter 注入（null = 无企业管控，不门控）。遵循
     * {@link com.nexusai.application.agent.permission.hook.HooksSettings#setManagedPolicySettingsSupplier}
     * 的 setter 注入模式。
     *
     * @param managedPolicy managed-only 判定器（null 忽略，保持无门控）
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setManagedPolicy(PermissionManagedPolicy managedPolicy) {
        this.managedPolicy = managedPolicy;
    }

    /**
     * [IMP-3 G1] 是否仅允许 managed permission rules（对齐 CC
     * {@code shouldAllowManagedPermissionRulesOnly}，permissionsLoader.ts:31-36）。
     *
     * @return true = policySettings.allowManagedPermissionRulesOnly === true
     */
    private boolean isManagedPermissionRulesOnly() {
        return managedPolicy != null && managedPolicy.shouldAllowManagedPermissionRulesOnly();
    }

    public boolean supportsPersistence(PermissionUpdate.Destination dest) {
        return switch (dest) {
            case USER_SETTINGS, PROJECT_SETTINGS, LOCAL_SETTINGS -> true;
            case CLI_ARG, SESSION -> false;
        };
    }

    /**
     * 持久化单条更新（对齐 CC {@code persistPermissionUpdate}）。
     *
     * <p>先按 destination 拦截非可持久化 source（CC supportsPersistence，
     * PermissionUpdate.ts:208-216），再按 {@code type} 分发到对应写盘通道。
     *
     * @param update 权限更新（非 null）
     */
    public void persist(PermissionUpdate update) {
        Objects.requireNonNull(update, "update is null");

        PermissionUpdate.Destination dest = extractDestination(update);

        if (!supportsPersistence(dest)) {
            if (log.isDebugEnabled()) {
                log.debug("PermissionUpdatePersister: 跳过非可持久化 destination {} type={}",
                    dest, update);
            }
            return;
        }

        PermissionSourceLoader loader = loaderFor(dest);

        switch (update) {
            case PermissionUpdate.AddRules a -> persistAddRules(a, loader);
            case PermissionUpdate.RemoveRules r -> persistRemoveRules(r, loader);
            case PermissionUpdate.ReplaceRules rp -> persistReplaceRules(rp, loader);
            case PermissionUpdate.SetMode s -> persistSetMode(s, loader);
            case PermissionUpdate.AddDirectories ad -> persistAddDirectories(ad, loader);
            case PermissionUpdate.RemoveDirectories rd -> persistRemoveDirectories(rd, loader);
        }
    }

    /**
     * 持久化多条更新（对齐 CC {@code persistPermissionUpdates}，PermissionUpdate.ts:349-353）。
     *
     * @param updates 权限更新列表（非 null）
     */
    public void persistAll(List<PermissionUpdate> updates) {
        Objects.requireNonNull(updates, "updates is null");
        for (PermissionUpdate update : updates) {
            persist(update);
        }
    }

    /**
     * addRules 增补桶 · 对齐 CC {@code addPermissionRulesToSettings}（permissionsLoader.ts:229-296）。
     *
     * <p>去重语义（CC :265-270）：existing 归一化 roundtrip（parse→serialize）后建 Set，
     * 仅追加不在 Set 中的新规则，避免 "Bash(*)" 与 "Bash" 重复写入。
     */
    private void persistAddRules(PermissionUpdate.AddRules a, PermissionSourceLoader loader) {
        // [IMP-3 G1] 对齐 CC addPermissionRulesToSettings（permissionsLoader.ts:239-242）：
        //   allowManagedPermissionRulesOnly 为 true 时直接 return false 不写任何新规则。
        //   注意 CC 仅在 addRules 路径门控（removeRules/setMode/addDirectories 等不经
        //   addPermissionRulesToSettings），故门控只放本方法入口，不放 persist() 总入口。
        if (isManagedPermissionRulesOnly()) {
            if (log.isInfoEnabled()) {
                log.info("PermissionUpdatePersister: managed-only 门控生效，拒绝写新权限规则 destination={} behavior={}",
                    a.destination(), a.behavior());
            }
            return;
        }

        String field = behaviorField(a.behavior());
        List<String> ruleStrings = a.rules().stream()
            .map(r -> r.ruleValue().toRuleString())
            .toList();

        List<String> existing = loader.readPermissionsStringArray(field);
        Set<String> existingSet = new HashSet<>();
        for (String raw : existing) {
            existingSet.add(normalizeRuleString(raw));
        }
        List<String> newRules = ruleStrings.stream()
            .filter(r -> !existingSet.contains(r))
            .toList();

        if (newRules.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("PermissionUpdatePersister: addRules 无新规则（全部已存在），跳过写盘 "
                    + "destination={} behavior={}", a.destination(), a.behavior());
            }
            return;
        }

        List<String> merged = new ArrayList<>(existing);
        merged.addAll(newRules);
        loader.savePermissionsField(field, merged);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: addRules 追加 {} 条到 {} 桶 destination={}（去重后新增 {} 条）",
                ruleStrings.size(), field, a.destination(), newRules.size());
        }
    }

    /**
     * removeRules 删指定桶 · 对齐 CC PermissionUpdate.ts:268-295。
     *
     * <p>CC 单桶语义（:275）：仅作用于 {@code update.behavior} 对应桶（非跨 3 桶），
     * roundtrip 归一化后 filter 匹配（:282-287）。
     */
    private void persistRemoveRules(PermissionUpdate.RemoveRules r, PermissionSourceLoader loader) {
        String field = behaviorField(r.behavior());
        Set<String> rulesToRemove = new HashSet<>();
        for (PermissionRule rule : r.rules()) {
            rulesToRemove.add(rule.ruleValue().toRuleString());
        }

        List<String> existing = loader.readPermissionsStringArray(field);
        List<String> filtered = existing.stream()
            .filter(raw -> !rulesToRemove.contains(normalizeRuleString(raw)))
            .toList();

        loader.savePermissionsField(field, filtered);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: removeRules 从 {} 桶移除 {} 条 destination={}（剩余 {} 条）",
                field, r.rules().size(), r.destination(), filtered.size());
        }
    }

    /**
     * replaceRules 替换桶 · 对齐 CC PermissionUpdate.ts:329-340。
     */
    private void persistReplaceRules(PermissionUpdate.ReplaceRules rp, PermissionSourceLoader loader) {
        String field = behaviorField(rp.behavior());
        List<String> ruleStrings = rp.rules().stream()
            .map(r -> r.ruleValue().toRuleString())
            .toList();

        loader.savePermissionsField(field, ruleStrings);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: replaceRules 整桶替换 {} 桶 destination={}（{} 条）",
                field, rp.destination(), ruleStrings.size());
        }
    }

    /**
     * setMode 写 defaultMode · 对齐 CC PermissionUpdate.ts:317-327。
     *
     * <p>CC setMode 的 mode 字段经 {@code externalPermissionModeSchema} 校验，只可能是 5 种
     * external mode。Java {@link PermissionMode#AUTO}/{@link PermissionMode#BUBBLE} 是 internal
     * （ant-only / 子 agent 内部），落盘时降级为 {@code "default"}（对齐 CC
     * {@code toExternalPermissionMode}，PermissionMode.ts:111-115）。
     */
    private void persistSetMode(PermissionUpdate.SetMode s, PermissionSourceLoader loader) {
        String externalMode = toExternalModeName(s.mode());
        loader.savePermissionsValue("defaultMode", externalMode);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: setMode 写 defaultMode={} destination={}",
                externalMode, s.destination());
        }
    }

    /**
     * addDirectories 追加 additionalDirectories · 对齐 CC PermissionUpdate.ts:244-266。
     *
     * <p>去重语义（CC :253-255）：精确字符串匹配（非 roundtrip），避免重复目录。
     */
    private void persistAddDirectories(PermissionUpdate.AddDirectories ad, PermissionSourceLoader loader) {
        List<String> existing = loader.readPermissionsStringArray("additionalDirectories");
        List<String> dirsToAdd = ad.paths().stream()
            .filter(d -> !existing.contains(d))
            .toList();

        if (dirsToAdd.isEmpty()) {
            if (log.isDebugEnabled()) {
                log.debug("PermissionUpdatePersister: addDirectories 无新目录（全部已存在），跳过写盘 destination={}",
                    ad.destination());
            }
            return;
        }

        List<String> merged = new ArrayList<>(existing);
        merged.addAll(dirsToAdd);
        loader.savePermissionsField("additionalDirectories", merged);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: addDirectories 追加 {} 个目录 destination={}（新增 {} 个）",
                ad.paths().size(), ad.destination(), dirsToAdd.size());
        }
    }

    /**
     * removeDirectories 删除 additionalDirectories · 对齐 CC PermissionUpdate.ts:297-315。
     */
    private void persistRemoveDirectories(PermissionUpdate.RemoveDirectories rd, PermissionSourceLoader loader) {
        Set<String> dirsToRemove = new HashSet<>(rd.paths());
        List<String> existing = loader.readPermissionsStringArray("additionalDirectories");
        List<String> filtered = existing.stream()
            .filter(d -> !dirsToRemove.contains(d))
            .toList();

        loader.savePermissionsField("additionalDirectories", filtered);

        if (log.isDebugEnabled()) {
            log.debug("PermissionUpdatePersister: removeDirectories 移除 {} 个目录 destination={}（剩余 {} 个）",
                rd.paths().size(), rd.destination(), filtered.size());
        }
    }

    /**
     * 提取 destination（6 case 全映射）。
     *
     * <p>旧实现对 SetMode/AddDirectories/RemoveDirectories 返回 null（导致跳过持久化）——
     * 已补齐：这 3 个 record 均携带 {@code destination}（CC PermissionUpdateSchema.ts:62-76 均必填）。
     */
    private PermissionUpdate.Destination extractDestination(PermissionUpdate update) {
        return switch (update) {
            case PermissionUpdate.AddRules a -> a.destination();
            case PermissionUpdate.RemoveRules r -> r.destination();
            case PermissionUpdate.ReplaceRules rp -> rp.destination();
            case PermissionUpdate.SetMode s -> s.destination();
            case PermissionUpdate.AddDirectories ad -> ad.destination();
            case PermissionUpdate.RemoveDirectories rd -> rd.destination();
        };
    }

    /** destination → 对应写盘 loader（仅 3 个 editable source）。 */
    private PermissionSourceLoader loaderFor(PermissionUpdate.Destination dest) {
        return switch (dest) {
            case USER_SETTINGS -> userSettingsLoader;
            case PROJECT_SETTINGS -> projectSettingsLoader;
            case LOCAL_SETTINGS -> localSettingsLoader;
            case CLI_ARG, SESSION -> throw new IllegalStateException(
                "非可持久化 destination 不可写盘: " + dest);
        };
    }

    /**
     * {@link PermissionBehavior} → settings.json 桶名（小写）。
     * 对齐 CC {@code SUPPORTED_RULE_BEHAVIORS = ['allow','deny','ask']}
     * （permissionsLoader.ts:46-50）。
     */
    private String behaviorField(PermissionBehavior behavior) {
        return switch (behavior) {
            case ALLOW -> "allow";
            case DENY -> "deny";
            case ASK -> "ask";
        };
    }

    /**
     * {@link PermissionMode} → CC external mode 字符串（camelCase）。
     * 对齐 CC {@code toExternalPermissionMode}（PermissionMode.ts:111-115）：
     * AUTO/BUBBLE（internal）→ {@code "default"}。
     */
    private String toExternalModeName(PermissionMode mode) {
        return switch (mode) {
            case ACCEPT_EDITS -> "acceptEdits";
            case BYPASS_PERMISSIONS -> "bypassPermissions";
            case DONT_ASK -> "dontAsk";
            case PLAN -> "plan";
            case DEFAULT -> "default";
            case AUTO, BUBBLE -> "default";
        };
    }

    /**
     * 原始 rule 字符串 roundtrip 归一化 · 对齐 CC
     * {@code permissionRuleValueToString(permissionRuleValueFromString(raw))}
     * （permissionsLoader.ts:265-270 / PermissionUpdate.ts:282-287）。
     *
     * <p>目的：让 legacy 别名（如 "KillShell"）与 canonical 形式（"TaskStop"）匹配，
     * 且 "Bash(*)" 归一为 "Bash"。parse 失败（malformed）时回退原始串（CC 亦回退整串工具名）。
     */
    private String normalizeRuleString(String raw) {
        PermissionRuleValue v = ruleValueParser.parse(raw);
        return v == null ? raw : v.toRuleString();
    }
}
