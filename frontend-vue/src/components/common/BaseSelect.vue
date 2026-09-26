<template>
  <select class="base-select" :aria-label="placeholder" :value="modelValue"
    @change="emit('update:modelValue', ($event.target as HTMLSelectElement).value)">
    <option v-if="!options.some(option => option.value === '') && placeholder" value="" disabled>{{ placeholder }}</option>
    <option v-for="option in options" :key="option.value" :value="option.value">{{ option.label }}</option>
  </select>
</template>
<script setup lang="ts">
defineProps<{
  modelValue: string
  options: { value: string; label: string; color?: string }[]
  placeholder?: string
}>()
const emit = defineEmits<{ 'update:modelValue': [value: string] }>()
</script>
<style scoped>
.base-select { width: 100%; min-width: 0; min-height: 42px; padding: 10px 34px 10px 16px;
  border: 1px solid #0000001a; border-radius: 8px; background-color: #ffffffb3;
  color: #2d3436; font: inherit; font-size: 14px; cursor: pointer; }
.base-select:focus-visible { outline: 2px solid #6c5ce7; outline-offset: 2px; }
.base-select:disabled { opacity: .65; cursor: wait; }
</style>
