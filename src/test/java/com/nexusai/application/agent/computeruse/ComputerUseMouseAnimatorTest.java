package com.nexusai.application.agent.computeruse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 鼠标缓动路径（{@code mouseAnimation} 子开关）的单测（Phase 1c）。
 *
 * <p><b>WHY（规则 9）</b>：缓动不是"锦上添花"——它决定目标应用（滚动条 / 窗口缩放 / 拖拽）
 * 能不能跟得上指针。CCB 原文（{@code src/utils/computerUse/executor.ts:270-276}）：
 * “target apps may watch for {@code .leftMouseDragged} specifically … and the slow motion gives
 * them time to process intermediate positions”。⇒ 参数（ease-out-cubic / 60fps / 2000px/s /
 * 0.5s 上限）必须逐字对齐，否则缓动时长与帧数都会漂。
 *
 * <p>本测试用<b>假光标 + 假时钟</b> ⇒ 零耗时地断言「调用序列」。
 */
class ComputerUseMouseAnimatorTest {

    /** 记录移动与按键的假光标。 */
    private static final class RecordingCursor implements ComputerUseCursor {
        final List<int[]> moves = new ArrayList<>();
        final List<String> buttons = new ArrayList<>();
        int[] position = new int[]{0, 0};

        @Override
        public void moveTo(int x, int y) {
            moves.add(new int[]{x, y});
            position = new int[]{x, y};
        }

        @Override
        public int[] location() {
            return new int[]{position[0], position[1]};
        }

        @Override
        public void leftButtonDown() {
            buttons.add("down");
        }

        @Override
        public void leftButtonUp() {
            buttons.add("up");
        }
    }

    /** 记录休眠时长的假时钟。 */
    private static final class RecordingSleeper implements ComputerUseSleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    @Test
    @DisplayName("缓动路径：ease-out-cubic 逐帧移动 + 帧间隔 16ms + 末帧后 50ms settle")
    void animatedMoveFollowsEaseOutCubic() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, true);

        animator.animatedMoveTo(200, 0);

        // 距离 200px → 时长 = 200/2000 = 0.1s → 帧数 = floor(0.1 * 60) = 6
        assertThat(cursor.moves).hasSize(6);
        assertThat(cursor.moves.get(0)).containsExactly(84, 0);
        assertThat(cursor.moves.get(1)).containsExactly(141, 0);
        assertThat(cursor.moves.get(2)).containsExactly(175, 0);
        assertThat(cursor.moves.get(3)).containsExactly(193, 0);
        assertThat(cursor.moves.get(4)).containsExactly(199, 0);
        assertThat(cursor.moves.get(5))
                .as("末帧必须落在目标点")
                .containsExactly(200, 0);
        assertThat(sleeper.sleeps)
                .as("帧间隔 16ms（1000/60 截断）× 5 + 末帧后 50ms settle")
                .containsExactly(16L, 16L, 16L, 16L, 16L, 50L);
    }

    @Test
    @DisplayName("开关关闭 ⇒ 退回「瞬移 + 50ms settle」，不做逐帧缓动")
    void disabledGateFallsBackToMoveAndSettle() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, false);

        animator.animatedMoveTo(200, 0);

        assertThat(cursor.moves).hasSize(1);
        assertThat(cursor.moves.get(0)).containsExactly(200, 0);
        assertThat(sleeper.sleeps).containsExactly(50L);
    }

    @Test
    @DisplayName("距离 < 1px ⇒ 什么都不做（CCB executor.ts:286 的 return，不是瞬移）")
    void tinyDistanceDoesNothing() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, true);

        animator.animatedMoveTo(0, 0);

        assertThat(cursor.moves).isEmpty();
        assertThat(sleeper.sleeps).isEmpty();
    }

    @Test
    @DisplayName("距离太近（时长 < 30ms）⇒ 瞬移 + settle（CCB executor.ts:290）")
    void shortDistanceFallsBackToInstantMove() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, true);

        animator.animatedMoveTo(50, 0);

        assertThat(cursor.moves).hasSize(1);
        assertThat(cursor.moves.get(0)).containsExactly(50, 0);
        assertThat(sleeper.sleeps).containsExactly(50L);
    }

    @Test
    @DisplayName("动画时长上限 0.5s：超长距离（4000px ⇒ 2s）被截到 0.5s ⇒ 30 帧")
    void durationIsCappedAtHalfSecond() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, true);

        animator.animatedMoveTo(4000, 0);

        assertThat(cursor.moves)
                .as("4000px 本该 2s，被 0.5s 上限截断 ⇒ floor(0.5*60)=30 帧")
                .hasSize(30);
        assertThat(cursor.moves.get(29)).containsExactly(4000, 0);
    }

    @Test
    @DisplayName("拖拽序列：移动到起点 + settle → 按下 → 50ms → 缓动 → 抬起（finally）")
    void dragSequenceFollowsCcb() throws Exception {
        RecordingCursor cursor = new RecordingCursor();
        RecordingSleeper sleeper = new RecordingSleeper();
        ComputerUseMouseAnimator animator = new ComputerUseMouseAnimator(cursor, sleeper, true);

        animator.drag(0, 0, 200, 0);

        assertThat(cursor.buttons).containsExactly("down", "up");
        assertThat(cursor.moves.get(0))
                .as("第一跳是移动到起点")
                .containsExactly(0, 0);
        assertThat(cursor.moves.get(cursor.moves.size() - 1))
                .as("最后一跳落在终点")
                .containsExactly(200, 0);
        assertThat(sleeper.sleeps)
                .as("起点 settle 50ms → 按下后 50ms → 5 帧 × 16ms → 末帧 settle 50ms")
                .containsExactly(50L, 50L, 16L, 16L, 16L, 16L, 16L, 50L);
    }
}
