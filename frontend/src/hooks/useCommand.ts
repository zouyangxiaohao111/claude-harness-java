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

  const execute = useCallback(async (name: string) => {
    await commandApi.executeBuiltin(name)
  }, [])

  useEffect(() => { void refresh() }, [refresh])

  return { builtins, error, execute }
}
