package com.nexusai.application.agent.subagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Transcript 持久化 · 对齐 CC sessionStorage.ts
 *
 * <p>文件结构：
 * <pre>
 * {sessionDir}/{sessionId}/subagents/agent-{agentId}.jsonl
 * {sessionDir}/{sessionId}/subagents/agent-{agentId}.meta.json
 * </pre>
 */
public class AgentTranscript {

    private static final Logger log = LoggerFactory.getLogger(AgentTranscript.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Agent 元数据 · 对齐 CC AgentMetadata（sessionStorage.ts:264-272）
     *
     * <p>[#25 删字段] 原 Java 扩展 model 字段已删除（open-decisions F2 #25 删字段改现算）——
     * CC AgentMetadata 无 model（sessionStorage.ts:264-272 仅 agentType/worktreePath?/description?），
     * CC resume 取 {@code toolUseContext.options.mainLoopModel} 当前主循环模型现算
     * （resumeAgent.ts:131/151）；Java 对应 = {@code AgentState.currentModel()}
     * （ResumeService.rebuildForkParentSystemPrompt），不再从 meta 持久化/读取。
     * 旧 meta 文件残留的 "model" JSON 字段由 readMetadata 忽略（无害，对齐 CC JSON.parse 容错）。
     *
     * @param agentType   agent 类型 · CC original: {@code agentType}（sessionStorage.ts:265）
     * @param worktreePath 隔离 worktree 路径 · CC original: {@code worktreePath?}（:267）
     * @param description 原始任务描述 · CC original: {@code description?}（:271）
     */
    public record AgentMetadata(
        String agentType,
        String worktreePath,
        String description
    ) {}

    /**
     * 获取 Agent transcript 文件路径 · 对齐 CC getAgentTranscriptPath
     */
    public static Path getTranscriptPath(Path sessionDir, String sessionId, String agentId) {
        return sessionDir.resolve(sessionId)
            .resolve("subagents")
            .resolve("agent-" + agentId + ".jsonl");
    }

    /**
     * 获取 Agent metadata 文件路径 · 对齐 CC getAgentMetadataPath
     */
    public static Path getMetadataPath(Path sessionDir, String sessionId, String agentId) {
        Path transcriptPath = getTranscriptPath(sessionDir, sessionId, agentId);
        String fileName = transcriptPath.getFileName().toString();
        String metaFileName = fileName.replace(".jsonl", ".meta.json");
        return transcriptPath.resolveSibling(metaFileName);
    }

    /**
     * 写入 Agent metadata · 对齐 CC writeAgentMetadata
     *
     * @param sessionDir  会话目录
     * @param sessionId   会话 ID
     * @param agentId     Agent ID
     * @param metadata    Agent 元数据
     */
    public static void writeMetadata(Path sessionDir, String sessionId, String agentId, 
                                     AgentMetadata metadata) throws IOException {
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        Files.createDirectories(path.getParent());
        ObjectNode node = JSON.createObjectNode();
        node.put("agentType", metadata.agentType());
        if (metadata.worktreePath() != null) {
            node.put("worktreePath", metadata.worktreePath());
        }
        if (metadata.description() != null) {
            node.put("description", metadata.description());
        }
        // [#25] model 字段已删（对齐 CC sessionStorage.ts:264-272 AgentMetadata 无 model 字段）——不再写入。
        Files.writeString(path, JSON.writeValueAsString(node));
        log.info("已写入 agent 元数据: agentId={}, type={}", agentId, metadata.agentType());
    }

    /**
     * 读取 Agent metadata · 对齐 CC readAgentMetadata
     */
    public static Optional<AgentMetadata> readMetadata(Path sessionDir, String sessionId, String agentId) {
        Path path = getMetadataPath(sessionDir, sessionId, agentId);
        if (!Files.exists(path)) return Optional.empty();
        try {
            String content = Files.readString(path);
            var node = JSON.readTree(content);
            // [#25] model 字段已删：旧 meta 残留 "model" JSON 字段忽略（对齐 CC JSON.parse 天然容错）
            return Optional.of(new AgentMetadata(
                node.path("agentType").asText(),
                node.path("worktreePath").isMissingNode() ? null : node.path("worktreePath").asText(),
                node.path("description").isMissingNode() ? null : node.path("description").asText()
            ));
        } catch (Exception e) {
            log.warn("读取 agent 元数据失败 {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 记录 sidechain transcript · 对齐 CC recordSidechainTranscript
     *
     * <p>[S5 P0] 对齐 CC sessionStorage.ts:995/1042 sidechain 消息结构: 写入时给每条消息补
     * {@code agentId} + {@code isSidechain=true} + {@code uuid} + {@code parentUuid} 链,
     * 否则 {@link #getAgentTranscript} 的 filter (agentId + isSidechain) 与 leaf 查找
     * (parentUuid) 无法工作.
     *
     * @param messages 消息 Map 列表 (就地补充字段)
     */
    public static void recordSidechainTranscript(Path sessionDir, String sessionId,
                                                 String agentId, List<Map<String, Object>> messages) {
        recordSidechainTranscript(sessionDir, sessionId, agentId, messages, null);
    }

    /**
     * 记录 sidechain transcript（跨调用链版本）· 对齐 CC recordSidechainTranscript
     * (sessionStorage.ts:1451-1462) + insertMessageChain (sessionStorage.ts:993-1080).
     *
     * <p>[W5-03b] 主会话后台化逐消息写 (LocalMainSessionTask.ts:411-419):
     * {@code recordSidechainTranscript([event], taskId, lastRecordedUuid)} — 每次增量写都以
     * 上一已写消息的 uuid 作为 {@code startingParentUuid}, 让新消息链到上一条, 跨独立 write()
     * 调用保持 parent 链完整. 无此参数时跨调用消息各自 parentUuid=null → getAgentTranscript
     * leaf 查找 (uuid 非任何 parentUuid, CC :4210-4214) 命中首条而非末条 → 链重建丢失后半段.
     *
     * @param sessionDir        会话目录
     * @param sessionId         会话 ID
     * @param agentId           Agent ID
     * @param messages          消息 Map 列表 (就地补充字段)
     * @param startingParentUuid 链首消息的 parentUuid (CC original: {@code startingParentUuid?}).
     *                           上一条已写消息的 uuid; null = 链首
     */
    public static void recordSidechainTranscript(Path sessionDir, String sessionId,
                                                 String agentId, List<Map<String, Object>> messages,
                                                 String startingParentUuid) {
        Path path = getTranscriptPath(sessionDir, sessionId, agentId);
        try {
            Files.createDirectories(path.getParent());
            // 追加模式写入
            StringBuilder sb = new StringBuilder();
            String prevUuid = startingParentUuid;
            for (Map<String, Object> msg : messages) {
                enrichTranscriptMessage(msg, agentId, prevUuid);
                sb.append(JSON.writeValueAsString(msg)).append("\n");
                prevUuid = String.valueOf(msg.get("uuid"));
            }
            Files.writeString(path, sb.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            if (log.isDebugEnabled()) {
                log.debug("已记录 {} 条消息 agent={} (startingParentUuid={})",
                    messages.size(), agentId, startingParentUuid);
            }
        } catch (IOException e) {
            log.warn("记录 sidechain transcript 失败: {}", e.getMessage());
        }
    }

    /**
     * 给一条 transcript 消息补 agentId/isSidechain/uuid/parentUuid.
     * 对齐 CC sessionStorage.ts:995 ({@code isSidechain: boolean = false}) + :1042
     * ({@code isSidechain, parentUuid} 序列化) — Java 端 sidechain 恒 true.
     */
    private static void enrichTranscriptMessage(Map<String, Object> msg, String agentId, String prevUuid) {
        if (msg == null) return;
        if (msg.get("agentId") == null) {
            msg.put("agentId", agentId);
        }
        msg.put("isSidechain", true);
        Object existingUuid = msg.get("uuid");
        String uuid = existingUuid == null || "null".equals(String.valueOf(existingUuid))
            ? UUID.randomUUID().toString()
            : String.valueOf(existingUuid);
        msg.put("uuid", uuid);
        if (!msg.containsKey("parentUuid") && prevUuid != null) {
            msg.put("parentUuid", prevUuid);
        }
    }

    /**
     * 清理 Agent transcript 子目录 · 对齐 CC clearAgentTranscriptSubdir
     * (sessionStorage.ts:243-245)
     *
     * <p><b>内存语义，不删磁盘文件</b>：CC clearAgentTranscriptSubdir 只做
     * {@code agentTranscriptSubdirs.delete(agentId)}（内存 Map 清理，
     * sessionStorage.ts:244），<b>绝不触碰磁盘</b> transcript/meta 文件
     * （sessionStorage.ts:245 无任何 fs 删除）。Java 无 agentTranscriptSubdirs
     * Map（getAgentTranscriptPath 直接按路径解析，不查 Map），故本方法对齐后为
     * no-op——磁盘 transcript/meta <b>保留</b>，供执行记录查看回读
     * （SubagentController.getAgentTranscript / ResumeService /
     * SummarySummarizerImpl 均读盘）。
     *
     * <p>[Bug A 修复] 旧实现用 Files.deleteIfExists 删除 .jsonl + .meta.json，
     * 导致 SubagentExecutor finally 清理后子代理执行记录查看详情 "No transcript found"。
     *
     * @param sessionDir 会话目录（保留，签名与 SubagentExecutor:1980 调用点对齐）
     * @param sessionId  会话 ID
     * @param agentId    Agent ID
     */
    public static void clearTranscript(Path sessionDir, String sessionId, String agentId) {
        if (log.isDebugEnabled()) {
            log.debug("clearTranscript 对齐 CC clearAgentTranscriptSubdir 内存语义，"
                + "不删除磁盘 transcript/meta（sessionDir={}, sessionId={}, agentId={}）",
                sessionDir, sessionId, agentId);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // [S5 P0 差异 4] getAgentTranscript 读取 · 对齐 CC sessionStorage.ts:4190-4236
    // ────────────────────────────────────────────────────────────────────────

    /**
     * getAgentTranscript 返回值 · 对齐 CC sessionStorage.ts:4190-4192
     * ({@code { messages: Message[]; contentReplacements: ContentReplacementRecord[] }}).
     */
    public record AgentTranscriptResult(
        List<AgentMessage> messages,
        List<ContentReplacementRecord> contentReplacements
    ) {}

    /**
     * 读取 agent transcript 并重建对话链 · 对齐 CC getAgentTranscript
     * (Open-ClaudeCode/src/utils/sessionStorage.ts:4190-4236).
     *
     * <p>流程:
     * <ol>
     *   <li>读 {@code agent-{agentId}.jsonl} 逐行解析 (消息 + contentReplacement 记录)</li>
     *   <li>filter {@code msg.agentId === agentId && msg.isSidechain} (:4201-4203)</li>
     *   <li>找 leaf: uuid 非任何 parentUuid (:4210-4214)</li>
     *   <li>{@link #buildConversationChain} 按 parentUuid 链重建 (:4221)</li>
     *   <li>再 filter agentId (:4223) + 剥离 isSidechain/parentUuid (:4229)</li>
     *   <li>返 {messages, contentReplacements} (:4230-4232); 异常 → Optional.empty (:4233)</li>
     * </ol>
     *
     * @param sessionDir 会话目录 (getTranscriptPath 的根)
     * @param sessionId  会话 ID
     * @param agentId    目标 agent id
     * @return 非空含重建后的消息链 + contentReplacements; 文件不存在/无匹配 → Optional.empty
     */
    public static Optional<AgentTranscriptResult> getAgentTranscript(
            Path sessionDir, String sessionId, String agentId) {
        Path path = getTranscriptPath(sessionDir, sessionId, agentId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            List<AgentMessage> all = new ArrayList<>();
            List<ContentReplacementRecord> contentReplacements = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                if (line == null || line.isBlank()) continue;
                JsonNode node = JSON.readTree(line);
                if (node == null) continue;
                if (isContentReplacementNode(node)) {
                    contentReplacements.addAll(parseContentReplacement(node, agentId));
                } else {
                    AgentMessage msg = parseMessageNode(node);
                    if (msg != null) all.add(msg);
                }
            }

            // filter agentId + isSidechain (CC :4201-4203)
            List<AgentMessage> agentMessages = all.stream()
                .filter(m -> agentId.equals(m.agentId()) && m.isSidechain())
                .toList();
            if (agentMessages.isEmpty()) {
                return Optional.empty();
            }

            // find leaf: uuid 非任何 parentUuid (CC :4210-4214)
            Set<String> parentUuids = agentMessages.stream()
                .map(AgentMessage::parentUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
            AgentMessage leaf = agentMessages.stream()
                .filter(m -> m.uuid() != null && !parentUuids.contains(m.uuid()))
                .findFirst()
                .orElse(null);
            if (leaf == null) {
                return Optional.empty();
            }

            // buildConversationChain (CC :4221)
            List<AgentMessage> transcript = buildConversationChain(all, leaf);
            // 再 filter agentId (CC :4223)
            List<AgentMessage> agentTranscript = transcript.stream()
                .filter(m -> agentId.equals(m.agentId()))
                .toList();
            return Optional.of(new AgentTranscriptResult(agentTranscript, contentReplacements));
        } catch (Exception e) {
            log.warn("读取 agent transcript 失败 {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 是否 content replacement 条目 · 对齐 CC sessionStorage.ts:3682-3693
     * ({@code entry.type === 'content-replacement'}).
     *
     * <p><b>CC JSONL 条目结构</b> (sessionStorage.ts:1119-1122 写入侧, 非顶层记录):
     * <pre>
     * { type: 'content-replacement', sessionId, agentId?, replacements: ContentReplacementRecord[] }
     * </pre>
     * 判定只看顶层 {@code type} 字段; 记录本体在 {@code replacements} 数组内
     * ({@code {kind, toolUseId, replacement}}, toolResultStorage.ts:475-479).
     *
     * <p>[S5 审查迭代修复] 旧实现查顶层 {@code toolUseId + preview} 字段与 CC 结构不匹配:
     * 真实写入方 SessionStorage.writeContentReplacement (R28-3.7) 写入的
     * {@code type='content-replacement'} 条目会被误判为消息节点 → contentReplacements 恒空.
     */
    private static boolean isContentReplacementNode(JsonNode node) {
        return "content-replacement".equals(node.path("type").asText());
    }

    /**
     * 解析 content replacement 条目 → 该 agent 的记录列表 · 对齐 CC
     * sessionStorage.ts:3682-3693 (loadTranscriptFile 解析侧) + getAgentTranscript
     * (:4231 {@code agentContentReplacements.get(agentId) ?? []}).
     *
     * <p>归桶语义: {@code entry.agentId} 真值且等于查询 agentId 才归入该 agent 桶;
     * 无 agentId 条目按 sessionId 归桶 (getAgentTranscript 不返回) → 返回空列表.
     *
     * <p>记录字段 CC original: ContentReplacementRecord.kind/toolUseId/replacement
     * (Open-ClaudeCode/src/utils/toolResultStorage.ts:475-479); kind 缺失时默认
     * {@code 'tool-result'} (宽松解析, 对齐 Java 写入方不写 kind 的现状).
     *
     * @param node    content-replacement 条目节点
     * @param agentId 查询 agent id (getAgentTranscript 参数)
     * @return 该 agent 的替换记录列表 (可能为空)
     */
    private static List<ContentReplacementRecord> parseContentReplacement(JsonNode node, String agentId) {
        String entryAgentId = node.has("agentId") && !node.get("agentId").isNull()
            ? node.path("agentId").asText() : null;
        if (entryAgentId == null || entryAgentId.isEmpty() || !agentId.equals(entryAgentId)) {
            return List.of();
        }
        List<ContentReplacementRecord> records = new ArrayList<>();
        JsonNode replacements = node.path("replacements");
        if (replacements.isArray()) {
            for (JsonNode r : replacements) {
                records.add(new ContentReplacementRecord(
                    r.path("kind").asText("tool-result"),
                    r.path("toolUseId").asText(),
                    r.path("replacement").asText()));
            }
        }
        if (log.isDebugEnabled() && !records.isEmpty()) {
            log.debug("解析到 {} 条 content replacement 记录 (agent={})", records.size(), agentId);
        }
        return records;
    }

    /** JSONL 节点 → AgentMessage (role/content/isApiError/agentId/isSidechain/uuid/parentUuid/toolCalls/toolCallId). */
    private static AgentMessage parseMessageNode(JsonNode node) {
        String role = node.path("role").asText("user");
        String content = node.has("content") && !node.get("content").isNull()
            ? node.path("content").asText("") : "";
        boolean isApiError = node.path("isApiError").asBoolean(false);
        String agentId = node.has("agentId") && !node.get("agentId").isNull()
            ? node.path("agentId").asText() : null;
        boolean isSidechain = node.path("isSidechain").asBoolean(false);
        String uuid = node.has("uuid") && !node.get("uuid").isNull()
            ? node.path("uuid").asText() : null;
        String parentUuid = node.has("parentUuid") && !node.get("parentUuid").isNull()
            ? node.path("parentUuid").asText() : null;
        String toolCallId = node.has("toolCallId") && !node.get("toolCallId").isNull()
            ? node.path("toolCallId").asText() : null;
        List<AgentMessage.ToolCallInfo> toolCalls = new ArrayList<>();
        JsonNode tcs = node.path("toolCalls");
        if (tcs.isArray()) {
            for (JsonNode tc : tcs) {
                toolCalls.add(new AgentMessage.ToolCallInfo(
                    tc.path("id").asText(),
                    tc.path("name").asText(),
                    tc.has("arguments") && !tc.get("arguments").isNull()
                        ? tc.path("arguments").asText() : null));
            }
        }
        return new AgentMessage(role, content, isApiError, agentId, isSidechain,
            uuid, parentUuid, toolCalls, toolCallId);
    }

    /**
     * 重建对话链 · 对齐 CC buildConversationChain (sessionStorage.ts:2069-2094).
     *
     * <p>从 leaf 沿 parentUuid 反向走 (cycle 检测), 再 reverse 得到正序链.
     * 简化: 不含 CC 的 recoverOrphanedParallelToolResults 后处理 (Java 单 parent 链模型).
     *
     * @param messages    全部消息 (按 uuid 索引)
     * @param leafMessage 链末端消息 (uuid 非任何 parentUuid)
     * @return 正序消息链 (leaf → root reversed)
     */
    static List<AgentMessage> buildConversationChain(List<AgentMessage> messages, AgentMessage leafMessage) {
        Map<String, AgentMessage> byUuid = new HashMap<>();
        for (AgentMessage m : messages) {
            if (m.uuid() != null) byUuid.put(m.uuid(), m);
        }
        List<AgentMessage> chain = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        AgentMessage current = leafMessage;
        while (current != null) {
            if (current.uuid() != null && !seen.add(current.uuid())) {
                log.warn("parentUuid 链检测到环 (消息 {}), 返回部分 transcript.",
                    current.uuid());
                break;
            }
            chain.add(current);
            current = current.parentUuid() != null ? byUuid.get(current.parentUuid()) : null;
        }
        Collections.reverse(chain);
        return chain;
    }
}
