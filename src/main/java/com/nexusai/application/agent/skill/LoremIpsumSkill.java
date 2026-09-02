package com.nexusai.application.agent.skill;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /lorem-ipsum skill · 对齐 CC skills/bundled/loremIpsum.ts.
 *
 * <p>CC source: skills/bundled/loremIpsum.ts (282 LOC).
 * Ant-only /lorem-ipsum skill: generate filler text for long context testing.
 * - 200+ 1-token words (tested via API token counting)
 * - generateLoremIpsum(targetTokens) — random sentence/paragraph structure
 * - Cap at 500,000 tokens for safety
 * - Non-ant → no register
 */
public final class LoremIpsumSkill {

    private static final Logger log = LoggerFactory.getLogger(LoremIpsumSkill.class);
    public static final int MAX_TOKENS = 500_000;
    public static final int DEFAULT_TOKENS = 10_000;
    /** CC original: loremIpsum.ts:240 {@code name: 'lorem-ipsum'}. */
    public static final String NAME = "lorem-ipsum";
    /** CC original: loremIpsum.ts:243 {@code argumentHint: '[token_count]'}. */
    public static final String ARGUMENT_HINT = "[token_count]";
    /** CC original: loremIpsum.ts:242 {@code description}. */
    public static final String DESCRIPTION =
        "Generate filler text for long context testing. Specify token count as argument "
        + "(e.g., /lorem-ipsum 50000). Outputs approximately the requested number of tokens. Ant-only.";

    /** ~200 common 1-token English words. */
    public static final List<String> ONE_TOKEN_WORDS = List.of(
        "the", "a", "an", "I", "you", "he", "she", "it", "we", "they",
        "me", "him", "her", "us", "them", "my", "your", "his", "its", "our",
        "this", "that", "what", "who",
        "is", "are", "was", "were", "be", "been", "have", "has", "had",
        "do", "does", "did", "will", "would", "can", "could", "may", "might",
        "must", "shall", "should", "make", "made", "get", "got", "go", "went",
        "come", "came", "see", "saw", "know", "take", "think", "look", "want",
        "use", "find", "give", "tell", "work", "call", "try", "ask", "need",
        "feel", "seem", "leave", "put",
        "time", "year", "day", "way", "man", "thing", "life", "hand", "part",
        "place", "case", "point", "fact", "good", "new", "first", "last", "long",
        "great", "little", "own", "other", "old", "right", "big", "high", "small",
        "large", "next", "early", "young", "few", "public", "bad", "same", "able",
        "in", "on", "at", "to", "for", "of", "with", "from", "by", "about",
        "like", "through", "over", "before", "between", "under", "since", "without",
        "and", "or", "but", "if", "than", "because", "as", "until", "while", "so",
        "though", "both", "each", "when", "where", "why", "how",
        "not", "now", "just", "more", "also", "here", "there", "then", "only",
        "very", "well", "back", "still", "even", "much", "too", "such", "never",
        "again", "most", "once", "off", "away", "down", "out", "up",
        "test", "code", "data", "file", "line", "text", "word", "number", "system",
        "program", "set", "run", "value", "name", "type", "state", "end", "start"
    );

    private final BooleanSupplier isAntSupplier;

    public LoremIpsumSkill(BooleanSupplier isAntSupplier) {
        this.isAntSupplier = Objects.requireNonNull(isAntSupplier);
    }

    public boolean isAvailable() {
        return isAntSupplier.getAsBoolean();
    }

    /** Generate lorem ipsum text with approximately targetTokens tokens. */
    public String generateLoremIpsum(int targetTokens) {
        java.util.Random rng = new java.util.Random();
        int tokens = 0;
        StringBuilder result = new StringBuilder();
        while (tokens < targetTokens) {
            // Sentence: 10-20 words
            int sentenceLength = 10 + rng.nextInt(11);
            int wordsInSentence = 0;
            for (int i = 0; i < sentenceLength && tokens < targetTokens; i++) {
                String word = ONE_TOKEN_WORDS.get(rng.nextInt(ONE_TOKEN_WORDS.size()));
                result.append(word);
                tokens++;
                wordsInSentence++;
                if (i == sentenceLength - 1 || tokens >= targetTokens) {
                    result.append(". ");
                } else {
                    result.append(" ");
                }
            }
            // Paragraph break every ~5-8 sentences (20% chance per sentence)
            if (wordsInSentence > 0 && rng.nextDouble() < 0.2 && tokens < targetTokens) {
                result.append("\n\n");
            }
        }
        return result.toString().trim();
    }

    /** CC registerLoremIpsumSkill → returns PromptBlock list (caller registers). */
    public List<PromptBlock> handleCommand(String args) {
        if (!isAvailable()) return List.of();

        int parsed = parseInt(args);
        if (args != null && !args.trim().isEmpty() && (parsed <= 0 || parsed == Integer.MIN_VALUE)) {
            return List.of(PromptBlock.text(
                "Invalid token count. Please provide a positive number (e.g., /lorem-ipsum 10000)."));
        }
        int targetTokens = parsed > 0 ? parsed : DEFAULT_TOKENS;
        int capped = Math.min(targetTokens, MAX_TOKENS);
        if (capped < targetTokens) {
            String text = "Requested " + targetTokens + " tokens, but capped at "
                + MAX_TOKENS + " for safety.\n\n" + generateLoremIpsum(capped);
            return List.of(PromptBlock.text(text));
        }
        return List.of(PromptBlock.text(generateLoremIpsum(capped)));
    }

    /** Simple int parse (replacement for parseInt). */
    static int parseInt(String s) {
        if (s == null || s.trim().isEmpty()) return Integer.MIN_VALUE;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return Integer.MIN_VALUE; }
    }
}
