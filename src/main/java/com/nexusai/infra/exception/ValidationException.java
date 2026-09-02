package com.nexusai.infra.exception;

/** 业务校验失败 → 400（区别于 @Valid 校验） */
public class ValidationException extends RuntimeException {
    public ValidationException(String message) { super(message); }
}