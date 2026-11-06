// Prevents additional console window on Windows in release, DO NOT REMOVE!!
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;

use tauri::{Emitter, Manager};

/// 后端进程生命周期管理（T12）：探活 / 启动 / 等待就绪 / 整树回收本地后端 java 进程。
mod backend;
/// 自动更新（多源 latest.json 检查 / 下载 sha256 / NSIS 安装）。
mod updater;
/// 启动错误上报（仅内网包；用户勾选同意后才上传到 MinIO）。
mod error_report;
/// 前端日志 / 心跳落盘（OBS1）：前端错误与每 10s 心跳经 IPC 落到 `{data_dir}/logs/frontend.log`。
mod frontlog;

/// 本会话由 Tauri 壳自启的后端 java 进程 pid。
/// None = 启动时 3458 已有外部后端在跑（复用）或尚未自启 —— 此时关窗不回收外部进程。
struct BackendState(std::sync::Mutex<Option<u32>>);

/// 「点关闭按钮 → 只隐藏到托盘」的开关。
///
/// <h2>WHY 需要一个可变开关而不是硬编码</h2>
/// 用户要的是「点 X 不退出，从托盘右键菜单里才能退出」。但退出那一下**也是**走
/// `CloseRequested`（同一个钩子）—— 若钩子无条件隐藏，程序就永远退不出去。
/// 所以托盘菜单的「退出」必须先把这个开关置 false，再调 `window.close()`，
/// 钩子看到 false 才放行。
///
/// <p>用 `AtomicBool` 而非 `Mutex<bool>`：钩子和菜单回调可能在两个线程上，
/// 读一次就完事，不需要临界区。
///
/// <p>⚠️ 默认 `true`（隐藏）：这正是用户要的行为。若托盘图标创建失败
/// （极少数 Linux 桌面环境没有托盘），[`install_tray`] 会把开关拨回 false，
/// 否则用户点了关闭就**再也找不回窗口**（无托盘可点、程序还在后台跑）= 死锁。
struct HideOnClose(std::sync::atomic::AtomicBool);

/// 主窗口 label。tauri.conf.json `app.windows` 未写 label，Tauri 首个窗口默认取 "main"。
const MAIN_WINDOW_LABEL: &str = "main";

/// 关窗事件是否应当回收后端？应当则返回要回收的 pid（`None` = 不回收）。
///
/// 【只认主窗口】`Builder::on_window_event` 是**应用级**钩子（对所有窗口都触发），
/// 而独立预览窗口（`standalone-preview`，承载 PDF/Word/HTML/Excel/图片等）关闭**不等于**
/// 应用退出——若一并回收，就会连带杀掉端口 3458 的后端（历史缺陷：用户关预览把后端也关了）。
/// 未知 / 空 label 一律不回收：宁可留孤儿让 [`backend::cleanup_stale_backend_pid`] 下次兜底，
/// 也不误杀正在服务的后端。
///
/// ⛔ **`take()` 必须由本函数执行，调用方不得先取出 pid 再判 label**：非主窗口关闭时
/// 只判定、**不出栈**——否则 pid 被子窗口提前消费，之后真正关主窗口时 state 已是 `None`，
/// 后端回收不掉 → 反而制造孤儿（与防孤儿的初衷相反）。
fn reclaim_target_on_close(
    window_label: &str,
    state: &std::sync::Mutex<Option<u32>>,
) -> Option<u32> {
    if window_label == MAIN_WINDOW_LABEL {
        state.lock().unwrap().take()
    } else {
        None
    }
}

/// 托盘菜单项 id（`on_menu_event` 里按它分发）。
const TRAY_MENU_SHOW: &str = "tray-show";
const TRAY_MENU_QUIT: &str = "tray-quit";
/// 「最近任务」子菜单容器的 id（不参与点击分发 —— 子菜单本身不触发事件）。
const TRAY_MENU_RECENT: &str = "tray-recent";
/// 「最近任务」菜单项的 id 前缀；后面接会话 id。点击时剥掉前缀得到 `sess-xxxx`。
///
/// <p>用**固定前缀**而不是每项一个全局常量：菜单项数量随会话数变化，
/// 前缀让分发逻辑保持一行（`strip_prefix`），也不必把 id 列表存到别处。
const TRAY_MENU_RECENT_PREFIX: &str = "tray-recent:";
/// 托盘菜单里最多列几个最近任务。
///
/// <p>为什么是 5：用户截图里 WorkBuddy 的菜单就是「最近任务 → 4 条 + 更多」。
/// 再多会撑满屏幕高度，且托盘菜单不适合滚动。
const TRAY_RECENT_LIMIT: usize = 5;

/// 「最近任务」的一条（前端通过 IPC 推过来）。
#[derive(serde::Deserialize)]
pub struct RecentTask {
    /// 会话 id（`sess-xxxx`）—— 点击后原样发给前端。
    pub id: String,
    /// 会话标题。空标题前端已兜底为「新会话」，但这里再防一次（空白菜单项点不了）。
    pub title: String,
}

