package com.nexusai.application.agent.browser;

import com.nexusai.application.agent.tool.Tool;

import java.util.ArrayList;
import java.util.List;

/**
 * nexusai-in-chrome 浏览器工具注册中心 · 对齐 CCB {@code browserTools.ts}（547 行，18 个 BROWSER_TOOLS）。
 *
 * <p>CC/CCB 的 nexusai-in-chrome = 浏览器自动化 MCP（Chrome 扩展执行，经 Native Host 桥接）。Java
 * Web 架构：自研 Chrome 扩展 → WebSocket → Java 后端。本类承载 <b>工具面</b> —— 18 个工具定义
 * （name + description + inputSchema 逐字对齐 CCB），经 {@link #createTools(BrowserChannel)}
 * 构建为 18 个 {@link BrowserMcpTool} 实例，注册进 {@link com.nexusai.application.agent.tool.ToolRegistry}
 * 后模型可见可调（tools 列表含 {@code mcp__nexusai-in-chrome__*}）。
 *
 * <p>工具前缀对齐 {@link com.nexusai.application.agent.skill.NexusaiInChromeSkill#TOOL_PREFIX}
 * = {@code "mcp__nexusai-in-chrome__"}（skill 提示词激活后才使用这些工具）。
 *
 * <p><b>信任边界（规则九）</b>：description / inputSchema 逐字来自 CCB browserTools.ts
 * （已读真源），不参考任何二手注释；JavaDoc 标注 CCB 行号供审计复验。
 */
public final class BrowserToolRegistry {

    /** 工具名前缀 · 对齐 {@code NexusaiInChromeSkill.TOOL_PREFIX} = "mcp__nexusai-in-chrome__"（NexusaiInChromeSkill.java:50）。 */
    public static final String TOOL_PREFIX = "mcp__nexusai-in-chrome__";

    private BrowserToolRegistry() {
    }

