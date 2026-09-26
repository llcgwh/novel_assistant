<template>
  <div class="studio-shell" :class="{ 'is-focused': studio.focused }">
    <WorkspaceHeader />
    <main id="workspace-content" class="studio-content"><router-view /></main>
  </div>
</template>
<script setup lang="ts">
import WorkspaceHeader from '@/components/layout/WorkspaceHeader.vue'
import { useStudioStore } from '@/stores/studio'
const studio = useStudioStore()
</script>
