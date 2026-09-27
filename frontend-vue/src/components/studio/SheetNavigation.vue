<template>
  <nav class="sheet-footer" aria-label="创作模块翻页">
    <RouterLink
      :to="base + previous.id"
      :aria-label="`上一个模块：${previous.label}`"
      :title="`上一个模块：${previous.label}`"
    >
      <StudioIcon name="arrow" />
    </RouterLink>
    <span
      :title="`创作导航第 ${Number(serial)} 个模块`"
      :aria-label="`${profile.label}，创作导航第 ${Number(serial)} 个模块`"
      ><b>{{ profile.label }}</b> / {{ serial }}</span
    >
    <RouterLink
      :to="base + next.id"
      :aria-label="`下一个模块：${next.label}`"
      :title="`下一个模块：${next.label}`"
    >
      <StudioIcon name="arrow" />
    </RouterLink>
  </nav>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import StudioIcon from '@/components/common/StudioIcon.vue'
import {
  adjacentSheet,
  sheetFor,
  sheetSerial,
  type SheetId,
} from '@/utils/sheets'

const props = defineProps<{ sheetId: SheetId }>()
const route = useRoute()
const base = computed(() => `/novel/${route.params.novelId}/`)
const profile = computed(() => sheetFor(props.sheetId))
const serial = computed(() => sheetSerial(props.sheetId))
const previous = computed(() => adjacentSheet(props.sheetId, -1))
const next = computed(() => adjacentSheet(props.sheetId, 1))
</script>
