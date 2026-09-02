package com.nexusai.application.agent.workflow.engine;

import java.util.concurrent.CompletableFuture;

/**
 * 子 workflow 执行器 · 对齐 CC {@code engine/hooks.ts:19-24 SubWorkflowRunner}（workflow() hook 注入，
 * 由 runWorkflow 构造以避免循环依赖）。
 *
 * <p>W-1c 支撑集：P0 以 {@code name}（命名 workflow，相对 cwd/.claude/workflows 解析）为最小形态；
 * scriptPath/script 变体由 W-1e service 扩展。</p>
 */
@FunctionalInterface
public interface SubWorkflowRunner {

    /**
     * 运行一个子 workflow（共享父 ctx 的 journal/并发/预算/计数器，depth 临时 +1）。
     *
     * @param name 命名 workflow 名
     * @param args 子 workflow 调用参数
     * @return 子 workflow 返回结果
     */
    CompletableFuture<Object> run(String name, Object args);
}
