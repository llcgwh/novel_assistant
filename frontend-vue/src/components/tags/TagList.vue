<template>
  <div class="element-tags" v-if="tags && tags.length > 0">
    <span
      v-for="tag in tags"
      :key="tag.id"
      class="element-tag"
      :style="{ backgroundColor: tag.color || '#95a5a6' }"
    >
      {{ tag.name }}
    </span>
  </div>
  <span v-else-if="showEmpty" class="no-tags">无标签</span>
</template>

<script setup lang="ts">
import type { Tag } from '@/types/tag'

withDefaults(defineProps<{
  tags?: Tag[]
  showEmpty?: boolean
}>(), {
  showEmpty: false
})
</script>
