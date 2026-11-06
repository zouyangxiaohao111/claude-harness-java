package com.nexusai.kb.app.graph;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图谱权重与度数计算 · 对齐 WeKnora
 * {@code graph.go:482 calculateWeights} 与 {@code :574 calculateDegrees}。
 *
 * <p><b>为什么单独抽出来</b>：这两个算法全是纯算术，但里面有<b>四个容易写错的归一</b>
 * （见下），且错了之后图上只是「线的粗细不对」，没有任何报错。
 * 抽成纯函数后可以逐条断言。
 *
 * <h2>⭐⭐ 四个归一陷阱（逐个对照源侧）</h2>
 * <ol>
 *   <li><b>PMI 取 max(…, 0)</b>：源侧 {@code math.Max(…, 0)}。
 *       PMI 为负表示「两个实体共同出现得比偶然还少」，那是<b>互斥</b>信号，
 *       不是「弱关联」。不截零会让这类边拿到负权，画出来的线比不存在的边还淡。</li>
 *   <li><b>用 log2 不是 log</b>：源侧 {@code math.Log2}。差一个常数倍，
 *       但因为后面按 maxPMI 归一，<b>单独看没影响</b> —— 只有和「强度项」加权合并时才体现，
 *       所以照抄 log2 才与源侧同分布。</li>
 *   <li><b>PMI 与强度各自按自己的最大值归一</b>，不是都按 1。
 *       强度原始值 5..10，直接乘 0.4 会占 2..4 分，而 PMI 归一后只有 0..1 ⇒ 强度项完全压过 PMI。</li>
 *   <li><b>最终缩放 1 + 9×combined</b>：把 [0,1] 映射到 [1,10]。
 *       漏掉 {@code 1 +} 会让权重为 0 的边在渲染时彻底消失（看起来像数据没抽到）。</li>
 * </ol>
 */
public final class KbGraphWeights {

    /** PMI 在合成权重中的占比（源侧 {@code PMIWeight}）。 */
    public static final double PMI_WEIGHT = 0.6;
    /** 强度在合成权重中的占比（源侧 {@code StrengthWeight}）。 */
    public static final double STRENGTH_WEIGHT = 0.4;
    /** 权重缩放系数（源侧 {@code WeightScaleFactor}）。 */
    public static final double WEIGHT_SCALE = 9.0;
    /** 避免除零的最小值（源侧 {@code MinWeightValue}）。 */
    public static final double MIN_WEIGHT_VALUE = 1.0;

    private KbGraphWeights() {
    }

    /**
     * 一条关系的计算结果。
     *
     * @param weight         最终权重 1..10
     * @param combinedDegree 两端实体度数之和
     */
    public record Result(double weight, int combinedDegree) {
    }

    /**
     * 计算一个实体的输入：名字 + 出现的块数 + 关联关系。
     *
     * @param name     实体名
     * @param chunkIds 出现过的块 ID（用于算出现次数）
     */
    public record EntityInput(String name, List<String> chunkIds) {
    }

    /**
     * 一条关系的输入。
     *
     * @param source     起点实体名
     * @param target     终点实体名
     * @param strength   强度 5..10
     * @param chunkIds   支撑该关系的块（用于算关系出现次数）
     */
    public record RelationInput(String source, String target, int strength, List<String> chunkIds) {
    }

