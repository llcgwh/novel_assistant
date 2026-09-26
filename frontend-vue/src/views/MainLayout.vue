<template>
  <div class="main-app"><div class="container">
    <WorkspaceHeader />
    <main><router-view /></main>
  </div></div>
</template>
<script setup lang="ts">
import WorkspaceHeader from '@/components/layout/WorkspaceHeader.vue'
</script>
