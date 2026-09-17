//! 前端日志 / 心跳落盘通道（OBS1）。
//!
//! 背景：2026-09-17 用户遇到「前端整屏完全静止」且服务端查不出根因 —— 前端侧无任何日志、
//! 无上报通道，叠加后端 chunk 推送也不打日志，「后端到底推没推」与「前端有没有渲染」
//! 无法从日志分辨。本模块补上前端→壳这条通道：前端 `console.error/warn`、未捕获异常、
//! 未处理 rejection 与**每 10s 心跳**经 IPC 追加写入 `{data_dir}/logs/frontend.log`
//! （与 backend.log / tauri-launcher.log 同目录、同时间戳格式）。
//!
//! ⭐ 心跳的设计意图：**日志里心跳断档 = 前端停了/僵了** —— 这是「整屏静止」最需要的信号。
//!
//! 约束：零新增依赖（仅 std + tauri）；阻塞文件 IO 必须下放 `spawn_blocking`，
//! 否则会占住 Tauri 命令处理线程，期间所有其他 IPC 排队
//! （先例见 `updater.rs::update_check` 的「点添加工作区卡顿」，2026-09-17 用户实测）。

use std::io::Write;
use std::path::PathBuf;

use crate::backend;

/// 单条 msg 的最大字符数（超出即截断并标注）：防止超大对象 / 超长 stack 把日志撑爆。
const MAX_MSG_CHARS: usize = 4000;

/// 前端日志文件：`{data_dir}/logs/frontend.log`（与 backend.log / tauri-launcher.log 同目录）。
/// 目录存在性由 [`backend::ensure_data_dirs`] 保证（其内部已覆盖 `logs` 子目录）。
fn log_file() -> PathBuf {
    backend::data_dir().join("logs").join("frontend.log")
}

/// 把任意文本规整为**单行**：换行转义为字面量 `\n`。
///
/// 必要性：stack trace 天然多行 —— 不转义则「一条事件」会被写成多行，
/// 破坏「一条事件 = 一行日志」的契约，按行 grep 时会把一次错误读成多次。
fn one_line(s: &str) -> String {
    s.replace('\r', "").replace('\n', "\\n")
}

/// 超长截断：先转单行、再按**字符**（非字节）截断保证 UTF-8 边界安全，附原文长度标注。
fn truncate_msg(msg: &str) -> String {
    let s = one_line(msg);
    let total = s.chars().count();
    if total <= MAX_MSG_CHARS {
        return s;
    }
    let head: String = s.chars().take(MAX_MSG_CHARS).collect();
    format!("{head}…(已截断，原文 {total} 字符)")
}

/// 格式化一条前端日志：`[<本地时间>] <LEVEL> [<tag>] <msg>`。
///
/// 【纯函数】不触碰文件系统 —— 抽出来是为了让截断 / 单行化可被单测覆盖，
/// 而**不必真写 `~/.nexusai`**（那会污染用户真实数据目录）。
fn format_log_line(level: &str, tag: &str, msg: &str) -> String {
    format!(
        "[{}] {} [{}] {}",
        local_timestamp(),
        level.to_uppercase(),
        tag,
        truncate_msg(msg)
    )
}

/// 格式化一条心跳：`[<本地时间>] HEARTBEAT seq=<seq> <note>`（note 为空 / 缺失则省略）。
fn format_heartbeat_line(seq: u64, note: Option<&str>) -> String {
    let mut line = format!("[{}] HEARTBEAT seq={}", local_timestamp(), seq);
    if let Some(n) = note.filter(|s| !s.is_empty()) {
        line.push(' ');
        line.push_str(&one_line(n));
    }
    line
}

/// 本地时间戳 `YYYY-MM-DD HH:MM:SS`（与 backend.rs `utc_timestamp` 的排版一致）。
///
/// Windows 直接取 kernel32 `GetLocalTime` 的**系统本地时间**（无需时区换算，零第三方依赖 ——
/// 本仓 `chrono` 仅是传递依赖，直接引用会改动 Cargo.lock，本批不允许）；
/// 非 Windows 退回 [`backend::utc_timestamp`]（UTC）—— 本批只要求 Windows 侧为本地时间。
fn local_timestamp() -> String {
    #[cfg(target_os = "windows")]
    {
        /// winbase.h `SYSTEMTIME`（字段全为 `WORD`）。
        #[repr(C)]
        struct SystemTime {
            year: u16,
            month: u16,
            day_of_week: u16,
            day: u16,
            hour: u16,
            minute: u16,
            second: u16,
            milliseconds: u16,
        }
        extern "system" {
            fn GetLocalTime(lp_system_time: *mut SystemTime);
        }
        let mut st = SystemTime {
            year: 0,
            month: 0,
            day_of_week: 0,
            day: 0,
            hour: 0,
            minute: 0,
            second: 0,
            milliseconds: 0,
        };
        // SAFETY: GetLocalTime 只写入调用方提供的 SYSTEMTIME（8 个 u16），st 即其有效可写实例。
        unsafe { GetLocalTime(&mut st) };
        format!(
            "{:04}-{:02}-{:02} {:02}:{:02}:{:02}",
            st.year, st.month, st.day, st.hour, st.minute, st.second
        )
    }
    #[cfg(not(target_os = "windows"))]
    {
        backend::utc_timestamp()
    }
}

