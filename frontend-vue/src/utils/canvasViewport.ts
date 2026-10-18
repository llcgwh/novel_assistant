export interface CanvasPoint { x: number; y: number }
export interface CanvasViewport extends CanvasPoint { scale: number }
export const CANVAS_MIN_SCALE = 0.5
export const CANVAS_MAX_SCALE = 3

export function clampCanvasScale(scale: number) {
  return Math.max(CANVAS_MIN_SCALE, Math.min(CANVAS_MAX_SCALE, Number.isFinite(scale) ? scale : 1))
}
export function canvasWorldPoint(point: CanvasPoint, viewport: CanvasViewport): CanvasPoint {
  return { x: (point.x - viewport.x) / viewport.scale, y: (point.y - viewport.y) / viewport.scale }
}
export function canvasScreenPoint(point: CanvasPoint, viewport: CanvasViewport): CanvasPoint {
  return { x: point.x * viewport.scale + viewport.x, y: point.y * viewport.scale + viewport.y }
}
export function zoomCanvasViewport(viewport: CanvasViewport, scale: number, anchor: CanvasPoint): CanvasViewport {
  const next = clampCanvasScale(scale), world = canvasWorldPoint(anchor, viewport)
  return { scale: next, x: anchor.x - world.x * next, y: anchor.y - world.y * next }
}
export function canvasWheelFactor(delta: number, mode: number, height: number) {
  const pixels = delta * (mode === 1 ? 16 : mode === 2 ? height : 1)
  return Math.exp(-Math.max(-200, Math.min(200, pixels)) * 0.002)
}
