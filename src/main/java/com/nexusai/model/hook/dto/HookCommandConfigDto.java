package com.nexusai.model.hook.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.nexusai.application.agent.permission.hook.AgentHook;
import com.nexusai.application.agent.permission.hook.CommandHook;
import com.nexusai.application.agent.permission.hook.HookCommand;
import com.nexusai.application.agent.permission.hook.HttpHook;
import com.nexusai.application.agent.permission.hook.PromptHook;

/**
 * Hook 命令配置 DTO（GET 展示用，扁平 shape）· 对齐前端
 * {@code nexusai/src/api/types.ts:836-849 HookCommandConfig}。
 *
 * <p>WHY (联调三问题·hooks 端点): 前端 HookPanel 只读
 * {@code type/statusMessage/once/command/prompt/url} 6 字段（types.ts:836-849，
 * {@code type: 'command'|'prompt'|'http'|'agent'} + statusMessage/once/command/prompt/url
 * 均可 null）。本 DTO 承载这 6 字段的<b>扁平视图</b>；子类型专属字段
 * （shell/timeout/async/asyncRewake/if/headers/allowedEnvVars 等）GET 展示不暴露
 * （前端多余字段忽略）。配合 {@link HookItemDto#from}，避免跨 API 边界泄漏
 * domain record（{@link HookCommand} 4 子类型各自完整 discriminated shape）。
 *
 * <p><b>序列化</b>: {@link JsonInclude#NON_NULL} —— 未用的子类型字段序列化为 null 时
 * JSON 省略（对齐前端 TS interface 字段全可选 + HookItem 形状）。
 *
 * <p><b>映射单点</b>: {@link #from(HookCommand)} 是唯一映射入口 —— switch 4 子类型
 * （CommandHook→command / PromptHook·AgentHook→prompt / HttpHook→url），其余字段 null；
 * type 用 {@link HookCommand#type()} 判别器字面量（command/prompt/http/agent）。
 *
 * @param type        CC original: type — 判别器字面量（command/prompt/http/agent）
 * @param statusMessage CC original: statusMessage — 自定义 spinner 文案；null 省略
 * @param once        CC original: once — true=执行一次后移除；null 省略
 * @param command     CC original: command — 仅 CommandHook 有；null 省略
 * @param prompt      CC original: prompt — 仅 PromptHook/AgentHook 有；null 省略
 * @param url         CC original: url — 仅 HttpHook 有；null 省略
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record HookCommandConfigDto(
    String type,
    String statusMessage,
    Boolean once,
    String command,
    String prompt,
    String url
) {

    /**
     * domain {@link HookCommand} → 扁平 config DTO · 对齐前端 HookCommandConfig 6 字段。
     *
     * <p>switch 4 子类型：CommandHook→command；PromptHook/AgentHook→prompt；HttpHook→url；
     * 其余字段 null（NON_NULL 序列化时省略）。type 统一取 {@link HookCommand#type()}
     * （各子类 override 返回 CC 字面量 command/prompt/http/agent）。
     *
     * @param hook HookCommand 4 子类型之一（null 防御 → null）
     * @return 扁平 config DTO；hook null → null
     */
    public static HookCommandConfigDto from(HookCommand hook) {
        if (hook == null) {
            return null;
        }
        return switch (hook.hookType()) {
            case COMMAND -> new HookCommandConfigDto(
                hook.type(), hook.statusMessage(), hook.once(),
                ((CommandHook) hook).command(), null, null);
            case PROMPT -> new HookCommandConfigDto(
                hook.type(), hook.statusMessage(), hook.once(),
                null, ((PromptHook) hook).prompt(), null);
            case HTTP -> new HookCommandConfigDto(
                hook.type(), hook.statusMessage(), hook.once(),
                null, null, ((HttpHook) hook).url());
            case AGENT -> new HookCommandConfigDto(
                hook.type(), hook.statusMessage(), hook.once(),
                null, ((AgentHook) hook).prompt(), null);
        };
    }
}
