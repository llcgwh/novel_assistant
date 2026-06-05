<template>
  <Transition name="toast">
    <div v-if="appStore.toast.visible" :class="['toast', `toast-${appStore.toast.type}`]">
      <span class="toast-icon">{{ icon }}</span>
      <span class="toast-message">{{ appStore.toast.message }}</span>
      <button class="toast-close" @click="appStore.hideToast">&times;</button>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { watch, computed } from 'vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

const icon = computed(() => {
  const icons: Record<string, string> = {
    success: '✅',
    error: '❌',
    info: 'ℹ️',
    warning: '⚠️'
  }
  return icons[appStore.toast.type] || 'ℹ️'
})

let timer: ReturnType<typeof setTimeout> | null = null

watch(
  () => appStore.toast.visible,
  (visible) => {
    if (visible) {
      if (timer) clearTimeout(timer)
      timer = setTimeout(() => {
        appStore.hideToast()
      }, 3000)
    }
  }
)
</script>

<style scoped>
.toast {
  position: fixed;
  top: 24px;
  right: 24px;
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 22px;
  border-radius: 12px;
  font-size: 14px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.12);
  max-width: 420px;
  backdrop-filter: blur(20px);
  border: 1px solid;
}

.toast-icon {
  font-size: 18px;
  flex-shrink: 0;
}

.toast-success {
  background: rgba(0, 184, 148, 0.12);
  color: #0f5132;
  border-color: rgba(0, 184, 148, 0.3);
}

.toast-error {
  background: rgba(231, 76, 60, 0.12);
  color: #991b1b;
  border-color: rgba(231, 76, 60, 0.3);
}

.toast-info {
  background: rgba(108, 92, 231, 0.12);
  color: #1e3a8a;
  border-color: rgba(108, 92, 231, 0.3);
}

.toast-warning {
  background: rgba(243, 156, 18, 0.12);
  color: #92400e;
  border-color: rgba(243, 156, 18, 0.3);
}

.toast-message {
  flex: 1;
  font-weight: 500;
}

.toast-close {
  background: none;
  border: none;
  font-size: 20px;
  cursor: pointer;
  color: inherit;
  opacity: 0.5;
  padding: 0;
  line-height: 1;
  transition: opacity 0.2s;
}

.toast-close:hover {
  opacity: 1;
}

.toast-enter-active {
  transition: all 0.3s ease-out;
}

.toast-leave-active {
  transition: all 0.3s ease-in;
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(40px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(40px);
}
</style>
