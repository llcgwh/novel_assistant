<template>
  <div
    class="studio-shell"
    :class="{ 'is-focused': studio.focused || reading }"
  >
    <WorkspaceHeader />
    <div v-if="focus.storageError || focus.syncError" class="focus-save-notice" role="status">
      <span>{{ focus.storageError || focus.syncError }}</span>
      <button class="btn-secondary" type="button" :disabled="focus.saving" @click="focus.retry">重试专注记录</button>
      <button class="btn-secondary" type="button" @click="focus.download">导出专注记录</button>
    </div>
    <main id="workspace-content" class="studio-content spatial-workspace">
      <div
        class="sheet-stage"
        :class="reading ? 'layout-reader' : `layout-${sheet.layout}`"
        :data-active-sheet="sheet.id"
      >
        <div
          v-if="sheet.id !== 'overview' && !reading"
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
    <MorphSheet v-if="!reading" :anchor="anchor" :sheet-id="sheet.id" />
  </div>
</template>
<script setup lang="ts">
import WorkspaceHeader from '@/components/layout/WorkspaceHeader.vue'
import { useStudioStore } from '@/stores/studio'
import { computed, provide, shallowRef, watch } from 'vue'
import { useRoute, onBeforeRouteLeave, onBeforeRouteUpdate } from 'vue-router'
import { useFocusStore } from '@/stores/focus'
import { sheetFor } from '@/utils/sheets'
import { sheetDockKey } from '@/composables/sheetDock'
import SheetDock from '@/components/studio/SheetDock.vue'
import MorphSheet from '@/components/studio/MorphSheet.vue'
const studio = useStudioStore()
const route = useRoute()
const focus = useFocusStore()
watch(() => Number(route.params.novelId), (id) => { if (id > 0) focus.load(id) }, { immediate: true })
function protectFocus() {
  focus.tick()
  return focus.localSafe
}
onBeforeRouteLeave(protectFocus)
onBeforeRouteUpdate((to, from) => to.params.novelId === from.params.novelId || protectFocus())
const sheet = computed(() => sheetFor(String(route.path.split('/').at(-1))))
const reading = computed(
  () => sheet.value.id === 'writing' && route.query.mode === 'read',
)
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
<style scoped>
.focus-save-notice { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin: 12px 24px 0; padding: 12px; border: 1px solid var(--line); border-radius: 12px; color: var(--text); background: var(--panel); font-size: 12px; }
.focus-save-notice span { flex: 1 1 180px; overflow-wrap: anywhere; }
.sheet-stage.layout-reader {
  display: block;
}
</style>