/// 追加一行到 `{data_dir}/logs/frontend.log`（写前确保目录存在）。
///
/// 失败**不 panic、不静默**：返回中文 `Err` 让前端知道（前端会丢弃，但壳侧不吞掉错误），
/// 并 `eprintln!` 留痕（dev 控制台可见；release 无控制台时以文件为准）。
fn append_line(line: &str) -> Result<(), String> {
    let write = || -> std::io::Result<()> {
        backend::ensure_data_dirs()?;
        let path = log_file();
        let mut f = std::fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(&path)?;
        f.write_all(line.as_bytes())?;
        f.write_all(b"\n")
    };
    match write() {
        Ok(()) => Ok(()),
        Err(e) => {
            let msg = format!("[frontlog] 写入前端日志失败：{e}");
            eprintln!("{msg}");
            Err(msg)
        }
    }
}

/// 前端日志落盘：写一行 `[<本地时间>] <LEVEL> [<tag>] <msg>`。
///
/// 阻塞文件 IO → 必须下放 `spawn_blocking`（同步跑会占住 Tauri 命令处理线程，期间全部 IPC 排队）。
#[tauri::command]
pub async fn frontend_log(level: String, tag: String, msg: String) -> Result<(), String> {
    let line = format_log_line(&level, &tag, &msg);
    tauri::async_runtime::spawn_blocking(move || append_line(&line))
        .await
        .map_err(|e| format!("前端日志任务失败: {e}"))?
}

