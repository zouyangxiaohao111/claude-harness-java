package com.nexusai.application.agent.computeruse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * UI Automation 服务实现（PowerShell 子进程 + .NET UIAutomation 程序集）·
 * computer-use Phase 2b 的 {@code click_element} / {@code type_into_element} 底座。
 *
 * <h2>WHY 用 PowerShell 而不是 JNA（路径 (b)）</h2>
 * <p>UI Automation 是 <b>COM API</b>，需要 {@code UIAutomationClient} / {@code UIAutomationTypes} /
 * {@code WindowsBase} 三个 .NET 程序集。用 JNA 直调需要手写 COM vtable 绑定（复杂、易错、
 * 且要处理 {@code VARIANT} 封送）⇒ 本批<b>先做 CCB 的做法</b>
 * （进程外调 PowerShell 加载程序集），把 JNA-COM 留作后续优化（200–500ms 的 shell-out 开销）。
 *
 * <h2>脚本来源</h2>
 * <p>快照脚本逐字移植自 CCB
 * {@code src/utils/computerUse/win32/accessibilitySnapshot.ts:67-129}（深度 4 + 交互型白名单 +
 * 不可见元素丢弃），只是把「输出 JSON」与「格式化文本」拆开：<b>PowerShell 只负责取树</b>，
 * <b>文本格式化在 Java 侧</b>（{@link ComputerUseAccessibilitySnapshot#formatForModel}）⇒
 * 格式逻辑可被纯单测覆盖。
 *
 * <h2>⚠️ 显式失败（规则十二）</h2>
 * <p>CCB 的脚本用 {@code catch { Write-Output '[]' }} 把所有异常吞成空树
 * （{@code accessibilitySnapshot.ts:126-128}）—— 那会让「UIA 通道坏了」和「窗口确实没有元素」
 * 无法区分。本实现<b>把异常转成非零退出码</b> ⇒ {@link ComputerUsePowerShell} 抛异常 ⇒
 * 工具层把它转成模型可见的明确错误。
 */
public final class Win32UiaService implements ComputerUseUiaService {

    private static final Logger log = LoggerFactory.getLogger(Win32UiaService.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 默认遍历深度 · CCB {@code accessibilitySnapshot.ts:46}（{@code maxDepth = 4}）。 */
    public static final int DEFAULT_MAX_DEPTH = 4;

    private final ComputerUsePowerShell powerShell;

    /** 默认构造（60s 超时）。 */
    public Win32UiaService() {
        this(new ComputerUsePowerShell());
    }

    /**
     * @param powerShell PowerShell 执行器
     */
    public Win32UiaService(ComputerUsePowerShell powerShell) {
        this.powerShell = powerShell == null ? new ComputerUsePowerShell() : powerShell;
    }

    @Override
    public ComputerUseAccessibilitySnapshot captureSnapshot(long hwnd, int maxDepth) {
        String raw = powerShell.run(snapshotScript(hwnd, maxDepth));
        if (raw == null || raw.isEmpty()) {
            // 脚本成功但无输出 ⇒ 视作「没有元素」（CCB 的 '[]' 分支）
            return ComputerUseAccessibilitySnapshot.empty();
        }
        JsonNode root;
        try {
            root = JSON.readTree(raw);
        } catch (Exception e) {
            // 解析失败必须明确报错（不能静默当空树）
            throw new IllegalStateException(
                    "[computer-use] UIA 快照 JSON 解析失败: " + e.getMessage()
                            + "（原始输出前 200 字符: " + preview(raw) + "）", e);
        }
        List<ComputerUseAccessibilityNode> nodes = parseNodes(root);
        String text = ComputerUseAccessibilitySnapshot.formatForModel(nodes);
        return new ComputerUseAccessibilitySnapshot(text, nodes, System.currentTimeMillis());
    }

    @Override
    public boolean invokeElement(long hwnd, String automationId) {
        if (automationId == null || automationId.isEmpty()) {
            return false;
        }
        String raw = powerShell.run(invokeScript(hwnd, automationId));
        return "true".equalsIgnoreCase(raw.trim());
    }

    @Override
    public boolean setElementValue(long hwnd, String automationId, String value) {
        if (automationId == null || automationId.isEmpty()) {
            return false;
        }
        String raw = powerShell.run(setValueScript(hwnd, automationId, value));
        return "true".equalsIgnoreCase(raw.trim());
    }

    /**
     * JSON 树 → 节点列表（CCB {@code accessibilitySnapshot.ts:147-161} 的 {@code parseNode}）。
     *
     * <p><b>⚠️ {@code c} 字段的三态</b>（PowerShell {@code ConvertTo-Json} 的实际行为）：
     * <ul>
     *   <li><b>不存在</b> ⇒ 无子节点；</li>
     *   <li><b>单元素数组</b> ⇒ PowerShell 会把它<b>塌缩成对象</b>（不是数组！）；</li>
     *   <li><b>多元素数组</b> ⇒ 真正的数组。</li>
     * </ul>
     * ⇒ 解析必须同时吃「对象」与「数组」两种形状（CCB 的
     * {@code parseNode} 同款：{@code Array.isArray(raw.c) ? raw.c.map(parseNode) : [parseNode(raw.c)]}）。
     *
     * <p>⛔ <b>不能用 {@code raw.path("c")} 判缺失</b>：Jackson 的
     * {@code path()} 对缺失字段返回 {@code MissingNode}（<b>不是 null</b>），
     * 而 {@code MissingNode.path("c")} 又返回它自己 ⇒ 递归永不到底 ⇒ {@code StackOverflowError}。
     * ⇒ 必须用 {@link JsonNode#has(String)} 判存在。
     */
    static List<ComputerUseAccessibilityNode> parseNodes(JsonNode root) {
        List<ComputerUseAccessibilityNode> out = new ArrayList<>();
        if (root == null || root.isNull() || root.isMissingNode()) {
            return out;
        }
        if (root.isArray()) {
            for (JsonNode child : root) {
                out.add(parseNode(child));
            }
        } else {
            out.add(parseNode(root));
        }
        return out;
    }

    /** 单个 JSON 节点 → 节点对象（CCB {@code accessibilitySnapshot.ts:147-161}）。 */
    static ComputerUseAccessibilityNode parseNode(JsonNode raw) {
        JsonNode children = raw.has("c") && !raw.get("c").isNull() ? raw.get("c") : null;
        return new ComputerUseAccessibilityNode(
                raw.path("role").asText(""),
                raw.path("name").asText(""),
                raw.path("id").asText(""),
                raw.path("x").asInt(0),
                raw.path("y").asInt(0),
                raw.path("w").asInt(0),
                raw.path("h").asInt(0),
                // CCB: enabled: raw.on !== false —— 缺字段即视为可用
                raw.path("on").asBoolean(true),
                raw.hasNonNull("v") ? raw.get("v").asText() : null,
                parseNodes(children));
    }

    /** 输出预览（错误信息里截断）。 */
    private static String preview(String raw) {
        String s = raw.replaceAll("\\s+", " ").trim();
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    /**
     * 快照脚本 · 逐字移植 CCB {@code accessibilitySnapshot.ts:67-129}。
     *
     * <p>与 CCB 的唯一差异：{@code catch} 分支改成<b>写 stderr + 非零退出</b>
     * （CCB 是 {@code Write-Output '[]'} 吞异常）⇒ 解析失败可被上层看见。
     *
     * @param hwnd    窗口句柄
     * @param maxDepth 最大深度
     * @return 脚本文本
     */
    static String snapshotScript(long hwnd, int maxDepth) {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                Add-Type -AssemblyName WindowsBase
                $interactiveTypes = @(
                  'Button','Edit','ComboBox','CheckBox','RadioButton',
                  'MenuItem','Menu','MenuBar','Link','Slider','Spinner',
                  'Tab','TabItem','List','ListItem','Tree','TreeItem',
                  'DataGrid','DataItem','Document','ScrollBar','ToolBar',
                  'SplitButton','ToggleButton','Hyperlink'
                )
                function Is-Interactive($ct) {
                  $typeName = $ct -replace 'ControlType\\.', ''
                  return $interactiveTypes -contains $typeName
                }
                function Get-Tree($el, $depth, $maxDepth) {
                    if ($depth -ge $maxDepth) { return @() }
                    $result = @()
                    $children = $el.FindAll(
                        [System.Windows.Automation.TreeScope]::Children,
                        [System.Windows.Automation.Condition]::TrueCondition)
                    foreach ($child in $children) {
                        $ct = $child.Current.ControlType.ProgrammaticName
                        $typeName = $ct -replace 'ControlType\\.', ''
                        $name = [string]$child.Current.Name
                        $autoId = [string]$child.Current.AutomationId
                        $rect = $child.Current.BoundingRectangle
                        $enabled = $child.Current.IsEnabled
                        if ($rect.Width -le 0 -or $rect.Height -le 0) { continue }
                        if ($rect.X -lt -10000) { continue }
                        $val = $null
                        try {
                            $vp = $child.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                            if ($vp -ne $null) { $val = $vp.Current.Value }
                        } catch {}
                        $isInteractive = Is-Interactive $ct
                        $sub = Get-Tree $child ($depth + 1) $maxDepth
                        if ($isInteractive -or $sub.Count -gt 0) {
                            $node = @{
                                role = $typeName
                                name = $name
                                id = $autoId
                                x = [int]$rect.X; y = [int]$rect.Y
                                w = [int]$rect.Width; h = [int]$rect.Height
                                on = $enabled
                            }
                            if ($val -ne $null -and $val -ne '') { $node['v'] = $val }
                            if ($sub.Count -gt 0) { $node['c'] = $sub }
                            $result += $node
                        }
                    }
                    return $result
                }
                try {
                    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new([long]%d))
                    if ($root -eq $null) { Write-Output '[]'; exit 0 }
                    $tree = Get-Tree $root 0 %d
                    if ($tree -eq $null -or $tree.Count -eq 0) {
                        Write-Output '[]'
                    } else {
                        $tree | ConvertTo-Json -Depth 20 -Compress
                    }
                } catch {
                    Write-Error $_.Exception.Message
                    exit 1
                }
                """.formatted(hwnd, maxDepth);
    }

    /**
     * InvokePattern 点击脚本 · 对齐 CCB {@code win32/uiAutomation.ts:252-275}。
     *
     * <p>与 CCB 的差异：<b>按句柄定位窗口</b>（{@code AutomationElement::FromHandle}），
     * 而不是 CCB 的「按标题在 UIA 根节点里查」（同名窗口会误命中）。
     *
     * @param hwnd        窗口句柄
     * @param automationId 元素的 automationId
     * @return 脚本文本
     */
    static String invokeScript(long hwnd, String automationId) {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                Add-Type -AssemblyName WindowsBase
                try {
                    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new([long]%d))
                    if ($root -eq $null) { Write-Output 'false'; exit 0 }
                    $cond = [System.Windows.Automation.PropertyCondition]::new(
                      [System.Windows.Automation.AutomationElement]::AutomationIdProperty, '%s')
                    $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
                    if ($el -eq $null) { Write-Output 'false'; exit 0 }
                    try {
                        $ip = $el.GetCurrentPattern([System.Windows.Automation.InvokePattern]::Pattern)
                        $ip.Invoke()
                        Write-Output 'true'
                    } catch {
                        Write-Output 'false'
                    }
                } catch {
                    Write-Error $_.Exception.Message
                    exit 1
                }
                """.formatted(hwnd, escapePs(automationId));
    }

    /**
     * ValuePattern 设值脚本 · 对齐 CCB {@code win32/uiAutomation.ts:288-313}。
     *
     * @param hwnd        窗口句柄
     * @param automationId 元素的 automationId
     * @param value       要写入的值
     * @return 脚本文本
     */
    static String setValueScript(long hwnd, String automationId, String value) {
        return """
                $ErrorActionPreference = 'Stop'
                Add-Type -AssemblyName UIAutomationClient
                Add-Type -AssemblyName UIAutomationTypes
                Add-Type -AssemblyName WindowsBase
                try {
                    $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]::new([long]%d))
                    if ($root -eq $null) { Write-Output 'false'; exit 0 }
                    $cond = [System.Windows.Automation.PropertyCondition]::new(
                      [System.Windows.Automation.AutomationElement]::AutomationIdProperty, '%s')
                    $el = $root.FindFirst([System.Windows.Automation.TreeScope]::Descendants, $cond)
                    if ($el -eq $null) { Write-Output 'false'; exit 0 }
                    try {
                        $vp = $el.GetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern)
                        $vp.SetValue('%s')
                        Write-Output 'true'
                    } catch {
                        Write-Output 'false'
                    }
                } catch {
                    Write-Error $_.Exception.Message
                    exit 1
                }
                """.formatted(hwnd, escapePs(automationId), escapePs(value));
    }

    /**
     * PowerShell 单引号字符串转义（{@code '} ⇒ {@code ''}）。
     *
     * @param s 原串
     * @return 转义后的串
     */
    static String escapePs(String s) {
        return s == null ? "" : s.replace("'", "''");
    }
}
