export interface SelectOption {
  value: string | number
  label: string
  disabled?: boolean
  color?: string
}
export function moveSelectIndex(
  options: readonly SelectOption[],
  current: number,
  step: 1 | -1,
) {
  const count = options.length
  for (let offset = 1; offset <= count; offset++) {
    const start = current < 0 ? (step === 1 ? -1 : count) : current
    const index = (((start + offset * step) % count) + count) % count
    if (!options[index].disabled) return index
  }
  return -1
}
export function matchSelectOption(
  options: readonly SelectOption[],
  text: string,
  current: number,
) {
  let query = text.toLocaleLowerCase()
  if (query.length > 1 && [...query].every((letter) => letter === query[0]))
    query = query[0]
  for (
    let offset = query.length > 1 ? 0 : 1;
    offset <= options.length;
    offset++
  ) {
    const index =
      (Math.max(-1, current) + offset + options.length) % options.length
    const option = options[index]
    if (
      option &&
      !option.disabled &&
      option.label.toLocaleLowerCase().startsWith(query)
    )
      return index
  }
  return -1
}
export function selectMenuBox(
  rect: { left: number; top: number; bottom: number; width: number },
  viewportWidth: number,
  viewportHeight: number,
  count: number,
) {
  const margin = 12,
    gap = 6
  const width = Math.min(
    Math.max(rect.width, 184),
    Math.max(0, viewportWidth - margin * 2),
  )
  const below = Math.max(0, viewportHeight - rect.bottom - margin - gap)
  const above = Math.max(0, rect.top - margin - gap)
  const desired = Math.min(334, Math.max(1, count) * 40 + 14)
  const opensAbove = below < desired && above > below
  const height = Math.min(desired, opensAbove ? above : below)
  return {
    left: Math.max(margin, Math.min(rect.left, viewportWidth - width - margin)),
    top: opensAbove
      ? Math.max(margin, rect.top - gap - height)
      : rect.bottom + gap,
    width,
    height,
  }
}
