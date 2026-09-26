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
