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
    :style="{ opacity: appStore.settings.backgroundOpacity }"
  ></div>
  <router-view :key="String(route.params.novelId || 'global')" />
  <BookPassage />
  <ThemeTransition />
  <ProjectSignature v-if="route.name !== 'NovelSelector'" />
  <Toast />
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import { watchEffect } from 'vue'
import { useStudioStore } from '@/stores/studio'
import Toast from '@/components/common/Toast.vue'
import BookPassage from '@/components/studio/BookPassage.vue'
import ThemeTransition from '@/components/studio/ThemeTransition.vue'
import ProjectSignature from '@/components/studio/ProjectSignature.vue'
import { useAppStore } from '@/stores/app'

const appStore = useAppStore()
const route = useRoute()
const studio = useStudioStore()
watchEffect(() => {
  document.documentElement.dataset.studioTheme = studio.theme
})
</script>

<style lang="scss">
// Global styles are imported in main.ts
</style>
