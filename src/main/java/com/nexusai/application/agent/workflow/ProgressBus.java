package com.nexusai.application.agent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * 类型化进度事件总线 · CC original: {@code ProgressBus}
 * (Open-ClaudeCode/src/workflow/progress/bus.ts:4-20)。
 *
 * <p>引擎 {@code progressEmitter.emit} → 广播给所有订阅者（store 归约 / telemetry）。
 * CC 用 {@code Set<listener>}（bus.ts:10）+ for..of 发射（bus.ts:13-14）+ subscribe 返回退订函数
 * （bus.ts:16-18）。
 *
 * <p><b>Spring 单例</b>：WorkflowPortsImpl 构造时订阅 telemetry；W-1e WorkflowServiceImpl 可注入
 * 同一实例给 store 订阅（对齐 CC getWorkflowService 中 bus → store → createWorkflowPorts 共享）。
 *
 * <p><b>健壮性微偏（显式记录）</b>：CC 发射循环不 catch——单个订阅者 throw 会中断后续订阅者。
 * Java 端逐订阅者 try/catch + warn 日志（fail-loud，不静默），避免 store/telemetry 单点拖垮整链。
 * 线程安全：{@link CopyOnWriteArraySet} 并发有序 Set（CC 单线程 `Set` + `for..of` 保持插入顺序；
 * Java 后台 agent 并发 emit 需线程安全，且<b>必须保序</b>——CC getWorkflowService 订阅序
 * store→persistence→notifications 决定 run_done 时 {@code store.get(runId)} 已终态
 * （persistence.ts:176-177「store 先于本订阅注册」），无序遍历会破坏该保证导致
 * attachRunStatePersistence 读到 RUNNING 终态错乱）。
 */
@Component
public final class ProgressBus {

    private static final Logger log = LoggerFactory.getLogger(ProgressBus.class);

    /** 订阅者集合 · 对齐 CC bus.ts:10 {@code Set<listener>}（CopyOnWriteArraySet 保插入序 = CC 单线程 Set 迭代序） */
    private final Set<Consumer<ProgressEvent>> listeners = new CopyOnWriteArraySet<>();

    /**
     * 发射一条进度事件 · CC original: bus.ts:12-14 {@code emit(event){for(const fn of listeners) fn(event)}}。
     *
     * @param event CC original: {@code event} — ProgressEvent 8 变体（均带 runId）
     */
    public void emit(ProgressEvent event) {
        if (event == null) {
            return;
        }
        for (Consumer<ProgressEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("ProgressBus.emit: 订阅者异常，不阻断其余订阅者 eventType={} err={}",
                        event.getClass().getSimpleName(), e.toString());
            }
        }
    }

    /**
     * 订阅进度事件 · CC original: bus.ts:15-18 {@code subscribe(listener){...return () => delete}}。
     *
     * @param listener 事件消费者
     * @return 退订 Runnable（对齐 CC 返回退订函数）
     */
    public Runnable subscribe(Consumer<ProgressEvent> listener) {
        if (listener == null) {
            return () -> {
            };
        }
        listeners.add(listener);
        if (log.isDebugEnabled()) {
            log.debug("ProgressBus.subscribe: 订阅者加入，当前订阅数={}", listeners.size());
        }
        return () -> {
            listeners.remove(listener);
            if (log.isDebugEnabled()) {
                log.debug("ProgressBus 退订，当前订阅数={}", listeners.size());
            }
        };
    }

    /** 当前订阅数（观测/测试用） */
    public int size() {
        return listeners.size();
    }
}
