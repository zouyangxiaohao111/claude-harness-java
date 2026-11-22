package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.ImChannelService;
import com.nexusai.domain.im_message.ImScanChannelService;
import com.nexusai.domain.im_message.ImScanTaskService;
import com.nexusai.model.im_channel.ImInstanceEntry;
import com.nexusai.model.im_message.ImScanChannelEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「消息扫描」（非 agent 任务）的配置端点。
 *
 * <h2>这是配置，不是任务管理</h2>
 * 扫描任务本身是**系统内置**的：用户不能新建、不能删除，只能决定「扫哪些渠道」。
 * 所以这里**没有 POST（新建）/ DELETE（删除）** —— 只有读 + 整体设置。
 *
 * <p>为什么不提供「按渠道 enable/disable」两个端点：界面是一组复选框，
 * 用户勾完点保存 = 一次提交整体意向。逐个调用会产生「勾了三个，前两个成功、
 * 第三个失败」的中间态，用户看到的状态和库里的不一致。
 *
 * <h2>为什么要跟实例目录一起返回</h2>
 * 界面要显示「所有渠道 + 各自是否在扫」，只返回 `im_scan_channel` 的话，
 * 从没配置过的渠道根本不在列表里（那张表只存「配置过」的），用户就看不到它。
 * ⇒ 以 {@link ImChannelService#list()}（全部实例）为骨架左连接配置。
 */
@RestController
@RequestMapping("/api/v1/im/scan-channels")
public class ImScanChannelController {

    private static final Logger log = LoggerFactory.getLogger(ImScanChannelController.class);

    @Autowired private ImScanChannelService scanChannelService;
    @Autowired private ImScanTaskService scanTaskService;
    @Autowired private ImChannelService channelService;

    /**
     * 全部渠道 + 是否在扫 + 上次扫描结果。
     *
     * @return {@code {channels:[{instanceId, platform, displayName, enabled, lastScanAt, ...}], enabledCount}}
     */
    @GetMapping
    public Map<String, Object> list() {
        List<ImInstanceEntry> instances = channelService.list();
        // 配置表只存「配置过」的；用它做左侧补充，实例列表做骨架
        Map<String, ImScanChannelEntry> byId = new LinkedHashMap<>();
        for (ImScanChannelEntry e : scanChannelService.list()) {
            byId.put(e.instanceId(), e);
        }

        List<Map<String, Object>> channels = new ArrayList<>(instances.size());
        int enabledCount = 0;
        for (ImInstanceEntry inst : instances) {
            ImScanChannelEntry cfg = byId.get(inst.id());
            boolean enabled = cfg != null && cfg.enabled();
            if (enabled) {
                enabledCount++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("instanceId", inst.id());
            m.put("platform", inst.platform());
            m.put("displayName", inst.displayName());
            m.put("enabled", enabled);
            if (cfg != null) {
                // 只在「配置过」时才带结果：没配过的渠道显示「从未扫描」比显示一堆 null 清楚
                m.put("lastScanAt", cfg.lastScanAt());
                m.put("lastChatCount", cfg.lastChatCount());
                m.put("lastNewCount", cfg.lastNewCount());
                m.put("lastError", cfg.lastError());
            }
            channels.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channels", channels);
        out.put("enabledCount", enabledCount);
        // 任务级暂停（V318）：与渠道勾选相互独立 —— 暂停时 channels 仍原样返回
        out.put("paused", scanTaskService.isPaused());
        return out;
    }

    /**
     * 暂停 / 恢复整个「消息扫描」系统任务（V318）。
     *
     * @param body {@code {paused: true}} —— 暂停；{@code false} = 恢复
     *
     * <p>与 {@link #setChannels(Map)} 分开的原因：暂停是**一次点击即时生效**的开关，
     * 不是「勾完一批再保存」的意图全集；混进渠道设置会让「保存渠道」顺手改掉暂停态。
     * <p>⛔ 暂停只换 {@code im_scan_task.paused}，不动 {@code im_scan_channel}
     * ⇒ 恢复后用户原来的渠道勾选还在。
     * <p>幂等：重复暂停/重复恢复都不报错。
     */
    @PutMapping("/paused")
    public Map<String, Object> setPaused(@RequestBody Map<String, Object> body) {
        Object raw = body == null ? null : body.get("paused");
        // 缺字段按「暂停」处理（这个端点只被明确的暂停/恢复动作调用；缺参更可能是前端漏传）
        boolean paused = raw == null || Boolean.parseBoolean(String.valueOf(raw));
        boolean saved = scanTaskService.setPaused(paused);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("paused", saved);
        return out;
    }

    /**
     * 整体设置「要扫哪些渠道」（覆盖式）。
     *
     * @param body {@code {instanceIds:["imc-a","imc-b"]}} —— 传空数组等于「全部不扫」
     *
     * <p>覆盖式而不是增量：界面是复选框 + 保存，用户提交的是**意图全集**。
     * 增量式（只发勾选项）无法表达「取消勾选」，而这是最常用的操作。
     */
    @PutMapping
    public Map<String, Object> setChannels(@RequestBody Map<String, Object> body) {
        Set<String> wanted = new LinkedHashSet<>();
        Object raw = body == null ? null : body.get("instanceIds");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o != null && !String.valueOf(o).isBlank()) {
                    wanted.add(String.valueOf(o).trim());
                }
            }
        }

        // 现有实例 id（防止把不存在的 id 写进配置表，留下再也清理不掉的孤儿行）
        Set<String> existing = new LinkedHashSet<>();
        for (ImInstanceEntry inst : channelService.list()) {
            existing.add(inst.id());
        }

        List<String> enabled = new ArrayList<>();
        List<String> disabled = new ArrayList<>();
        for (String id : existing) {
            if (wanted.contains(id)) {
                scanChannelService.enable(id);
                enabled.add(id);
            } else {
                // disable 是幂等的；从没配置过的渠道调用它只会写一行 enabled=0，无害
                scanChannelService.disable(id);
                disabled.add(id);
            }
        }
        // 传入但库里不存在的 id：不报错（实例可能刚被删），但要可观测
        Set<String> unknown = new LinkedHashSet<>(wanted);
        unknown.removeAll(existing);
        if (!unknown.isEmpty()) {
            log.warn("[ImScanChannel] 忽略不存在的实例 id: {}", unknown);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        out.put("disabled", disabled);
        if (!unknown.isEmpty()) {
            out.put("ignored", new ArrayList<>(unknown));
        }
        log.info("[ImScanChannel] 扫描渠道已更新: enabled={} disabled={}", enabled.size(), disabled.size());
        return out;
    }
}
