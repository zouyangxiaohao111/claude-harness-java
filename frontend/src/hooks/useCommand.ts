import { useCallback, useEffect, useState } from 'react'
import { commandApi } from '../api/command'
import { ApiError } from '../api/rest'
import type { BuiltInCommandDto } from '../api/command'

export function useCommand() {
  const [builtins, setBuiltins] = useState<BuiltInCommandDto[]>([])
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try { setBuiltins(await commandApi.builtins()) }
    catch (e) { setError(e instanceof ApiError ? e.userMessage() : String(e)) }
  }, [])

  // [批 3a] 后端口 sessionId 必传（缺值 400）——本 hook 是 CLI 时代的薄封装，无会话源，
  //   故把 sessionId 上提为调用方入参（本 hook 目前无调用方，保留不删）。
  const execute = useCallback(async (name: string, sessionId: string) => {
    await commandApi.executeBuiltin(name, sessionId)
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  return { builtins, error, execute }
}
