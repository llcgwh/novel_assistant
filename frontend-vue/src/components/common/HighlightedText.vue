<template>
  <template v-for="(part, index) in parts" :key="index"><mark v-if="part.match">{{ part.text }}</mark><template v-else>{{ part.text }}</template></template>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { highlightParts } from '@/utils/searchResults'
const props = defineProps<{ text: string; keyword: string }>()
const parts = computed(() => highlightParts(props.text, props.keyword))
</script>

<style scoped>
mark { background: #ece6ff; color: #513bb0; border-radius: 3px; padding: 0 2px; }
</style>
