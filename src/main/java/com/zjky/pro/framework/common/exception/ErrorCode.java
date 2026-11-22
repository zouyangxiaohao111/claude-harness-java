package com.zjky.pro.framework.common.exception;

import java.io.Serializable;
import java.util.Objects;

/**
 * 统一错误码（code 为数字 + 可直接展示的 msg）。
 *
 * <p>本仓暂按 zjky-framework 的同名类自建，包名/类名/构造器/方法名与
 * {@code com.zjky.boot:zjky-common} 中的 {@code com.zjky.pro.framework.common.exception.ErrorCode}
 * 逐项一致（{@code ErrorCode(Integer code, String msg)} + {@code getCode()}/{@code getMsg()}），
 * 后期改为依赖 jar 时直接删本文件、业务代码零改动。
 */
public class ErrorCode implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 数字错误码；0 = 成功。 */
    private final Integer code;

    /** 面向用户的错误提示。 */
    private final String msg;

    public ErrorCode(Integer code, String msg) {
        this.code = code;
        this.msg = Objects.requireNonNull(msg, "msg");
    }

    public Integer getCode() {
        return code;
    }

    public String getMsg() {
        return msg;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ErrorCode other)) {
            return false;
        }
        return Objects.equals(code, other.code) && Objects.equals(msg, other.msg);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code, msg);
    }

    @Override
    public String toString() {
        return "ErrorCode(code=" + code + ", msg=" + msg + ")";
    }
}