/// 前端推送「最近任务」→ 重建托盘菜单。
///
/// <h2>WHY 由前端推而不是 Rust 自己去拉</h2>
/// 会话列表在**前端 store**里已经是最新的（含本地乐观新建、标题实时改名）。
/// 让 Rust 再走一遍 HTTP 会制造第二份真源，且要处理鉴权/端口/错误。
/// 这里只做「照单重建菜单」，数据从哪来不归它管。
///
/// <h2>菜单形态（对齐用户给的 WorkBuddy 截图）</h2>
/// <pre>
///   NexusAI            ← 品牌项（禁用）
///   打开主窗口
///   最近任务 ›          ← 子菜单
///     ✓ 会话标题 1      ← 当前会话打勾
///       会话标题 2
///       ...
///   ─────────
///   退出 NexusAI
/// </pre>
///
/// <p>菜单重建失败（托盘已销毁 / 平台不支持）→ 返回 Err 给前端，前端静默忽略
/// （托盘只是增强，推列表失败不该影响任何主流程）。
///
/// <p>`current_id` 是前端**当前打开的会话**，用来在子菜单里打勾；缺省则都不打勾。
#[tauri::command]
fn update_tray_recent(
    app: tauri::AppHandle,
    tasks: Vec<RecentTask>,
    current_id: Option<String>,
) -> Result<(), String> {
    rebuild_tray_menu(&app, &tasks, current_id.as_deref()).map_err(|e| e.to_string())
}

/// 按最近任务列表重建托盘菜单（供 `update_tray_recent` 与启动期共用）。
///
/// <h2>WHY 用子菜单而不是平铺</h2>
/// Windows 托盘菜单是**系统原生菜单**（muda → Win32），行高/内边距/字体全部由系统渲染，
/// 代码改不了样式。会话一多就挤成一坨、容易误点到「退出」。
/// 唯一能让每项「松」下来的手段是**减少主菜单项数** ⇒ 会话列表收进「最近任务 ›」子菜单。
///
/// <h2>菜单形态</h2>
/// <pre>
///   NexusAI            ← 品牌项（禁用）
///   打开主窗口
///   最近任务 ›          ← 子菜单
///     ✓ 会话标题 1      ← 当前会话打勾
///       会话标题 2
///       ...
///   ─────────
///   退出 NexusAI
/// </pre>
fn rebuild_tray_menu(
    app: &tauri::AppHandle,
    tasks: &[RecentTask],
    current_id: Option<&str>,
) -> tauri::Result<()> {
    use tauri::menu::{CheckMenuItem, MenuItem, MenuItemBuilder, SubmenuBuilder};

    let mut menu = tauri::menu::MenuBuilder::new(app);

    // 品牌项：纯标识，不可点；把「最近任务」这个名字腾给子菜单
    let brand = MenuItemBuilder::with_id("tray-brand", "NexusAI").enabled(false).build(app)?;
    menu = menu.item(&brand);

    let show = MenuItemBuilder::with_id(TRAY_MENU_SHOW, "打开主窗口").build(app)?;
    menu = menu.item(&show);

    // 「最近任务」子菜单：只有真的有条目时才建（空子菜单点开是空白，比没有更糟）
    if !tasks.is_empty() {
        let mut sub = SubmenuBuilder::with_id(app, TRAY_MENU_RECENT, "最近任务");
        for t in tasks.iter().take(TRAY_RECENT_LIMIT) {
            // ⚠️ 标题为空会让菜单项变成一条空白（点不到也看不懂）⇒ 兜底文案
            let label = if t.title.trim().is_empty() { "新会话" } else { t.title.trim() };
            // 菜单过长会撑爆托盘菜单宽度 ⇒ 截断（字符边界安全：按 chars 取，不按字节）
            let label: String = if label.chars().count() > 40 {
                format!("{}…", label.chars().take(39).collect::<String>())
            } else {
                label.to_string()
            };
            // 当前会话打勾：一眼看出「我在这」
            let item = CheckMenuItem::with_id(
                app,
                format!("{}{}", TRAY_MENU_RECENT_PREFIX, t.id),
                label,
                true,
                current_id == Some(t.id.as_str()),
                None::<&str>,
            )?;
            sub = sub.item(&item);
        }
        let sub = sub.build()?;
        menu = menu.item(&sub);
    }

    menu = menu.separator();

    let quit = MenuItem::with_id(app, TRAY_MENU_QUIT, "退出 NexusAI", true, None::<&str>)?;
    let menu = menu.item(&quit).build()?;

    if let Some(tray) = app.tray_by_id("main-tray") {
        tray.set_menu(Some(menu))?;
    }
    Ok(())
}

