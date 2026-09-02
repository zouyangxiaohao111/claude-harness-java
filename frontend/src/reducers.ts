/**
 * Application reducers + matching `useXxx()` hooks.
 *
 * Each reducer owns a single slice of UI state. The exported `useXxx()` hooks
 * wrap `React.useReducer` and return `{ state, dispatch }`, mirroring the
 * pattern used elsewhere in the codebase. Logic here is kept bug-for-bug
 * compatible with the original `useState`-based handlers in `App.tsx`.
 */

import { useReducer } from 'react'
import type { Dispatch } from 'react'
import type { ContextMenuState, Project, SettingsTab, ThemeMode, FontSize } from './types'
import { allProjects } from './data'

/* ------------------------------------------------------------------ */
/*  Session slice                                                      */
/* ------------------------------------------------------------------ */

export type SessionAction =
  | { type: 'SWITCH'; sessionId: string }
  | { type: 'CLOSE_TAB'; tabId: string }
  | { type: 'ADD_TAB'; tabId: string }

export interface SessionState {
  activeSession: string
  centerTabId: string
  openSessions: string[]
}

const initialSessionState: SessionState = {
  activeSession: 'sess-msgbus',
  centerTabId: 'sess-msgbus',
  openSessions: ['sess-msgbus', 'sess-npe', 'sess-pg', 'sess-p1', 'sess-perf'],
}

function sessionReducer(state: SessionState, action: SessionAction): SessionState {
  switch (action.type) {
    case 'SWITCH':
      return {
        ...state,
        activeSession: action.sessionId,
        centerTabId: action.sessionId,
      }
    case 'CLOSE_TAB': {
      const next = state.openSessions.filter((id) => id !== action.tabId)
      // Mirrors App.tsx: never reduce to zero open sessions.
      if (next.length === 0) return state
      return { ...state, openSessions: next }
    }
    case 'ADD_TAB': {
      if (state.openSessions.includes(action.tabId)) return state
      return { ...state, openSessions: [...state.openSessions, action.tabId] }
    }
    default:
      return state
  }
}

/* ------------------------------------------------------------------ */
/*  Project slice                                                     */
/* ------------------------------------------------------------------ */

export type ProjectAction =
  | { type: 'PROMOTE'; newMain: Project }
  | { type: 'BIND'; project: Project }
  | { type: 'UNBIND'; name: string }
  | { type: 'TOGGLE_SUB'; name: string }
  | { type: 'FLASH'; name: string }
  | { type: 'CLEAR_FLASH' }
  | { type: 'TOGGLE_DROPDOWN' }
  | { type: 'CLOSE_DROPDOWN' }
  | { type: 'SET_ADD_SEARCH'; value: string }

export interface ProjectState {
  mainProject: Project
  subProjects: Project[]
  expandedSubs: Record<string, boolean>
  showDropdown: boolean
  flashingProject: string | null
  addSearch: string
}

const initialProjectState: ProjectState = {
  mainProject: allProjects[0],
  subProjects: allProjects.slice(1, 3),
  expandedSubs: {},
  showDropdown: false,
  flashingProject: null,
  addSearch: '',
}

function projectReducer(state: ProjectState, action: ProjectAction): ProjectState {
  switch (action.type) {
    case 'PROMOTE': {
      const oldMain = state.mainProject
      const filtered = state.subProjects.filter((p) => p.name !== action.newMain.name)
      return {
        ...state,
        mainProject: action.newMain,
        subProjects: [...filtered, oldMain],
      }
    }
    case 'BIND': {
      // Skip if already bound (as main or sub). Matches App.tsx handleBind guard.
      if (
        state.mainProject.name === action.project.name ||
        state.subProjects.some((p) => p.name === action.project.name)
      ) {
        return state
      }
      return { ...state, subProjects: [...state.subProjects, action.project] }
    }
    case 'UNBIND':
      return {
        ...state,
        subProjects: state.subProjects.filter((p) => p.name !== action.name),
      }
    case 'TOGGLE_SUB':
      return {
        ...state,
        expandedSubs: {
          ...state.expandedSubs,
          [action.name]: !state.expandedSubs[action.name],
        },
      }
    case 'FLASH':
      return { ...state, flashingProject: action.name }
    case 'CLEAR_FLASH':
      return { ...state, flashingProject: null }
    case 'TOGGLE_DROPDOWN':
      return { ...state, showDropdown: !state.showDropdown }
    case 'CLOSE_DROPDOWN':
      return { ...state, showDropdown: false }
    case 'SET_ADD_SEARCH':
      return { ...state, addSearch: action.value }
    default:
      return state
  }
}

/* ------------------------------------------------------------------ */
/*  UI slice                                                           */
/* ------------------------------------------------------------------ */

