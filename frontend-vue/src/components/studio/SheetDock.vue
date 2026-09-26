<template>
  <div ref="dock" class="sheet-dock" aria-hidden="true">
    <span class="dock-registration"></span>
  </div>
</template>
<script setup lang="ts">
import { inject, onMounted, onBeforeUnmount, ref } from 'vue'
import { sheetDockKey } from '@/composables/sheetDock'
const dock = ref<HTMLElement | null>(null)
const host = inject(sheetDockKey)
onMounted(() => {
  if (dock.value) host?.register(dock.value)
})
onBeforeUnmount(() => {
  if (dock.value) host?.unregister(dock.value)
})
</script>
