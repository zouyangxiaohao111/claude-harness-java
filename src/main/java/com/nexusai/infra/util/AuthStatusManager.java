package com.nexusai.infra.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * AuthStatusManager · 对齐 CC utils/awsAuthStatusManager.ts.
 *
 * <p>L1 语义: 云 provider (AWS Bedrock / GCP Vertex) 认证状态 singleton manager。
 * 共享 startAuthentication / addOutput / setError / endAuthentication 状态机 +
 * 订阅者 signal,用于 REPL/SDK 输出。
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: AuthStatus record + AuthStatusManager singleton (getInstance) + start/addOutput/setError/endAuthentication + subscribe</li>
 *   <li><b>A2 Golden Trace</b>: getInstance 单例;subscribe 收到 emit;endAuthentication success 清空 + failure 保留</li>
 *   <li><b>A3 不可变外发</b>: getStatus 返新 record (defensive copy);内部 mutable 只有 manager</li>
 *   <li><b>A4 边界</b>: reset() 仅 test 使用;subscribe 多次合法</li>
 *   <li><b>A5 业务场景</b>: AWS Bedrock auth refresh → AuthStatusManager.addOutput('Logging in...') → REPL 实时显示</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS Bun.which 等特殊运行时 → Java 标准 JDK 17;
 * TS class + private static instance → Java final class + AtomicReference 静态 instance;
 * TS createSignal subscriber → Java Consumer subscriber list。
 */
public final class AuthStatusManager {

    public record AuthStatus(boolean isAuthenticating, List<String> output, String error) {}

    public interface AuthSubscriber extends Consumer<AuthStatus> {}

    private static final AtomicReference<AuthStatusManager> INSTANCE = new AtomicReference<>();

    private final AtomicReference<AuthStatus> statusRef = new AtomicReference<>(
        new AuthStatus(false, new ArrayList<>(), null));
    private final List<AuthSubscriber> subscribers = new ArrayList<>();

    private AuthStatusManager() {}

    public static AuthStatusManager getInstance() {
        AuthStatusManager current = INSTANCE.get();
        if (current == null) {
            current = new AuthStatusManager();
            if (!INSTANCE.compareAndSet(null, current)) {
                current = INSTANCE.get();
            }
        }
        return current;
    }

    public AuthStatus getStatus() {
        AuthStatus s = statusRef.get();
        return new AuthStatus(s.isAuthenticating(), new ArrayList<>(s.output()), s.error());
    }

    public void startAuthentication() {
        statusRef.set(new AuthStatus(true, new ArrayList<>(), null));
        emit();
    }

    public void addOutput(String line) {
        AuthStatus current = statusRef.get();
        List<String> newOutput = new ArrayList<>(current.output());
        newOutput.add(line);
        statusRef.set(new AuthStatus(current.isAuthenticating(), newOutput, current.error()));
        emit();
    }

    public void setError(String error) {
        AuthStatus current = statusRef.get();
        statusRef.set(new AuthStatus(current.isAuthenticating(), current.output(), error));
        emit();
    }

    public void endAuthentication(boolean success) {
        AuthStatus current = statusRef.get();
        AuthStatus next = success
            ? new AuthStatus(false, new ArrayList<>(), null)
            : new AuthStatus(false, current.output(), current.error());
        statusRef.set(next);
        emit();
    }

    public void subscribe(AuthSubscriber s) {
        subscribers.add(s);
    }

    /** Test-only cleanup. */
    public static void reset() {
        AuthStatusManager removed = INSTANCE.getAndSet(null);
        if (removed != null) {
            removed.subscribers.clear();
        }
    }

    private void emit() {
        AuthStatus snapshot = getStatus();
        for (AuthSubscriber s : subscribers) {
            s.accept(snapshot);
        }
    }
}
