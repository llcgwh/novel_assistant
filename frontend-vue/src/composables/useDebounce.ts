export function useDebounce(delay = 300) {
  let timeout: ReturnType<typeof setTimeout> | null = null

  function debounce(fn: () => void) {
    if (timeout) clearTimeout(timeout)
    timeout = setTimeout(fn, delay)
  }

  function cleanup() {
    if (timeout) clearTimeout(timeout)
  }

  return { debounce, cleanup }
}
