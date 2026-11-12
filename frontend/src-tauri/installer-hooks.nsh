; NexusAI installer hooks (included by Tauri NSIS template, Unicode build).
;
; 1) Install: write the install dir to the per-user environment so a backend
;    started OUTSIDE the Tauri shell can still resolve <install>/data/workspace.
;    Runtime precedence: the shell injects NEXUSAI_INSTALL_DIR into the backend
;    process env (backend.rs), which wins over this registry value.
; 2) Uninstall: KEEP user data by default. The uninstaller moves
;    <install>/data/workspace OUT of $INSTDIR before Tauri's RMDir /r runs,
;    then restores it afterwards. Only an explicit "Yes" in the prompt deletes it.
;    Silent uninstall (/S) also keeps data (safe default).
;
; WM_SETTINGCHANGE = 0x001A, HWND_BROADCAST = 0xFFFF.

!include LogicLib.nsh

!define NEXUSAI_KEEP_DIR "$INSTDIR\..\NexusAI-workspace-keep"

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

  ; --- 用户数据：默认保留；静默卸载也保留 ---
  IfSilent nexusai_keep_data

  MessageBox MB_YESNO|MB_ICONQUESTION \
    "是否同时删除用户数据？$\r$\n$\r$\n包括：工作区、知识库原文、产物、附件缓存。$\r$\n$\r$\n选择「否」将保留这些数据，重新安装后可继续使用。" \
    IDYES nexusai_delete_data IDNO nexusai_keep_data

  nexusai_delete_data:
    ; 不搬走 —— 随安装目录一起删除
    Goto nexusai_uninstall_done

  nexusai_keep_data:
    StrCpy $1 "$INSTDIR\data\workspace"
    IfFileExists "$1\*.*" 0 nexusai_uninstall_done
    ; 目标在同一父目录下（同盘 rename，瞬间完成）；先清掉上次卸载的残留
    IfFileExists "${NEXUSAI_KEEP_DIR}\*.*" 0 nexusai_no_old_keep
      RMDir /r "${NEXUSAI_KEEP_DIR}"
    nexusai_no_old_keep:
    Rename "$1" "${NEXUSAI_KEEP_DIR}"

  nexusai_uninstall_done:
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
  ; 恢复被保留的 workspace（Tauri 的 RMDir /r 已执行完）
  IfFileExists "${NEXUSAI_KEEP_DIR}\*.*" nexusai_restore_keep
    Goto nexusai_postuninstall_done
  nexusai_restore_keep:
    CreateDirectory "$INSTDIR\data"
    Rename "${NEXUSAI_KEEP_DIR}" "$INSTDIR\data\workspace"
  nexusai_postuninstall_done:
!macroend
