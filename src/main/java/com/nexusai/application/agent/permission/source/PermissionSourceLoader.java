package com.nexusai.application.agent.permission.source;

import com.nexusai.application.agent.permission.PermissionRule;
import com.nexusai.application.agent.permission.PermissionRuleSource;

import java.util.List;

import com.nexusai.application.agent.permission.PermissionMode;
import com.nexusai.application.agent.permission.ToolPermissionContext;

/**
 * 权限规则 source loader 接口 · 对齐 CC {@code utils/permissions/permissionsLoader.ts}
 *
 * <h2>实施现状 (8 个 source, s03 P2 #3 修补完成)</h2>
 * <ul>
 *   <li><b>3 editable disk source</b>
 *     <ul>
 *       <li>{@code UserSettingsLoader} — {@code ~/.nexusai/settings.json}</li>
 *       <li>{@code ProjectSettingsLoader} — {@code <project>/.nexusai/settings.json}</li>
 *       <li>{@code LocalSettingsLoader} — {@code <project>/.nexusai/settings.local.json}</li>
 *     </ul>
 *   </li>
 *   <li><b>2 read-only disk source</b>
 *     <ul>
 *       <li>{@code FlagSettingsLoader} — {@code --settings} CLI flag <b>(web 系统不实装, 永远 empty)</b></li>
 *       <li>{@code PolicySettingsLoader} — 企业 managed policy</li>
 *     </ul>
 *   </li>
 *   <li><b>1 runtime source</b>
 *     <ul>
 *       <li>{@code CliArgSource} — {@code --allowed-tools} / {@code --disallowed-tools}
 *           <b>(web 系统不实装, 永远 empty)</b></li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h2>[s03 P2 #3 修补] load(String) 重载</h2>
 * <p>原接口 {@link #load()} 是全局的（不感知 session）；加 {@link #load(String)} default method，
 * 向后兼容所有只读 loader（自动转调 {@code load()}）。
 *
 * <p><b>[P11d 2026-09-16] 3 个 editable disk loader（User/Project/Local）现覆写
 * {@link #load(String)}</b> —— 项目级（project/local）按会话解析项目根，不再共用后端启动目录：
 * <ul>
 *   <li>{@code LocalSettingsLoader} / {@code ProjectSettingsLoader}：会话非空 ⇒
 *       {@code CwdResolution.getProjectRoot(sessionId)}（对齐 CC {@code getProjectRoot()}）；
 *       会话为空/哨兵 ⇒ 命名无会话出口（进程 {@code user.dir}，见
 *       {@code CwdResolution.getOriginalCwdLayerForNonSession()}）。</li>
 *   <li>{@code UserSettingsLoader}：<b>用户级，不感知会话</b>（{@code ~/.{appName}/settings.json}
 *       与 sessionId 无关）⇒ 覆写仅为形态一致，行为与 {@code load()} 逐字节相同。</li>
 * </ul>
 * 只读 loader（Policy/Flag/CliArg）不覆写，仍等价于 {@code load()}。
 *
 * <h2>设计哲学</h2>
 * <ul>
 *   <li><b>无状态</b>：每次 {@link #load(UUID)} 重新读；Spring 单例 + 缓存交给上游。
 *       这样可以保证"修改 settings.json 后下次构造 context 即生效"。</li>
 *   <li><b>异常 lenient</b>：load 失败返回空 list，<b>不抛异常</b>。</li>
 *   <li><b>顺序确定</b>：list 输出按文件读入顺序（即 settings.json 中数组顺序）。</li>
 *   <li><b>无副作用</b>：{@code load(UUID)} 不修改任何状态；只读。</li>
 * </ul>
 *
 * @see PermissionRuleSource
 * @see PermissionRule
 * @see com.nexusai.application.agent.permission.PermissionContextBuilder
 */
public interface PermissionSourceLoader {


    /**
     * 此 loader 对应的 source 类型。
     *
     * <p>对应 settings.json 中的 bucket（如 {@link PermissionRuleSource#USER_SETTINGS}
     * 对应 {@code ~/.nexusai/settings.json}）。
     *
     * @return source 枚举值（非 null）
     */
    PermissionRuleSource source();

