package com.nexusai.infra.exception;

/** 资源未找到 → 404 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) { super(message); }
}