<template>
  <div class="tag-selector">
    <label
      v-for="tag in tags"
      :key="tag.id"
      class="tag-checkbox"
    >
      <input
        type="checkbox"
        :value="tag.id"
        :checked="modelValue.includes(tag.id)"
        @change="toggleTag(tag.id)"
      />
      <span
        class="tag-label"
        :style="{ backgroundColor: tag.color || '#95a5a6' }"
      >
        {{ tag.name }}
      </span>
    </label>
    <span v-if="tags.length === 0" class="no-tags">暂无标签</span>
  </div>
</template>

<script setup lang="ts">
import type { Tag } from '@/types/tag'

const props = defineProps<{
  tags: Tag[]
  modelValue: number[]
}>()

const emit = defineEmits<{
  'update:modelValue': [value: number[]]
}>()

function toggleTag(tagId: number) {
  const newValue = props.modelValue.includes(tagId)
    ? props.modelValue.filter(id => id !== tagId)
    : [...props.modelValue, tagId]
  emit('update:modelValue', newValue)
}
</script>
