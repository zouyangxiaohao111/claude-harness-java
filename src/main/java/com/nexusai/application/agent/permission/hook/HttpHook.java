package com.nexusai.application.agent.permission.hook;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * HTTP hook · 对齐 CC {@code Open-ClaudeCode/src/schemas/hooks.ts:97-126}
 * {@code HttpHookSchema} (type='http').
 *
 * <p>WHY: CC http hook POST hook input JSON 到指定 URL, 用于集成外部 webhook.
 * 字段语义对齐 CC Zod schema.
 *
 * <p><b>CC 真源字段 (schemas/hooks.ts:97-126)</b>:
 * <ul>
 *   <li>{@code url} (:99) — POST 目标 URL (必填, URL 格式)</li>
 *   <li>{@code if} (:100) — 权限规则过滤条件</li>
 *   <li>{@code timeout} (:101-105) — 超时秒数</li>
 *   <li>{@code headers} (:106-111) — 附加请求头, 值可用 $VAR_NAME 插值环境变量</li>
 *   <li>{@code allowedEnvVars} (:112-117) — 允许插值的环境变量名白名单</li>
 *   <li>{@code onFailure} — [Wave1-C] 失败语义 {@code continue|block}：请求起不来 / 超时 /
 *       返回非 2xx / body 非法 JSON 时，{@code block} 视同 exit 2 拦住该事件守护的动作；
 *       缺省 {@code continue}（exe 描述同 command 的 {@code onFailure}）</li>
 *   <li>{@code statusMessage} (:118-121) — 自定义 spinner 文案</li>
 *   <li>{@code once} (:122-125) — true=执行一次后移除</li>
 * </ul>
 *
 * @param url            CC original: url (schemas/hooks.ts:99)
 * @param ifCondition    CC original: if (schemas/hooks.ts:100)
 * @param timeout        CC original: timeout (schemas/hooks.ts:101)
 * @param headers        CC original: headers (schemas/hooks.ts:106)
 * @param allowedEnvVars CC original: allowedEnvVars (schemas/hooks.ts:112)
 * @param statusMessage  CC original: statusMessage (schemas/hooks.ts:118)
 * @param once           CC original: once (schemas/hooks.ts:122)
 * @param onFailure      CC original: onFailure（[Wave1-C] continue|block，缺省 continue）
 */
public record HttpHook(
    String url,
    @JsonProperty("if") String ifCondition,
    Integer timeout,
    Map<String, String> headers,
    List<String> allowedEnvVars,
    String statusMessage,
    Boolean once,
    String onFailure
) implements HookCommand, SessionHook {

    /**
     * [Wave1-C] 7 参转发构造器 · {@code onFailure} 缺失（旧调用点零改动）。
     *
     * <p>{@code @JsonCreator(DISABLED)} 同 {@link CommandHook}：防止 -parameters +
     * ParameterNamesModule 把本构造器当成隐式 creator 与规范构造器冲突。
     */
    @JsonCreator(mode = JsonCreator.Mode.DISABLED)
    public HttpHook(String url, String ifCondition, Integer timeout, Map<String, String> headers,
                    List<String> allowedEnvVars, String statusMessage, Boolean once) {
        this(url, ifCondition, timeout, headers, allowedEnvVars, statusMessage, once, null);
    }

    @Override
    public HookType hookType() {
        return HookType.HTTP;
    }

    /**
     * CC original: {@code type} = 'http' · 对齐 schemas/hooks.ts discriminatedUnion 字面量.
     *
     * <p>[3-1 拆开] 显式实现 — 本类同时 implements {@link SessionHook} (抽象 type()) 与
     * {@link HookCommand} (default type()), Java 要求显式声明以同时满足两接口契约.
     */
    @Override
    public String type() {
        return "http";
    }
}