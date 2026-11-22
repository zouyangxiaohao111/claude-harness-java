package com.nexusai.domain.im_message;

import com.nexusai.repository.im_message.entity.ImScanTaskRecord;
import com.nexusai.repository.im_message.mapper.ImScanTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 「消息扫描」系统任务的<b>任务级</b>开关（V318）· 用户可以暂停/恢复整个扫描任务。
 *
 * <p>与 {@link ImScanChannelService} 的分工：
 * <ul>
 *   <li>渠道级 {@code enabled} = 「扫哪些渠道」（暂停后原样保留）；</li>
 *   <li>任务级 {@code paused} = 「这个任务现在跑不跑」。</li>
 * </ul>
 *
 * <p><b>暂停只是一个读取判据，不是删除配置</b>：{@link ImMessageSyncJob} 到点照常触发，
 * {@link ImMessageSyncService#runOnce()} 开头读本开关，暂停则这一轮直接跳过。
 * ⇒ 不需要动 Quartz（暂停/恢复即时生效，也不用担心重启后 register 覆盖触发状态）。
 *
 * <p>行缺失/列为 null ⇒ 按<b>未暂停</b>处理（与 DDL 默认值一致）：
 * 读配置失败不应该让扫描悄悄停掉。
 */
@Service
public class ImScanTaskService {

    private static final Logger log = LoggerFactory.getLogger(ImScanTaskService.class);

    /** V318 里预置的唯一行主键。 */
    public static final String ROW_ID = "scan-default";

    @Autowired private ImScanTaskMapper mapper;

    /** 任务是否处于暂停态（读不到行 ⇒ false）。 */
    public boolean isPaused() {
        ImScanTaskRecord r = mapper.selectOneById(ROW_ID);
        if (r == null) {
            return false;
        }
        return r.paused();
    }

    /**
     * 设置暂停态（幂等；行缺失则补插）。
     *
     * @return 保存后的状态
     */
    public boolean setPaused(boolean paused) {
        ImScanTaskRecord r = mapper.selectOneById(ROW_ID);
        boolean creating = r == null;
        if (creating) {
            r = new ImScanTaskRecord();
            r.setId(ROW_ID);
            r.setCreator("system");
            r.setCreateTime(now());
        }
        r.setPaused(paused ? 1 : 0);
        r.setUpdateTime(now());
        r.setUpdater("user");
        r.setDeleted(0);
        if (creating) {
            mapper.insert(r);
        } else {
            mapper.update(r);
        }
        log.info("[ImScanTaskService] 消息扫描任务已{}", paused ? "暂停" : "恢复");
        return paused;
    }

    private static String now() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
