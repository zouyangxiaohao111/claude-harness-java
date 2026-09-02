package com.nexusai.infra.exception;

/** 资源冲突（如重名）→ 409 */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) { super(message); }
}