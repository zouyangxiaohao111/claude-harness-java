package com.nexusai.apis.im;

import com.nexusai.wechatlog.key.WeChatKeyExtractor;
import com.nexusai.wechatlog.locate.WeChatAccount;
import com.nexusai.wechatlog.locate.WeChatDataLocator;
import com.nexusai.wechatlog.model.WeChatMessage;
import com.nexusai.wechatlog.service.WeChatIncrementalSync;
import com.nexusai.wechatlog.service.WeChatLogService;
import com.nexusai.wechatlog.service.WeChatMessageIndexer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机微信聊天记录同步端点（产品内入口，替代原来的 CLI）。
 *
 * <h2>这条链和「微信渠道」不是一回事</h2>
 * 「频道」页的微信 = iLink Bot 扫码，只能收<b>发给机器人</b>的新消息，拿不到历史。
 * 本端点走另一条：解密本机 PC 微信（已登录客户端）的 SQLCipher 库，把历史消息
 * 写进 {@code wechat_message}，供扫描任务与 agent 工具检索。
 *
 * <h2>取密钥的权限</h2>
 * 取密钥要读微信进程内存（{@code WindowsKeyExtractor}）。先按普通权限尝试；
 * 只有「打开进程失败（权限）」才按需请求一次管理员授权（UAC）。
 * 失败按真实分支返回 {@code category + error + remedy}
 * （版本未适配 / 扫描预算不足 / 取消提权 / 真的无权限 …），
 * 不再把版本或覆盖问题写成「需要管理员」。
 *
 * <h2>⚠️ 微信改过存储位置时</h2>
 * 微信 4.x 不把自定义存储路径写注册表（本机实测 FileSavePath 为空），
 * 代码猜不出来 ⇒ 先用 {@code PUT /root} 指定一次。
 */
@RestController
@RequestMapping("/api/v1/wechat-log")
public class WeChatLogController {

    private static final Logger log = LoggerFactory.getLogger(WeChatLogController.class);

    private final WeChatMessageIndexer indexer;

    @Autowired
    public WeChatLogController(WeChatMessageIndexer indexer) {
        this.indexer = indexer;
    }

    /**
     * 探测：当前根目录、候选目录、发现到的账号。只读，不提权。
     */
    @GetMapping("/probe")
    public Map<String, Object> probe() {
        Path root = WeChatDataLocator.defaultXwechatFilesRoot();
        List<WeChatAccount> accounts = WeChatDataLocator.discoverAccounts();

        List<Map<String, Object>> items = new ArrayList<>(accounts.size());
        for (WeChatAccount a : accounts) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("wxid", a.wxid());
            m.put("version", a.version());
            m.put("dir", a.accountDir().toString());
            m.put("encryptedDbs", WeChatDataLocator.listEncryptedDatabases(a.accountDir()).size());
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("root", root == null ? null : root.toString());
        out.put("configuredRoot", str(WeChatDataLocator.configuredRoot()));
        out.put("candidates", WeChatDataLocator.rootCandidates().stream().map(Path::toString).toList());
        out.put("accounts", items);
        return out;
    }

    /**
     * 执行一次同步：取密钥（必要时）→ 解密 → 写消息索引 → 刷新会话摘要。
     */
    @PostMapping("/sync")
    public Map<String, Object> sync() {
        List<WeChatAccount> accounts = WeChatDataLocator.discoverAccounts();
        List<Map<String, Object>> results = new ArrayList<>(accounts.size());
        int totalIndexed = 0;

        Map<String, Object> out = new LinkedHashMap<>();
        if (accounts.isEmpty()) {
            out.put("accounts", results);
            out.put("totalIndexed", 0);
            out.put("error", "没有发现微信账号目录。请先指定数据目录。");
            return out;
        }

        for (WeChatAccount account : accounts) {
            Map<String, Object> r = syncOne(account);
            results.add(r);
            if (r.get("indexed") instanceof Integer i) {
                totalIndexed += i;
            }
        }
        out.put("accounts", results);
        out.put("totalIndexed", totalIndexed);
        return out;
    }

    /**
     * 指定微信数据根目录（含 {@code xwechat_files} 的那一级）。
     *
     * @param body {@code {root: "D:\\wechat\\chat\\xwechat_files"}}
     */
    @PutMapping("/root")
    public Map<String, Object> setRoot(@RequestBody Map<String, Object> body) {
        String raw = str(body == null ? null : body.get("root"));
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) {
            out.put("error", "root 不能为空");
            return out;
        }
        Path root = Path.of(raw.trim());
        if (!Files.isDirectory(root)) {
            out.put("error", "目录不存在: " + root);
            return out;
        }
        WeChatDataLocator.setConfiguredRoot(root);
        out.put("root", root.toString());
        out.put("accounts", probe().get("accounts"));
        return out;
    }

    // ==================================================================

    /** 同步单个账号；异常转成结构化结果，不整轮中断。 */
    private Map<String, Object> syncOne(WeChatAccount account) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wxid", account.wxid());
        try {
            List<WeChatMessage> messages = collectMessages(account);
            int indexed = indexer.index(account.wxid(), messages);

            // 刷新会话摘要：监控会话选择器（/api/v1/im/wechat/conversations）依赖这张表。
            // 不刷新的话消息写进去了，界面上仍然一个会话都选不到。
            Path workDir = WeChatLogService.defaultWorkRoot()
                .resolve(WeChatLogService.safeName(account.wxid()));
            indexer.refreshConversations(account.wxid(), indexer.loadDisplayNames(workDir));

            m.put("status", "ok");
            m.put("messages", messages.size());
            m.put("indexed", indexed);
            log.info("[WeChatLog] 账号 {} 同步完成: 消息 {} 条 / 索引 {} 行",
                account.wxid(), messages.size(), indexed);
        } catch (WeChatKeyExtractor.KeyExtractionException e) {
            m.put("status", "error");
            // category 让前端按真实分支展示（不再写死「需要管理员权限」）
            m.put("category", e.kind().name());
            m.put("error", e.getMessage());
            m.put("remedy", e.remedy());
            log.warn("[WeChatLog] 账号 {} 取密钥失败[{}]: {}",
                    account.wxid(), e.kind(), e.getMessage());
        } catch (Exception e) {
            m.put("status", "error");
            m.put("category", "UNKNOWN");
            m.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            log.warn("[WeChatLog] 账号 {} 同步失败", account.wxid(), e);
        }
        return m;
    }

    /**
     * 取本轮要索引的消息。
     *
     * <p>有缓存密钥走增量（只重解变化的库）；没有缓存密钥 / 缓存失效时
     * 才走全量 {@link WeChatLogService#sync}（普通权限优先，仅在打开进程失败时按需提权）。
     */
    private List<WeChatMessage> collectMessages(WeChatAccount account) {
        WeChatIncrementalSync.SyncOutcome outcome =
            WeChatIncrementalSync.withDefaults().syncIncremental(account, false);

        // 结构化判据（needsKeyRefresh）而不是 note 子串：旧写法在「缓存非空但全部失配」时
        // 会直接返回空，导致坏 .keys 永远修不好。
        if (!outcome.needsKeyRefresh()) {
            return outcome.messages();
        }

        log.info("[WeChatLog] 账号 {} 缓存密钥为空/失效，转为全量同步（必要时提权）", account.wxid());
        return WeChatLogService.withDefaults().sync(account, true).messages();
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
