import { computed, inject, onBeforeUnmount, provide, shallowReactive, type InjectionKey } from 'vue'

type RegisterDraft = (hasChanges: () => boolean) => () => void
const childDrafts: InjectionKey<RegisterDraft> = Symbol('modal-child-drafts')

/** Each dialog owns only its descendants; a nested dialog starts its own scope. */
export function provideModalDrafts() {
  const drafts = shallowReactive(new Map<symbol, () => boolean>())
  provide(childDrafts, (hasChanges) => {
    const id = Symbol('child-draft')
    drafts.set(id, hasChanges)
    return () => drafts.delete(id)
  })
  onBeforeUnmount(() => drafts.clear())
  return computed(() => [...drafts.values()].some(hasChanges => {
    try { return hasChanges() } catch { return true }
  }))
}

export function useModalDraftGuard(hasChanges: () => boolean) {
  const register = inject(childDrafts, null)
  if (register) onBeforeUnmount(register(hasChanges))
}
