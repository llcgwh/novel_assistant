// Map records use the fixed canvas coordinate space, regardless of its CSS size.
export function mapPoint(
  clientX: number,
  clientY: number,
  rect: { left: number; top: number; width: number; height: number },
  width: number,
  height: number,
) {
  return {
    x: Math.round(
      Math.max(
        0,
        Math.min(width, ((clientX - rect.left) * width) / rect.width),
      ),
    ),
    y: Math.round(
      Math.max(
        0,
        Math.min(height, ((clientY - rect.top) * height) / rect.height),
      ),
    ),
  }
}

// Viewport pan/zoom is removed before this conversion. Stored map positions
// remain in the original fixed map space, never in CSS or backing pixels.
export function mapWorldPoint(
  point: { x: number; y: number },
  display: { width: number; height: number },
  width = 800,
  height = 600,
) {
  if (display.width <= 0 || display.height <= 0) return null
  const x = point.x * width / display.width, y = point.y * height / display.height
  return Number.isFinite(x) && Number.isFinite(y) ? { x, y } : null
}

export function mapContainsPoint(point: { x: number; y: number }, width = 800, height = 600) {
  return Number.isFinite(point.x) && Number.isFinite(point.y) && point.x >= 0 && point.x <= width && point.y >= 0 && point.y <= height
}

export function mapMarkerAt<T extends { positionX?: number; positionY?: number }>(
  locations: readonly T[],
  point: { x: number; y: number },
  radiusX: number,
  radiusY = radiusX,
): T | undefined {
  if (!(radiusX > 0) || !(radiusY > 0)) return undefined
  let selected: T | undefined, closest = Infinity
  for (const location of locations) {
    if (!Number.isFinite(location.positionX) || !Number.isFinite(location.positionY)) continue
    const distance = ((location.positionX! - point.x) / radiusX) ** 2 + ((location.positionY! - point.y) / radiusY) ** 2
    if (distance <= 1 && distance <= closest) { selected = location; closest = distance }
  }
  return selected
}