/// 建立系统托盘图标 + 右键菜单（「显示主窗口」/「退出 NexusAI」）。
///
/// <h2>WHY 需要托盘</h2>
/// 用户裁定：「点击 X 不直接退出，最小化；右键最小化图标里有『退出』再退出」。
/// 没有托盘的话，「隐藏到后台」就等于「程序还在跑但用户再也找不到它」。
///
/// <h2>菜单形态</h2>
/// <ul>
///   <li><b>显示主窗口</b> —— 托盘图标左键单击也会触发同一个动作（`show_menu_on_left_click(false)`
///       让它左键直接唤回窗口，右键才出菜单，与 Windows 托盘惯例一致）；</li>
///   <li><b>退出 NexusAI</b> —— 先把 [`HideOnClose`] 拨到 false，再 `close()` 主窗口，
///       让 `CloseRequested` 钩子放行走真正的退出路径（回收后端 → `ExitRequested`）。</li>
/// </ul>
///
/// <h2>失败处理（重要）</h2>
/// 托盘创建失败（Linux 无 StatusNotifier / 图标缺失）时**必须**把 `HideOnClose` 拨回 false：
/// 否则用户点关闭 → 窗口隐藏 → 没有托盘可点回来 → 程序在后台跑但永远不可达 = 死锁。
/// 这里只记日志、不返回 Err —— 托盘是增强功能，不该让应用启动失败。
fn install_tray(app: &tauri::App) -> Result<(), Box<dyn std::error::Error>> {
    // 启动期还不知道会话列表（前端 store 尚未挂载）⇒ 先建空列表版菜单，
    // 前端首个 useEffect 会立刻推真实列表过来。⛔ 别在这里自己拼一份菜单 ——
    // 会与 rebuild_tray_menu 的形态分叉（曾经就是两份菜单代码各自演化）。
    let show = tauri::menu::MenuItemBuilder::with_id(TRAY_MENU_SHOW, "打开主窗口").build(app)?;
    let quit = tauri::menu::MenuItemBuilder::with_id(TRAY_MENU_QUIT, "退出 NexusAI").build(app)?;
    let brand = tauri::menu::MenuItemBuilder::with_id("tray-brand", "NexusAI")
        .enabled(false)
        .build(app)?;
    let menu = tauri::menu::MenuBuilder::new(app)
        .items(&[&brand, &show, &quit])
        .build()?;

    let mut builder = tauri::tray::TrayIconBuilder::with_id("main-tray")
        .menu(&menu)
        // 左键单击 = 唤回窗口（与 Windows 托盘惯例一致）；右键才弹菜单
        .show_menu_on_left_click(false)
        .on_tray_icon_event(|tray, event| {
            // 左键单击唤回（右键由 menu 自己处理，这里只管 Left + Up 的同一次点击）
            if let tauri::tray::TrayIconEvent::Click {
                button: tauri::tray::MouseButton::Left,
                button_state: tauri::tray::MouseButtonState::Up,
                ..
            } = event
            {
                show_main_window(tray.app_handle());
            }
        });

    // 复用应用自身图标（bundle.icon 里已声明），避免额外资源文件
    if let Some(icon) = app.default_window_icon() {
        builder = builder.icon(icon.clone());
    }
    builder.build(app)?;
    Ok(())
}

/// 托盘菜单的统一分发（**应用级**处理器）。
///
/// <h2>WHY 挂在 Builder 上而不是 TrayIconBuilder 上</h2>
/// 「最近任务」菜单会**在运行时整块重建**（`rebuild_tray_menu` 里的 `tray.set_menu`）。
/// 挂在 `TrayIconBuilder` 上的处理函数只对**建托盘时那份菜单**生效；
/// 重建出来的新菜单项**不在**任何 `TrayIconBuilder` 的注册表里 ⇒ 点了没反应。
/// 应用级 `on_menu_event`（`Builder::on_menu_event`）按 **id** 分发，与菜单实例无关 ⇒
/// 重建前后都能路由。
fn handle_menu_event(app: &tauri::AppHandle, event: tauri::menu::MenuEvent) {
    let id = event.id().as_ref().to_string();
    match id.as_str() {
        TRAY_MENU_SHOW => show_main_window(app),
        TRAY_MENU_QUIT => {
            // 先放行关闭钩子，再关窗 —— 顺序不能反（反了钩子会把这次关闭也当成「隐藏」）
            app.state::<HideOnClose>()
                .0
                .store(false, std::sync::atomic::Ordering::SeqCst);
            if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
                let _ = window.close();
            }
        }
        other => {
            // 「最近任务」项：id = "tray-recent:<sessionId>" → 唤回窗口 + 通知前端切换会话。
            // 前端收不到（未监听 / 已销毁）→ 至少把窗口唤起来，用户自己能点左侧列表。
            if let Some(sid) = other.strip_prefix(TRAY_MENU_RECENT_PREFIX) {
                show_main_window(app);
                let _ = app.emit("tray://switch-session", sid.to_string());
            }
        }
    }
}

/// 从托盘唤回主窗口：显示 + 取消最小化 + 置前聚焦。
fn show_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 查找本机 Chrome / Chromium 可执行文件。
/// Windows：Program Files / Program Files (x86) / %LOCALAPPDATA%；
/// macOS：/Applications/Google Chrome.app；
/// Linux：PATH 内 google-chrome / chromium 系列（等价 which 语义）。
fn find_chrome() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();

    #[cfg(target_os = "windows")]
    {
        candidates.push(PathBuf::from(r"C:\Program Files\Google\Chrome\Application\chrome.exe"));
        candidates.push(PathBuf::from(r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"));
        if let Ok(local) = std::env::var("LOCALAPPDATA") {
            candidates.push(PathBuf::from(format!(r"{}\Google\Chrome\Application\chrome.exe", local)));
        }
    }

    #[cfg(target_os = "macos")]
    {
        candidates.push(PathBuf::from("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"));
    }

    // macOS 与 Linux 都额外扫 PATH（mac 也可能只有 chromimum；Linux 必须经 PATH）
    #[cfg(not(target_os = "windows"))]
    {
        for name in ["google-chrome", "google-chrome-stable", "chromium", "chromium-browser"] {
            if let Some(p) = which_in_path(name) {
                candidates.push(p);
            }
        }
    }

    candidates.into_iter().find(|p| p.is_file())
}

/// 在 PATH 中查找可执行文件（Linux 的 which 语义，不依赖外部 which 命令）。
#[cfg(not(target_os = "windows"))]
fn which_in_path(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let p = dir.join(name);
        if p.is_file() {
            return Some(p);
        }
    }
    None
}

