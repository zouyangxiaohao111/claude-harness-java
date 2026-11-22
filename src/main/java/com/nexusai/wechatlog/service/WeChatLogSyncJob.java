package com.nexusai.wechatlog.service;

import com.nexusai.wechatlog.locate.WeChatAccount;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 定时同步微信聊天记录到 NexusAI 索引。
 *
 * <h2>⚠️ 这个任务<b>绝不提权</b></h2>
 * 提权（macOS 弹密码框 / Windows UAC / Linux sudo）必须由用户主动触发 ——
 * 一个每小时自己弹密码框的定时任务是不可接受的。所以：
 * <ul>
 *   <li>首次同步请在设置页手动执行（会提权取密钥并缓存）；</li>
 *   <li>之后的定时任务只用<b>缓存密钥</b>；</li>
 *   <li>缓存失效时任务只记一条「需要重新授权」的日志并跳过本轮，
 *       <b>不弹任何框、不报错弹窗</b>。</li>
 * </ul>
 *
 * <h2>为什么用 Quartz 的 Job 而不是 @Scheduled</h2>
 * 项目里已经有 Quartz（{@code QuartzScheduleService}），用同一套调度器便于统一管理
 * 与「暂停/恢复」。{@link DisallowConcurrentExecution} 防止上一轮没跑完又启动下一轮
 * （全量解密 92MB 库可能跑几十秒）。
 */
@Component
@DisallowConcurrentExecution
public class WeChatLogSyncJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(WeChatLogSyncJob.class);

    @Autowired
    private WeChatLogService logService;


    @Autowired
    private WeChatMessageIndexer indexer;

    /** 是否启用定时同步（默认关闭，用户开启后生效）。 */
    @Value("${nexusai.wechatlog.sync.enabled:false}")
    private boolean enabled;

    /** 指定同步哪个账号；留空表示同步全部发现的账号。 */
    @Value("${nexusai.wechatlog.sync.account:}")
    private String accountFilter;

    @Override
    public void execute(JobExecutionContext context) {
        if (!enabled) {
            log.debug("微信聊天记录定时同步未启用，跳过");
            return;
        }
        try {
            SyncSummary summary = runOnce();
            log.info("微信聊天记录同步完成: {}", summary);
        } catch (Exception e) {
            // Job 不能抛异常，否则 Quartz 会反复重试并刷日志
            log.warn("微信聊天记录同步失败（将在下个周期重试）: {}", e.getMessage());
        }
    }

    /**
     * 执行一次同步（也可被设置页的手动同步按钮复用）。
     *
     * @return 汇总
     */
    public SyncSummary runOnce() {
        List<WeChatAccount> accounts = WeChatDataLocator.discoverAccounts();
        if (accounts.isEmpty()) {
            return new SyncSummary(0, 0, 0, "未发现微信账号目录");
        }

        int ok = 0;
        int skipped = 0;
        int totalMessages = 0;
        StringBuilder notes = new StringBuilder();

        for (WeChatAccount account : accounts) {
            if (accountFilter != null && !accountFilter.isBlank()
                    && !accountFilter.equals(account.wxid())) {
                continue;
            }
            try {
                // ⚠️ 用增量同步：只重解「源库大小/mtime/WAL」有变化的库。
                // 微信 WAL 模式下主库可能长时间不变，所以指纹必须含 WAL（见 WeChatIncrementalSync）。
                var outcome = WeChatIncrementalSync.withDefaults().syncIncremental(account, false);

                if (outcome.hasChanges()) {
                    ok++;
                    totalMessages += outcome.messages().size();
                    // 只把「本轮新解出的消息」写索引（唯一键去重，重复写不会产生重复行）
                    int indexed = indexer.index(account.wxid(), outcome.messages());
                    // 会话摘要表必须跟着刷，否则消息进了 wechat_message，
                    // /api/v1/im/wechat/conversations 仍然是空的（扫描任务选不到会话）。
                    Path workDir = WeChatLogService.defaultWorkRoot()
                            .resolve(WeChatLogService.safeName(account.wxid()));
                    indexer.refreshConversations(account.wxid(), indexer.loadDisplayNames(workDir));
                    log.info("账号 {}: 增量重解 {} 库 / {} 条消息 / 写入索引 {} 行 / 未变化 {} 库",
                            account.wxid(), outcome.changedDbs(), outcome.messages().size(),
                            indexed, outcome.unchangedDbs());
                    if (!outcome.skippedNoKey().isEmpty()) {
                        notes.append(account.wxid()).append(": ")
                                .append(outcome.skippedNoKey().size())
                                .append(" 个库缺密钥; ");
                    }
                } else {
                    skipped++;
                    notes.append(account.wxid()).append(": ").append(outcome.note()).append("; ");
                }
            } catch (Exception e) {
                skipped++;
                // 缓存失效是预期情况（微信重新登录），只记 info 级别
                String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                if (msg.contains("需要重新取密钥") || msg.contains("需要授权")) {
                    log.info("账号 {} 需要用户手动授权一次（跳过本轮）", account.wxid());
                    notes.append(account.wxid()).append(": 需要手动授权; ");
                } else {
                    log.warn("账号 {} 同步失败", account.wxid(), e);
                    notes.append(account.wxid()).append(": ").append(msg).append("; ");
                }
            }
        }
        return new SyncSummary(ok, skipped, totalMessages, notes.toString());
    }

    /**
     * 同步汇总。
     *
     * @param succeeded 成功账号数
     * @param skipped   跳过账号数
     * @param messages  本轮读出的消息总数
     * @param note      说明
     */
    public record SyncSummary(int succeeded, int skipped, int messages, String note) {
        @Override
        public String toString() {
            return "成功 " + succeeded + " / 跳过 " + skipped + " / 消息 " + messages
                    + (note.isEmpty() ? "" : " | " + note);
        }
    }
}
