package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话级 computer-use 授权快照（应用白名单 + 授权位）· 会话列
 * {@code sessions.computer_use_grants}（V250 列）的规范形编解码。
 *
 * <h2>规范形</h2>
 * <pre>
 * {
 *   "apps": [ {"appId":"chrome","displayName":"Chrome","grantedAt":1750000000000,"tier":"read"} ],
 *   "flags": { "clipboardRead":false, "clipboardWrite":false, "systemKeyCombos":false }
 * }
 * </pre>
 *
 * <h2>读法（fail-soft，照抄 {@code SessionService.getDisabledTools}
 * {@code domain/session/SessionService.java:612-629}）</h2>
 * <p>解析失败 → <b>降级为空授权 + WARN</b>，<b>不抛</b>（一个坏列不得让整轮 run 崩掉）。
 *
 * <h2>写法（RMW 单点，照抄 {@code SessionPermissionOverlay.persistSessionUpdates}
 * {@code application/agent/permission/SessionPermissionOverlay.java:226,244,263}）</h2>
 * <p>读改写全程在静态锁内；写回用 {@code sessionMapper.update(entity, false)}（显式写 NULL）
 * 或 {@code sessionMapper.update(entity)}（忽略 null 字段）—— 见 {@link ComputerUseGrantStore}。
 */
public final class ComputerUseGrants {

    private static final Logger log = LoggerFactory.getLogger(ComputerUseGrants.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 规范形字段名（单一来源，读写同源）。 */
    private static final String F_APPS = "apps";
    private static final String F_FLAGS = "flags";

    private final List<ComputerUseGrant> apps;
    private final ComputerUseGrantFlags flags;

    /**
     * @param apps  已授权应用（有序；null ⇒ 空）
     * @param flags 授权位（null ⇒ {@link ComputerUseGrantFlags#DEFAULT}）
     */
    public ComputerUseGrants(List<ComputerUseGrant> apps, ComputerUseGrantFlags flags) {
        this.apps = apps == null ? List.of() : List.copyOf(apps);
        this.flags = flags == null ? ComputerUseGrantFlags.DEFAULT : flags;
    }

    /**
     * @return 空授权快照（无应用 + 全 false 授权位）
     */
    public static ComputerUseGrants empty() {
        return new ComputerUseGrants(List.of(), ComputerUseGrantFlags.DEFAULT);
    }

    /**
     * @return 已授权应用（不可变）
     */
    public List<ComputerUseGrant> apps() {
        return apps;
    }

    /**
     * @return 授权位
     */
    public ComputerUseGrantFlags flags() {
        return flags;
    }

    /**
     * 查某个授权键的授权条目。
     *
     * @param appKey 授权键（会被归一为小写 exe 名）
     * @return 授权条目；未授权 ⇒ null
     */
    public ComputerUseGrant findByAppId(String appKey) {
        String key = ComputerUseAppPolicy.normalizeAppKey(appKey);
        if (key.isEmpty()) {
            return null;
        }
        for (ComputerUseGrant grant : apps) {
            if (grant.normalizedAppId().equals(key)) {
                return grant;
            }
        }
        return null;
    }

    /**
     * 查某个授权键是否已授权。
     *
     * @param appKey 授权键
     * @return true = 已授权
     */
    public boolean isGranted(String appKey) {
        return findByAppId(appKey) != null;
    }

    /**
     * 合并新授权（应用维度按授权键去重，<b>后者覆盖前者</b>）。
     *
     * <p>授权位走 <b>truthy-only</b> 合并（{@link ComputerUseGrantFlags#mergeTruthyOnly}）——
     * 后一次 {@code request_access} 不能撤销先前的剪贴板授权。
     *
     * @param newGrants      新增/更新的授权条目
     * @param requestedFlags 本次请求的授权位
     * @return 合并后的快照
     */
    public ComputerUseGrants merge(List<ComputerUseGrant> newGrants, ComputerUseGrantFlags requestedFlags) {
        Map<String, ComputerUseGrant> byKey = new LinkedHashMap<>();
        for (ComputerUseGrant grant : apps) {
            byKey.put(grant.normalizedAppId(), grant);
        }
        if (newGrants != null) {
            for (ComputerUseGrant grant : newGrants) {
                if (grant == null) {
                    continue;
                }
                byKey.put(grant.normalizedAppId(), grant);
            }
        }
        return new ComputerUseGrants(new ArrayList<>(byKey.values()),
                ComputerUseGrantFlags.mergeTruthyOnly(flags, requestedFlags));
    }

    /**
     * 规范形 JSON 串（写列用）。
     *
     * @return JSON 串（无应用 + 全 false 授权位 ⇒ 仍写完整形状，不写 null）
     */
    public String toJson() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode appsNode = root.putArray(F_APPS);
        for (ComputerUseGrant grant : apps) {
            ObjectNode node = appsNode.addObject();
            grant.writeTo(node);
        }
        ObjectNode flagsNode = root.putObject(F_FLAGS);
        flags.writeTo(flagsNode);
        return root.toString();
    }

    /**
     * 规范形 JSON 串 → 授权快照（fail-soft）。
     *
     * <p><b>解析失败降级为空 + WARN</b>（不抛）—— 照抄
     * {@code SessionService.getDisabledTools}（{@code SessionService.java:612-629}）：
     * <ul>
     *   <li>列 null / 空白 ⇒ 空授权（不 warn，无事发生）</li>
     *   <li>坏 JSON / 非对象 ⇒ 空授权 + <b>WARN</b></li>
     *   <li>单条坏授权条目 ⇒ 跳过该条 + WARN（一条脏数据不得让整列消失）</li>
     * </ul>
     *
     * @param columnJson 列 JSON 串（可 null）
     * @return 授权快照（永不 null）
     */
    public static ComputerUseGrants parse(String columnJson) {
        if (columnJson == null || columnJson.isBlank()) {
            return empty();
        }
        try {
            JsonNode root = JSON.readTree(columnJson);
            if (root == null || !root.isObject()) {
                log.warn("[computer-use] 会话列 computer_use_grants 非 JSON 对象，按空授权处理: {}",
                        abbreviate(columnJson));
                return empty();
            }
            List<ComputerUseGrant> apps = new ArrayList<>();
            JsonNode appsNode = root.get(F_APPS);
            if (appsNode != null && appsNode.isArray()) {
                for (JsonNode item : appsNode) {
                    ComputerUseGrant grant = ComputerUseGrant.fromJson(item);
                    if (grant == null) {
                        log.warn("[computer-use] 会话列 computer_use_grants 含坏授权条目，跳过: {}", item);
                        continue;
                    }
                    apps.add(grant);
                }
            }
            ComputerUseGrantFlags flags = ComputerUseGrantFlags.fromJson(root.get(F_FLAGS));
            return new ComputerUseGrants(apps, flags);
        } catch (Exception e) {
            log.warn("[computer-use] 会话列 computer_use_grants 解析失败（降级为空授权，不阻断 run）：{}",
                    e.toString());
            return empty();
        }
    }

    /**
     * 授权键归一（小写）。
     *
     * @param appKey 原始键
     * @return 小写键
     */
    public static String normalizeKey(String appKey) {
        return ComputerUseAppPolicy.normalizeAppKey(appKey).toLowerCase(Locale.ROOT);
    }

    /**
     * 日志用缩写（长列串只打头尾）。
     *
     * @param s 原串
     * @return 缩写串
     */
    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...(len=" + s.length() + ")";
    }
}
