package com.nexusai.application.agent.skill;

import com.nexusai.model.command.Command;
import com.nexusai.model.command.CommandLoadedFrom;
import com.nexusai.model.command.CommandSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内置捆绑技能注册中心 · 对齐 CC skills/bundledSkills.ts
 *
 * <h2>CC 对齐</h2>
 * <table>
 *   <tr><th>CC 函数</th><th>Java 方法</th></tr>
 *   <tr><td>{@code registerBundledSkill(definition)}</td><td>{@link #register(Command)}</td></tr>
 *   <tr><td>{@code getBundledSkills()}</td><td>{@link #getAll()}</td></tr>
 *   <tr><td>{@code clearBundledSkills()}</td><td>{@link #clear()}</td></tr>
 * </table>
 *
 * <p>捆绑技能在应用启动时注册（通过 {@code @PostConstruct} 或
 * {@link com.nexusai.domain.command.CommandService} 导入）。
 * 这些技能编译在 classpath 中，所有用户可用。
 */
public class BundledSkills {

    /** 线程安全的内部注册表 · 对齐 CC bundledSkills.ts:44 */
    private static final List<Command> registry = new CopyOnWriteArrayList<>();

    private BundledSkills() {}

    /**
     * 注册一个捆绑技能 · 对齐 CC registerBundledSkill()
     *
     * <p>P3-9 01-1 / DEL-03：不再设 {@code setBuiltin(TRUE)} —— CC registerBundledSkill 无 builtin 字段
     * （bundledSkills.ts:75-98），'builtin' 仅是 source 枚举值之一；「不可删」语义由
     * source==BUNDLED 表达（{@link CommandSource#isSystem()}，CommandService:287 删除守卫按 source 判定）。
     */
    public static void register(Command skill) {
        skill.setSource(CommandSource.BUNDLED);       // CC bundledSkills.ts:88 source: 'bundled'
        skill.setLoadedFrom(CommandLoadedFrom.BUNDLED); // CC bundledSkills.ts:89 loadedFrom: 'bundled'
        registry.add(skill);
    }

    /**
     * 获取所有已注册的捆绑技能（防御性拷贝）· 对齐 CC getBundledSkills()
     */
    public static List<Command> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(registry));
    }

    /**
     * 清空注册表（仅用于测试）· 对齐 CC clearBundledSkills()
     */
    public static void clear() {
        registry.clear();
    }

    /**
     * 获取已注册数量
     */
    public static int count() {
        return registry.size();
    }
}
