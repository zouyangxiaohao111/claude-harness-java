package com.nexusai.application.agent.workflow.engine;

import com.nexusai.application.agent.workflow.BudgetExhaustedError;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Token 预算累计器 · 对齐 CC {@code engine/budget.ts:12-36 Budget}。
 *
 * <p>脚本经 {@code budget.total / budget.spent() / budget.remaining()} 读取；
 * {@link #assertCanSpend()} 在每个 agent() 调用前强制硬上限（budget.ts:31-35）。</p>
 *
 * <ul>
 *   <li>{@code total == null} → 不限额（{@link #remaining()} 返回 {@link Long#MAX_VALUE}，CC Infinity 等价）</li>
 *   <li>{@link #addOutputTokens} 只加 {@code n &gt; 0}（budget.ts:27-29）——dead 不调它，故 budget 不双计</li>
 *   <li>{@link #assertCanSpend()}：{@code spent &gt;= total} 抛 {@link BudgetExhaustedError}</li>
 * </ul>
 *
 * <p><b>预算检查必须在信号量临界区内</b>（hooks.ts:124-128）：队列 waiter 唤醒时看到最新 spent，
 * 否则 N 个在 spent=0 时入队的 waiter 全过检查、唤醒后超支。</p>
 */
public final class Budget {

    private static final Logger log = LoggerFactory.getLogger(Budget.class);

    /** CC original: total (budget.ts:14) — 预算上限；null = 不限。 */
    private final Integer total;
    private int spentTokens = 0;

    public Budget(Integer total) {
        this.total = total;
    }

    /** CC original: spent() (budget.ts:17-19)。 */
    public int spent() {
        return spentTokens;
    }

    /** CC original: remaining() (budget.ts:21-25) — total==null → Infinity（Java Long.MAX_VALUE）。 */
    public long remaining() {
        return total == null ? Long.MAX_VALUE : Math.max(0, (long) total - spentTokens);
    }

    /** CC original: addOutputTokens(n) (budget.ts:27-29) — n&gt;0 才累计。 */
    public void addOutputTokens(int n) {
        if (n > 0) {
            spentTokens += n;
            if (log.isDebugEnabled()) {
                log.debug("Budget.addOutputTokens：+{}，spent={}/total={}", n, spentTokens, total);
            }
        }
    }

    /** CC original: assertCanSpend() (budget.ts:31-35) — spent&gt;=total 抛 BudgetExhaustedError。 */
    public void assertCanSpend() {
        if (total != null && spentTokens >= total) {
            log.error("Budget.assertCanSpend 拒绝：spent={} 已达 total={}（BudgetExhaustedError）",
                    spentTokens, total);
            throw new BudgetExhaustedError();
        }
    }
}
