<template>
  <img
    v-if="appStore.settings.backgroundImage"
    id="global-bg-layer"
    :src="appStore.settings.backgroundImage"
    class="visible"
    alt=""
  />
  <div
    v-if="appStore.settings.backgroundImage"
    id="global-bg-overlay"
    class="visible"
    :style="{ background: `rgba(255, 255, 255, ${appStore.settings.backgroundOpacity})` }"
  ></div>
  <router-view />
  <Toast />
</template>

<script setup lang="ts">
import Toast from '@/components/common/Toast.vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
</script>

<style lang="scss">
// Global styles are imported in main.ts
</style>
