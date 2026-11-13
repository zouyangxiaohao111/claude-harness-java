package com.nexusai.application.session;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.mybatisflex.core.query.QueryWrapper;
import com.nexusai.application.agent.compact.SqliteBusyRetry;
import com.nexusai.common.NexusaiDataRoots;
import com.nexusai.domain.session.SessionService;
import com.nexusai.infra.exception.NotFoundException;
import com.nexusai.infra.exception.ValidationException;
import com.nexusai.model.session.dto.ForkSessionResponse;
import com.nexusai.model.session.dto.SessionGroup;
import com.nexusai.repository.session.entity.AttachmentRecord;
import com.nexusai.repository.session.entity.MessageRecord;
import com.nexusai.repository.session.entity.SessionArtifactRecord;
import com.nexusai.repository.session.entity.SessionFileRecord;
import com.nexusai.repository.session.entity.SessionRecord;
import com.nexusai.repository.session.entity.ToolCallRecord;
import com.nexusai.repository.session.mapper.AttachmentMapper;
import com.nexusai.repository.session.mapper.MessageMapper;
import com.nexusai.repository.session.mapper.SessionArtifactMapper;
import com.nexusai.repository.session.mapper.SessionFileMapper;
import com.nexusai.repository.session.mapper.SessionMapper;
import com.nexusai.repository.session.mapper.ToolCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 会话分叉（截断复制）。
 *
 * <p><b>语义</b>：从某条消息所在轮末分叉 —— 新会话只含 {@code messages.seq <= pivotSeq}
 * 的消息；标题追加 {@code " 分叉"}；项目/模型/effort/权限等会话偏好继承，
 * {@code todos / goal / team_context / 授权 / 用量}清空。
 *
 * <p><b>为什么不复用 MessageService</b>：分叉是静态复制，不是「新消息落库」——
 * 走业务方法会重复取 seq、推 STOMP 事件、改统计。这里直接写仓储层。
 *
 * <p><b>顺序</b>：先生成新会话 id → 复制文件目录 → 事务内写 DB。文件复制不可回滚，
 * 所以 DB 失败时删掉已复制目录；文件失败则不写 DB。DB 里永远不会出现指向半截路径的行。
 */
@Service
public class SessionForkService {
    private static final Logger log = LoggerFactory.getLogger(SessionForkService.class);

    /** 附件缓存目录（与 AttachmentStoreBase 三个子类同源）。会话删除时会被清理，必须真拷。 */
    private static final String[] ATTACHMENT_CACHE_DIRS = {"image-cache", "pdf-cache", "media-cache"};

    /** tool_calls 批量 IN 查询的分片大小（避免超 SQLite 参数上限）。 */
    private static final int IN_BATCH = 500;

    @Autowired private SessionMapper sessionMapper;
    @Autowired private MessageMapper messageMapper;
    @Autowired private ToolCallMapper toolCallMapper;
    @Autowired private SessionFileMapper sessionFileMapper;
    @Autowired private AttachmentMapper attachmentMapper;
    @Autowired private SessionArtifactMapper sessionArtifactMapper;
    @Autowired private SessionService sessionService;
    @Autowired private PlatformTransactionManager txManager;

    public ForkSessionResponse fork(String sourceSessionId, String throughMessageId) {
        SessionRecord src = sessionMapper.selectOneById(sourceSessionId);
        if (src == null) {
            throw new NotFoundException("Session " + sourceSessionId + " not found");
        }
        MessageRecord pivot = messageMapper.selectOneById(throughMessageId);
        if (pivot == null || !sourceSessionId.equals(pivot.getSessionId())) {
            throw new NotFoundException("Message " + throughMessageId + " not found in session " + sourceSessionId);
        }
        if (pivot.getSeq() == null) {
            throw new ValidationException("Message " + throughMessageId + " has no seq; cannot fork");
        }
        long pivotSeq = pivot.getSeq();

        String newSessionId = generateId("sess");
        List<Path> createdDirs = new ArrayList<>();
        try {
            copySessionFiles(sourceSessionId, newSessionId, createdDirs);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(createdDirs);
            throw new IllegalStateException("分叉文件复制失败: " + sourceSessionId + " -> " + newSessionId, e);
        }

        try {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            int copied = SqliteBusyRetry.executeInTxWithBusyRetry(
                "fork session=" + sourceSessionId + " pivot=" + throughMessageId,
                tx,
                () -> copyDatabase(src, newSessionId, pivotSeq),
                null);
            log.info("[SessionFork] 分叉完成 source={} new={} pivot={} seq={} messages={}",
                sourceSessionId, newSessionId, throughMessageId, pivotSeq, copied);
            return new ForkSessionResponse(sessionService.getById(newSessionId), copied);
        } catch (RuntimeException e) {
            deleteQuietly(createdDirs);
            throw e;
        }
    }

