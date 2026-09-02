package com.nexusai.application.agent.workflow.script;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * 脚本可见的 6 个 hook 能力（对齐 CC Open-ClaudeCode/packages/workflow-engine/src/engine/script.ts:11-26 WorkflowHooks）。
 *
 * <p>脚本以 AsyncFunction 形参注入：agent / parallel / pipeline / phase / log / workflow（位置 1-6），
 * 加上 execute 的 args（位置 7）/ budget（位置 8），共 <b>8 个业务注入参数</b>；Date/Math 沙箱为位置 9-10
 * （script-doc §3.1「8 注入」口径注明两沙箱参数）。</p>
 *
 * <p>hook 运行时语义（CC engine/hooks.ts）由执行引擎 W-1c 实现：
 * parallel/pipeline 超过 MAX_ITEMS_PER_CALL(4096) 抛 WorkflowError（hooks.ts:266-269,292-295）；
 * phase() 在 currentPhase 非空时先发旧 phase_done 再发新 phase_started（hooks.ts:315-321）；
 * workflow() 传 string=name、传 {scriptPath} 为文件引用，depth&gt;=1 抛错（hooks.ts:327-336）。</p>
 */
public interface WorkflowHooks {

    /**
     * 派发子 agent（CC hooks.agent，script.ts:12）。返回 ok.output 或 null（dead/skipped 降级）。
     *
     * @param prompt 子 agent 指令
     * @param opts   可选参数（CC 类型 Record&lt;string, unknown&gt;；Java 侧以 Map 透传；
     *               W-1a AgentRunParams 落地后可替换，行为对齐 opts?: Record）
     * @return 子 agent 输出或 null
     */
    CompletableFuture<Object> agent(String prompt, Map<String, Object> opts);

    /**
     * 并行执行一组 thunk（CC hooks.parallel，script.ts:13）。单 item 失败 → 对应位 null（日志 warn）。
     *
     * @param thunks 待并行执行的 thunk 列表
     * @return 与 thunks 等长的结果列表，失败项为 null 占位
     */
    CompletableFuture<List<Object>> parallel(List<Supplier<CompletableFuture<Object>>> thunks);

    /**
     * 每 item 顺序过 stages（prev 链）（CC hooks.pipeline，script.ts:14-19）。item 失败 → null 占位。
     *
     * @param items  输入列表（CC readonly T[]）
     * @param stages 阶段链，每阶段 (prev, item, index) =&gt; Promise&lt;unknown&gt;
     * @return 每个 item 的最终结果列表，失败项为 null 占位
     */
    CompletableFuture<List<Object>> pipeline(List<Object> items, List<PipelineStage> stages);

    /**
     * 切阶段（CC hooks.phase，script.ts:20）：先发旧 phase_done 再发新 phase_started（hooks.ts:315-321）。
     *
     * @param title 新阶段标题
     */
    void phase(String title);

    /**
     * 发 log 进度事件（CC hooks.log，script.ts:21）。
     *
     * @param message 日志消息
     */
    void log(String message);

    /**
     * 子 workflow（CC hooks.workflow，script.ts:22-25）。传 string=name、传 {scriptPath} 为文件引用，
     * 只允许一层嵌套（depth&gt;=1 抛 WorkflowError('workflow() nesting allows only one level')，hooks.ts:327-330）。
     *
     * @param nameOrRef workflow 名（string）或 {scriptPath} 文件引用
     * @param args      传给子 workflow 的调用参数
     * @return 子 workflow 返回结果
     */
    CompletableFuture<Object> workflow(String nameOrRef, Object args);

    /**
     * 管线阶段（CC pipeline stage：(prev, item, index) =&gt; Promise&lt;unknown&gt;）。
     */
    @FunctionalInterface
    interface PipelineStage {
        CompletableFuture<Object> apply(Object prev, Object item, int index);
    }
}
