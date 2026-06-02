<template>
  <span class="status-badge" :class="status">{{ label }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = defineProps<{
  status: string
}>()

const statusLabels: Record<string, string> = {
  'PLANNING': '规划中',
  'WRITING': '写作中',
  'COMPLETED': '已完成',
  'UNREVEALED': '未揭示',
  'REVEALED': '已揭示',
  'ABANDONED': '已废弃',
  'planning': '规划中',
  'writing': '写作中',
  'completed': '已完成',
  'pending': '待揭示',
  'revealed': '已揭示',
  'abandoned': '已废弃',
  'paused': '已暂停'
}

const label = computed(() => statusLabels[props.status] || props.status)
</script>