    /**
     * 事务内复制：每次重试都重新 SELECT（新读快照），只做 INSERT / UPDATE。
     *
     * @return 复制到新会话的消息条数
     */
    private int copyDatabase(SessionRecord src, String newSessionId, long pivotSeq) {
        String now = OffsetDateTime.now().toString();

        List<MessageRecord> srcMessages = messageMapper.selectListByQuery(
            QueryWrapper.create()
                .eq("session_id", src.getId())
                .le("seq", pivotSeq)
                .orderBy("seq", true));
        if (srcMessages.isEmpty()) {
            throw new ValidationException("Nothing to fork before seq " + pivotSeq);
        }

        Map<String, String> msgIdMap = new HashMap<>();
        for (MessageRecord m : srcMessages) {
            msgIdMap.put(m.getId(), generateId("msg"));
        }

        List<ToolCallRecord> srcToolCalls = loadToolCalls(srcMessages);
        Map<String, String> toolIdMap = new HashMap<>();
        for (ToolCallRecord tc : srcToolCalls) {
            toolIdMap.put(tc.getId(), generateId("tc"));
        }

        List<AttachmentRecord> srcAttachments = attachmentMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", src.getId()));
        Map<Long, Long> attachmentIdMap = new HashMap<>();
        for (AttachmentRecord a : srcAttachments) {
            AttachmentRecord copy = new AttachmentRecord();
            copy.setSessionId(newSessionId);
            copy.setPath(rewriteSessionPath(a.getPath(), src.getId(), newSessionId));
            copy.setMediaType(a.getMediaType());
            copy.setFilename(a.getFilename());
            copy.setSize(a.getSize());
            copy.setSourceType(a.getSourceType());
            copy.setCreatedAt(a.getCreatedAt());
            attachmentMapper.insert(copy);
            if (a.getId() != null && copy.getId() != null) {
                attachmentIdMap.put(a.getId(), copy.getId());
            }
        }

        SessionRecord session = buildForkSession(src, newSessionId, now, srcMessages.size());
        sessionMapper.insert(session);

        for (MessageRecord m : srcMessages) {
            MessageRecord copy = new MessageRecord();
            copy.setId(msgIdMap.get(m.getId()));
            copy.setSessionId(newSessionId);
            copy.setRole(m.getRole());
            copy.setAuthor(m.getAuthor());
            copy.setContent(m.getContent());
            copy.setReasoning(m.getReasoning());
            copy.setReasoningDurationMs(m.getReasoningDurationMs());
            copy.setFinishReason(m.getFinishReason());
            copy.setInputTokens(m.getInputTokens());
            copy.setOutputTokens(m.getOutputTokens());
            copy.setCacheReadInputTokens(m.getCacheReadInputTokens());
            copy.setCacheCreationInputTokens(m.getCacheCreationInputTokens());
            copy.setCreatedAt(m.getCreatedAt());
            copy.setToolCallId(mapOrNull(toolIdMap, m.getToolCallId()));
            copy.setSubtype(m.getSubtype());
            copy.setStructuredOutput(m.getStructuredOutput());
            copy.setAssistantMessageId(mapOrNull(msgIdMap, m.getAssistantMessageId()));
            copy.setCompactMetadata(m.getCompactMetadata());
            copy.setMicrocompactMetadata(m.getMicrocompactMetadata());
            copy.setLogicalParentUuid(mapOrNull(msgIdMap, m.getLogicalParentUuid()));
            copy.setSnipMetadata(m.getSnipMetadata());
            copy.setCwd(m.getCwd());
            copy.setImagePasteIds(m.getImagePasteIds());
            copy.setUserAttachments(remapAttachmentJson(
                m.getUserAttachments(), attachmentIdMap, src.getId(), newSessionId));
            copy.setUserMessageId(mapOrNull(msgIdMap, m.getUserMessageId()));
            copy.setIsMeta(m.getIsMeta());
            copy.setQueuedOrigin(m.getQueuedOrigin());
            copy.setThinkingSignature(m.getThinkingSignature());
            copy.setSeq(m.getSeq());
            copy.setIsCompactSummary(m.getIsCompactSummary());
            copy.setIsVisibleInTranscriptOnly(m.getIsVisibleInTranscriptOnly());
            messageMapper.insert(copy);
        }

        for (ToolCallRecord tc : srcToolCalls) {
            ToolCallRecord copy = new ToolCallRecord();
            copy.setId(toolIdMap.get(tc.getId()));
            copy.setMessageId(mapOrNull(msgIdMap, tc.getMessageId()));
            copy.setToolName(tc.getToolName());
            copy.setArguments(tc.getArguments());
            copy.setResult(tc.getResult());
            copy.setIsError(tc.getIsError());
            copy.setCreatedAt(tc.getCreatedAt());
            toolCallMapper.insert(copy);
        }

        List<SessionFileRecord> srcFiles = sessionFileMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", src.getId()));
        for (SessionFileRecord f : srcFiles) {
            SessionFileRecord copy = new SessionFileRecord();
            copy.setId(generateId("sf"));
            copy.setSessionId(newSessionId);
            copy.setPath(f.getPath());
            copy.setStatus(f.getStatus());
            copy.setAdditions(f.getAdditions());
            copy.setDeletions(f.getDeletions());
            copy.setOldRev(f.getOldRev());
            copy.setNewRev(f.getNewRev());
            sessionFileMapper.insert(copy);
        }

        List<SessionArtifactRecord> srcArtifacts = sessionArtifactMapper.selectListByQuery(
            QueryWrapper.create().eq("session_id", src.getId()));
        for (SessionArtifactRecord a : srcArtifacts) {
            SessionArtifactRecord copy = new SessionArtifactRecord();
            copy.setId("art-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
            copy.setSessionId(newSessionId);
            copy.setKind(a.getKind());
            copy.setTitle(a.getTitle());
            copy.setLocalPath(rewriteArtifactPath(a.getLocalPath(), src.getId(), newSessionId));
            copy.setUrl(a.getUrl());
            copy.setSizeBytes(a.getSizeBytes());
            copy.setSummary(a.getSummary());
            // B 口径：范围内能映射就按轮归属；截断点之后 / 历史 null → null（前端落「历史产物」）
            copy.setSourceMessageId(mapOrNull(msgIdMap, a.getSourceMessageId()));
            copy.setOrigin(a.getOrigin());
            copy.setCreatedAt(a.getCreatedAt());
            copy.setUpdatedAt(a.getUpdatedAt());
            sessionArtifactMapper.insert(copy);
        }

        return srcMessages.size();
    }

    private SessionRecord buildForkSession(SessionRecord src, String newSessionId, String now, int messageCount) {
        SessionRecord s = new SessionRecord();
        s.setId(newSessionId);
        s.setModelTag(src.getModelTag());
        s.setModelName(src.getModelName());
        s.setTitle(src.getTitle() + " 分叉");
        s.setTime("现在");
        s.setSessionGroup(SessionGroup.current.name());
        s.setTabId(null);
        s.setMainProjectId(src.getMainProjectId());
        s.setMessageCount(messageCount);
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        s.setConversationId(UUID.randomUUID().toString());
        s.setEffortLevel(src.getEffortLevel());
        s.setUltracodeEnabled(src.getUltracodeEnabled());
        s.setBareMode(src.getBareMode());
        s.setDisabledTools(src.getDisabledTools());
        s.setPermissionMode(src.getPermissionMode());
        s.setLoopModeOverride(src.getLoopModeOverride());
        s.setNonInteractiveSession(src.getNonInteractiveSession());
        s.setAutoModeEnabled(src.getAutoModeEnabled());
        s.setMainThreadAgent(src.getMainThreadAgent());
        s.setSessionPermissionRules(src.getSessionPermissionRules());
        s.setTitleExplicit(1);
        s.setCoordinatorMode(src.getCoordinatorMode());
        s.setKbToolsEnabled(src.getKbToolsEnabled());
        // 以下为「当前状态」而非历史值，无法还原到分叉点 → 清空（用户 2026-11-13 裁定）
        s.setTodos(null);
        s.setGoal(null);
        s.setTeamContext(null);
        s.setComputerUseGrants(null);
        s.setTotalCostYuan(null);
        s.setModelUsageJson(null);
        s.setUsageTreeJson(null);
        s.setPinned(null);
        return s;
    }

    private List<ToolCallRecord> loadToolCalls(List<MessageRecord> messages) {
        List<String> ids = new ArrayList<>(messages.size());
        for (MessageRecord m : messages) {
            ids.add(m.getId());
        }
        List<ToolCallRecord> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += IN_BATCH) {
            List<String> part = ids.subList(i, Math.min(ids.size(), i + IN_BATCH));
            out.addAll(toolCallMapper.selectListByQuery(
                QueryWrapper.create().in("message_id", part)));
        }
        return out;
    }

