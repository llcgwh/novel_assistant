import { defineStore } from 'pinia'
import { ref, reactive } from 'vue'

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
    message: '',
    type: 'info' as 'success' | 'error' | 'info' | 'warning',
    visible: false
  })

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
    toast.message = message
    toast.type = type
    toast.visible = true
  }

  function hideToast() {
    toast.visible = false
  }

  return {
    loading,
    error,
    modalVisible,
    modalComponent,
    modalProps,
    toast,
    setLoading,
    setError,
    clearError,
    openModal,
    closeModal,
    showToast,
    hideToast
  }
})
