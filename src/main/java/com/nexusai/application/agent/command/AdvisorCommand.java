package com.nexusai.application.agent.command;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * `/advisor` 斜杠命令 handler · 对齐 CC commands/advisor.ts.
 *
 * <p>L1 语义: 配置 advisor 模型. 无参 → 显示当前状态; "off"/"unset" → 禁用;
 *            其他 → normalize + validate + 写入 AppState + UserSettings + 提示 base model 是否支持 advisor.
 *
 * <p>L2 契约 (5 Release Gate):
 * <ul>
 *   <li><b>A1</b>: handle(args, state) → CommandResult;3 个分支: 无参/取消/设置;
 *       AppState.advisorModel (String|undefined);normalize → API model string;validate → (valid, error);
 *       canUserConfigureAdvisor / modelSupportsAdvisor / isValidAdvisorModel 3 守卫.</li>
 *   <li><b>A2 Golden Trace</b>: 主链 — args trim → lowerCase → 分支:
 *       空 → 显示 (current / not set);"off" → 清 advisorModel + 写 userSettings;
 *       其他 → normalize → validate(valid=true) → isValidAdvisorModel → setAppState + updateSettingsForSource →
 *       若 baseModel 不支持 → 警告;否则 → 成功.</li>
 *   <li><b>A3</b>: 状态: AppState.advisorModel (Set → Unset);幂等 — 已是 normalizedModel → 不重复写.</li>
 *   <li><b>A4</b>: validate 返回 valid=false → error message;isValidAdvisorModel=false → 不能用作 advisor;
 *       "off" 时 prev=undefined → "Advisor already unset.";
 *       normalize 已对 baseModel 检查过 modelSupportsAdvisor (展示用).</li>
 *   <li><b>A5</b>: 真实场景 — 用户 `/advisor opus` → 写 AppState + UserSettings → 模型支持 → 成功;
 *       用户 `/advisor haiku` 在 opus 主 loop → haiku 不支持 advisor → 警告 + 仍写入.</li>
 * </ul>
 *
 * <p>L3 (Java idiom): TS `(args, context) => CommandResult` → Java `handle(args, AppStateView)`;
 *                    TS `context.getAppState()` → 注入式 Supplier;
 *                    TS `context.setAppState(s => ...)` → 注入式 Consumer&lt;UnaryOperator&gt;;
 *                    TS `updateSettingsForSource('userSettings', ...)` → 注入式 SettingsUpdater;
 *                    TS async `await validateModel` → Java 同步 (注入 validate 已是 sync).
 */
public final class AdvisorCommand {

    private static final Logger log = LoggerFactory.getLogger(AdvisorCommand.class);

    private final BooleanSupplier canUserConfigureAdvisor;
    private final ModelNormalizer normalizeModel;          // arg → API model string
    private final ModelParser parseUserSpecifiedModel;     // arg → internal model id
    private final ModelValidator validateModel;            // model → (valid, error)
    private final AdvisorModelFilter isValidAdvisorModel;
    private final SupportsAdvisorFn modelSupportsAdvisor;
    private final Supplier<String> defaultMainLoopModel;
    private final Supplier<AppState> stateReader;
    private final Consumer<UnaryOperator<AppState>> stateWriter;
    private final SettingsUpdater settingsUpdater;

    public AdvisorCommand(BooleanSupplier canUserConfigureAdvisor,
                           ModelNormalizer normalizeModel,
                           ModelParser parseUserSpecifiedModel,
                           ModelValidator validateModel,
                           AdvisorModelFilter isValidAdvisorModel,
                           SupportsAdvisorFn modelSupportsAdvisor,
                           Supplier<String> defaultMainLoopModel,
                           Supplier<AppState> stateReader,
                           Consumer<UnaryOperator<AppState>> stateWriter,
                           SettingsUpdater settingsUpdater) {
        this.canUserConfigureAdvisor = Objects.requireNonNull(canUserConfigureAdvisor);
        this.normalizeModel = Objects.requireNonNull(normalizeModel);
        this.parseUserSpecifiedModel = Objects.requireNonNull(parseUserSpecifiedModel);
        this.validateModel = Objects.requireNonNull(validateModel);
        this.isValidAdvisorModel = Objects.requireNonNull(isValidAdvisorModel);
        this.modelSupportsAdvisor = Objects.requireNonNull(modelSupportsAdvisor);
        this.defaultMainLoopModel = Objects.requireNonNull(defaultMainLoopModel);
        this.stateReader = Objects.requireNonNull(stateReader);
        this.stateWriter = Objects.requireNonNull(stateWriter);
        this.settingsUpdater = Objects.requireNonNull(settingsUpdater);
    }

