package com.nexusai.kb.app.profile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 知识库级画像聚合 · 照源站 {@code internal/types/knowledgebase_profile.go}。
 *
 * <p><b>源站对照</b>：WeKnora {@code BuildKnowledgeBaseProfileAggregate}
 * （{@code internal/types/knowledgebase_profile.go:250-380}）。
 *
 * <h2>为什么需要聚合</h2>
 * 每篇文档在摘要阶段已经产出了自己的画像（{@code kb_knowledge.profile} 的
 * {@code gist}/{@code topics}/{@code doc_type}/{@code typical_question}）。
 * 知识库级描述**不读文档正文**，只聚合这些画像 —— 既省 token，又比标题列表准。
 *
 * <h2>⭐ 指纹（{@link #hash()}）的作用</h2>
 * 聚合结果变了才需要重新调模型。指纹 = 各文档贡献排序后 SHA-256，
 * 与查询顺序无关 ⇒ 同一批文档永远得到同一个指纹 ⇒ 聚合未变时**不调模型**
 * （源站 {@code existing.IsReady(agg.Hash)} 短路）。
 *
 * <h2>⭐ 主题合并规则（照源站）</h2>
 * <ul>
 *   <li>主题键 = 小写 + 折叠空白（{@code TopicKey}），⇒ 大小写/空格差异算同一个主题</li>
 *   <li>按出现次数降序；次数相同按**首次出现**顺序（稳定排序，源站 {@code sort.SliceStable}）</li>
 *   <li>截断上限 {@link #MAX_RAW_TOPICS}</li>
 * </ul>
 */
public final class KbProfileAggregate {

    /** JSON 工具（口径与 {@code KbSummaryService} 一致）。 */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ── 上限常量（照源站 internal/types/knowledgebase_profile.go:39-51）─────────

    /** 合并后喂给模型的主题上限。 */
    public static final int MAX_TOPICS = 10;
    /** 典型问题上限。 */
    public static final int MAX_QUESTIONS = 5;
    /** 存进 stats 的原始主题计数上限。 */
    public static final int MAX_RAW_TOPICS = 30;
    /** 存进 stats 的标签计数上限。 */
    public static final int MAX_TAGS = 30;
    /** 存进 stats 的目录名上限。 */
    public static final int MAX_FOLDERS = 20;
    /** 喂给模型的标题样本上限。 */
    public static final int MAX_SAMPLE_TITLES = 60;
    /** 喂给模型的 gist 样本上限（源站 {@code MAX_SAMPLE_TITLES/3}）。 */
    public static final int MAX_SAMPLE_GISTS = MAX_SAMPLE_TITLES / 3;

    private int documentCount;
    private int profiledCount;
    private String earliestAt;
    private String latestAt;
    private final Map<String, Integer> fileTypes = new TreeMap<>();
    private final Map<String, Integer> tags = new TreeMap<>();
    private final Set<String> folders = new java.util.TreeSet<>();
    private final List<String> sampleTitles = new ArrayList<>();
    private final List<String> sampleGists = new ArrayList<>();
    private final List<String> sampleQuestions = new ArrayList<>();
    /** 主题：键 → 显示名 + 次数 + 该主题下的典型问题。 */
    private final Map<String, TopicBucket> topics = new LinkedHashMap<>();
    /** 未挂到任何主题上的问题（源站 {@code untopicedQuestions}）。 */
    private final List<String> untopicedQuestions = new ArrayList<>();
    /** 各文档贡献的指纹原料。 */
    private final List<String> fingerprints = new ArrayList<>();

    /** 主题桶（照源站 {@code topicBucket}）。 */
    private static final class TopicBucket {
        final String display;
        int count;
        final List<String> questions = new ArrayList<>();

        TopicBucket(String display) {
            this.display = display;
        }
    }

    /**
     * 累加一篇文档的画像。
     *
     * @param id         文档 ID
     * @param title      显示标题
     * @param fileType   文件类型（可空）
     * @param folderPath 目录路径（可空）
     * @param createdAt  创建时间（可空，字符串）
     * @param tags       标签（可空）
     * @param profile    文档画像 JSON（可空 ⇒ 该文档无画像）
     */
    public void add(String id, String title, String fileType, String folderPath,
                    String createdAt, List<String> docTags, String profile) {
        if (id == null || id.isBlank()) {
            return;
        }
        documentCount++;
        String ft = normalize(fileType);
        if (!ft.isEmpty()) {
            fileTypes.merge(ft, 1, Integer::sum);
        }
        if (docTags != null) {
            for (String tag : docTags) {
                String t = normalize(tag);
                if (!t.isEmpty()) {
                    tags.merge(t, 1, Integer::sum);
                }
            }
        }
        String folder = topLevelFolder(folderPath);
        if (!folder.isEmpty()) {
            folders.add(folder);
        }
        if (createdAt != null && !createdAt.isBlank()) {
            if (earliestAt == null || createdAt.compareTo(earliestAt) < 0) {
                earliestAt = createdAt;
            }
            if (latestAt == null || createdAt.compareTo(latestAt) > 0) {
                latestAt = createdAt;
            }
        }
        String displayTitle = title == null || title.isBlank() ? "" : title.trim();
        if (!displayTitle.isEmpty()) {
            sampleTitles.add(displayTitle);
        }

        String fp = id + "|" + displayTitle + "|" + String.join(",", docTags == null ? List.of() : docTags);
        String p = profile == null ? "" : profile.trim();
        if (!p.isEmpty()) {
            profiledCount++;
            JsonNode node = readJson(p);
            String gist = normalize(node.path("gist").asText(""));
            String docType = normalize(node.path("doc_type").asText(""));
            String question = normalize(node.path("typical_question").asText(""));
            List<String> topicList = new ArrayList<>();
            if (node.path("topics").isArray()) {
                for (var t : node.path("topics")) {
                    topicList.add(t.asText(""));
                }
            }
            fp += "|" + gist + "|" + String.join(",", topicList) + "|" + docType + "|" + question;
            if (!gist.isEmpty()) {
                sampleGists.add(gist);
            }
            if (!docType.isEmpty()) {
                String key = topicKey(docType);
                if (!key.isEmpty()) {
                    topics.computeIfAbsent(key, k -> new TopicBucket(docType)).count++;
                }
            }
            boolean assigned = false;
            for (String topic : topicList) {
                String display = normalize(topic);
                String key = topicKey(display);
                if (key.isEmpty()) {
                    continue;
                }
                TopicBucket b = topics.computeIfAbsent(key, k -> new TopicBucket(display));
                b.count++;
                if (!question.isEmpty() && !assigned) {
                    b.questions.add(question);
                    assigned = true;
                }
            }
            if (!question.isEmpty() && !assigned) {
                untopicedQuestions.add(question);
            }
        }
        fingerprints.add(fp);
    }

    /**
     * 聚合指纹：各文档贡献排序后 SHA-256。
     *
     * <p>排序后再 hash ⇒ 与查询顺序无关（源站 {@code hashAggregateInputs}）。
     */
    public String hash() {
        List<String> sorted = new ArrayList<>(fingerprints);
        java.util.Collections.sort(sorted);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (String s : sorted) {
                md.update(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                md.update((byte) 0);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走不到这里
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /** 主题列表（按次数降序、首次出现稳定排序、截断上限）。 */
    public List<String> orderedTopics() {
        List<Map.Entry<String, TopicBucket>> entries = new ArrayList<>(topics.entrySet());
        // 稳定排序：次数降序；次数相同保持插入顺序（LinkedHashMap 的迭代序 = 首次出现序）
        entries.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, TopicBucket> e : entries) {
            if (out.size() >= MAX_RAW_TOPICS) {
                break;
            }
            out.add(e.getValue().display);
        }
        return out;
    }

    /** 主题 + 次数（进 stats 的原始主题计数）。 */
    public List<String> rawTopicCounts() {
        List<Map.Entry<String, TopicBucket>> entries = new ArrayList<>(topics.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, TopicBucket> e : entries) {
            if (out.size() >= MAX_RAW_TOPICS) {
                break;
            }
            out.add(e.getValue().display + " (" + e.getValue().count + ")");
        }
        return out;
    }

    /** 典型问题样本（照源站 {@code roundRobinQuestions}：每主题先取一个，再轮次推进）。 */
    public List<String> sampleQuestions() {
        List<Map.Entry<String, TopicBucket>> entries = new ArrayList<>(topics.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue().count, a.getValue().count));
        List<String> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; ; round++) {
            boolean progressed = false;
            for (Map.Entry<String, TopicBucket> e : entries) {
                List<String> qs = e.getValue().questions;
                if (round < qs.size()) {
                    progressed = true;
                    String q = qs.get(round);
                    if (!seen.add(q.toLowerCase(java.util.Locale.ROOT))) {
                        continue;
                    }
                    out.add(q);
                    if (out.size() >= MAX_QUESTIONS) {
                        return out;
                    }
                }
            }
            if (!progressed) {
                break;
            }
        }
        for (String q : untopicedQuestions) {
            if (!seen.add(q.toLowerCase(java.util.Locale.ROOT))) {
                continue;
            }
            out.add(q);
            if (out.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return out;
    }

    /** 标题样本（均匀取样，覆盖全库而不是只取最新）。 */
    public List<String> sampleTitles() {
        return spreadSample(sampleTitles, MAX_SAMPLE_TITLES);
    }

    /** gist 样本（均匀取样）。 */
    public List<String> sampleGists() {
        return spreadSample(sampleGists, MAX_SAMPLE_GISTS);
    }

    /** 没有任何可描述的内容（无文档 / 无标题 / 无画像）。 */
    public boolean isEmpty() {
        return documentCount == 0 || (sampleTitles.isEmpty() && sampleGists.isEmpty()
            && topics.isEmpty() && untopicedQuestions.isEmpty());
    }

    public int documentCount() {
        return documentCount;
    }

    public int profiledCount() {
        return profiledCount;
    }

    public String earliestAt() {
        return earliestAt;
    }

    public String latestAt() {
        return latestAt;
    }

    /** 文件类型计数（按次数降序）。 */
    public List<String> fileTypeCounts() {
        return topCounts(fileTypes, 0);
    }

    /** 标签计数（按次数降序）。 */
    public List<String> tagCounts() {
        return topCounts(tags, MAX_TAGS);
    }

    /** 顶层目录名（已排序）。 */
    public List<String> folderNames() {
        List<String> out = new ArrayList<>(folders);
        if (out.size() > MAX_FOLDERS) {
            return new ArrayList<>(out.subList(0, MAX_FOLDERS));
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────────────

    private static List<String> topCounts(Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) {
            return List.of();
        }
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> {
            int c = Integer.compare(b.getValue(), a.getValue());
            return c != 0 ? c : a.getKey().compareTo(b.getKey());
        });
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : entries) {
            if (limit > 0 && out.size() >= limit) {
                break;
            }
            out.add(e.getKey() + " (" + e.getValue() + ")");
        }
        return out;
    }

    /** 均匀取样：覆盖整个语料而不是只取最新的几篇（照源站 {@code spreadSample}）。 */
    private static List<String> spreadSample(List<String> items, int limit) {
        if (items.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }
        List<String> out = new ArrayList<>(limit);
        double step = (double) items.size() / limit;
        for (int i = 0; i < limit; i++) {
            out.add(items.get((int) (i * step)));
        }
        return out;
    }

    /** 主题键：小写 + 折叠空白 ⇒ 大小写/空格差异算同一主题（照源站 {@code TopicKey}）。 */
    private static String topicKey(String display) {
        String s = normalize(display);
        return s.toLowerCase(java.util.Locale.ROOT);
    }

    /** 折叠连续空白并去首尾（照源站 {@code collapseWhitespace}）。 */
    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.trim().replaceAll("\\s+", " ");
    }

    /** 顶层目录名（照源站 {@code topLevelFolder}）。 */
    private static String topLevelFolder(String path) {
        String s = path == null ? "" : path.trim();
        s = s.replaceAll("^/+", "").replaceAll("/+$", "");
        if (s.isEmpty()) {
            return "";
        }
        int idx = s.indexOf('/');
        return idx >= 0 ? s.substring(0, idx) : s;
    }

    private static JsonNode readJson(String s) {
        try {
            JsonNode n = JSON.readTree(s);
            return n == null ? MissingNode.getInstance() : n;
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