    private static String mapOrNull(Map<String, String> map, String oldId) {
        if (oldId == null || oldId.isBlank()) {
            return null;
        }
        return map.get(oldId);
    }

    /**
     * attachments.path 里的会话段替换（upload 类文件落在 {@code <cache>/<sessionId>/}）。
     * path 类（外部绝对路径）不含该段，原样返回。
     */
    private static String rewriteSessionPath(String path, String oldSessionId, String newSessionId) {
        if (path == null || oldSessionId == null || oldSessionId.equals(newSessionId)) {
            return path;
        }
        return path
            .replace("/" + oldSessionId + "/", "/" + newSessionId + "/")
            .replace("\\" + oldSessionId + "\\", "\\" + newSessionId + "\\");
    }

    /**
     * artifacts 目录内的产物路径按 {@code <dataRoot>/artifacts/<sessionId>/} 前缀改写。
     * 不在该目录下的路径（外部项目文件）原样保留。
     */
    private static String rewriteArtifactPath(String path, String oldSessionId, String newSessionId) {
        if (path == null || path.isBlank()) {
            return path;
        }
        try {
            Path oldDir = SessionArtifactService.sessionArtifactsDir(oldSessionId);
            Path newDir = SessionArtifactService.sessionArtifactsDir(newSessionId);
            Path abs = Paths.get(path).toAbsolutePath().normalize();
            if (abs.startsWith(oldDir)) {
                return newDir.resolve(oldDir.relativize(abs)).toString();
            }
        } catch (RuntimeException e) {
            log.warn("[SessionFork] 产物路径改写失败 path={} cause={}", path, e.getMessage());
        }
        return path;
    }

