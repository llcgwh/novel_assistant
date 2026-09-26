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
  const spatialMotion = ref(true)
  try {
    spatialMotion.value = localStorage.getItem('ink-spatial-motion') !== 'off'
  } catch {
    /* optional preference */
  }
  watch(spatialMotion, (value) => {
    try {
      localStorage.setItem('ink-spatial-motion', value ? 'on' : 'off')
    } catch {
      /* session only */
    }
  })
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
  return { theme, focused, commandsOpen, spatialMotion, toggleTheme }
})