/// 返回扩展目录绝对路径（内含 manifest.json）。
/// 1) 优先打包资源目录 resources/extension（安装后随包分发）；
/// 2) 开发模式资源目录无 extension 时，回退项目根 extension/（CARGO_MANIFEST_DIR 上一级）。
fn resolve_chrome_extension_dir(app: &tauri::AppHandle) -> Result<String, String> {
    // 打包后：resource_dir()/extension
    let resource_dir = app.path().resource_dir().map_err(|e| e.to_string())?;
    let bundled = resource_dir.join("extension");
    if bundled.join("manifest.json").is_file() {
        return Ok(bundled.to_string_lossy().to_string());
    }
    // dev 回退：env!("CARGO_MANIFEST_DIR") = 编译期 src-tauri/，其上一级即项目根 extension/
    // （CARGO_MANIFEST_DIR 是编译期常量，运行时用 env! 宏内联；std::env::var 运行时读不到）
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../extension");
    if dev.join("manifest.json").is_file() {
        return Ok(dev.to_string_lossy().to_string());
    }
    Err(format!(
        "未找到扩展目录（resources/extension 与项目根 extension/ 均无 manifest.json，当前资源目录：{}）",
        resource_dir.to_string_lossy()
    ))
}

/// 获取 Chrome 扩展目录路径（供前端展示安装位置）。
#[tauri::command]
fn chrome_extension_dir(app: tauri::AppHandle) -> Result<String, String> {
    resolve_chrome_extension_dir(&app)
}

/// 返回扩展 ZIP 包绝对路径（内含 manifest.json 等 5 文件，供备份/手动安装/分享）。
/// 1) 优先打包资源目录 resources/extension-pack/nexusai-extension.zip（随安装包分发，安装后与 extension 同级）；
/// 2) 开发模式资源目录无该 zip 时，回退 src-tauri/extension-pack/nexusai-extension.zip（make-extension-zip.mjs 产物）。
fn resolve_chrome_extension_zip_path(app: &tauri::AppHandle) -> Result<String, String> {
    // 打包后：resource_dir()/extension-pack/nexusai-extension.zip
    let resource_dir = app.path().resource_dir().map_err(|e| e.to_string())?;
    let bundled = resource_dir
        .join("extension-pack")
        .join("nexusai-extension.zip");
    if bundled.is_file() {
        return Ok(bundled.to_string_lossy().to_string());
    }
    // dev 回退：env!("CARGO_MANIFEST_DIR") = 编译期 src-tauri/，extension-pack/nexusai-extension.zip 即脚本产物
    let dev = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("extension-pack")
        .join("nexusai-extension.zip");
    if dev.is_file() {
        return Ok(dev.to_string_lossy().to_string());
    }
    Err(format!(
        "未找到扩展 zip 包（resources/extension-pack/nexusai-extension.zip 与 src-tauri/extension-pack/nexusai-extension.zip 均不存在；候选路径：{} / {}）",
        bundled.to_string_lossy(),
        dev.to_string_lossy()
    ))
}

/// 获取 Chrome 扩展 ZIP 包路径（供前端展示 zip 备份/手动安装位置）。
#[tauri::command]
fn chrome_extension_zip_path(app: tauri::AppHandle) -> Result<String, String> {
    resolve_chrome_extension_zip_path(&app)
}

/// 检测本机是否安装 Chrome / Chromium。
#[tauri::command]
fn is_chrome_installed() -> Result<bool, String> {
    Ok(find_chrome().is_some())
}

