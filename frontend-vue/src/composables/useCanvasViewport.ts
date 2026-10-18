import { onBeforeUnmount, reactive, ref, type Ref } from 'vue'
import { canvasScreenPoint, canvasWheelFactor, canvasWorldPoint, zoomCanvasViewport, type CanvasPoint } from '@/utils/canvasViewport'

/** View-only transforms in CSS pixels; callers retain their original data coordinates. */
export function useCanvasViewport(canvas: Ref<HTMLCanvasElement | null>, onChange: () => void = () => {}) {
  const state = reactive({ scale: 1, x: 0, y: 0 })
  const dragging = ref(false)
  let frame = 0, suppressClick = false
  let pointer: { id: number; clientX: number; clientY: number; x: number; y: number } | null = null
  function changed() {
    if (!frame) frame = requestAnimationFrame(() => { frame = 0; onChange() })
  }
  function localPoint(event: { clientX: number; clientY: number }): CanvasPoint {
    const rect = canvas.value?.getBoundingClientRect()
    return { x: event.clientX - (rect?.left || 0), y: event.clientY - (rect?.top || 0) }
  }
  function toWorld(event: { clientX: number; clientY: number }) { return canvasWorldPoint(localPoint(event), state) }
  function toScreen(point: CanvasPoint) { return canvasScreenPoint(point, state) }
  function zoomBy(factor: number, anchor?: CanvasPoint) {
    if (!Number.isFinite(factor) || factor <= 0) return
    const rect = canvas.value?.getBoundingClientRect()
    Object.assign(state, zoomCanvasViewport(state, state.scale * factor, anchor || { x: (rect?.width || 0) / 2, y: (rect?.height || 0) / 2 }))
    changed()
  }
  function reset() { Object.assign(state, { scale: 1, x: 0, y: 0 }); changed() }
  function onWheel(event: WheelEvent) {
    event.preventDefault()
    zoomBy(canvasWheelFactor(event.deltaY, event.deltaMode, canvas.value?.clientHeight || 600), localPoint(event))
  }
  function onPointerDown(event: PointerEvent) {
    if (event.button !== 0 || pointer) return
    suppressClick = false
    pointer = { id: event.pointerId, clientX: event.clientX, clientY: event.clientY, x: state.x, y: state.y }
    canvas.value?.focus({ preventScroll: true })
    canvas.value?.setPointerCapture(event.pointerId)
  }
  function onPointerMove(event: PointerEvent) {
    if (!pointer || pointer.id !== event.pointerId) return
    const dx = event.clientX - pointer.clientX, dy = event.clientY - pointer.clientY
    if (!dragging.value && Math.hypot(dx, dy) < 6) return
    event.preventDefault()
    dragging.value = true
    suppressClick = true
    state.x = pointer.x + dx; state.y = pointer.y + dy
    changed()
  }
  function endPointer(event: PointerEvent, cancelled = false) {
    if (!pointer || pointer.id !== event.pointerId) return
    const id = pointer.id
    pointer = null
    if (cancelled) suppressClick = true
    dragging.value = false
    if (canvas.value?.hasPointerCapture(id)) canvas.value.releasePointerCapture(id)
  }
  function onPointerUp(event: PointerEvent) { endPointer(event) }
  function onPointerCancel(event: PointerEvent) { endPointer(event, true) }
  function onLostPointerCapture(event: PointerEvent) { endPointer(event, true) }
  function consumeClick() { const suppressed = suppressClick; suppressClick = false; return suppressed }
  function onKeydown(event: KeyboardEvent) {
    if (event.ctrlKey || event.metaKey || event.altKey) return
    const step = event.shiftKey ? 100 : 40
    if (event.key === '+' || event.key === '=') zoomBy(1.2)
    else if (event.key === '-') zoomBy(1 / 1.2)
    else if (event.key === '0' || event.key === 'Home') reset()
    else if (event.key === 'ArrowLeft') { state.x -= step; changed() }
    else if (event.key === 'ArrowRight') { state.x += step; changed() }
    else if (event.key === 'ArrowUp') { state.y -= step; changed() }
    else if (event.key === 'ArrowDown') { state.y += step; changed() }
    else return
    event.preventDefault()
  }
  onBeforeUnmount(() => {
    cancelAnimationFrame(frame)
    const id = pointer?.id
    pointer = null
    if (id !== undefined && canvas.value?.hasPointerCapture(id)) canvas.value.releasePointerCapture(id)
  })
  return { state, dragging, toWorld, toScreen, zoomBy, reset, onWheel, onPointerDown, onPointerMove, onPointerUp, onPointerCancel, onLostPointerCapture, onKeydown, consumeClick }
}
