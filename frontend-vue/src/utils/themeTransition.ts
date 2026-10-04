export type StudioTheme = 'dark' | 'light'
export type ThemeSource = Event | Element | null
export type ThemePhase = 'cover' | 'hold' | 'morph' | 'fade'
export const THEME_PHASES = Object.freeze({ cover: 260, hold: 140, morph: 380, fade: 200 })

interface TransitionAdapter {
  enabled(): boolean
  show(from: StudioTheme, to: StudioTheme, source?: ThemeSource): void
  phase(name: ThemePhase, duration: number): Promise<unknown>
  commit(target: StudioTheme): void
  nextTick(): Promise<unknown>
  cleanup(): void
}

/** A flight owns its commit and cleanup; late promises cannot touch a newer flight. */
export function createThemeTransition(adapter: TransitionAdapter) {
  type Flight = { target: StudioTheme; committed: StudioTheme | null; finishing: boolean; done: Promise<boolean>; resolve(value: boolean): void }
  let active: Flight | null = null, disposed = false
  const current = (flight: Flight) => active === flight && !flight.finishing
  function commit(flight: Flight) {
    if (flight.committed === flight.target) return
    flight.committed = flight.target
    adapter.commit(flight.target)
  }
  async function complete(flight: Flight) {
    if (flight.finishing) { await flight.done; return }
    flight.finishing = true
    try { commit(flight); await adapter.nextTick() }
    catch { /* Cleanup must run even when the host is being removed. */ }
    finally {
      try { adapter.cleanup() } catch { /* The host may already be gone. */ }
      if (active === flight) active = null
      flight.resolve(true)
    }
  }
  async function run(flight: Flight, from: StudioTheme, source?: ThemeSource) {
    try {
      if (!adapter.enabled()) return
      adapter.show(from, flight.target, source)
      await adapter.nextTick()
      for (const name of ['cover', 'hold', 'morph'] as const) {
        if (!current(flight)) return
        await adapter.phase(name, THEME_PHASES[name])
      }
      if (!current(flight)) return
      commit(flight)
      await adapter.nextTick()
      if (!current(flight)) return
      await adapter.phase('fade', THEME_PHASES.fade)
    } catch { /* Failed or cancelled animation still fulfils the chosen theme. */ }
    finally { if (active === flight) await complete(flight) }
  }
  function request(from: StudioTheme, to: StudioTheme, source?: ThemeSource): Promise<boolean> {
    if (disposed || active || from === to) return Promise.resolve(false)
    let resolve!: (value: boolean) => void
    const done = new Promise<boolean>(yes => { resolve = yes })
    const flight: Flight = { target: to, committed: null, finishing: false, done, resolve }
    active = flight
    void run(flight, from, source)
    return done
  }
  async function finish(target?: StudioTheme) {
    const flight = active
    if (!flight) return
    if (target) {
      flight.target = target
      if (flight.finishing) { try { commit(flight) } catch { /* best effort during teardown */ } }
    }
    await complete(flight)
  }
  async function dispose(target?: StudioTheme) { disposed = true; await finish(target) }
  return { get busy() { return !!active }, request, finish, dispose }
}

export function resolveThemeOrigin(source: ThemeSource | undefined, width: number, height: number) {
  width = Number.isFinite(width) && width > 0 ? width : 1
  height = Number.isFinite(height) && height > 0 ? height : 1
  let x = width / 2, y = height / 2
  try {
    const candidate = source && 'currentTarget' in source ? source.currentTarget : source
    if (candidate && 'getBoundingClientRect' in candidate && typeof candidate.getBoundingClientRect === 'function') {
      const box = candidate.getBoundingClientRect()
      if ([box.left, box.top, box.width, box.height].every(Number.isFinite) && box.width > 0 && box.height > 0) {
        x = Math.max(0, Math.min(width, box.left + box.width / 2))
        y = Math.max(0, Math.min(height, box.top + box.height / 2))
      }
    }
  } catch { /* Detached or synthetic sources start at the viewport centre. */ }
  return { x, y, radius: Math.hypot(Math.max(x, width - x), Math.max(y, height - y)) + 2 }
}

/** Preserve a text editor's focus for mouse clicks; keyboard activation is untouched. */
export function preserveThemeFocus(event: PointerEvent) {
  if (event.button !== 0 || (event.pointerType && event.pointerType !== 'mouse')) return
  const active = globalThis.document?.activeElement
  if (active?.matches('input,textarea,[contenteditable]:not([contenteditable="false"])') || active?.closest('[contenteditable]:not([contenteditable="false"])')) event.preventDefault()
}
