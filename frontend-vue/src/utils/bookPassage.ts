export interface PassageRoute {
  path: string
  params: Record<string, unknown>
}
export function bookPassage(to: PassageRoute, from: PassageRoute) {
  const entering = from.path === '/' && !!to.params.novelId
  const leaving = to.path === '/' && !!from.params.novelId
  if (!entering && !leaving) return null
  const novelId = Number(entering ? to.params.novelId : from.params.novelId)
  if (!Number.isSafeInteger(novelId) || novelId < 1) return null
  return {
    novelId,
    direction: entering ? ('open' as const) : ('close' as const),
  }
}

export function visibleBookBox(
  rect: { left: number; top: number; width: number; height: number },
  width: number,
  height: number,
) {
  return (
    rect.width > 0 &&
    rect.height > 0 &&
    rect.left < width &&
    rect.top < height &&
    rect.left + rect.width > 0 &&
    rect.top + rect.height > 0
  )
}