    /**
     * 18 个浏览器工具定义 · 逐字对齐 CCB browserTools.ts。
     *
     * <p>readOnly / concurrencySafe 按任务契约设置：读类工具（read_page/find/get_page_text/
     * read_console_messages/read_network_requests/shortcuts_list/tabs_context_mcp）true；
     * 写类工具（javascript_tool/form_input/computer/navigate/resize_window/gif_creator/
     * upload_image/tabs_create_mcp/update_plan/shortcuts_execute/switch_browser）false。
     */
    public static final List<BrowserToolSpec> SPECS = List.of(
        new BrowserToolSpec(
            "javascript_tool",
            """
            Execute JavaScript code in the context of the current page. The code runs in the page's context and can interact with the DOM, window object, and page variables. Returns the result of the last expression or any thrown errors. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "description": "Must be set to 'javascript_exec'"
                },
                "text": {
                  "type": "string",
                  "description": "The JavaScript code to execute. The code will be evaluated in the page context. The result of the last expression will be returned automatically. Do NOT use 'return' statements - just write the expression you want to evaluate (e.g., 'window.myData.value' not 'return window.myData.value'). You can access and modify the DOM, call page functions, and interact with page variables."
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to execute the code in. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["action", "text", "tabId"]
            }""",
            "browserTools.ts:2-26",
            false, false),

        new BrowserToolSpec(
            "read_page",
            """
            Get an accessibility tree representation of elements on the page. By default returns all elements including non-visible ones. Output is limited to 50000 characters by default. If the output exceeds this limit, you will receive an error asking you to specify a smaller depth or focus on a specific element using ref_id. Optionally filter for only interactive elements. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "filter": {
                  "type": "string",
                  "enum": ["interactive", "all"],
                  "description": "Filter elements: \\"interactive\\" for buttons/links/inputs only, \\"all\\" for all elements including non-visible ones (default: all elements)"
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to read from. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                },
                "depth": {
                  "type": "number",
                  "description": "Maximum depth of the tree to traverse (default: 15). Use a smaller depth if output is too large."
                },
                "ref_id": {
                  "type": "string",
                  "description": "Reference ID of a parent element to read. Will return the specified element and all its children. Use this to focus on a specific part of the page when output is too large."
                },
                "max_chars": {
                  "type": "number",
                  "description": "Maximum characters for output (default: 50000). Set to a higher value if your client can handle large outputs."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:28-63",
            true, true),

        new BrowserToolSpec(
            "find",
            """
            Find elements on the page using natural language. Can search for elements by their purpose (e.g., "search bar", "login button") or by text content (e.g., "organic mango product"). Returns up to 20 matching elements with references that can be used with other tools. If more than 20 matches exist, you'll be notified to use a more specific query. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "query": {
                  "type": "string",
                  "description": "Natural language description of what to find (e.g., \\"search bar\\", \\"add to cart button\\", \\"product title containing organic\\")"
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to search in. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["query", "tabId"]
            }""",
            "browserTools.ts:65-84",
            true, true),

        new BrowserToolSpec(
            "form_input",
            """
            Set values in form elements using element reference ID from the read_page tool. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "ref": {
                  "type": "string",
                  "description": "Element reference ID from the read_page tool (e.g., \\"ref_1\\", \\"ref_2\\")"
                },
                "value": {
                  "type": ["string", "boolean", "number"],
                  "description": "The value to set. For checkboxes use boolean, for selects use option value or text, for other inputs use appropriate string/number"
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to set form value in. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["ref", "value", "tabId"]
            }""",
            "browserTools.ts:86-110",
            false, false),

        new BrowserToolSpec(
            "computer",
            """
            Use a mouse and keyboard to interact with a web browser, and take screenshots. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.
            * Whenever you intend to click on an element like an icon, you should consult a screenshot to determine the coordinates of the element before moving the cursor.
            * If you tried clicking on a program or link but it failed to load, even after waiting, try adjusting your click location so that the tip of the cursor visually falls on the element that you want to click.
            * Make sure to click any buttons, links, icons, etc with the cursor tip in the center of the element. Don't click boxes on their edges unless asked.""",
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["left_click", "right_click", "type", "screenshot", "wait", "scroll", "key", "left_click_drag", "double_click", "triple_click", "zoom", "scroll_to", "hover"],
                  "description": "The action to perform:\\n* `left_click`: Click the left mouse button at the specified coordinates.\\n* `right_click`: Click the right mouse button at the specified coordinates to open context menus.\\n* `double_click`: Double-click the left mouse button at the specified coordinates.\\n* `triple_click`: Triple-click the left mouse button at the specified coordinates.\\n* `type`: Type a string of text.\\n* `screenshot`: Take a screenshot of the screen.\\n* `wait`: Wait for a specified number of seconds.\\n* `scroll`: Scroll up, down, left, or right at the specified coordinates.\\n* `key`: Press a specific keyboard key.\\n* `left_click_drag`: Drag from start_coordinate to coordinate.\\n* `zoom`: Take a screenshot of a specific region for closer inspection.\\n* `scroll_to`: Scroll an element into view using its element reference ID from read_page or find tools.\\n* `hover`: Move the mouse cursor to the specified coordinates or element without clicking. Useful for revealing tooltips, dropdown menus, or triggering hover states."
                },
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y): The x (pixels from the left edge) and y (pixels from the top edge) coordinates. Required for `left_click`, `right_click`, `double_click`, `triple_click`, and `scroll`. For `left_click_drag`, this is the end position."
                },
                "text": {
                  "type": "string",
                  "description": "The text to type (for `type` action) or the key(s) to press (for `key` action). For `key` action: Provide space-separated keys (e.g., \\"Backspace Backspace Delete\\"). Supports keyboard shortcuts using the platform's modifier key (use \\"cmd\\" on Mac, \\"ctrl\\" on Windows/Linux, e.g., \\"cmd+a\\" or \\"ctrl+a\\" for select all)."
                },
                "duration": {
                  "type": "number",
                  "minimum": 0,
                  "maximum": 30,
                  "description": "The number of seconds to wait. Required for `wait`. Maximum 30 seconds."
                },
                "scroll_direction": {
                  "type": "string",
                  "enum": ["up", "down", "left", "right"],
                  "description": "The direction to scroll. Required for `scroll`."
                },
                "scroll_amount": {
                  "type": "number",
                  "minimum": 1,
                  "maximum": 10,
                  "description": "The number of scroll wheel ticks. Optional for `scroll`, defaults to 3."
                },
                "start_coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 2,
                  "maxItems": 2,
                  "description": "(x, y): The starting coordinates for `left_click_drag`."
                },
                "region": {
                  "type": "array",
                  "items": { "type": "number" },
                  "minItems": 4,
                  "maxItems": 4,
                  "description": "(x0, y0, x1, y1): The rectangular region to capture for `zoom`. Coordinates define a rectangle from top-left (x0, y0) to bottom-right (x1, y1) in pixels from the viewport origin. Required for `zoom` action. Useful for inspecting small UI elements like icons, buttons, or text."
                },
                "repeat": {
                  "type": "number",
                  "minimum": 1,
                  "maximum": 100,
                  "description": "Number of times to repeat the key sequence. Only applicable for `key` action. Must be a positive integer between 1 and 100. Default is 1. Useful for navigation tasks like pressing arrow keys multiple times."
                },
                "ref": {
                  "type": "string",
                  "description": "Element reference ID from read_page or find tools (e.g., \\"ref_1\\", \\"ref_2\\"). Required for `scroll_to` action. Can be used as alternative to `coordinate` for click actions."
                },
                "modifiers": {
                  "type": "string",
                  "description": "Modifier keys for click actions. Supports: \\"ctrl\\", \\"shift\\", \\"alt\\", \\"cmd\\" (or \\"meta\\"), \\"win\\" (or \\"windows\\"). Can be combined with \\"+\\" (e.g., \\"ctrl+shift\\", \\"cmd+alt\\"). Optional."
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to execute the action on. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["action", "tabId"]
            }""",
            "browserTools.ts:112-210",
            false, false),

        new BrowserToolSpec(
            "navigate",
            """
            Navigate to a URL, or go forward/back in browser history. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "url": {
                  "type": "string",
                  "description": "The URL to navigate to. Can be provided with or without protocol (defaults to https://). Use \\"forward\\" to go forward in history or \\"back\\" to go back in history."
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to navigate. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["url", "tabId"]
            }""",
            "browserTools.ts:212-231",
            false, false),

        new BrowserToolSpec(
            "resize_window",
            """
            Resize the current browser window to specified dimensions. Useful for testing responsive designs or setting up specific screen sizes. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "width": {
                  "type": "number",
                  "description": "Target window width in pixels"
                },
                "height": {
                  "type": "number",
                  "description": "Target window height in pixels"
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to get the window for. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["width", "height", "tabId"]
            }""",
            "browserTools.ts:233-255",
            false, false),

        new BrowserToolSpec(
            "gif_creator",
            """
            Manage GIF recording and export for browser automation sessions. Control when to start/stop recording browser actions (clicks, scrolls, navigation), then export as an animated GIF with visual overlays (click indicators, action labels, progress bar, watermark). All operations are scoped to the tab's group. When starting recording, take a screenshot immediately after to capture the initial state as the first frame. When stopping recording, take a screenshot immediately before to capture the final state as the last frame. For export, either provide 'coordinate' to drag/drop upload to a page element, or set 'download: true' to download the GIF.""",
            """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["start_recording", "stop_recording", "export", "clear"],
                  "description": "Action to perform: 'start_recording' (begin capturing), 'stop_recording' (stop capturing but keep frames), 'export' (generate and export GIF), 'clear' (discard frames)"
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to identify which tab group this operation applies to"
                },
                "download": {
                  "type": "boolean",
                  "description": "Always set this to true for the 'export' action only. This causes the gif to be downloaded in the browser."
                },
                "filename": {
                  "type": "string",
                  "description": "Optional filename for exported GIF (default: 'recording-[timestamp].gif'). For 'export' action only."
                },
                "options": {
                  "type": "object",
                  "description": "Optional GIF enhancement options for 'export' action. Properties: showClickIndicators (bool), showDragPaths (bool), showActionLabels (bool), showProgressBar (bool), showWatermark (bool), quality (number 1-30). All default to true except quality (default: 10).",
                  "properties": {
                    "showClickIndicators": {
                      "type": "boolean",
                      "description": "Show orange circles at click locations (default: true)"
                    },
                    "showDragPaths": {
                      "type": "boolean",
                      "description": "Show red arrows for drag actions (default: true)"
                    },
                    "showActionLabels": {
                      "type": "boolean",
                      "description": "Show black labels describing actions (default: true)"
                    },
                    "showProgressBar": {
                      "type": "boolean",
                      "description": "Show orange progress bar at bottom (default: true)"
                    },
                    "showWatermark": {
                      "type": "boolean",
                      "description": "Show Claude logo watermark (default: true)"
                    },
                    "quality": {
                      "type": "number",
                      "description": "GIF compression quality, 1-30 (lower = better quality, slower encoding). Default: 10"
                    }
                  }
                }
              },
              "required": ["action", "tabId"]
            }""",
            "browserTools.ts:257-321",
            false, false),

        new BrowserToolSpec(
            "upload_image",
            """
            Upload a previously captured screenshot or user-uploaded image to a file input or drag & drop target. Supports two approaches: (1) ref - for targeting specific elements, especially hidden file inputs, (2) coordinate - for drag & drop to visible locations like Google Docs. Provide either ref or coordinate, not both.""",
            """
            {
              "type": "object",
              "properties": {
                "imageId": {
                  "type": "string",
                  "description": "ID of a previously captured screenshot (from the computer tool's screenshot action) or a user-uploaded image"
                },
                "ref": {
                  "type": "string",
                  "description": "Element reference ID from read_page or find tools (e.g., \\"ref_1\\", \\"ref_2\\"). Use this for file inputs (especially hidden ones) or specific elements. Provide either ref or coordinate, not both."
                },
                "coordinate": {
                  "type": "array",
                  "items": { "type": "number" },
                  "description": "Viewport coordinates [x, y] for drag & drop to a visible location. Use this for drag & drop targets like Google Docs. Provide either ref or coordinate, not both."
                },
                "tabId": {
                  "type": "number",
                  "description": "Tab ID where the target element is located. This is where the image will be uploaded to."
                },
                "filename": {
                  "type": "string",
                  "description": "Optional filename for the uploaded file (default: \\"image.png\\")"
                }
              },
              "required": ["imageId", "tabId"]
            }""",
            "browserTools.ts:323-360",
            false, false),

        new BrowserToolSpec(
            "get_page_text",
            """
            Extract raw text content from the page, prioritizing article content. Ideal for reading articles, blog posts, or other text-heavy pages. Returns plain text without HTML formatting. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to extract text from. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:362-376",
            true, true),

        new BrowserToolSpec(
            "tabs_context_mcp",
            """
            Get context information about the current MCP tab group. Returns all tab IDs inside the group if it exists. CRITICAL: You must get the context at least once before using other browser automation tools so you know what tabs exist. Each new conversation should create its own new tab (using tabs_create_mcp) rather than reusing existing tabs, unless the user explicitly asks to use an existing tab.""",
            """
            {
              "type": "object",
              "properties": {
                "createIfEmpty": {
                  "type": "boolean",
                  "description": "Creates a new MCP tab group if none exists, creates a new Window with a new tab group containing an empty tab (which can be used for this conversation). If a MCP tab group already exists, this parameter has no effect."
                }
              },
              "required": []
            }""",
            "browserTools.ts:378-393",
            true, true),

        new BrowserToolSpec(
            "tabs_create_mcp",
            """
            Creates a new empty tab in the MCP tab group. CRITICAL: You must get the context using tabs_context_mcp at least once before using other browser automation tools so you know what tabs exist.""",
            """
            {
              "type": "object",
              "properties": {},
              "required": []
            }""",
            "browserTools.ts:395-404",
            false, false),

        new BrowserToolSpec(
            "update_plan",
            """
            Present a plan to the user for approval before taking actions. The user will see the domains you intend to visit and your approach. Once approved, you can proceed with actions on the approved domains without additional permission prompts.""",
            """
            {
              "type": "object",
              "properties": {
                "domains": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "List of domains you will visit (e.g., ['github.com', 'stackoverflow.com']). These domains will be approved for the session when the user accepts the plan."
                },
                "approach": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "High-level description of what you will do. Focus on outcomes and key actions, not implementation details. Be concise - aim for 3-7 items."
                }
              },
              "required": ["domains", "approach"]
            }""",
            "browserTools.ts:406-427",
            false, false),

        new BrowserToolSpec(
            "read_console_messages",
            """
            Read browser console messages (console.log, console.error, console.warn, etc.) from a specific tab. Useful for debugging JavaScript errors, viewing application logs, or understanding what's happening in the browser console. Returns console messages from the current domain only. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs. IMPORTANT: Always provide a pattern to filter messages - without a pattern, you may get too many irrelevant messages.""",
            """
            {
              "type": "object",
              "properties": {
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to read console messages from. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                },
                "onlyErrors": {
                  "type": "boolean",
                  "description": "If true, only return error and exception messages. Default is false (return all message types)."
                },
                "clear": {
                  "type": "boolean",
                  "description": "If true, clear the console messages after reading to avoid duplicates on subsequent calls. Default is false."
                },
                "pattern": {
                  "type": "string",
                  "description": "Regex pattern to filter console messages. Only messages matching this pattern will be returned (e.g., 'error|warning' to find errors and warnings, 'MyApp' to filter app-specific logs). You should always provide a pattern to avoid getting too many irrelevant messages."
                },
                "limit": {
                  "type": "number",
                  "description": "Maximum number of messages to return. Defaults to 100. Increase only if you need more results."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:429-463",
            true, true),

        new BrowserToolSpec(
            "read_network_requests",
            """
            Read HTTP network requests (XHR, Fetch, documents, images, etc.) from a specific tab. Useful for debugging API calls, monitoring network activity, or understanding what requests a page is making. Returns all network requests made by the current page, including cross-origin requests. Requests are automatically cleared when the page navigates to a different domain. If you don't have a valid tab ID, use tabs_context_mcp first to get available tabs.""",
            """
            {
              "type": "object",
              "properties": {
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to read network requests from. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                },
                "urlPattern": {
                  "type": "string",
                  "description": "Optional URL pattern to filter requests. Only requests whose URL contains this string will be returned (e.g., '/api/' to filter API calls, 'example.com' to filter by domain)."
                },
                "clear": {
                  "type": "boolean",
                  "description": "If true, clear the network requests after reading to avoid duplicates on subsequent calls. Default is false."
                },
                "limit": {
                  "type": "number",
                  "description": "Maximum number of requests to return. Defaults to 100. Increase only if you need more results."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:465-494",
            true, true),

        new BrowserToolSpec(
            "shortcuts_list",
            """
            List all available shortcuts and workflows (shortcuts and workflows are interchangeable). Returns shortcuts with their commands, descriptions, and whether they are workflows. Use shortcuts_execute to run a shortcut or workflow.""",
            """
            {
              "type": "object",
              "properties": {
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to list shortcuts from. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:496-510",
            true, true),

        new BrowserToolSpec(
            "shortcuts_execute",
            """
            Execute a shortcut or workflow by running it in a new sidepanel window using the current tab (shortcuts and workflows are interchangeable). Use shortcuts_list first to see available shortcuts. This starts the execution and returns immediately - it does not wait for completion.""",
            """
            {
              "type": "object",
              "properties": {
                "tabId": {
                  "type": "number",
                  "description": "Tab ID to execute the shortcut on. Must be a tab in the current group. Use tabs_context_mcp first if you don't have a valid tab ID."
                },
                "shortcutId": {
                  "type": "string",
                  "description": "The ID of the shortcut to execute"
                },
                "command": {
                  "type": "string",
                  "description": "The command name of the shortcut to execute (e.g., 'debug', 'summarize'). Do not include the leading slash."
                }
              },
              "required": ["tabId"]
            }""",
            "browserTools.ts:512-535",
            false, false),

        new BrowserToolSpec(
            "switch_browser",
            """
            Switch which Chrome browser is used for browser automation. Call this when the user wants to connect to a different Chrome browser. Broadcasts a connection request to all Chrome browsers with the extension installed — the user clicks 'Connect' in the desired browser.""",
            """
            {
              "type": "object",
              "properties": {},
              "required": []
            }""",
            "browserTools.ts:537-545",
            false, false)
    );

    /**
     * 由定义构建 18 个 {@link BrowserMcpTool} 实例。
     *
     * @param channel 转发通道；{@code null} = 未注入实现 → 各工具 execute fail loud
     * @return 18 个工具实例（不可变 List，顺序与 {@link #SPECS} 一致）
     */
    public static List<Tool> createTools(BrowserChannel channel) {
        List<Tool> tools = new ArrayList<>(SPECS.size());
        for (BrowserToolSpec spec : SPECS) {
            tools.add(new BrowserMcpTool(spec, channel));
        }
        return List.copyOf(tools);
    }
}