/// 检测 Chrome / Chromium 是否正在运行。
/// Windows：tasklist 查 chrome.exe；macOS/Linux：pgrep 查进程名。
fn is_chrome_running() -> bool {
    #[cfg(target_os = "windows")]
    {
        backend::silent_command("tasklist")
            .output()
            .ok()
            .map(|o| String::from_utf8_lossy(&o.stdout).to_lowercase().contains("chrome.exe"))
            .unwrap_or(false)
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("pgrep")
            .args(["-x", "Google Chrome"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false)
    }
    #[cfg(target_os = "linux")]
    {
        std::process::Command::new("pgrep")
            .args(["-x", "google-chrome"])
            .output()
            .map(|o| o.status.success())
            .unwrap_or_else(|_| {
                std::process::Command::new("pgrep")
                    .args(["-x", "chromium"])
                    .output()
                    .map(|o| o.status.success())
                    .unwrap_or(false)
            })
    }
}

/// 一键安装/引导加载扩展。
///
/// <p><b>关键语义（修复 --load-extension 两个坑）</b>：
/// <ol>
///   <li><b>Chrome 已运行 → --load-extension 被静默忽略</b>（spawn 附加到已有单例实例）。
///       此时改引导「chrome://extensions → 开发者模式 → 加载已解压的扩展程序」（选 ext_dir），
///       该加载方式<b>持久</b>（Chrome 重启后扩展仍保留）。</li>
///   <li><b>Chrome 未运行 → 冷启动 --load-extension</b>（本次会话有效；命令行加载在 Chrome
///       重启后消失，文案提示建议持久化安装）。</li>
/// </ol>
#[tauri::command]
fn install_chrome_extension(app: tauri::AppHandle) -> Result<String, String> {
    let ext_dir = resolve_chrome_extension_dir(&app)?;
    let chrome = find_chrome()
        .ok_or_else(|| "未找到 Chrome，请先安装：https://www.google.com/chrome/".to_string())?;

    if is_chrome_running() {
        // Chrome 运行中：--load-extension 无效。打开 chrome://extensions 引导持久化加载。
        std::process::Command::new(&chrome)
            .arg("--new-window")
            .arg("chrome://extensions")
            .spawn()
            .map_err(|e| format!("启动 Chrome 失败：{}", e))?;
        return Ok(format!(
            "检测到 Chrome 正在运行（命令行 --load-extension 会失效）。已打开 chrome://extensions 页面，\n\
             请按以下步骤完成持久化安装（Chrome 重启后仍保留）：\n\
             1. 开启右上角「开发者模式」开关\n\
             2. 点「加载已解压的扩展程序」\n\
             3. 选择扩展目录：{}",
            ext_dir
        ));
    }

    // Chrome 未运行：冷启动 --load-extension（本次会话有效）
    std::process::Command::new(&chrome)
        .arg(format!("--load-extension={}", ext_dir))
        .arg("--new-window")
        .arg("chrome://extensions")
        .spawn()
        .map_err(|e| format!("启动 Chrome 失败：{}", e))?;

    Ok(format!(
        "Chrome 已冷启动并加载扩展（--load-extension={}）。\n\
         注意：命令行加载在 Chrome 重启后失效——建议按「开发者模式 → 加载已解压」做持久化安装（重启后仍保留）。",
        ext_dir
    ))
}

fn main() {
    let app = tauri::Builder::default()
        // 单实例：第二实例启动即退出并把已运行实例的主窗口置前聚焦，
        // 防止两个壳实例各自拉起后端、争抢 3458 端口（窗口 label 默认 "main"）
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
                let _ = window.set_focus();
            }
        }))
        .manage(BackendState(std::sync::Mutex::new(None)))
        .manage(HideOnClose(std::sync::atomic::AtomicBool::new(true)))
        // [2026-11-04] 托盘菜单统一分发挂在**应用级**（见 handle_menu_event 的 WHY）：
        //   「最近任务」菜单会在运行时整块重建，挂在 TrayIconBuilder 上的处理器收不到新菜单项。
        .on_menu_event(handle_menu_event)
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_fs::init())
        // 系统级通知（Windows/macOS 右下角/通知中心）：轮次结束时由前端调用。
        // 前端走 @tauri-apps/plugin-notification；未装插件时该 API 会 reject，
        // 前端封装里已降级到 Web Notification API（详见 utils/systemNotify.ts）。
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            chrome_extension_dir,
            chrome_extension_zip_path,
            is_chrome_installed,
            install_chrome_extension,
            updater::app_version,
            updater::update_check,
            updater::update_download,
            updater::update_install,
            error_report::report_capability,
            error_report::report_startup_error,
            frontlog::frontend_log,
            frontlog::frontend_heartbeat,
            update_tray_recent
        ])
        .setup(|app| {
            // [2026-11-04 用户裁定] 系统托盘：「点 X 隐藏到托盘」，托盘右键菜单才有「退出」。
            //   ⚠️ 失败时必须把 HideOnClose 拨回 false —— 否则没有托盘可唤回窗口，
            //   用户点关闭后程序在后台跑却永远不可达（死锁）。故这里吞掉错误只记日志。
            if let Err(e) = install_tray(app) {
                app.state::<HideOnClose>()
                    .0
                    .store(false, std::sync::atomic::Ordering::SeqCst);
                backend::log_launcher(&format!(
                    "[tray] 托盘创建失败，关闭行为退回「直接退出」以免窗口不可唤回: {}", e
                ));
            }

            // ===== 启动期小窗（承载 loader）+ 就绪放大交给前端 =====
            // 背景：后端冷启动实测可能 >8s（首次 Flyway 全量迁移 / c3p0 / Quartz bean 初始化）。
            // 若窗口初始就是 85% 大窗，首帧将长时间空白/白屏。故启动期用 420x420 小正方形窗承载 loader，
            // 前端收到 backend-ready 事件后自驱放放大到显示器 85%（放大权在前端，L2 前端实现）。
            // 注：420x420 小于 tauri.conf.json 的 minWidth/minHeight(960x600)，先临时下调最小尺寸，
            //     否则 set_size 会被系统 clamp 回 960x600，小窗不生效；前端放大后如需恢复
            //     960x600 下限，由前端 setMinSize 再设回（后端就绪前保持小窗由壳保证）。
            if let Some(window) = app.get_webview_window(MAIN_WINDOW_LABEL) {
                // [2026-11-02 用户实测「启动动画在左上角、且被截断」] 两个根因：
                //   ① `PhysicalSize` 是**物理像素**：Retina 屏 460×400 物理 ≈ 230×200 逻辑点，
                //      而 `.cl-scene` 内容本身就要 230px 宽（猫 + 墙 200px）⇒ 一放就裁。
                //      改用 `LogicalSize`（逻辑点），与 CSS 的 px 同一坐标系。
                //   ② `set_size` 在 macOS 上是**异步生效**的，紧接着 `center()` 会按旧尺寸算位置
                //      ⇒ 小窗被摆在左上角。故先 `set_min_size` + `set_size`，再让后台线程稍等
                //      settle 后 `center()`（一次性，不轮询）。
                //   ③ [2026-11-02 第二轮] 放大动画改为「中心钉死、四边外扩」后，小窗自身也必须在正中，
                //      否则初始偏左上 ⇒ 整段动画仍看着是从左上角长出来。
                //      `center()` 依赖「当前 outer_size」，而 set_size 异步 ⇒ 改成按显示器几何**直接算位置**，
                //      完全不依赖 set_size 何时生效（原 120ms sleep + center 的猜测性等待一并去掉）。
                // [2026-11-02 第三轮] 用户预期「显示小小的正方形 + 猫咪动画，结束时往四角放大」
                //   ⇒ 小窗改成正方形（420x420 逻辑点）。⚠️ W 与 H 必须相等，且与前端的放大起点无关
                //   （前端读 outerSize 作起点，不写死尺寸）。
                const SPLASH_W: f64 = 420.0;
                const SPLASH_H: f64 = 420.0;
                let _ = window.set_min_size(Some(tauri::LogicalSize::new(SPLASH_W, SPLASH_H)));
                let _ = window.set_size(tauri::LogicalSize::new(SPLASH_W, SPLASH_H));
                if let Ok(Some(monitor)) = window.primary_monitor() {
                    // Monitor 给出的是物理像素，换算成逻辑点后再算偏移，避免 Retina 下偏一半
                    let scale = monitor.scale_factor();
                    let mon_w = monitor.size().width as f64 / scale;
                    let mon_h = monitor.size().height as f64 / scale;
                    let mon_x = monitor.position().x as f64 / scale;
                    let mon_y = monitor.position().y as f64 / scale;
                    let _ = window.set_position(tauri::LogicalPosition::new(
                        mon_x + (mon_w - SPLASH_W) / 2.0,
                        mon_y + (mon_h - SPLASH_H) / 2.0,
                    ));
                }
            }

            // ===== 后端进程生命周期（T12）=====
            // 1) 数据目录 .nexusai 与 .nexusai/logs 就绪（后端日志落盘前提）。
            //    失败先把关键信息写启动器日志 tauri-launcher.log（release 无控制台时唯一排障通道），再返回 Err
            if let Err(e) = backend::ensure_data_dirs() {
                let msg = format!("初始化本地数据目录失败：{}（请检查用户主目录权限）", e);
                backend::log_launcher(&msg);
                return Err(msg.into());
            }

            // 供后台线程 emit 事件；clone 出独立 AppHandle，避免线程内借用 app
            let handle = app.handle().clone();

            // [T12-pid] 仅 release：启动前清理上次自启残留（身份校验 —— 只杀命令行含
            //   nexusai-backend.jar 的后端，pid 复用/其它 java 一律不碰）；dev 由外部/IDE 后端
            //   自行管理，不做跨进程清理。
            if !cfg!(debug_assertions) {
                backend::cleanup_stale_backend_pid();
            }

            // 2) 3458 无后端才自启；已有（外部进程 / 上一会话残留）则直接复用，不重复拉起
            if !backend::backend_ready() {
                match backend::spawn_backend(&handle) {
                    Ok(pid) => {
                        // 记录 pid 到 state（关窗/退出整树回收）；pid 落盘已在 spawn_backend 内完成
                        *app.state::<BackendState>().0.lock().unwrap() = Some(pid);
                        eprintln!("[backend] 已启动本地后端，pid={}，后台等待健康就绪…", pid);

                        // 3) 关键：setup 不阻塞主线程 —— 起后台线程轮询 /actuator/health 最多 60s，
                        //    窗口首帧立即渲染 loader（Spring Boot prod 冷启动实测 >8s：首次 Flyway 全量迁移 /
                        //    c3p0 / Quartz bean 初始化）。就绪 / 超时都经事件通知前端（事件名定死，L2 前端 listen）：
                        //    - backend-ready：后端已就绪，前端据此放大窗口到 85%
                        //    - backend-error：启动超时，前端据此提示；超时先 kill_process_tree(pid) 防孤儿
                        std::thread::spawn(move || {
                            if backend::wait_backend_ready(backend::WAIT_BACKEND_READY_TIMEOUT) {
                                eprintln!("[backend] 本地后端已就绪：http://localhost:3458/actuator/health");
                                let _ = handle.emit("backend-ready", ());
                            } else {
                                backend::kill_process_tree(pid);
                                let msg = format!(
                                    "本地后端启动超时（{} 秒内未就绪，已回收进程树 pid={}），请查看 {}/logs/backend.log（中文）。",
                                    backend::WAIT_BACKEND_READY_TIMEOUT.as_secs(),
                                    pid,
                                    backend::data_dir().display()
                                );
                                backend::log_launcher(&msg);
                                let _ = handle.emit("backend-error", msg);
                            }
                        });
                    }
                    Err(e) => {
                        if cfg!(debug_assertions) {
                            // dev 模式（tauri dev）：不一定随包分发 backend —— 不因缺少捆绑后端而 panic，
                            // 跳过自启、交给前端探活（外部后端通常由 IDE 单独启动 3458）。日志中文便于排障。
                            let msg = format!(
                                "dev 模式未找到随包后端，跳过自启（请用 IDE 启动后端 3458）。原因：{}",
                                e
                            );
                            backend::log_launcher(&msg);
                            eprintln!("[backend-launcher] {}", msg);
                        } else {
                            // release：捆绑后端缺失/无法启动属部署错误 → fail loud，但**保留窗口**
                            // 让 LaunchGate 显示错误卡并提供「上报错误日志」（用户 2026-10-28 要求）。
                            // ⛔ 不再 `return Err`：setup 返回 Err 会直接结束应用，前端拿不到错误卡。
                            let msg = format!(
                                "本地后端启动失败：{}（请查看 {}/logs/backend.log）",
                                e,
                                backend::data_dir().display()
                            );
                            backend::log_launcher(&msg);
                            eprintln!("[backend-launcher] {}", msg);
                            let _ = handle.emit("backend-error", msg);
                        }
                    }
                }
            } else {
                eprintln!("[backend] 检测到 3458 已有后端在运行，跳过自启");
                // dev 等外部后端已就绪：立即通知前端。
                // 注：此刻 webview 可能尚未挂载监听，事件可能 miss —— 前端启动时应自带一次探活兜底
                let _ = handle.emit("backend-ready", ());
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            // [2026-11-04 用户裁定] 主窗口「点 X」= 隐藏到托盘（不退出）；
            //   真要退出走托盘右键菜单的「退出 NexusAI」（它先把 HideOnClose 拨 false 再关窗）。
            //   独立预览窗（standalone-preview）不受影响：它本就该正常关闭。
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == MAIN_WINDOW_LABEL
                    && window
                        .app_handle()
                        .state::<HideOnClose>()
                        .0
                        .load(std::sync::atomic::Ordering::SeqCst)
                {
                    // prevent_close() 阻止默认销毁；hide() 让窗口从任务栏消失但进程与后端继续活着
                    api.prevent_close();
                    let _ = window.hide();
                    return;
                }
            }
            // 用户关窗：整树杀掉本会话自启的后端 java（防孤儿）。本钩子对所有窗口触发，
            // 是否该回收（及出栈）一律交给 reclaim_target_on_close 判定。
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                let state = window.app_handle().state::<BackendState>();
                if let Some(p) = reclaim_target_on_close(window.label(), &state.0) {
                    // 走 log_launcher：release 无控制台，eprintln 会丢，文件才是唯一排障通道
                    backend::log_launcher(&format!("主窗口关闭，回收后端进程树 pid={}", p));
                    backend::kill_process_tree(p);
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application");

    app.run(|app_handle, event| {
        // 兜底：非“关窗”路径的退出（RunEvent::ExitRequested）再回收一次；
        // take() 幂等——若已在关窗时 kill 过则此处为 None，直接跳过
        if let tauri::RunEvent::ExitRequested { .. } = event {
            let pid = app_handle
                .state::<BackendState>()
                .0
                .lock()
                .unwrap()
                .take();
            if let Some(p) = pid {
                eprintln!("[backend] 应用退出，回收后端进程树 pid={}", p);
                backend::kill_process_tree(p);
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state_of(pid: Option<u32>) -> std::sync::Mutex<Option<u32>> {
        std::sync::Mutex::new(pid)
    }

    /// 主窗口关闭 → 必须回收（防孤儿的原始意图，不许丢），且出栈后 state 置 None
    /// （保证 `RunEvent::ExitRequested` 兜底分支幂等，不会重复 kill）。
    #[test]
    fn main_window_close_reclaims_and_clears_state() {
        let state = state_of(Some(1234));
        assert_eq!(reclaim_target_on_close("main", &state), Some(1234));
        assert_eq!(*state.lock().unwrap(), None);
        // 无 pid（启动时复用外部后端）⇒ 无事发生
        assert_eq!(reclaim_target_on_close("main", &state_of(None)), None);
    }

    /// 独立预览子窗口（PDF/Word/HTML/Excel/图片）关闭 → **绝不**回收后端。
    /// 这是本次缺陷的不变量：关预览 ≠ 应用退出，不该连带杀掉端口 3458 的后端。
    #[test]
    fn preview_child_window_close_does_not_reclaim_backend() {
        assert_eq!(
            reclaim_target_on_close("standalone-preview", &state_of(Some(1234))),
            None
        );
    }

    /// ⛔ 子窗口关闭**不得消费掉 pid**：否则之后关主窗口时 state 已空 → 后端回收不掉，
    /// 「防孤儿」反而变成「制造孤儿」。这条守的是 `take()` 的**时机**，不只是返回值。
    #[test]
    fn child_window_close_must_not_consume_pid() {
        let state = state_of(Some(1234));
        assert_eq!(reclaim_target_on_close("standalone-preview", &state), None);
        assert_eq!(*state.lock().unwrap(), Some(1234)); // pid 仍在
        // 之后关主窗口仍能正常回收
        assert_eq!(reclaim_target_on_close("main", &state), Some(1234));
    }

    /// 空 / 未知 label → 不回收（保守：宁可留孤儿给下次启动兜底，也不误杀在服务的后端）。
    #[test]
    fn unknown_or_empty_label_does_not_reclaim_backend() {
        assert_eq!(reclaim_target_on_close("", &state_of(Some(1234))), None);
        assert_eq!(
            reclaim_target_on_close("some-other-window", &state_of(Some(1234))),
            None
        );
        assert_eq!(
            reclaim_target_on_close("Main", &state_of(Some(1234))),
            None // label 大小写敏感，不做模糊匹配
        );
    }

    /// ⭐ 接线层守护（源码级）：把「该不该杀、杀哪个」抽成纯函数后，上面几条只守住了
    /// **函数本身**，守不住「钩子是否真的把 `window.label()` 喂进来了」——而 Tauri 的
    /// `on_window_event` 钩子需要 App 运行时，**无法在单测里执行**，故行为级测试永远抓不到
    /// 「钩子绕过判定函数、恢复原始 bug」这类变异（实测：钩子改 `if true` 时上列测试全绿）。
    /// 本测试补上这一层。
    ///
    /// ⚠️ **局限（如实登记，非完备守护）**：这是字符串级守护，只拦「调用被整行删除 / 改名」，
    /// 拦不住「改了传参」或等价改写（如 `if window.label() == "main"`）。它是对
    /// 「钩子不可单测」这一平台约束的妥协。
    #[test]
    fn close_hook_must_route_through_reclaim_target() {
        let src = include_str!("main.rs");
        // 只查 `#[cfg(test)]` 之前的生产代码段：否则本测试自己的字符串字面量会自匹配 → 假绿
        let prod = src.split("#[cfg(test)]").next().unwrap_or("");
        assert!(
            prod.contains("reclaim_target_on_close(window.label()"),
            "关窗钩子必须经由 reclaim_target_on_close(window.label(), …) 判定；\
             绕过它直接 kill 会复活「关独立预览窗连带杀掉后端」缺陷"
        );
    }

    /// ⭐ [2026-11-04 用户裁定] 「点 X 只隐藏到托盘」的不变量。
    ///
    /// <p><b>为什么要守它</b>：这个功能有两处都能把应用变成「不可达的后台进程」——
    /// <ol>
    ///   <li>钩子隐藏了窗口但**没有** `prevent_close()` ⇒ 窗口照样被销毁，托盘唤回的是
    ///       一个已销毁的窗口（`get_webview_window` 返回 None）⇒ 用户再也打不开；</li>
    ///   <li>托盘创建失败却仍保持「隐藏」⇒ 没有托盘可点回来 ⇒ 同上。</li>
    /// </ol>
    /// 两条都无法在单测里跑 Tauri 运行时，故用源码级守护（与上面那条同款取舍）。
    #[test]
    fn close_hides_instead_of_destroying() {
        let src = include_str!("main.rs");
        let prod = src.split("#[cfg(test)]").next().unwrap_or("");

        assert!(
            prod.contains("api.prevent_close()"),
            "隐藏到托盘前必须 prevent_close()，否则窗口被销毁、托盘再也唤不回来"
        );
        assert!(
            prod.contains("window.hide()"),
            "关闭主窗口应当 hide() 而不是销毁"
        );
        // 托盘失败 → 必须退回「直接退出」，否则无可唤回入口
        assert!(
            prod.contains("托盘创建失败") && prod.contains("store(false"),
            "托盘创建失败必须把 HideOnClose 拨回 false（否则用户点关闭后窗口永远不可唤回）"
        );
        // 菜单「退出」必须先放行钩子，再 close —— 顺序反了钩子会把退出也当成隐藏
        let quit_pos = prod.find("TRAY_MENU_QUIT =>").expect("托盘退出菜单项必须存在");
        // ⚠️ 不能直接 `&prod[quit_pos..quit_pos + 600]`：源码里全是中文，按**字节**切会
        //   落在多字节字符中间 ⇒ panic（实测 `not a char boundary`）。按字符数取窗口。
        let quit_seg: String = prod[quit_pos..].chars().take(600).collect();
        let store_pos = quit_seg.find("store(false").expect("退出项必须先把 HideOnClose 置 false");
        let close_pos = quit_seg.find("window.close()").expect("退出项必须真的关闭窗口");
        assert!(
            store_pos < close_pos,
            "必须先 store(false) 再 close()：顺序反了关闭钩子会把这一下也当成「隐藏」，程序退不出去"
        );
    }

    /// ⭐ [2026-11-04 用户裁定] 托盘菜单「间隔太短」⇒ 会话列表必须收进**子菜单**。
    ///
    /// <p><b>为什么值得守</b>：Windows 托盘菜单是系统原生菜单，行高与内边距**由 OS 渲染，
    /// 代码改不了**。唯一能让每项松下来的手段是减少主菜单项数 —— 一旦有人把会话重新
    /// 平铺回主菜单，「间隔太短」立刻复发，而这是纯观感缺陷、任何功能测试都抓不到。
    #[test]
    fn recent_tasks_must_live_in_submenu() {
        let src = include_str!("main.rs");
        let prod = src.split("#[cfg(test)]").next().unwrap_or("");

        let build_start = prod
            .find("fn rebuild_tray_menu")
            .expect("必须存在 rebuild_tray_menu");
        let build_seg: String = prod[build_start..].chars().take(2600).collect();

        // ⚠️ 必须断言**构造调用**而不是类型名：函数开头的 `use tauri::menu::{CheckMenuItem, ...}`
        //   本身就能满足 `contains("CheckMenuItem")`，实测把构造换成普通 MenuItem 后守护仍是绿的。
        assert!(
            build_seg.contains("SubmenuBuilder::with_id(app, TRAY_MENU_RECENT"),
            "最近任务必须建为 SubmenuBuilder 子菜单；平铺回主菜单会让系统原生行距挤成一坨"
        );
        assert!(
            build_seg.contains("CheckMenuItem::with_id("),
            "当前会话必须真的用 CheckMenuItem::with_id 构造（仅 use 导入不算），否则用户看不出自己在哪个会话"
        );
        assert!(
            build_seg.contains("current_id == Some(t.id.as_str())"),
            "勾选判据必须是「当前会话 == 该条」，否则永远不打勾或全打勾"
        );
        assert!(
            build_seg.contains("TRAY_MENU_RECENT_PREFIX"),
            "子菜单项 id 仍须带 tray-recent: 前缀，否则 handle_menu_event 分发不到"
        );
        // 品牌项必须禁用（它只是标识，点它什么都不该发生）
        assert!(
            prod.contains("tray-brand"),
            "顶部品牌项 tray-brand 必须存在（把「最近任务」这个名字腾给子菜单）"
        );
    }
}
