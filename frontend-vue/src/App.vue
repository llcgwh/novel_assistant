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
  <router-view :key="String(route.params.novelId || 'global')" />
  <Toast />
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import Toast from '@/components/common/Toast.vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const route = useRoute()
</script>

<style lang="scss">
// Global styles are imported in main.ts
</style>
