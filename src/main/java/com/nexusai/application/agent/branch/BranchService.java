package com.nexusai.application.agent.branch;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Branch (session fork) service · 对齐 CC commands/branch/branch.ts.
 *
 * <p>CC source: commands/branch/branch.ts (296 LOC).
 * 3 main functions:
 * - deriveFirstPrompt(message) → first user message collapsed
 * - createFork(customTitle?) → copies transcript with new sessionId
 * - getUniqueForkName(baseName) → handles "(Branch N)" collision
 */
public final class BranchService {

    private static final Logger log = LoggerFactory.getLogger(BranchService.class);

    public record ForkResult(
        String sessionId,
        String title,
        String forkPath,
        List<String> serializedMessages,
        List<String> contentReplacementRecords
    ) {}

    public record SerializedMessage(String type, Object content, String uuid, String timestamp) {}

    /** CC deriveFirstPrompt. */
    public static String deriveFirstPrompt(SerializedMessage firstUserMessage) {
        if (firstUserMessage == null) return "Branched conversation";
        Object content = firstUserMessage.content();
        if (content == null) return "Branched conversation";
        String raw = null;
        if (content instanceof String s) {
            raw = s;
        } else if (content instanceof List<?> list) {
            for (Object block : list) {
                if (block instanceof java.util.Map<?, ?> m
                    && "text".equals(m.get("type"))
                    && m.get("text") instanceof String s) {
                    raw = s;
                    break;
                }
            }
        }
        if (raw == null) return "Branched conversation";
        String trimmed = raw.replaceAll("\\s+", " ").trim();
        if (trimmed.isEmpty()) return "Branched conversation";
        return trimmed.substring(0, Math.min(100, trimmed.length()));
    }

    /** CC createFork (simplified). */
    public ForkResult createFork(String originalSessionId,
                                  String projectDir,
                                  String transcriptContent,
                                  String customTitle) {
        Objects.requireNonNull(originalSessionId);
        if (transcriptContent == null || transcriptContent.isEmpty()) {
            throw new IllegalStateException("No conversation to branch");
        }
        String newSessionId = java.util.UUID.randomUUID().toString();
        // [S2] 改经 seam（config-home 派生）· CC createFork 走 getTranscriptPath 同源布局；
        //   旧硬编码扁平路径 {projectDir}/{newSessionId}.jsonl 与 S2 迁 config-home 漂移。
        Path forkPath = com.nexusai.application.agent.tool.SessionStorage.getTranscriptPath(
            Paths.get(projectDir), newSessionId);
        String forkPathStr = forkPath != null ? forkPath.toString() : projectDir + "/" + newSessionId + ".jsonl";

        // Parse transcript lines (simplified)
        String[] lines = transcriptContent.split("\n");
        java.util.List<String> entries = new java.util.ArrayList<>();
        for (String line : lines) {
            if (!line.trim().isEmpty()) entries.add(line);
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("No messages to branch");
        }

        // Rewrite sessionId
        java.util.List<String> rewritten = new java.util.ArrayList<>();
        for (String e : entries) {
            rewritten.add(e.replace("\"sessionId\":\"" + originalSessionId + "\"",
                "\"sessionId\":\"" + newSessionId + "\""));
        }

        String firstPrompt = deriveFirstPrompt(null);
        return new ForkResult(newSessionId, customTitle != null ? customTitle : firstPrompt,
            forkPathStr, rewritten, java.util.List.of());
    }

    /** CC getUniqueForkName (handles "(Branch N)" collision). */
    public String getUniqueForkName(String baseName, java.util.function.Predicate<String> titleExists) {
        if (baseName == null || baseName.isEmpty()) baseName = "Branched conversation";
        String candidate = baseName + " (Branch)";
        if (!titleExists.test(candidate)) return candidate;
        int next = 2;
        while (titleExists.test(baseName + " (Branch " + next + ")")) next++;
        return baseName + " (Branch " + next + ")";
    }
}
