package com.nexusai.application.agent.team;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teammate Init · 对齐 CC utils/swarm/teammateInit.ts.
 *
 * <p>FIX-SWARM-MISC: teammate hooks 初始化 + 事件订阅.
 *
 * <p>L1 行为: 每个 teammate 启动时注册 hooks (TeammateStarted/Idle/Stopped),
 * 与 TS teammateInit.ts 兼容.
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
@Component
public class TeammateInit {

    private static final Logger log = LoggerFactory.getLogger(TeammateInit.class);

    private final Map<String, TeammateInitRecord> records = new ConcurrentHashMap<>();

    public TeammateInitRecord init(String teammateId, String taskId, String model) {
        TeammateInitRecord rec = new TeammateInitRecord(teammateId, taskId, model,
            System.currentTimeMillis(), Map.of());
        records.put(teammateId, rec);
        log.info("TeammateInit: teammate={} task={} model={}", teammateId, taskId, model);
        return rec;
    }

    public void teardown(String teammateId) {
        records.remove(teammateId);
    }

    public TeammateInitRecord get(String teammateId) {
        return records.get(teammateId);
    }

    public record TeammateInitRecord(String teammateId, String taskId, String model,
                                     long initializedAt, Map<String, Object> hooks) {}
}