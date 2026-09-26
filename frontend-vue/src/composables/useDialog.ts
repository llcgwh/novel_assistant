import { onMounted, onBeforeUnmount, nextTick, type Ref } from 'vue'

const dialogs: HTMLElement[] = []
let previousOverflow = ''
let previousInert = false

export function useDialog(panel: Ref<HTMLElement | null>, busy: () => boolean, close: () => void) {
  let opener: HTMLElement | null = null
  const focusables = () => Array.from(panel.value?.querySelectorAll<HTMLElement>(
    'button:not(:disabled), input:not(:disabled), textarea:not(:disabled), select:not(:disabled), a[href], [tabindex]:not([tabindex="-1"])'
  ) || []).filter(el => el.getClientRects().length && !el.closest('[disabled], [inert]'))
  function keydown(event: KeyboardEvent) {
    if (dialogs.at(-1) !== panel.value) return
    if (event.key === 'Escape') {
      // Close the select popup before dismissing its parent dialog.
      if ((event.target as Element)?.closest('[role="combobox"][aria-expanded="true"]')) return
      event.preventDefault(); event.stopPropagation()
      if (!busy()) close()
    }
    if (event.key === 'Tab') {
      const elements = focusables()
      const first = elements[0], last = elements.at(-1)
      if (!first) { event.preventDefault(); panel.value?.focus(); return }
      const active = document.activeElement
      if (event.shiftKey && (active === first || !elements.includes(active as HTMLElement))) {
        event.preventDefault(); last?.focus()
      } else if (!event.shiftKey && (active === last || !elements.includes(active as HTMLElement))) {
        event.preventDefault(); first.focus()
      }
    }
  }
  onMounted(async () => {
    opener = document.activeElement as HTMLElement | null
    if (!dialogs.length) {
      previousOverflow = document.body.style.overflow
      previousInert = document.getElementById('app')?.inert || false
      document.body.style.overflow = 'hidden'
      const app = document.getElementById('app'); if (app) app.inert = true
    }
    dialogs.push(panel.value!)
    document.addEventListener('keydown', keydown, true)
    await nextTick()
    if (panel.value && dialogs.at(-1) === panel.value) (focusables()[0] || panel.value).focus()
  })
  onBeforeUnmount(() => {
    const index = dialogs.indexOf(panel.value!)
    const wasTop = index === dialogs.length - 1
    if (index !== -1) dialogs.splice(index, 1)
    document.removeEventListener('keydown', keydown, true)
    if (!dialogs.length) {
      document.body.style.overflow = previousOverflow
      const app = document.getElementById('app'); if (app) app.inert = previousInert
    }
    if (wasTop && opener?.isConnected && !opener.closest('[inert]')) opener.focus()
  })
}
