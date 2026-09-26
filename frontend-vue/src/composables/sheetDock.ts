import type { InjectionKey } from 'vue'

export const sheetDockKey: InjectionKey<{
  register: (element: HTMLElement) => void
  unregister: (element: HTMLElement) => void
}> = Symbol('sheet-dock')
