import { defineStore } from 'pinia'
import { ref, watch, onScopeDispose } from 'vue'
import { preserveThemeFocus, type StudioTheme, type ThemeSource } from '@/utils/themeTransition'

export type ThemePreference = StudioTheme | 'system'
type TransitionHandler = (from: StudioTheme, to: StudioTheme, source?: ThemeSource) => Promise<boolean>
type TransitionFinisher = (target?: StudioTheme) => Promise<void>

export const useStudioStore = defineStore('studio', () => {
  let initial: ThemePreference = 'dark'
  try {
    const saved = localStorage.getItem('ink-studio-theme')
    if (saved === 'light' || saved === 'system') initial = saved
  } catch { /* optional preference */ }
  let systemMedia: MediaQueryList | undefined
  try { if (typeof globalThis.matchMedia === 'function') systemMedia = globalThis.matchMedia('(prefers-color-scheme: dark)') } catch { /* Dark is the safe fallback. */ }
  const systemTheme = (): StudioTheme => systemMedia ? systemMedia.matches ? 'dark' : 'light' : 'dark'
  const themePreference = ref<ThemePreference>(initial)
  const theme = ref<StudioTheme>(initial === 'system' ? systemTheme() : initial)
  const focused = ref(false), commandsOpen = ref(false), spatialMotion = ref(true), themeTransitionBusy = ref(false)
  const bookArrival = ref<{ direction: 'open' | 'close'; novelId: number } | null>(null)
  let handler: TransitionHandler | null = null, finisher: TransitionFinisher | undefined, sequence = 0, disposed = false
  try { spatialMotion.value = localStorage.getItem('ink-spatial-motion') !== 'off' } catch { /* optional preference */ }
  watch(spatialMotion, value => { try { localStorage.setItem('ink-spatial-motion', value ? 'on' : 'off') } catch { /* session only */ } })
  watch(themePreference, value => { try { localStorage.setItem('ink-studio-theme', value) } catch { /* session only */ } })
  function commitTheme(target: StudioTheme) { theme.value = target }
  function motionEnabled() {
    try { return spatialMotion.value && typeof globalThis.matchMedia === 'function' && !globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches }
    catch { return false }
  }
  async function requestTheme(preference: ThemePreference, source?: ThemeSource) {
    if (disposed || themeTransitionBusy.value) return false
    const changedPreference = themePreference.value !== preference
    themePreference.value = preference
    const target = preference === 'system' ? systemTheme() : preference
    if (target === theme.value) return changedPreference
    const ticket = ++sequence, from = theme.value
    if (!handler || !motionEnabled()) { commitTheme(target); return true }
    themeTransitionBusy.value = true
    try { return await handler(from, target, source) }
    catch { return true }
    finally {
      if (ticket === sequence) { if (theme.value !== target) commitTheme(target); themeTransitionBusy.value = false }
    }
  }
  function toggleTheme(source?: ThemeSource) { return requestTheme(theme.value === 'dark' ? 'light' : 'dark', source) }
  function registerThemeTransition(run: TransitionHandler | null, finish?: TransitionFinisher) {
    handler = run; finisher = finish
    return () => { if (handler === run) { handler = null; finisher = undefined } }
  }
  function systemChanged() {
    if (disposed || themePreference.value !== 'system') return
    const ticket = ++sequence, target = systemTheme()
    if (!themeTransitionBusy.value || !finisher) { commitTheme(target); themeTransitionBusy.value = false; return }
    void Promise.resolve().then(() => finisher?.(target)).catch(() => {}).finally(() => {
      if (ticket === sequence && themePreference.value === 'system') { commitTheme(systemTheme()); themeTransitionBusy.value = false }
    })
  }
  try {
    if (systemMedia?.addEventListener) systemMedia.addEventListener('change', systemChanged)
    else systemMedia?.addListener(systemChanged)
  } catch { /* A static resolved theme remains usable. */ }
  onScopeDispose(() => {
    disposed = true; ++sequence
    try {
      if (systemMedia?.removeEventListener) systemMedia.removeEventListener('change', systemChanged)
      else systemMedia?.removeListener(systemChanged)
    } catch { /* Already detached. */ }
    void Promise.resolve().then(() => finisher?.()).catch(() => {}).finally(() => { themeTransitionBusy.value = false })
  })
  return { theme, themePreference, focused, commandsOpen, spatialMotion, bookArrival, themeTransitionBusy, toggleTheme, requestTheme, commitTheme, registerThemeTransition, preserveThemeFocus }
})