    /**
     * {@code messages.user_attachments} 里的 contentId 是 attachments 自增主键，复制后会变；
     * 这里按映射替换，并同步改写 upload 文件的会话目录段。解析失败原样返回（fail-soft）。
     */
    private static String remapAttachmentJson(String raw, Map<Long, Long> attachmentIdMap,
                                              String oldSessionId, String newSessionId) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        try {
            JSONArray arr = JSON.parseArray(raw);
            if (arr == null) {
                return raw;
            }
            boolean changed = false;
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (!(item instanceof JSONObject o)) {
                    continue;
                }
                String contentId = o.getString("contentId");
                if (contentId != null && !contentId.isBlank()) {
                    try {
                        Long newId = attachmentIdMap.get(Long.parseLong(contentId));
                        if (newId != null) {
                            o.put("contentId", String.valueOf(newId));
                            changed = true;
                        }
                    } catch (NumberFormatException ignore) {
                        // 非数字 contentId（旧格式）→ 原样
                    }
                }
                String p = o.getString("path");
                String rewritten = rewriteSessionPath(p, oldSessionId, newSessionId);
                if (rewritten != null && !rewritten.equals(p)) {
                    o.put("path", rewritten);
                    changed = true;
                }
            }
            return changed ? arr.toJSONString() : raw;
        } catch (RuntimeException e) {
            log.warn("[SessionFork] userAttachments 重映射失败，原样复制 cause={}", e.getMessage());
            return raw;
        }
    }

    /** 复制 artifacts 会话目录 + 三个附件缓存会话目录（不存在则跳过）。 */
    private void copySessionFiles(String oldSessionId, String newSessionId, List<Path> createdDirs)
            throws IOException {
        Path artifactsSrc = SessionArtifactService.sessionArtifactsDir(oldSessionId);
        Path artifactsDst = SessionArtifactService.sessionArtifactsDir(newSessionId);
        copyDirIfExists(artifactsSrc, artifactsDst, createdDirs);
        for (String dirName : ATTACHMENT_CACHE_DIRS) {
            Path src = NexusaiDataRoots.sub(dirName).resolve(oldSessionId);
            if (!Files.isDirectory(src)) {
                // 数据根迁移（2026-11-12）前的历史附件可能仍在 configHome 下 → 回退尝试一次
                Path legacy = java.nio.file.Paths.get(
                    com.nexusai.application.agent.skill.NexusaiPaths.getAppConfigHomeDir(),
                    dirName).resolve(oldSessionId);
                if (Files.isDirectory(legacy)) {
                    src = legacy;
                }
            }
            Path dst = NexusaiDataRoots.sub(dirName).resolve(newSessionId);
            copyDirIfExists(src, dst, createdDirs);
        }
    }

    private static void copyDirIfExists(Path src, Path dst, List<Path> createdDirs) throws IOException {
        if (!Files.isDirectory(src)) {
            return;
        }
        Files.createDirectories(dst);
        createdDirs.add(dst);
        try (Stream<Path> walk = Files.walk(src)) {
            List<Path> entries = walk.sorted(Comparator.naturalOrder()).toList();
            for (Path s : entries) {
                Path target = dst.resolve(src.relativize(s));
                if (Files.isDirectory(s)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(s, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    private static void deleteQuietly(List<Path> dirs) {
        for (int i = dirs.size() - 1; i >= 0; i--) {
            Path dir = dirs.get(i);
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            } catch (IOException | RuntimeException e) {
                log.warn("[SessionFork] 补偿删除失败 dir={} cause={}", dir, e.getMessage());
            }
        }
    }

    private static String generateId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