    /**
     * 计算全部关系的权重与两端度数和。
     *
     * @param entities  实体列表
     * @param relations 关系列表（顺序与返回的 List 一一对应）
     * @return 每条关系的结果（与 {@code relations} 同序）
     */
    public static List<Result> compute(List<EntityInput> entities, List<RelationInput> relations) {
        if (entities == null || relations == null || relations.isEmpty()) {
            return List.of();
        }

        // ── ① 实体出现次数 & 总出现次数 ──────────────────────────────
        // 同名只算一次（源侧 entityMapByTitle 语义：同名是同一个实体）
        Map<String, Integer> entityFreq = new HashMap<>();
        for (EntityInput e : entities) {
            if (e == null || e.name() == null || entityFreq.containsKey(e.name())) {
                continue;
            }
            entityFreq.put(e.name(), e.chunkIds() == null ? 0 : e.chunkIds().size());
        }
        int totalEntityOccurrences = 0;
        for (int f : entityFreq.values()) {
            totalEntityOccurrences += f;
        }

        // ── ② PMI + 强度最大值 ──────────────────────────────────────
        int totalRelOccurrences = 0;
        for (RelationInput r : relations) {
            totalRelOccurrences += size(r.chunkIds());
        }
        if (totalEntityOccurrences == 0 || totalRelOccurrences == 0) {
            // 数据不足 ⇒ 只按强度给权重（⛔ 不抛异常：图谱只是增强，不该让整篇失败）
            double maxStrength = MIN_WEIGHT_VALUE;
            for (RelationInput r : relations) {
                maxStrength = Math.max(maxStrength, r.strength());
            }
            double[] fallback = new double[relations.size()];
            for (int i = 0; i < relations.size(); i++) {
                double ns = relations.get(i).strength() / maxStrength;
                fallback[i] = 1.0 + WEIGHT_SCALE * (0.0 * PMI_WEIGHT + ns * STRENGTH_WEIGHT);
            }
            double[] degrees = degreesOf(relations);
            List<Result> out = new java.util.ArrayList<>(relations.size());
            for (int i = 0; i < relations.size(); i++) {
                out.add(new Result(fallback[i], (int) degrees[i]));
            }
            return List.copyOf(out);
        }

        double maxPmi = 0.0;
        double maxStrength = MIN_WEIGHT_VALUE;
        double[] pmis = new double[relations.size()];
        for (int i = 0; i < relations.size(); i++) {
            RelationInput r = relations.get(i);
            int sf = entityFreq.getOrDefault(r.source(), 0);
            int tf = entityFreq.getOrDefault(r.target(), 0);
            int rf = size(r.chunkIds());
            if (sf > 0 && tf > 0 && rf > 0) {
                double sp = (double) sf / totalEntityOccurrences;
                double tp = (double) tf / totalEntityOccurrences;
                double rp = (double) rf / totalRelOccurrences;
                // 源侧：math.Max(math.Log2(rp/(sp*tp)), 0) —— 负 PMI 是互斥信号，截零
                pmis[i] = Math.max(log2(rp / (sp * tp)), 0.0);
                maxPmi = Math.max(maxPmi, pmis[i]);
            }
            maxStrength = Math.max(maxStrength, r.strength());
        }

        // ── ③ 合成 ──────────────────────────────────────────────────
        double[] weights = new double[relations.size()];
        for (int i = 0; i < relations.size(); i++) {
            double normPmi = maxPmi > 0 ? pmis[i] / maxPmi : 0.0;
            double normStrength = relations.get(i).strength() / maxStrength;
            double combined = normPmi * PMI_WEIGHT + normStrength * STRENGTH_WEIGHT;
            weights[i] = 1.0 + WEIGHT_SCALE * combined;      // ⇒ [1, 10]
        }

        double[] degrees = degreesOf(relations);
        List<Result> out = new java.util.ArrayList<>(relations.size());
        for (int i = 0; i < relations.size(); i++) {
            out.add(new Result(weights[i], (int) degrees[i]));
        }
        return List.copyOf(out);
    }

    /**
     * 每条关系的「两端度数和」。
     *
     * <p>度数 = 该实体的入度 + 出度。<b>自环不计</b>（源侧解析阶段已丢弃自环）。
     *
     * @param relations 关系列表
     * @return 与 relations 同序的度数和
     */
    static double[] degreesOf(List<RelationInput> relations) {
        Map<String, Integer> out = new HashMap<>();
        Map<String, Integer> in = new HashMap<>();
        for (RelationInput r : relations) {
            out.merge(r.source(), 1, Integer::sum);
            in.merge(r.target(), 1, Integer::sum);
        }
        double[] result = new double[relations.size()];
        for (int i = 0; i < relations.size(); i++) {
            RelationInput r = relations.get(i);
            int sd = out.getOrDefault(r.source(), 0) + in.getOrDefault(r.source(), 0);
            int td = out.getOrDefault(r.target(), 0) + in.getOrDefault(r.target(), 0);
            result[i] = sd + td;
        }
        return result;
    }

    /** log2（Java 没有现成的）。 */
    static double log2(double v) {
        return Math.log(v) / Math.log(2.0);
    }

    private static int size(Collection<?> c) {
        return c == null ? 0 : c.size();
    }
}