    /** AppState 最小子集 — advisorModel 字段. */
    public record AppState(String mainLoopModel, String advisorModel) {
        public static final AppState EMPTY = new AppState(null, null);
    }

    /** Command 输出 (CC type:'text' value). */
    public record CommandResult(String type, String value) {
        public static CommandResult text(String value) { return new CommandResult("text", value); }
    }

    /** Model 验证结果. */
    public record ModelValidation(boolean valid, String error) {}

    @FunctionalInterface
    public interface BooleanSupplier { boolean getAsBoolean(); }

    @FunctionalInterface
    public interface ModelNormalizer { String normalize(String model); }

    @FunctionalInterface
    public interface ModelParser { String parse(String model); }

    @FunctionalInterface
    public interface ModelValidator { ModelValidation validate(String model); }

    @FunctionalInterface
    public interface AdvisorModelFilter { boolean isValidAdvisorModel(String model); }

    @FunctionalInterface
    public interface SupportsAdvisorFn { boolean supports(String model); }

    @FunctionalInterface
    public interface SettingsUpdater {
        void update(String source, String key, Object value);
    }

    /** CC advisor command — 主链. */
    public CommandResult handle(String args) {
        String arg = args == null ? "" : args.trim().toLowerCase();
        AppState state = stateReader.get();
        String baseModel = parseUserSpecifiedModel.parse(
            state.mainLoopModel() != null ? state.mainLoopModel() : defaultMainLoopModel.get());

        if (arg.isEmpty()) {
            // 显示当前状态
            String current = state.advisorModel();
            if (current == null) {
                return CommandResult.text(
                    "Advisor: not set\nUse \"/advisor <model>\" to enable (e.g. \"/advisor opus\").");
            }
            if (!modelSupportsAdvisor.supports(baseModel)) {
                return CommandResult.text(
                    "Advisor: " + current + " (inactive)\nThe current model (" + baseModel
                        + ") does not support advisors.");
            }
            return CommandResult.text(
                "Advisor: " + current
                    + "\nUse \"/advisor unset\" to disable or \"/advisor <model>\" to change.");
        }

        if ("unset".equals(arg) || "off".equals(arg)) {
            String prev = state.advisorModel();
            stateWriter.accept(s -> s.advisorModel() == null
                ? s
                : new AppState(s.mainLoopModel(), null));
            settingsUpdater.update("userSettings", "advisorModel", null);
            return CommandResult.text(prev != null
                ? "Advisor disabled (was " + prev + ")."
                : "Advisor already unset.");
        }

        // 设置新 advisor 模型
        String normalized = normalizeModel.normalize(arg);
        String resolved = parseUserSpecifiedModel.parse(arg);
        ModelValidation v = validateModel.validate(resolved);
        if (!v.valid()) {
            return CommandResult.text(v.error() != null
                ? "Invalid advisor model: " + v.error()
                : "Unknown model: " + arg + " (" + resolved + ")");
        }
        if (!isValidAdvisorModel.isValidAdvisorModel(resolved)) {
            return CommandResult.text(
                "The model " + arg + " (" + resolved + ") cannot be used as an advisor");
        }

        // 写 AppState (幂等: 相同值不写)
        stateWriter.accept(s -> normalized.equals(s.advisorModel())
            ? s
            : new AppState(s.mainLoopModel(), normalized));
        settingsUpdater.update("userSettings", "advisorModel", normalized);

        if (!modelSupportsAdvisor.supports(baseModel)) {
            return CommandResult.text(
                "Advisor set to " + normalized + ".\nNote: Your current model (" + baseModel
                    + ") does not support advisors. Switch to a supported model to use the advisor.");
        }
        return CommandResult.text("Advisor set to " + normalized + ".");
    }

    /** Command metadata. */
    public String name() { return "advisor"; }
    public String description() { return "Configure the advisor model"; }
    public String argumentHint() { return "[<model>|off]"; }
    public boolean isEnabled() { return canUserConfigureAdvisor.getAsBoolean(); }
    public boolean isHidden() { return !canUserConfigureAdvisor.getAsBoolean(); }
    public boolean supportsNonInteractive() { return true; }
}
