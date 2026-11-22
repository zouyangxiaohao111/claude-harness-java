package com.nexusai.apis.im;

import com.nexusai.domain.im_channel.inbound.ImInboundManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入站监听的显式启停（NXS-UC-CHN-007 的前置接线）。
 *
 * <h2>为什么是显式端点而不是自动启动</h2>
 * 入站连接**有真实副作用**（真的去平台收消息、真的入队触发 agent）。
 * 自动连会让「打开 App 看一眼」变成「bot 开始干活」，也让测试环境去打真实平台。
 * 所以：用户/前端明确点「开始接收」才连。
 *
 * <h2>权限 relay 依赖这里</h2>
 * 渠道里回复 {@code yes abcde} 能被解析的前提，就是这个实例的监听在跑。
 * 没启动 ⇒ 消息根本收不到 ⇒ 那个回复永远不会生效（本地弹窗只能等本地操作）。
 */
@RestController
@RequestMapping("/api/v1/im/inbound")
public class ImInboundController {

    @Autowired private ImInboundManager manager;

    /** 当前正在监听的实例。 */
    @GetMapping
    public List<Map<String, Object>> status() {
        return manager.status();
    }

    /** 开始接收某实例的消息。 */
    @PostMapping("/{instanceId}/start")
    public Map<String, Object> start(@PathVariable String instanceId) {
        boolean ok = manager.start(instanceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instanceId);
        body.put("started", ok);
        if (!ok) {
            // 不抛异常：可能是「平台暂无实现」或「实例不存在」——都是可预期结果，
            // 用 ok=false + reason 表达，比 500 更容易被前端处理。
            body.put("reason", "无法启动（实例不存在 / 该平台暂无入站实现 / 凭证不可用，见后端日志）");
        }
        return body;
    }

    /** 停止接收（幂等）。 */
    @PostMapping("/{instanceId}/stop")
    public Map<String, Object> stop(@PathVariable String instanceId) {
        boolean stopped = manager.stop(instanceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("instanceId", instanceId);
        body.put("stopped", stopped);
        return body;
    }
}
