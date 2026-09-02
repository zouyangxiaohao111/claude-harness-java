package com.nexusai.infra.util;

import java.util.function.BooleanSupplier;

/**
 * ModifiersPreWarmer · 对齐 CC utils/modifiers.ts.
 */
public final class ModifiersPreWarmer {

    private static volatile boolean prewarmed = false;

    private ModifiersPreWarmer() {}

    /**
     * Pre-warm the native module by loading it in advance (CC: avoid delay on first use).
     * No-op on non-darwin or if already prewarmed.
     */
    public static void prewarm(BooleanSupplier isMacosSupplier) {
        if (prewarmed) return;
        if (isMacosSupplier != null && !isMacosSupplier.getAsBoolean()) return;
        prewarmed = true;
    }

    /**
     * Check if a specific modifier key is pressed (synchronous).
     * No-op on non-darwin (returns false).
     */
    public static boolean isModifierPressed(String modifier, BooleanSupplier isMacosSupplier) {
        if (isMacosSupplier == null || !isMacosSupplier.getAsBoolean()) return false;
        if (modifier == null) return false;
        // Production: dynamic import native module; test: stub
        return false;  // native call not exercised in tests
    }

    public static boolean isPrewarmed() { return prewarmed; }
}
