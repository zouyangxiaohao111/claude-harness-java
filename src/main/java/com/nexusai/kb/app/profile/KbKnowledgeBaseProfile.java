package com.nexusai.kb.app.profile;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 知识库画像（落进 {@code kb_knowledge_base.generated_profile} 的 JSON）。
 *
 * <p><b>源站对照</b>：WeKnora {@code internal/types/knowledgebase_profile.go:114-134}
 * 的 {@code KnowledgeBaseProfile}。
 *
 * <h2>⚠️ 这不是「知识库描述」字段</h2>
 * 它写进的是 {@code generated_profile}（画像），**不是** {@code description}（用户手写描述）。
 * 源站的自动链同样只写画像 —— 用户手写的描述永远不被自动覆盖。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class KbKnowledgeBaseProfile {

    /** 一句话说明这个知识库覆盖什么。 */
    @JsonProperty("gist")
    private String gist;

    /** 合并后的主题列表（上限 {@link KbProfileAggregate#MAX_TOPICS}）。 */
    @JsonProperty("topics")
    private List<String> topics;

    /** 这个知识库能回答的典型问题（上限 {@link KbProfileAggregate#MAX_QUESTIONS}）。 */
    @JsonProperty("typical_questions")
    private List<String> typicalQuestions;

    /** 聚合快照（统计口径，源站 {@code KnowledgeBaseProfileStats}）。 */
    @JsonProperty("stats")
    private Stats stats;

    /**
     * 生成这段文字时的聚合指纹。
     *
     * <p>与当前聚合不一致 ⇒ 文字已过期（源站 {@code IsReady(hash)} 用它短路）。
     */
    @JsonProperty("aggregate_hash")
    private String aggregateHash;

    /** 生成状态：ready / empty / failed。 */
    @JsonProperty("status")
    private String status;

    /** 失败原因（仅 status=failed 时有）。 */
    @JsonProperty("error")
    private String error;

    /** 产出这段文字的模型 ID。 */
    @JsonProperty("model_id")
    private String modelId;

    /** 生成时间（ISO-8601）。 */
    @JsonProperty("generated_at")
    private String generatedAt;

    public String getGist() {
        return gist;
    }

    public void setGist(String gist) {
        this.gist = gist;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public List<String> getTypicalQuestions() {
        return typicalQuestions;
    }

    public void setTypicalQuestions(List<String> typicalQuestions) {
        this.typicalQuestions = typicalQuestions;
    }

    public Stats getStats() {
        return stats;
    }

    public void setStats(Stats stats) {
        this.stats = stats;
    }

    public String getAggregateHash() {
        return aggregateHash;
    }

    public void setAggregateHash(String aggregateHash) {
        this.aggregateHash = aggregateHash;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    /**
     * 是否「可用且未过期」。
     *
     * <p>照源站 {@code IsReady(hash)}：状态是 ready 且指纹与当前聚合一致。
     */
    public boolean isReady(String currentHash) {
        return "ready".equals(status) && aggregateHash != null && aggregateHash.equals(currentHash);
    }

    /** 聚合统计快照（源站 {@code KnowledgeBaseProfileStats}）。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Stats {
        @JsonProperty("document_count")
        private int documentCount;
        @JsonProperty("profiled_count")
        private int profiledCount;
        @JsonProperty("earliest_at")
        private String earliestAt;
        @JsonProperty("latest_at")
        private String latestAt;
        @JsonProperty("file_types")
        private List<String> fileTypes;
        @JsonProperty("tags")
        private List<String> tags;
        @JsonProperty("folders")
        private List<String> folders;
        @JsonProperty("raw_topics")
        private List<String> rawTopics;

        public int getDocumentCount() {
            return documentCount;
        }

        public void setDocumentCount(int documentCount) {
            this.documentCount = documentCount;
        }

        public int getProfiledCount() {
            return profiledCount;
        }

        public void setProfiledCount(int profiledCount) {
            this.profiledCount = profiledCount;
        }

        public String getEarliestAt() {
            return earliestAt;
        }

        public void setEarliestAt(String earliestAt) {
            this.earliestAt = earliestAt;
        }

        public String getLatestAt() {
            return latestAt;
        }

        public void setLatestAt(String latestAt) {
            this.latestAt = latestAt;
        }

        public List<String> getFileTypes() {
            return fileTypes;
        }

        public void setFileTypes(List<String> fileTypes) {
            this.fileTypes = fileTypes;
        }

        public List<String> getTags() {
            return tags;
        }

        public void setTags(List<String> tags) {
            this.tags = tags;
        }

        public List<String> getFolders() {
            return folders;
        }

        public void setFolders(List<String> folders) {
            this.folders = folders;
        }

        public List<String> getRawTopics() {
            return rawTopics;
        }

        public void setRawTopics(List<String> rawTopics) {
            this.rawTopics = rawTopics;
        }
    }
}
