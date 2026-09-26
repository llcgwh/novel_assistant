<template>
  <div class="studio-shell" :class="{ 'is-focused': studio.focused }">
    <WorkspaceHeader />
    <main id="workspace-content" class="studio-content spatial-workspace">
      <div
        class="sheet-stage"
        :class="`layout-${sheet.layout}`"
        :data-active-sheet="sheet.id"
      >
        <div
          v-if="sheet.id !== 'overview'"
          :key="sheet.id"
          class="sheet-dock-column"
        >
          <SheetDock />
        </div>
        <div class="sheet-page">
          <router-view v-slot="{ Component }"
            ><component :is="Component"
          /></router-view>
        </div>
      </div>
    </main>
    <MorphSheet :anchor="anchor" :sheet-id="sheet.id" />
  </div>
</template>
<script setup lang="ts">
import WorkspaceHeader from '@/components/layout/WorkspaceHeader.vue'
import { useStudioStore } from '@/stores/studio'
import { computed, provide, shallowRef } from 'vue'
import { useRoute } from 'vue-router'
import { sheetFor } from '@/utils/sheets'
import { sheetDockKey } from '@/composables/sheetDock'
import SheetDock from '@/components/studio/SheetDock.vue'
import MorphSheet from '@/components/studio/MorphSheet.vue'
const studio = useStudioStore()
const route = useRoute()
const sheet = computed(() => sheetFor(String(route.path.split('/').at(-1))))
const anchor = shallowRef<HTMLElement | null>(null)
provide(sheetDockKey, {
  register: (element) => {
    anchor.value = element
  },
  unregister: (element) => {
    if (anchor.value === element) anchor.value = null
  },
})
</script>