/// 前端心跳落盘（前端每 10s 一次）：日志里心跳断档 = 前端停了/僵了。
#[tauri::command]
pub async fn frontend_heartbeat(seq: u64, note: Option<String>) -> Result<(), String> {
    let line = format_heartbeat_line(seq, note.as_deref());
    tauri::async_runtime::spawn_blocking(move || append_line(&line))
        .await
        .map_err(|e| format!("前端心跳任务失败: {e}"))?
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 超长 msg 必须被截断且带标注 —— 否则超大对象 / 长 stack 会把日志撑爆。
    /// 断言按**字符**计数（非字节）：中文 msg 下字节数与字符数不同，用字节断言会掩盖 UTF-8 截断错误。
    #[test]
    fn truncate_msg_cuts_oversized_and_marks_it() {
        let huge = "字".repeat(MAX_MSG_CHARS + 500);
        let out = truncate_msg(&huge);
        assert!(
            out.contains("已截断"),
            "超长 msg 必须带截断标注（否则日志无法判断「这条是不是被截过」）"
        );
        let head = out.split('…').next().unwrap();
        assert_eq!(
            head.chars().count(),
            MAX_MSG_CHARS,
            "截断后正文必须恰为 MAX_MSG_CHARS 个字符"
        );
        assert!(
            out.contains(&format!("原文 {} 字符", MAX_MSG_CHARS + 500)),
            "标注必须带原文长度，实得：{}",
            out
        );
        // 边界：恰好不超长 → 原样保留（防止 off-by-one 把正常消息也截了）
        let exact = "a".repeat(MAX_MSG_CHARS);
        assert_eq!(truncate_msg(&exact), exact);
        assert_eq!(truncate_msg("短消息"), "短消息");
    }

    /// 一条事件必须是**一行**：stack trace 里的换行不得把单条日志拆成多行
    /// （否则按行 grep 会把一次错误读成多次，「心跳断档」这类按行判读的结论随之失真）。
    #[test]
    fn truncation_collapses_newlines_into_one_line() {
        assert!(!truncate_msg("a\nb").contains('\n'));
        assert_eq!(truncate_msg("a\r\nb"), "a\\nb");
    }

    /// 落盘行的形状：`[时间] LEVEL [tag] msg`，且超长 msg 在整行里也带截断标注。
    #[test]
    fn format_log_line_shape_and_truncation() {
        let line = format_log_line("error", "console.error", &"x".repeat(MAX_MSG_CHARS + 1));
        assert!(line.starts_with('['), "行首必须是本地时间戳，实得：{}", line);
        assert!(
            line.contains("] ERROR [console.error] "),
            "LEVEL 必须大写、tag 必须带方括号，实得：{}",
            line
        );
        assert!(line.contains("已截断"), "整行里也必须能看出被截断");
    }

    /// 本地时间戳必须是**真实填充**的 `YYYY-MM-DD HH:MM:SS`。
    ///
    /// 要防住的反例：`GetLocalTime` 的 FFI 声明 / `SYSTEMTIME` 布局写错**不会编译报错**，
    /// 只会静默产出 `0000-00-00 00:00:00` —— 那等于整条日志通道的时间轴全废，
    /// 而「心跳断档」这类结论恰恰只能靠时间轴读出。
    #[test]
    fn local_timestamp_is_populated() {
        let ts = local_timestamp();
        assert_eq!(
            ts.len(),
            19,
            "时间戳必须是 YYYY-MM-DD HH:MM:SS / 19 字符，实得：{ts}"
        );
        let year: u32 = ts[..4].parse().expect("前 4 位必须是年份");
        assert!(
            year >= 2024,
            "年份必须真实填充（GetLocalTime 未生效会得 0000），实得：{ts}"
        );
    }

    /// 心跳行：note 缺失 / 空串都只写 `HEARTBEAT seq=N`，不留下悬空空格。
    #[test]
    fn heartbeat_line_omits_empty_note() {
        let none = format_heartbeat_line(7, None);
        assert!(none.ends_with("HEARTBEAT seq=7"), "实得：{}", none);
        let empty = format_heartbeat_line(7, Some(""));
        assert!(empty.ends_with("HEARTBEAT seq=7"), "实得：{}", empty);
        let with_note = format_heartbeat_line(8, Some("visibility=visible"));
        assert!(
            with_note.ends_with("HEARTBEAT seq=8 visibility=visible"),
            "实得：{}",
            with_note
        );
    }

    /// ⭐ 接线层守护（源码级）：两条命令必须 (a) 在 `main.rs` 的 `generate_handler!` 里注册、
    /// (b) 保持 `pub async fn` 且经 `spawn_blocking` 执行。
    ///
    /// 要防住的两类反例：
    /// ① **写了命令但没注册** —— 前端 `invoke` 恒失败，通道静默失效（且只在运行时才暴露）；
    /// ② 拆掉 `spawn_blocking` 外壳但保留 `pub async fn` —— **编译照样通过**，
    ///    而阻塞文件 IO 会占住 Tauri 命令处理线程（先例：`update_check` 的「点添加工作区卡顿」）。
    ///
    /// ⚠️ 同 `main.rs` 既有守护：单测里跑不起 `#[tauri::command]`（需 App 运行时），故只能做源码断言。
    #[test]
    fn commands_must_be_registered_and_run_on_blocking_pool() {
        // 只查 `#[cfg(test)]` 之前的生产段：否则下面的字符串字面量会匹配到自己 → 假绿
        let main_prod = include_str!("main.rs").split("#[cfg(test)]").next().unwrap_or("");
        for name in ["frontlog::frontend_log", "frontlog::frontend_heartbeat"] {
            assert!(
                main_prod.contains(name),
                "{} 必须在 main.rs 的 generate_handler! 里注册，否则前端 invoke 恒失败",
                name
            );
        }
        let prod = include_str!("frontlog.rs").split("#[cfg(test)]").next().unwrap_or("");
        for decl in ["pub async fn frontend_log", "pub async fn frontend_heartbeat"] {
            let start = prod.find(decl).unwrap_or_else(|| {
                panic!("{} 必须保持 `pub async fn`（改回同步 fn 会占住 Tauri 命令处理线程）", decl)
            });
            let open = start + prod[start..].find('{').expect("函数必有函数体");
            let mut depth = 0usize;
            let mut end = open;
            for (i, c) in prod[open..].char_indices() {
                match c {
                    '{' => depth += 1,
                    '}' => {
                        depth -= 1;
                        if depth == 0 {
                            end = open + i + 1;
                            break;
                        }
                    }
                    _ => {}
                }
            }
            assert!(
                prod[start..end].contains("spawn_blocking("),
                "{} 必须经 tauri::async_runtime::spawn_blocking 执行：阻塞文件 IO 同步跑会占住 \
                 Tauri 命令处理线程，期间全部 IPC 排队",
                decl
            );
        }
    }
}
