import { parseAppearance, defaultAppearance } from '@/utils/appearance'
import { defineStore } from 'pinia'
import { ref, reactive, watch } from 'vue'

export interface AppSettings {
  backgroundImage: string   // 全局背景图片 URL
  backgroundOpacity: number // 背景覆盖层透明度 0-1
}

export const useAppStore = defineStore('app', () => {
  // 全局加载状态
  const loading = ref(false)
  const error = ref<string | null>(null)

  // 模态框状态
  const modalVisible = ref(false)
  const modalComponent = ref<string | null>(null)
  const modalProps = ref<Record<string, unknown>>({})

  // Toast 状态
  const toast = reactive({
    sequence: 0,
    message: '',
    type: 'info' as 'success' | 'error' | 'info' | 'warning',
    visible: false
  })

  // 全局设置 - 从 localStorage 恢复
  let saved = { ...defaultAppearance }
  try {
    const raw = localStorage.getItem('app-settings')
    if (raw) saved = parseAppearance(JSON.parse(raw))
  } catch { /* A damaged or old preference must not prevent the app from opening. */ }
  const settings = reactive<AppSettings>(saved)

  // 持久化设置
  watch(
    () => ({ ...settings }),
    (newSettings) => {
      try { localStorage.setItem('app-settings', JSON.stringify(newSettings)) }
      catch { showToast('浏览器存储空间不足，外观设置暂未保存', 'warning') }
    },
    { deep: true }
  )

  // Actions
  function setLoading(value: boolean) {
    loading.value = value
  }

  function setError(message: string | null) {
    error.value = message
  }

  function openModal(component: string, props: Record<string, unknown> = {}) {
    modalComponent.value = component
    modalProps.value = props
    modalVisible.value = true
  }

  function closeModal() {
    modalVisible.value = false
    modalComponent.value = null
    modalProps.value = {}
  }

  function clearError() {
    error.value = null
  }

  function showToast(message: string, type: 'success' | 'error' | 'info' | 'warning' = 'info') {
    toast.sequence++
    toast.message = message
    toast.type = type
    toast.visible = true
  }

  function hideToast() {
    toast.visible = false
  }

  function updateSetting<K extends keyof AppSettings>(key: K, value: AppSettings[K]) {
    settings[key] = value
  }

  return {
    loading,
    error,
    modalVisible,
    modalComponent,
    modalProps,
    toast,
    settings,
    setLoading,
    setError,
    clearError,
    openModal,
    closeModal,
    showToast,
    hideToast,
    updateSetting
  }
})
