/** Compare actual form content, including selections, without depending on object identity or key order. */
export function draftFingerprint(value: unknown): string | undefined {
  try {
    return JSON.stringify(value, (_key, item) => item && typeof item === 'object' && !Array.isArray(item)
      ? Object.fromEntries(Object.keys(item).sort().map(key => [key, item[key]]))
      : item)
  } catch { return undefined }
}

export function draftHasChanges(baseline: string | undefined, current: unknown, explicitDirty?: boolean): boolean {
  if (explicitDirty !== undefined) return explicitDirty
  if (current === undefined && baseline === undefined) return false
  const snapshot = draftFingerprint(current)
  // A supplied draft that cannot be serialized must never silently lose input.
  return baseline === undefined || snapshot === undefined || snapshot !== baseline
}
