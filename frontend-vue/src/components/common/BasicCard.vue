<template>
  <div class="basic-card">
    <!-- Image area -->
    <div v-if="$slots.image || imageUrl" class="basic-card__image">
      <slot name="image">
        <img v-if="imageUrl" :src="imageUrl" :alt="title" />
      </slot>
    </div>

    <!-- Body -->
    <div class="basic-card__body">
      <!-- Header: title + status badge -->
      <div class="basic-card__header">
        <h3 class="basic-card__title">{{ title }}</h3>
        <StatusBadge v-if="status" :status="status" />
      </div>

      <!-- Description / custom content -->
      <div v-if="$slots.default || description" class="basic-card__content">
        <slot>
          <p v-if="description">{{ description }}</p>
        </slot>
      </div>

      <!-- Meta fields -->
      <div v-if="$slots.meta" class="basic-card__meta">
        <slot name="meta" />
      </div>

      <!-- Tags -->
      <div v-if="tags && tags.length > 0" class="basic-card__tags">
        <TagList :tags="tags" />
      </div>

      <!-- Actions -->
      <div v-if="$slots.actions" class="basic-card__actions">
        <slot name="actions" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import type { Tag } from '@/types/tag'
import StatusBadge from '@/components/common/StatusBadge.vue'
import TagList from '@/components/tags/TagList.vue'

defineOptions({
  name: 'BasicCard'
})

defineProps<{
  title: string
  imageUrl?: string
  description?: string
  status?: string
  tags?: Tag[]
}>()

defineSlots<{
  image?: () => void
  default?: () => void
  meta?: () => void
  actions?: () => void
}>()
</script>

<style scoped>
.basic-card {
  background: #fff;
  border: 1px solid #e0e0e0;
  border-radius: 8px;
  overflow: hidden;
  transition: box-shadow 0.2s ease;
}

.basic-card:hover {
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}

.basic-card__image {
  width: 100%;
  height: 180px;
  overflow: hidden;
  background: #f5f5f5;
}

.basic-card__image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.basic-card__body {
  padding: 16px;
}

.basic-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.basic-card__title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: #2c3e50;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.basic-card__content {
  margin-bottom: 8px;
}

.basic-card__content p {
  margin: 0;
  font-size: 13px;
  color: #666;
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.basic-card__meta {
  margin-bottom: 8px;
  font-size: 13px;
  color: #666;
}

.basic-card__tags {
  margin-bottom: 12px;
}

.basic-card__actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 12px;
  border-top: 1px solid #f0f0f0;
}
</style>
