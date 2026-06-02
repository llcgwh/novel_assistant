<template>
  <Transition name="toast">
    <div v-if="appStore.toast.visible" :class="['toast', `toast-${appStore.toast.type}`]">
      <span class="toast-message">{{ appStore.toast.message }}</span>
      <button class="toast-close" @click="appStore.hideToast">&times;</button>
    </div>
  </Transition>
</template>

<script setup lang="ts">
import { watch } from 'vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()

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
  top: 20px;
  right: 20px;
  z-index: 9999;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 20px;
  border-radius: 8px;
  font-size: 14px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.15);
  max-width: 400px;
}

.toast-success {
  background-color: #f0fdf4;
  color: #166534;
  border: 1px solid #bbf7d0;
}

.toast-error {
  background-color: #fef2f2;
  color: #991b1b;
  border: 1px solid #fecaca;
}

.toast-info {
  background-color: #eff6ff;
  color: #1e40af;
  border: 1px solid #bfdbfe;
}

.toast-warning {
  background-color: #fffbeb;
  color: #92400e;
  border: 1px solid #fde68a;
}

.toast-message {
  flex: 1;
}

.toast-close {
  background: none;
  border: none;
  font-size: 18px;
  cursor: pointer;
  color: inherit;
  opacity: 0.6;
  padding: 0;
  line-height: 1;
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
