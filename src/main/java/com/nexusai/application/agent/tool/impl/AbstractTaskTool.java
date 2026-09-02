package com.nexusai.application.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusai.application.agent.tasks.TaskSystemConfig;
import com.nexusai.application.agent.tool.Tool;

/**
 * Task V2 工具抽象基类 · s12.5 L3 v2.1
 *
 * <p>封装 6 个 Task V2 工具（Create/Get/Update/List/Stop/Output）的公共默认行为：
 * <ul>
 *   <li>{@code isEnabled()} → {@code isTodoV2Enabled()}</li>
 *   <li>{@code shouldDefer()} → {@code false}（2026-09-01 用户拍板：V2 系列直接开放，不懒加载——
 *       模型无需 ToolSearch 即可用 Task*；CC 原 shouldDefer=true 懒加载已偏离）</li>
 *   <li>{@code isConcurrencySafe()} → {@code true}</li>
 *   <li>{@code maxResultSizeChars()} → {@code 100_000}</li>
 * </ul>
 *
 * <p>[IMP-G] G25③ 修正过时 Javadoc：TaskStopTool / TaskOutputTool 均 override
 * {@code maxResultSizeChars} 为 {@code 100_000}（对齐 CC TaskStopTool.ts:45 / TaskOutputTool.tsx:147），
 * {@code shouldDefer} 恒 {@code true}（CC 同值，显式 override 文档化）；{@code isConcurrencySafe}
 * 基类返回 {@code true}（CC TaskStopTool.ts:54-56 / TaskOutputTool.tsx:160-162 均为 true）。
 */
public abstract class AbstractTaskTool implements Tool {

    protected static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public boolean isEnabled() {
        return TaskSystemConfig.isTodoV2Enabled();
    }

    @Override
    public boolean shouldDefer(JsonNode input) {
        // [2026-09-01 用户拍板] V2 系列（TaskCreate/TaskGet/TaskList/TaskUpdate/TaskStop/TaskOutput）
        //   直接开放（不懒加载）：模型无需 ToolSearch 即可用 Task* 系列（CC 原 shouldDefer=true 懒加载，
        //   用户要求偏离——Web 对话模型应直接看到 Task* schema）。注释 21 行已同步。
        return false;
    }

    @Override
    public boolean isConcurrencySafe(JsonNode input) {
        return true;
    }

    @Override
    public long maxResultSizeChars() {
        return 100_000L;
    }
}
