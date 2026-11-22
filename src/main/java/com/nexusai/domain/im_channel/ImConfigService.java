package com.nexusai.domain.im_channel;

import com.nexusai.model.im_channel.ImConfigEntry;
import com.nexusai.repository.im_channel.entity.ImConfigRecord;
import com.nexusai.repository.im_channel.mapper.ImConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * IM 安全与权限配置（单行读写）。
 *
 * <p>行由 V307 预置（{@code imcfg-default}）；极端情况下行缺失时返回<b>默认值</b>而不是报错 ——
 * 配置读不到不该让整个 IM 页打不开。写操作会补插缺失的行。
 */
@Service
public class ImConfigService {

    private static final Logger log = LoggerFactory.getLogger(ImConfigService.class);

    /** V307 里预置的唯一行主键。 */
    public static final String ROW_ID = "imcfg-default";

    @Autowired private ImConfigMapper mapper;

    public ImConfigEntry get() {
        ImConfigRecord r = mapper.selectOneById(ROW_ID);
        if (r == null) {
            log.warn("[ImConfigService] im_config 缺少预置行，返回默认值");
            return new ImConfigEntry(true, true, false, true, true, true, false, null);
        }
        return r.toDomain();
    }

    /**
     * 整体覆盖 6 个开关。
     *
     * <p>整体覆盖而不是逐字段 patch：前端是一屏开关 + 一次保存，
     * patch 语义会让"关掉某个开关"和"没传这个字段"无法区分。
     */
    public ImConfigEntry update(ImConfigEntry in) {
        ImConfigRecord r = mapper.selectOneById(ROW_ID);
        boolean creating = r == null;
        if (creating) {
            r = new ImConfigRecord();
            r.setId(ROW_ID);
            r.setCreator("system");
            r.setCreateTime(now());
        }
        r.setReadAll(in.readAll() ? 1 : 0);
        r.setAutoBind(in.autoBind() ? 1 : 0);
        r.setAiDirect(in.aiDirect() ? 1 : 0);
        r.setExistingOnly(in.existingOnly() ? 1 : 0);
        r.setTakeoverConfirm(in.takeoverConfirm() ? 1 : 0);
        r.setAudit(in.audit() ? 1 : 0);
        r.setAllowUserIdentity(in.allowUserIdentity() ? 1 : 0);
        r.setUpdateTime(now());
        r.setUpdater("user");
        r.setDeleted(0);
        if (creating) {
            mapper.insert(r);
        } else {
            mapper.update(r);
        }
        log.info("[ImConfigService] 安全开关已更新: readAll={} autoBind={} aiDirect={} "
            + "existingOnly={} takeoverConfirm={} audit={} allowUserIdentity={}",
            in.readAll(), in.autoBind(), in.aiDirect(),
            in.existingOnly(), in.takeoverConfirm(), in.audit(), in.allowUserIdentity());
        return r.toDomain();
    }

    /** 发送链路用的便捷读取（后续接 SendUserMessage 渠道投递时消费）。 */
    public boolean aiDirect() {
        return get().aiDirect();
    }

    /**
     * 是否允许 AI <b>以用户本人名义</b>在 IM 发消息。
     *
     * <p>默认 false：AI 默认只能以机器人身份说话。让"以用户名义"需要用户**显式打开**
     * 这个开关 —— 对方看到的发送者身份不同（是用户本人而非机器人），
     * 这属于"替用户对外发言"，不��默认授权。
     *
     * <p>⛔ 读不到配置时按 false（fail-closed）：宁可 AI 不能冒充，也不能在配置库
     * 异常时让它突然获得冒充能力。
     */
    public boolean isAllowUserIdentity() {
        try {
            return get().allowUserIdentity();
        } catch (RuntimeException e) {
            log.warn("[ImConfigService] 读取 allow_user_identity 失败，按关闭处理: {}", e.toString());
            return false;
        }
    }

    /**
     * 设置「允许 AI 以用户名义发消息」开关，返回更新后的整份配置。
     *
     * <p>为什么用整体 update 而不是只改一列：{@link #update(ImConfigEntry)} 的语义是
     * "整屏开关一次保存"，单独一列更新会让两个入口（IM 页 / 对话框）互相覆盖别人的改动。
     * 这里先读当前值再整体写回，等价于"只动这一个开关"且不破坏整体覆盖语义。
     */
    public ImConfigEntry setAllowUserIdentity(boolean allow) {
        ImConfigEntry cur = get();
        return update(new ImConfigEntry(
                cur.readAll(), cur.autoBind(), cur.aiDirect(),
                cur.existingOnly(), cur.takeoverConfirm(), cur.audit(),
                allow, null));
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
