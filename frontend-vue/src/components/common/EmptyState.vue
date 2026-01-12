<template>
  <div class="empty-state">
    <h3>{{ title }}</h3>
    <p>{{ message }}</p>
    <slot></slot>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  message?: string
}>()
</script>
