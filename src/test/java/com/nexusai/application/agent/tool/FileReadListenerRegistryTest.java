package com.nexusai.application.agent.tool;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Session L+ · {@link FileReadListenerRegistry} 监听器注册表契约验证.
 *
 * <p><b>WHY（意图验证 · CLAUDE.md 规则九）</b>：
 * FileReadListenerRegistry 是 CC {@code registerFileReadListener} 的 Java 对应物，
 * 支撑 MagicDocs 等下游服务的"文件被读到即触发"语义。本测试断言的不仅是"能注册"，
 * 更是三条易被破坏的不变量：
 * <ol>
 *   <li><b>unsubscribe idempotent</b>——多次注销同一个 listener 不应抛异常；
 *       CC 通过 {@code indexOf >= 0} 守护等价语义。</li>
 *   <li><b>[L+ R2] listener 异常 fail-fast</b>——单个 listener 抛异常向上传播给主流程
 *       (ReadFileTool.execute)，后续 listener 不被调用。对齐 CC FileReadTool.ts:1042
 *       裸调用哲学 (CLAUDE.md 规则十二·显式失败). Listener 实现方自 catch 业务异常.</li>
 *   <li><b>snapshot 遍历</b>——listener 在回调中注销不应导致下一个 listener 被跳过。
 *       CC :1040-1044 注释明确。</li>
 * </ol>
 *
 * <p><b>[IMP-C5] FileReadEvent 退役（TR-D1-⊕-2）</b>：签名对齐 CC 二元
 * {@code (filePath: string, content: string) => void}（FileReadTool.ts:162），
 * 删除 mtime/callId 扩展字段。测试以 {@code notifyRead(path, content)} 断言。
 */
@DisplayName("Session L · FileReadListenerRegistry 监听器契约")
class FileReadListenerRegistryTest {

    /** 捕获 (path, content) 二元投递 · CC FileReadListener 签名（FileReadTool.ts:162）. */
    private record ReadNotification(String path, String content) {}

    @Test
    @DisplayName("基本路径: 注册后 notifyRead 把 (path,content) 投递到 listener —— MagicDocs 监听到的前提")
    void registersAndNotifies() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        List<ReadNotification> received = Collections.synchronizedList(new ArrayList<>());
        registry.register((p, c) -> received.add(new ReadNotification(p, c)));

        registry.notifyRead("doc.md", "hello");

        assertThat(received).hasSize(1);
        assertThat(received.get(0).path()).isEqualTo("doc.md");
        assertThat(received.get(0).content()).isEqualTo("hello");
    }

    @Test
    @DisplayName("注销: unsubscribe 一次后再调用不抛异常 —— idempotent（CC indexOf>=0 守护）")
    void unsubscribeIsIdempotent() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        Runnable unsubscribe = registry.register((p, c) -> {});

        unsubscribe.run();
        // 第二次调用：再次删除同一个 listener 应 noop，不抛
        unsubscribe.run();

        assertThat(registry.listenerCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("多 listener 顺序投递: register 顺序 = notify 顺序 —— CC fileReadListeners.slice() 语义")
    void notifiesInRegistrationOrder() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        registry.register((p, c) -> received.add("A"));
        registry.register((p, c) -> received.add("B"));
        registry.register((p, c) -> received.add("C"));

        registry.notifyRead("p", "c");

        assertThat(received).containsExactly("A", "B", "C");
    }

    @Test
    @DisplayName("[L+ R2] 异常哲学对齐 CC fail-fast: 单 listener 抛 RuntimeException → 异常向上传播, 后续 listener 不被调用")
    void propagatesExceptionFromListener() {
        // [L+ R2] CC FileReadTool.ts:1042 是裸调用 (无 try/catch 隔离).
        // L session 决策保留 Java fail-soft (catch RuntimeException 隔离), L+ R2 改回 CC
        // fail-fast 哲学: listener 异常不再吞, 暴露给主流程 (规则十二·显式失败).
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        List<ReadNotification> received = Collections.synchronizedList(new ArrayList<>());
        registry.register((p, c) -> { throw new RuntimeException("boom"); });
        registry.register((p, c) -> received.add(new ReadNotification(p, c)));
        registry.register((p, c) -> received.add(new ReadNotification(p, c)));

        // notifyRead 期望: 首个 listener 抛异常 → 异常向上传播, 后续 listener 不被调用
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            registry.notifyRead("p", "c")
        )
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("boom");

        // 后续 listener 不该被调用 (CC fail-fast 语义)
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("snapshot 遍历: listener 在回调中注销自己，不影响其它 listener 投递 —— CC :1040-1044 不变量")
    void snapshotIteratesMidIterationUnsubscribe() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        Runnable[] unsubscribeHolder = new Runnable[1];
        unsubscribeHolder[0] = registry.register((p, c) -> {
            received.add("A");
            unsubscribeHolder[0].run(); // A 注销自己
        });
        registry.register((p, c) -> received.add("B"));

        registry.notifyRead("p", "c");

        // snapshot 应保证 A 和 B 都投递 —— 若遍历 live 数组 splice 会跳过 B
        assertThat(received).containsExactly("A", "B");
    }

    @Test
    @DisplayName("null listener 注册显式失败 —— 规则十二不掩盖空对象")
    void nullListenerRejected() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();

        assertThatThrownBy(() -> registry.register(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("null filePath notifyRead 静默跳过 —— 不该让 ReadFileTool 主流程因 listener 空入参炸掉")
    void nullPathNoOp() {
        FileReadListenerRegistry registry = new FileReadListenerRegistry();
        List<ReadNotification> received = Collections.synchronizedList(new ArrayList<>());
        registry.register((p, c) -> received.add(new ReadNotification(p, c)));

        registry.notifyRead(null, "c");

        assertThat(received).isEmpty();
    }
}
