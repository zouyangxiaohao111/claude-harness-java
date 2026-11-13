; NexusAI installer hooks (included by Tauri NSIS template, Unicode build).
;
; 1) Install: write the install dir to the per-user environment so a backend
;    started OUTSIDE the Tauri shell can still resolve <install>/data/workspace.
;    Runtime precedence: the shell injects NEXUSAI_INSTALL_DIR into the backend
;    process env (backend.rs), which wins over this registry value.
; 2) Uninstall: 用户数据默认保留；用户要删时用 **Tauri 自带的**「Delete the
;    application data」复选框（卸载确认页，默认不勾选）。
;
;    ⛔ 我们**不再自己弹 MessageBox**：
;       Tauri v2 的 NSIS 模板已在卸载确认页放了那个复选框
;       （模板函数 un.ConfirmShow 创建、un.ConfirmLeave 把状态写进
;       $DeleteAppDataCheckboxState；默认未勾选）。
;       再加一个自己的弹窗 = 用户面对两个"删不删数据"的询问，
;       而且会破坏 /S（静默卸载）与 /P（被动模式）流程。
;
;    ⛔ 但 Tauri 自己的处理只删 %APPDATA%\com.nexusai.client 与
;       %LOCALAPPDATA%\com.nexusai.client（Tauri 的 app-data 目录），
;       **我们的用户数据不在那里**：
;         - <install>\data\...  工作区 / 知识库原件 / 产物 / 各类缓存
;         - ~\.nexusai\...      **nexusai.db（真正的库）** / agents / plugins / logs
;       ⇒ 复选框勾选时，由本钩子把这两处一并删掉（= 用户说的"全部删除"）。
;
;    ⛔ 未勾选（默认）时**什么都不用做**：Tauri 只会逐条 Delete 它自己安装的
;       文件（data 不在其中），最后的 RMDir "$INSTDIR" 是**非递归**的
;       ⇒ <install>\data 原地留存，不会被删。
;       （曾经担心 Tauri 会 RMDir /r "$INSTDIR"，已核对生成的 installer.nsi：
;        38 处 RMDir 全是 /REBOOTOK 或普通 RMDir，**没有一处递归删 $INSTDIR**。）
;
;    WM_SETTINGCHANGE = 0x001A, HWND_BROADCAST = 0xFFFF.

!include LogicLib.nsh

; 配置自有根（NexusaiPaths.getAppConfigHomeDir() = {user.home}\.{appName}，
; appName 由 spring.application.name 固定为 nexusai）⇒ %USERPROFILE%\.nexusai
; ⛔ 别名化：便于测试时指向临时目录（勿在生产里改）。
!ifndef NEXUSAI_CONFIG_HOME
  !define NEXUSAI_CONFIG_HOME "$PROFILE\.nexusai"
!endif

; 旧版（≤0.1.74）卸载残留：把 data\workspace 搬到同级目录再搬回；
; 若上次卸载被中断 ⇒ 同级目录里会留下它。目标已存在时不搬也不删（不覆盖新数据、不丢旧数据）。
!define NEXUSAI_LEGACY_KEEP_DIR "$INSTDIR\..\NexusAI-workspace-keep"

!macro NSIS_HOOK_POSTINSTALL
  WriteRegExpandStr HKCU "Environment" "NEXUSAI_INSTALL_DIR" "$INSTDIR"
  System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; --- 环境变量（仅当仍指向本次安装） ---
  ReadRegStr $0 HKCU "Environment" "NEXUSAI_INSTALL_DIR"
  ${If} $0 == "$INSTDIR"
    DeleteRegValue HKCU "Environment" "NEXUSAI_INSTALL_DIR"
    System::Call 'user32::SendMessageTimeoutW(i 0xFFFF, i 0x001A, i 0, w "Environment", i 0, i 5000, *i .r0)'
  ${EndIf}

  ; --- 这里刻意不提示、不删除任何数据 ---
  ; 删除与否由用户在**卸载确认页的复选框**决定，
  ; 该状态在卸载段执行时已由模板写入 $DeleteAppDataCheckboxState
  ; ⇒ 实际删除放在 POSTUNINSTALL（此时应用已被 Tauri 关掉、文件不再被占用）。
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; 勾选了「Delete the application data」⇒ 把**我们的**数据也删掉
  ; （Tauri 只会删它自己的 %APPDATA%/%LOCALAPPDATA%\com.nexusai.client）。
  ; $UpdateMode <> 1：与 Tauri 自身逻辑一致 —— 更新模式下不删应用数据。
  ${If} $DeleteAppDataCheckboxState = 1
  ${AndIf} $UpdateMode <> 1
    ; ① <install>\data：工作区 / 知识库原件 / 产物 / 附件缓存
    RMDir /r "$INSTDIR\data"
    ; ② 配置自有根：nexusai.db（真正的库）/ agents / plugins / logs / settings
    ;    ⛔ 这里**不能**用 NSIS 的 RMDir /r：配置根里有指向安装目录的 **junction**
    ;       （image-cache / kb-storage / pdf-cache → <install>\data\workspace\default\*）。
    ;       实测（makensis + 真实 junction 夹具）：
    ;         - NSIS `RMDir /r`   → 不删穿目标（安全），但**删不掉 junction 本身**
    ;                              ⇒ 残留悬空链接
    ;         - cmd `rmdir /s /q` → 同样不删穿目标，且**能删掉 junction 本身** ⇒ 用它
    ;    用 cmd 时把路径加引号（路径含空格/中文时不加会解析错）。
    ;    ⛔ 用 NSIS **内置** ExecWait 而非 nsExec 插件：真实 installer.nsi 里
    ;       从未使用过 nsExec ⇒ 打包时未必携带该插件，用它会报"找不到插件"。
    ;       实测内置 ExecWait 同样能删掉 junction 本身且不删穿目标。
    ExecWait 'cmd /c rmdir /s /q "${NEXUSAI_CONFIG_HOME}"'
    ; ③ 上面把 data 删掉后 $INSTDIR 才可能为空 ⇒ 顺手清掉（否则留个空壳目录）
    RMDir "$INSTDIR"
    Goto nexusai_postuninstall_done
  ${EndIf}

  ; --- 未勾选（默认）：保留数据 ---
  ; <install>\data 原地留存（Tauri 不删它）。
  ; 兼容旧版残留：0.1.73/0.1.74 的卸载器会把 data\workspace 搬到同级目录再搬回，
  ; 上次卸载被中断就会留下 NexusAI-workspace-keep ⇒ 仅在目标不存在时搬回。
  IfFileExists "${NEXUSAI_LEGACY_KEEP_DIR}\*" 0 nexusai_postuninstall_done
    IfFileExists "$INSTDIR\data\workspace\*" 0 nexusai_legacy_move
      ; 目标已有 workspace（新版留下的）⇒ 旧残留保持原样，不动
      Goto nexusai_postuninstall_done
    nexusai_legacy_move:
    CreateDirectory "$INSTDIR\data"
    Rename "${NEXUSAI_LEGACY_KEEP_DIR}" "$INSTDIR\data\workspace"
  nexusai_postuninstall_done:
!macroend
