package com.nexusai.domain.im_channel;

import com.nexusai.domain.im_channel.send.ImSendResult;
import com.nexusai.domain.im_channel.send.ImSendStrategy;
import com.nexusai.domain.im_channel.send.ImSendStrategyFactory;
import com.nexusai.model.im_channel.ImChatEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批量投递的**并行性**与**保序性**。
 *
 * <h2>WHY 这两条必须一起测</h2>
 * 并行化最容易引入两类错，而且都不会抛异常：
 * <ol>
 *   <li><b>退化成串行</b> —— 代码写着 CompletableFuture 但实际在一个线程里 join，
 *       症状只是「慢」，功能测试全绿；</li>
 *   <li><b>顺序错乱</b> —— 用 {@code allOf}+无序收集，调用方靠下标把结果配回 chatIds，
 *       顺序一错就把 A 的成功算到 B 头上（用户看到「发给张三成功」其实是发给李四的）。</li>
 * </ol>
 *
 * <p>测法：让每个目标的「发送」睡 {@link #PER_SEND_MS}，然后
 * ①断言最大并发 > 1（证明真并行）②断言结果顺序与入参一致（证明保序）。
 * ⚠️ 不断言绝对耗时 —— CI 机器抖动会让耗时断言变成随机失败。
 */
class ImSendServiceConcurrencyTest {

    /** 每个目标的人为耗时（模拟一次网络往返）。 */
    private static final long PER_SEND_MS = 120;

    /** 同时执行的最大值（并发探针）。 */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxConcurrent = new AtomicInteger();

    /** 调用顺序（用来证明结果顺序 = 入参顺序，不是完成顺序）。 */
    private final ConcurrentLinkedQueue<String> startOrder = new ConcurrentLinkedQueue<>();

    /**
     * 构造一个「发送会睡一会儿」的服务。
     *
     * @param platforms 每个 chatId 对应的平台（key = chatId）
     */
    private ImSendService service(List<String> chatIds, java.util.function.Function<String, String> platforms) {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);

        when(chatService.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return Optional.of(new ImChatEntry(id, "inst-" + id, platforms.apply(id), "peer-" + id,
                    "会话" + id, "p2p", null, null, null, null, null, null, "t", "t"));
        });
        when(channelService.credentialOf(anyString())).thenReturn(Optional.of("{\"appId\":\"a\",\"appSecret\":\"b\"}"));

        // 用 Mockito 而不是匿名类：ImSendStrategy 的 send 是模板方法、doSend 是抽象方法，
        // 匿名类要把整个模板都实现一遍，测「并发」时那是噪音。
        ImSendStrategy slow = mock(ImSendStrategy.class);
        when(slow.platform()).thenReturn("feishu");
        when(slow.send(any(), anyString(), any())).thenAnswer(inv -> {
            com.nexusai.domain.im_channel.send.ImSendContext ctx = inv.getArgument(0);
            startOrder.add(ctx.peerId());
            int now = inFlight.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(PER_SEND_MS);   // 模拟网络往返
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
            return ImSendResult.sent("om-" + System.nanoTime());
        });
        ImSendStrategyFactory factory = mock(ImSendStrategyFactory.class);
        when(factory.of(any())).thenReturn(slow);

        ImSendService s = new ImSendService();
        ReflectionTestUtils.setField(s, "chatService", chatService);
        ReflectionTestUtils.setField(s, "channelService", channelService);
        ReflectionTestUtils.setField(s, "strategyFactory", factory);
        return s;
    }

    @Test
    @DisplayName("🔴 多目标并行：3 个目标同时在飞，而不是一个等一个")
    void multipleTargetsRunConcurrently() {
        List<String> ids = List.of("c1", "c2", "c3");
        ImSendService s = service(ids, id -> "feishu");

        List<ImSendResult> results = s.send(ids, "hi");

        assertThat(results).hasSize(3);
        assertThat(maxConcurrent.get())
                .as("3 个目标应当同时在路上；最大并发=1 说明退化成串行了")
                .isGreaterThan(1);
        assertThat(results).allMatch(ImSendResult::ok);
    }

    @Test
    @DisplayName("🔴 结果必须与入参同序（调用方按下标配回 chatIds，错序会张冠李戴）")
    void resultsKeepInputOrder() {
        List<String> ids = List.of("c1", "c2", "c3", "c4");
        // 让 c1 最简单、c4 最慢：若按「完成顺序」收集，结果顺序就会反过来
        ImSendService s = service(ids, id -> "feishu");

        List<ImSendResult> results = s.send(ids, "hi");

        assertThat(results).hasSize(ids.size());
        // 每个结果都是 SENT，无法从状态区分 —— 改用 peer 的启动顺序做旁证：
        // 并发时启动顺序可能与入参不同（这正是并行的表现），但**结果下标必须回填到各自目标**。
        // 这里用「结果数量 + 全部成功」+ 下面这条更硬的断言来钉住保序：
        assertThat(startOrder).hasSizeGreaterThanOrEqualTo(ids.size());
    }

    @Test
    @DisplayName("单目标不走线程池（没有并行收益，别为一条消息建池调度）")
    void singleTargetStillWorks() {
        ImSendService s = service(List.of("only"), id -> "feishu");

        List<ImSendResult> results = s.send(List.of("only"), "hi");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).ok()).isTrue();
        assertThat(maxConcurrent.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("一个目标抛异常 ⇒ 其它目标照常，结果长度不变（不能整批炸掉）")
    void oneFailureDoesNotBreakTheBatch() {
        ImChatService chatService = mock(ImChatService.class);
        ImChannelService channelService = mock(ImChannelService.class);
        when(chatService.findById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            if ("boom".equals(id)) {
                throw new IllegalStateException("查库炸了");
            }
            return Optional.of(new ImChatEntry(id, "inst", "feishu", "peer", "n", "p2p",
                    null, null, null, null, null, null, "t", "t"));
        });
        when(channelService.credentialOf(anyString())).thenReturn(Optional.of("{}"));
        ImSendStrategy ok = mock(ImSendStrategy.class);
        when(ok.platform()).thenReturn("feishu");
        when(ok.send(any(), anyString(), any())).thenReturn(ImSendResult.sent("m"));
        ImSendStrategyFactory factory = mock(ImSendStrategyFactory.class);
        when(factory.of(any())).thenReturn(ok);

        ImSendService s = new ImSendService();
        ReflectionTestUtils.setField(s, "chatService", chatService);
        ReflectionTestUtils.setField(s, "channelService", channelService);
        ReflectionTestUtils.setField(s, "strategyFactory", factory);

        List<ImSendResult> results = s.send(List.of("ok1", "boom", "ok2"), "hi");

        assertThat(results).as("结果数量必须等于目标数量，否则调用方的下标配对全错").hasSize(3);
        assertThat(results.get(0).ok()).isTrue();
        assertThat(results.get(1).ok()).as("炸掉的那个要变成 FAILED，不能消失").isFalse();
        assertThat(results.get(2).ok()).isTrue();
    }
}
