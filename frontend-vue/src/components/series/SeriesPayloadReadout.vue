<template>
  <dl class="series-payload-readout">
    <div v-for="field in fields" :key="field.key" class="series-readout-field">
      <dt>{{ field.label }}</dt>
      <dd><span v-if="payload[field.key] === ''" class="series-empty">（空）</span><span v-else>{{ payload[field.key] }}</span></dd>
    </div>
  </dl>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SeriesKind, SeriesPayload } from '@/types/series'
import { seriesFields } from '@/utils/series'

const props = defineProps<{ kind: SeriesKind; payload: SeriesPayload }>()
const fields = computed(() => seriesFields(props.kind))
</script>

<style scoped>
.series-payload-readout { min-width: 0; margin: 0; color: var(--text); }
.series-readout-field { display: grid; grid-template-columns: 90px minmax(0, 1fr); gap: 14px; padding: 14px 0; border-bottom: 1px solid var(--line); }
.series-readout-field:last-child { border-bottom: 0; }
.series-readout-field dt { color: var(--muted); font-size: 12px; line-height: 1.9; }
.series-readout-field dd { min-width: 0; margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.9; }
.series-empty { color: var(--muted); }
@media (max-width: 600px) {
  .series-readout-field { grid-template-columns: minmax(0, 1fr); gap: 5px; }
}
</style>
