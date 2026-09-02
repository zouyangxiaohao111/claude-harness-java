package com.nexusai.infra.exception;

/** 资源存在但禁止访问/修改 → 403 */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) { super(message); }
}
