package com.nexusai.infra.util;

import java.util.List;
import java.util.Map;

/**
 * BashSpecPyright · 对齐 CC utils/bash/specs/pyright.ts.
 *
 * <p>Re-export wrapper for the pyright CommandSpec from PyrightCommandSpec.
 * Inherits the full Option list (--help, --version, --watch, --project, --createstub,
 * --typeshedpath, --verifytypes, --ignoreexternal, --pythonpath, --pythonplatform,
 * --pythonversion, --venvpath, --outputjson, --verbose, --stats, --dependencies,
 * --level, --skipunannotated, --warnings, --threads).
 */
public final class BashSpecPyright {

    private BashSpecPyright() {}

    public static PyrightCommandSpec.CommandSpec get() {
        return PyrightCommandSpec.get();
    }

    /** Test access: options list. */
    public static List<PyrightCommandSpec.Option> options() {
        return PyrightCommandSpec.get().options();
    }

    /** Test access: args record. */
    public static PyrightCommandSpec.Arg args() {
        return PyrightCommandSpec.get().args();
    }

    /** Test access: name. */
    public static String name() {
        return PyrightCommandSpec.get().name();
    }

    /** Test access: description. */
    public static String description() {
        return PyrightCommandSpec.get().description();
    }
}
