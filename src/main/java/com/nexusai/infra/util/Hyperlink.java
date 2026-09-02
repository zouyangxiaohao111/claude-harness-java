package com.nexusai.infra.util;

import java.util.function.BooleanSupplier;

public final class Hyperlink {
    public static final String OSC8_START = "[OSC8_START_PLACEHOLDER]";
    public static final String OSC8_END = "[OSC8_END_PLACEHOLDER]";
    public static final String DEFAULT_BLUE = "[BLUE_PLACEHOLDER]";
    public static final String DEFAULT_RESET = "[RESET_PLACEHOLDER]";

    private Hyperlink() {}

    public static String createHyperlink(
        String url, String content, BooleanSupplier supportsHyperlinks) {
        boolean hasSupport = supportsHyperlinks == null ? false : supportsHyperlinks.getAsBoolean();
        if (!hasSupport) return url;
        String displayText = (content == null || content.isEmpty()) ? url : content;
        return OSC8_START + url + OSC8_END + DEFAULT_BLUE + displayText + DEFAULT_RESET + OSC8_START + OSC8_END;
    }
}
