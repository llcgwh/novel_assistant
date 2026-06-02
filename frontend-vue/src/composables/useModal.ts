import { ref } from 'vue'

export function useModal<T = any>() {
  const isOpen = ref(false)
  const data = ref<T | null>(null)

  function open(payload?: T) {
    isOpen.value = true
    if (payload !== undefined) data.value = payload
  }

  function close() {
    isOpen.value = false
    data.value = null
  }

  return { isOpen, data, open, close }
}
