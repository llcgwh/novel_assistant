<template>
  <div class="empty-state">
    <div class="empty-art" aria-hidden="true"><StudioIcon name="spark" /></div>
    <h3>{{ title }}</h3>
    <p>{{ message }}</p>
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
import StudioIcon from './StudioIcon.vue'
defineProps<{
  title?: string
  message?: string
}>()
</script>
