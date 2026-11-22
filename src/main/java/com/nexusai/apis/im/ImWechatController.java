package com.nexusai.apis.im;

import com.nexusai.repository.im_channel.entity.WechatConversationRecord;
import com.nexusai.repository.im_channel.mapper.WechatConversationMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本机微信记录数据端点（只读）· 供扫描规则的「监控会话」选择器使用。
 *
 * <p><b>数据源是 wechatlog 的同步产物</b>（{@code wechat_conversation} / {@code wechat_message}，
 * V301），不是 iLink —— iLink 拿不到历史。所以这里只读，不写。
 *
 * <p>表为空（还没同步过）时返回空数组，前端据此提示「先去授权/同步」，
 * ⛔ 不要造演示数据混在真数据里。
 */
@RestController
@RequestMapping("/api/v1/im/wechat")
public class ImWechatController {

    @Autowired private WechatConversationMapper conversationMapper;

    /** 会话选择器条目（带「累计」与「近 7 天」两个计数，对齐设计稿）。 */
    public record ConversationOption(
        String conversationId,
        String displayName,
        boolean group,
        int messageCount,
        long recentCount,
        Long lastMessageTime
    ) {}

    /** 账号同步状态（「微信同步状态」面板 · 对齐设计稿的账号表）。 */
    public record AccountStat(
        String wxid,
        int conversationCount,
        long messageCount,
        Long lastSyncedAt
    ) {}

    /** 数据位置（给人看的三个路径 · 设计稿明确要求可查）。 */
    public record DataPaths(String store, String keys) {}

    /**
     * 账号列表 + 数据位置。
     *
     * <p>表为空 ⇒ accounts 为空数组（前端显示「未授权 / 从未同步」），
     * ⛔ 不造演示账号。
     */
    @GetMapping("/accounts")
    public Map<String, Object> accounts() {
        List<AccountStat> out = new ArrayList<>();
        for (Map<String, Object> m : conversationMapper.aggregateByAccount()) {
            Object wxid = m.get("wxid");
            Object conv = m.get("convCount");
            Object msg = m.get("msgCount");
            Object last = m.get("lastSyncedAt");
            out.add(new AccountStat(
                wxid == null ? "" : String.valueOf(wxid),
                conv == null ? 0 : Integer.parseInt(String.valueOf(conv)),
                msg == null ? 0L : Long.parseLong(String.valueOf(msg)),
                last == null ? null : Long.parseLong(String.valueOf(last))));
        }
        // 微信记录落盘位置：与 wechatlog 模块实际使用的一致（~/.nexusai/wechat-log）
        Path base = Path.of(System.getProperty("user.home"), ".nexusai", "wechat-log");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accounts", out);
        body.put("paths", new DataPaths(
            base.toString(),
            base.resolve(".keys").toString()));
        return body;
    }

    /**
     * 可监控的会话列表。
     *
     * @return 按最后消息时间倒序；表为空时返回空数组
     */
    @GetMapping("/conversations")
    public List<ConversationOption> conversations() {
        List<WechatConversationRecord> rows = conversationMapper.selectAllOrdered();
        if (rows.isEmpty()) return List.of();

        long since = Instant.now().minus(7, ChronoUnit.DAYS).getEpochSecond();
        Map<String, Long> recent = new HashMap<>();
        for (Map<String, Object> m : conversationMapper.countRecentGrouped(since)) {
            Object id = m.get("conversationId");
            Object cnt = m.get("cnt");
            if (id != null) recent.put(String.valueOf(id), cnt == null ? 0L : Long.parseLong(String.valueOf(cnt)));
        }

        List<ConversationOption> out = new ArrayList<>(rows.size());
        for (WechatConversationRecord r : rows) {
            out.add(new ConversationOption(
                r.getConversationId(),
                r.getDisplayName() == null || r.getDisplayName().isBlank() ? r.getConversationId() : r.getDisplayName(),
                r.getIsGroup() != null && r.getIsGroup() != 0,
                r.getMessageCount() == null ? 0 : r.getMessageCount(),
                recent.getOrDefault(r.getConversationId(), 0L),
                r.getLastMessageTime()));
        }
        return out;
    }
}
