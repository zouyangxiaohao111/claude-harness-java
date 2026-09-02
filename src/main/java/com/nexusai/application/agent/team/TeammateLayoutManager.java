package com.nexusai.application.agent.team;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Teammate Layout Manager · 对齐 CC utils/swarm/teammateLayoutManager.ts.
 *
 * <p>FIX-SWARM-MISC: tmux swarm-view pane 布局管理.
 *
 * <p>L1 行为: 给定 teammateId, 返回 pane layout (width/height/color).
 *
 * @deprecated 本期未启用，教学版 stub，参见 探查/subagent/本期不上生产的模块.md
 */
@Component
public class TeammateLayoutManager {

    private final Map<String, Layout> layouts = new ConcurrentHashMap<>();

    public Layout get(String teammateId) {
        return layouts.computeIfAbsent(teammateId, k ->
            new Layout(k, 80, 24, "blue"));
    }

    public void set(String teammateId, Layout layout) {
        layouts.put(teammateId, layout);
    }

    public record Layout(String teammateId, int width, int height, String color) {}
}