export type UIAction =
  | { type: 'TOGGLE_ADD_PANEL' }
  | { type: 'CLOSE_ADD_PANEL' }
  | { type: 'SET_SEARCH_QUERY'; value: string }
  | { type: 'TOGGLE_SEARCH' }
  | { type: 'CLOSE_SEARCH' }
  | { type: 'TOGGLE_SETTINGS' }
  | { type: 'CLOSE_SETTINGS' }
  | { type: 'TOGGLE_MODEL_DROPDOWN' }
  | { type: 'CLOSE_MODEL_DROPDOWN' }
  | { type: 'SET_RIGHT_TAB'; tab: 'files' | 'tasks' | 'projects' }
  | { type: 'SET_DIFF'; file: string | null }
  | { type: 'OPEN_CONTEXT_MENU'; menu: ContextMenuState }
  | { type: 'CLOSE_CONTEXT_MENU' }
  | { type: 'SHOW_TOAST'; msg: string; toastType: 'success' | 'info' }
  | { type: 'HIDE_TOAST' }

export interface UIState {
  showAddPanel: boolean
  showSearchPalette: boolean
  showSettings: boolean
  showModelDropdown: boolean
  rightTab: 'files' | 'tasks' | 'projects'
  diffFile: string | null
  contextMenu: ContextMenuState | null
  toast: { msg: string; type: 'success' | 'info' } | null
  searchQuery: string
}

const initialUIState: UIState = {
  showAddPanel: false,
  showSearchPalette: false,
  showSettings: false,
  showModelDropdown: false,
  rightTab: 'projects',
  diffFile: null,
  contextMenu: null,
  toast: null,
  searchQuery: '',
}

function uiReducer(state: UIState, action: UIAction): UIState {
  switch (action.type) {
    case 'TOGGLE_ADD_PANEL':
      return { ...state, showAddPanel: !state.showAddPanel }
    case 'CLOSE_ADD_PANEL':
      return { ...state, showAddPanel: false }
    case 'SET_SEARCH_QUERY':
      return { ...state, searchQuery: action.value }
    case 'TOGGLE_SEARCH':
      return { ...state, showSearchPalette: !state.showSearchPalette }
    case 'CLOSE_SEARCH':
      return { ...state, showSearchPalette: false }
    case 'TOGGLE_SETTINGS':
      return { ...state, showSettings: !state.showSettings }
    case 'CLOSE_SETTINGS':
      return { ...state, showSettings: false }
    case 'TOGGLE_MODEL_DROPDOWN':
      return { ...state, showModelDropdown: !state.showModelDropdown }
    case 'CLOSE_MODEL_DROPDOWN':
      return { ...state, showModelDropdown: false }
    case 'SET_RIGHT_TAB':
      return { ...state, rightTab: action.tab }
    case 'SET_DIFF':
      return { ...state, diffFile: action.file }
    case 'OPEN_CONTEXT_MENU':
      return { ...state, contextMenu: action.menu }
    case 'CLOSE_CONTEXT_MENU':
      return { ...state, contextMenu: null }
    case 'SHOW_TOAST':
      return { ...state, toast: { msg: action.msg, type: action.toastType } }
    case 'HIDE_TOAST':
      return { ...state, toast: null }
    default:
      return state
  }
}

/* ------------------------------------------------------------------ */
/*  Settings slice                                                     */
/* ------------------------------------------------------------------ */

export type SettingsAction =
  | { type: 'SET_THEME'; theme: ThemeMode }
  | { type: 'SET_FONT_SIZE'; size: FontSize }
  | { type: 'SET_ANIMATIONS'; enabled: boolean }
  | { type: 'SET_SETTINGS_TAB'; tab: SettingsTab }

export interface SettingsState {
  theme: ThemeMode
  fontSize: FontSize
  animationsEnabled: boolean
  settingsTab: SettingsTab
}

const initialSettingsState: SettingsState = {
  theme: 'light',
  fontSize: 'medium',
  animationsEnabled: true,
  settingsTab: 'general',
}

function settingsReducer(state: SettingsState, action: SettingsAction): SettingsState {
  switch (action.type) {
    case 'SET_THEME':
      return { ...state, theme: action.theme }
    case 'SET_FONT_SIZE':
      return { ...state, fontSize: action.size }
    case 'SET_ANIMATIONS':
      return { ...state, animationsEnabled: action.enabled }
    case 'SET_SETTINGS_TAB':
      return { ...state, settingsTab: action.tab }
    default:
      return state
  }
}

/* ------------------------------------------------------------------ */
/*  Hook wrappers                                                      */
/* ------------------------------------------------------------------ */

export function useSession(): { state: SessionState; dispatch: Dispatch<SessionAction> } {
  const [state, dispatch] = useReducer(sessionReducer, initialSessionState)
  return { state, dispatch }
}

export function useProject(): { state: ProjectState; dispatch: Dispatch<ProjectAction> } {
  const [state, dispatch] = useReducer(projectReducer, initialProjectState)
  return { state, dispatch }
}

export function useUI(): { state: UIState; dispatch: Dispatch<UIAction> } {
  const [state, dispatch] = useReducer(uiReducer, initialUIState)
  return { state, dispatch }
}

export function useSettings(): { state: SettingsState; dispatch: Dispatch<SettingsAction> } {
  const [state, dispatch] = useReducer(settingsReducer, initialSettingsState)
  return { state, dispatch }
}