    /**
     * 加载该 source 的所有规则（[s03 P2 #3] 无 session 上下文的全局 load）。
     *
     * <p>保留向后兼容：所有 disk-based loader (User/Project/Local/Policy/Flag)
     * 和 runtime loader (CliArg/Session) 的 {@code load()} 都不感知 session，
     * 因此 default {@link #load(UUID)} 实现就是调用本方法。
     *
     * <h3>行为契约</h3>
     * <ul>
     *   <li>文件不存在 → 返回空 list（<b>不抛异常</b>）</li>
     *   <li>文件格式错误（JSON 损坏）→ 返回空 list + 记录 warn 日志（不抛异常）</li>
     *   <li>permissions 字段缺失或非 object → 返回空 list</li>
     *   <li>任一 bucket 缺失（仅 allow 没 deny/ask）→ 返回部分 list</li>
     *   <li>rule 字符串无法解析（{@code PermissionRuleValueParser.parse} 返回 null）
     *       → 跳过该条目（其余正常解析的条目仍返回）</li>
     * </ul>
     *
     * @return 规则列表（按文件中顺序；可能为空；不可变 view）
     */
    List<PermissionRule> load();

    /**
     * <b>[s03 P2 #3 新增]</b> 加载指定 session 的规则。
     *
     * <p>对齐 CC session-scoped source 语义：按 {@code sessionId} 解析该会话的项目根
     * （项目级 source 的文件位置依赖它）。default 实现转调 {@link #load()}
     * （只读 loader 不感知 session，返回全部规则）。
     *
     * <p>PermissionContextBuilder 对所有 loader 调用本方法（见该类 {@code :355}）。
     *
     * <p>[session-id-short] sessionId 统一 short（sess-xxx）。
     *
     * @param sessionId 会话 ID（short；null/空白/{@link com.nexusai.common.SessionKeys#NO_SESSION}
     *                  哨兵 ⇒ 「本环境确无会话」命名出口，见 3 个 editable loader 的覆写）
     * @return 该 session 的规则列表（可能为空）
     */
    default List<PermissionRule> load(String sessionId) {
        return load();  // [s03 P2 #3] 向后兼容 — 只读 loader 不感知 session
    }

    // ────────────────────────────────────────────────────────────────────────
    // 增量写盘能力（仅 3 个 editable disk source 实现；对齐 CC EditableSettingSource）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * 读取 {@code permissions.<field>} 原始字符串数组（增量写盘前读现有桶内容）。
     *
     * <p>仅 3 个 editable loader（User/Project/Local）override。read-only loader
     * （Policy/Flag/CliArg/Command/Session）不支持写盘，default 抛 {@link UnsupportedOperationException}。
     *
     * <p><b>[P11d] 读与写共用同一个 {@code sessionId} 解析出的<b>同一个文件</b></b> ——
     * 这是本批最关键的不变量（只改一侧会让「界面显示已保存、agent 运行期不认」静默失效）。
     *
     * @param field     permissions 下的桶名（{@code allow/deny/ask/additionalDirectories}）
     * @param sessionId 会话 ID（short；解析项目级 source 的文件位置）
     * @return 原始字符串列表（可能为空）
     */
    default List<String> readPermissionsStringArray(String field, String sessionId) {
        throw new UnsupportedOperationException(
            source() + " 是只读 source，不支持读取权限桶用于写盘");
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 数组（整体替换）。
     *
     * @param field     permissions 下的桶名
     * @param values    新数组值（可为空 → 写 {@code []}）
     * @param sessionId 会话 ID（short；解析项目级 source 的文件位置，须与读侧同一值）
     */
    default void savePermissionsField(String field, List<String> values, String sessionId) {
        throw new UnsupportedOperationException(
            source() + " 是只读 source，不支持写盘");
    }

    /**
     * 单字段 merge 写 {@code permissions.<field>} 字符串值（如 {@code defaultMode}）。
     *
     * @param field     permissions 下的字段名
     * @param value     新字符串值
     * @param sessionId 会话 ID（short；解析项目级 source 的文件位置，须与读侧同一值）
     */
    default void savePermissionsValue(String field, String value, String sessionId) {
        throw new UnsupportedOperationException(
            source() + " 是只读 source，不支持写盘");
    }
}
