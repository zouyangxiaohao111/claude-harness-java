package com.zjky.pro.framework.common.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zjky.pro.framework.common.exception.ErrorCode;

import java.io.Serializable;
import java.util.Objects;

/**
 * 统一返回体：所有给前端的 JSON 都包一层。
 *
 * <p>结构（与 {@code com.zjky.boot:zjky-common} 的同名类保持字段/方法兼容）：
 * <pre>
 * { "code": 0, "msg": "成功", "data": {...}, "success": true }
 * </pre>
 * <ul>
 *   <li>{@code code}：数字错误码，0 = 成功；取值见
 *       {@code com.zjky.pro.framework.common.exception.enums.GlobalErrorCodeConstants}；</li>
 *   <li>{@code msg}：面向用户的提示；</li>
 *   <li>{@code data}：业务数据（保留原有状态字段）；</li>
 *   <li>{@code success}：前端<b>只按它判断成败</b>，恒与 {@code code == 0} 一致
 *       （{@link #setCode(Integer)} 会同步刷新，不会出现「code=0 但 success=false」）。</li>
 * </ul>
 *
 * <p>SSE / STOMP / 流式响应不包（它们不是一次性 JSON）。
 *
 * <p>本仓暂按上游同名类自建，后期接入 zjky-framework 时改为依赖 jar 并删除本文件。
 */
public class CommonResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 成功码。 */
    public static final int SUCCESS_CODE = 0;

    /** 数字错误码；0 = 成功。 */
    private Integer code;

    /** 错误提示。 */
    private String msg;

    /** 业务数据。 */
    private T data;

    /** 是否成功；与 {@code code == 0} 保持一致（由 {@link #setCode(Integer)} 维护）。 */
    private boolean success;

    public CommonResult() {
    }

    public CommonResult(Integer code, String msg, T data) {
        setCode(code);
        this.msg = msg;
        this.data = data;
    }

    // ───────────────────────────── 静态构造 ─────────────────────────────

    /** 成功（data 可为 null）。 */
    public static <T> CommonResult<T> ok(T data) {
        return new CommonResult<>(SUCCESS_CODE, "成功", data);
    }

    /** 与上游同名方法保持一致的成功构造。 */
    public static <T> CommonResult<T> success(T data) {
        return ok(data);
    }

    /** 无数据的成功。 */
    public static <T> CommonResult<T> success() {
        return ok(null);
    }

    /** 按错误码构造；msg 取错误码自带提示。 */
    public static <T> CommonResult<T> error(ErrorCode errorCode) {
        return error(errorCode, errorCode.getMsg());
    }

    /**
     * 按错误码构造，用 {@code {}} 占位符填充 msg（如 {@code "会话 {} 不存在"}）。
     *
     * @param errorCode 错误码
     * @param params    占位参数（按顺序替换 {@code {}}）
     */
    public static <T> CommonResult<T> error(ErrorCode errorCode, Object... params) {
        return error(errorCode.getCode(), format(errorCode.getMsg(), params));
    }

    /** 按数字码 + 提示构造。 */
    public static <T> CommonResult<T> error(Integer code, String msg) {
        return new CommonResult<>(code, msg, null);
    }

    /** 按数字码 + 提示 + 业务数据构造（失败时仍可带 messageId/status 等上下文）。 */
    public static <T> CommonResult<T> error(Integer code, String msg, T data) {
        return new CommonResult<>(code, msg, data);
    }

    /** 复制另一个返回体的码与提示。 */
    public static <T> CommonResult<T> error(CommonResult<?> result) {
        return error(result.getCode(), result.getMsg());
    }

    /**
     * 仅按码判断成败（{@code null} = 失败）。
     *
     * @param code 数字码
     * @return true = 成功
     */
    public static boolean isSuccess(Integer code) {
        return code != null && code == SUCCESS_CODE;
    }

    // ───────────────────────────── 实例方法 ─────────────────────────────

    public Integer getCode() {
        return code;
    }

    /** 设置码时同步 success，保证二者永不矛盾。 */
    public CommonResult<T> setCode(Integer code) {
        this.code = code;
        this.success = isSuccess(code);
        return this;
    }

    public String getMsg() {
        return msg;
    }

    public CommonResult<T> setMsg(String msg) {
        this.msg = msg;
        return this;
    }

    public T getData() {
        return data;
    }

    public CommonResult<T> setData(T data) {
        this.data = data;
        return this;
    }

    public boolean isSuccess() {
        return success;
    }

    /** 是否为失败（与上游同名方法一致；不参与 JSON 序列化，避免多出 error 字段）。 */
    @JsonIgnore
    public boolean isError() {
        return !success;
    }

    private static String format(String template, Object... params) {
        if (template == null || params == null || params.length == 0) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length() + 16);
        int from = 0;
        for (Object param : params) {
            int at = template.indexOf("{}", from);
            if (at < 0) {
                break;
            }
            out.append(template, from, at).append(param);
            from = at + 2;
        }
        out.append(template, from, template.length());
        return out.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof CommonResult<?> other)) {
            return false;
        }
        return success == other.success
            && Objects.equals(code, other.code)
            && Objects.equals(msg, other.msg)
            && Objects.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, msg, data, success);
    }

    @Override
    public String toString() {
        return "CommonResult(code=" + code + ", msg=" + msg + ", success=" + success + ", data=" + data + ")";
    }
}
