/** Decide whether a card surface, rather than one of its controls, requested editing. */
const INTERACTIVE = [
  'button', 'a[href]', 'input', 'select', 'textarea', 'label', 'summary',
  'audio[controls]', 'video[controls]', '[contenteditable]:not([contenteditable="false"])',
  '[role="button"]', '[role="link"]', '[role="checkbox"]', '[role="radio"]',
  '[role="switch"]', '[role="combobox"]', '[role="textbox"]', '[role="slider"]',
  '[tabindex]', '[data-no-card-edit]',
].join(',')

export function isCardEditEvent(event: MouseEvent | KeyboardEvent): boolean {
  if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) return false
  const card = event.currentTarget as Element | null
  const target = event.target as Node | null
  if (!card || card.nodeType !== 1 || !target || !card.contains(target)) return false

  if (event.type === 'keydown') {
    const key = event as KeyboardEvent
    if (key.key !== 'Enter' || key.repeat || key.isComposing || target !== card) return false
  } else if (event.type !== 'click' || (event as MouseEvent).button !== 0) return false

  const element = target.nodeType === 1 ? target as Element : target.parentElement
  const control = element?.closest(INTERACTIVE)
  if (control && control !== card) return false

  const selection = card.ownerDocument.getSelection()
  if (selection && !selection.isCollapsed) {
    if (card.contains(selection.anchorNode) || card.contains(selection.focusNode)) return false
    for (let index = 0; index < selection.rangeCount; index++) {
      if (selection.getRangeAt(index).intersectsNode(card)) return false
    }
  }
  return true
}
