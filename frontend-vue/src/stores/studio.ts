import { defineStore } from 'pinia'
import { ref, watch } from 'vue'
export const useStudioStore = defineStore('studio', () => {
  let initial: 'dark' | 'light' = 'dark'
  try {
    if (localStorage.getItem('ink-studio-theme') === 'light') initial = 'light'
  } catch {
    /* optional preference */
  }
  const theme = ref(initial),
    focused = ref(false),
    commandsOpen = ref(false)
  watch(theme, (value) => {
    try {
      localStorage.setItem('ink-studio-theme', value)
    } catch {
      /* keep theme for this session */
    }
  })
  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark'
  }
  return { theme, focused, commandsOpen, toggleTheme }
})
