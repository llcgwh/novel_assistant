<template>
  <BaseModal
    title="快捷指令"
    @close="studio.commandsOpen = false"
    :submit="submit"
  >
    <div class="command-intro-row">
      <p class="command-intro">跳转工作区，或者搜索当前小说的正文与资料。</p>
      <button type="button" class="btn-secondary" @click="search">
        全局搜索 <StudioIcon name="arrow" />
      </button>
    </div>
    <input
      ref="input"
      v-model="query"
      placeholder="搜索模块或输入资料关键词…"
      aria-label="快捷指令搜索"
      @keydown.down.prevent="move(1)"
      @keydown.up.prevent="move(-1)"
      @keydown.enter.prevent="submit"
    />
    <div ref="results" class="command-results">
      <button
        v-for="(item, index) in matches"
        :key="item.path"
        class="command-row"
        :class="{ selected: index === selected }"
        @click="go(item.path)"
      >
        <StudioIcon :name="item.path" /><span
          >{{ item.name }}<small>{{ item.hint }}</small></span
        ><kbd>↵</kbd>
      </button>
      <button
        v-if="query.trim()"
        class="command-row"
        :class="{ selected: selected === matches.length }"
        @click="search"
      >
        <StudioIcon name="search" /><span
          >搜索「{{ query }}」<small>人物、场景、设定与情节</small></span
        ><kbd>↵</kbd>
      </button>
    </div>
    <template #actions
      ><p class="command-help">↑ ↓ 选择 · Enter 打开 · Esc 关闭</p>
      <button class="btn-secondary" @click="studio.commandsOpen = false">
        关闭
      </button></template
    >
  </BaseModal>
</template>
<script setup lang="ts">
import { ref, computed, watch, nextTick, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useStudioStore } from '@/stores/studio'
import { studioModules } from '@/utils/studio'
import BaseModal from '@/components/common/BaseModal.vue'
import StudioIcon from '@/components/common/StudioIcon.vue'
const studio = useStudioStore(),
  router = useRouter(),
  route = useRoute()
const query = ref(''),
  selected = ref(0),
  input = ref<HTMLInputElement | null>(null)
const results = ref<HTMLElement | null>(null)
const matches = computed(() =>
  studioModules.filter((item) =>
    `${item.name}${item.hint}${item.path}`
      .toLowerCase()
      .includes(query.value.trim().toLowerCase()),
  ),
)
watch(query, () => (selected.value = 0))
watch(selected, async () => {
  await nextTick()
  results.value
    ?.querySelector('.selected')
    ?.scrollIntoView({ block: 'nearest' })
})
onMounted(async () => {
  await nextTick()
  input.value?.focus()
})
function move(delta: number) {
  const size = matches.value.length + (query.value.trim() ? 1 : 0)
  if (size) selected.value = (selected.value + delta + size) % size
}
function go(path: string) {
  studio.commandsOpen = false
  router.push(`/novel/${route.params.novelId}/${path}`)
}
function search() {
  studio.commandsOpen = false
  router.push({
    name: 'SearchResults',
    params: { novelId: route.params.novelId },
    query: query.value.trim() ? { keyword: query.value.trim() } : {},
  })
}
function submit() {
  const item = matches.value[selected.value]
  if (item) go(item.path)
  else if (query.value.trim()) search()
}
</script>

<style scoped>
.command-intro-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}
.command-intro-row .command-intro {
  flex: 1 1 240px;
  margin: 0;
}
.command-intro-row button {
  flex-shrink: 0;
}
.command-intro-row .studio-icon {
  width: 15px;
  height: 15px;
}
</style>
