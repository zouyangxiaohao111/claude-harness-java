package com.nexusai.apis.mcp_channel_allowlist;

import com.nexusai.domain.mcp_channel_allowlist.ChannelAllowlistService;
import com.nexusai.model.mcp_channel_allowlist.ChannelAllowlistEntry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * MCP channel allowlist REST 端点（Q-37 ledger 白名单 DB 表管理接口）。
 *
 * <p>CC original: {@code getChannelAllowlist()}（channelAllowlist.ts:37-44，GrowthBook 表）—
 * Java 侧由前端开关写入本表。独立命名空间 {@code /api/v1/mcp/channel-allowlist}，
 * 不与既有 {@code /api/v1/mcp} server CRUD 冲突。
 */
@RestController
@RequestMapping("/api/v1/mcp/channel-allowlist")
public class ChannelAllowlistController {

    @Autowired private ChannelAllowlistService channelAllowlistService;

    /** 全部白名单条目 · CC original: getChannelAllowlist()（channelAllowlist.ts:37-44）。 */
    @GetMapping
    public List<ChannelAllowlistEntry> list() {
        return channelAllowlistService.listAll();
    }

    /** 新增白名单条目（{marketplace, plugin}）· POST → 201。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChannelAllowlistEntry create(@RequestBody Map<String, String> body) {
        String marketplace = body.get("marketplace");
        String plugin = body.get("plugin");
        return channelAllowlistService.create(marketplace, plugin);
    }

    /** 删除白名单条目 · DELETE → 204。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        channelAllowlistService.delete(id);
    }
}
