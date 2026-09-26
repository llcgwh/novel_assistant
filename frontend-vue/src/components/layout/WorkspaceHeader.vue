<template>
      <header class="workspace-header">
        <div class="header-top">
          <div class="header-left">
            <button class="btn-back" aria-label="返回小说列表" @click="goBack"><span aria-hidden="true">←</span><span class="back-label"> 返回小说列表</span></button>
            <h1>{{ novelStore.currentNovel?.title || '小说写作助手' }}</h1>
          </div>
          <button class="mobile-tools" type="button" :aria-expanded="showTools" @click="showTools = !showTools">{{ showTools ? '收起工具' : '搜索与工具' }}</button>
          <div class="header-right" :class="{ expanded: showTools }">
            <div class="global-search">
              <input
                v-model="searchKeyword"
                type="text"
                placeholder="全局搜索..."
                @keyup.enter="doSearch"
              />
              <button class="btn-search" @click="doSearch">搜索</button>
            </div>
            <router-link
              :to="`/novel/${route.params.novelId}/settings`"
              class="btn-settings"
              title="全局设置"
            >
              ⚙️ 设置
            </router-link>
            <div ref="exportDropdownRef" class="export-dropdown">
              <button class="btn-secondary" @click="showExportMenu = !showExportMenu">
                导出 ▼
              </button>
              <div v-if="showExportMenu" class="dropdown-menu">
                <a href="#" @click.prevent="exportJson">导出 JSON</a>
                <a href="#" @click.prevent="exportMarkdown">导出 Markdown</a>
                <a href="#" @click.prevent="exportCharacters">导出人物 Markdown</a>
                <a href="#" @click.prevent="exportOutlines">导出大纲 Markdown</a>
                <a href="#" @click.prevent="exportWorldview">导出世界观 Markdown</a>
              </div>
            </div>
          </div>
        </div>
        <nav ref="navigation" aria-label="创作模块">
          <router-link
            v-for="item in navItems"
            :key="item.name"
            :to="item.to"
            class="nav-btn"
            active-class="active"
          >
            {{ item.label }}
          </router-link>
        </nav>
      </header>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { exportApi } from '@/api/export'

const router = useRouter()
const route = useRoute()
const novelStore = useNovelStore()

const searchKeyword = ref('')
const showTools = ref(false)
const showExportMenu = ref(false)
const exportDropdownRef = ref<HTMLElement | null>(null)
const navigation = ref<HTMLElement | null>(null)
watch(() => route.path, async () => {
  await nextTick()
  const nav = navigation.value
  const active = nav?.querySelector<HTMLElement>('.nav-btn.active')
  if (nav && active) nav.scrollLeft = active.offsetLeft - nav.offsetLeft - (nav.clientWidth - active.clientWidth) / 2
}, { immediate: true })

function handleClickOutside(event: MouseEvent) {
  if (exportDropdownRef.value && !exportDropdownRef.value.contains(event.target as Node)) {
    showExportMenu.value = false
  }
}

const navItems = computed(() => {
  const novelId = route.params.novelId
  return [
    { name: 'Timeline', label: '时间轴', to: `/novel/${novelId}/timeline` },
    { name: 'Worldview', label: '世界观', to: `/novel/${novelId}/worldview` },
    { name: 'Characters', label: '人物', to: `/novel/${novelId}/characters` },
    { name: 'Scenes', label: '场景', to: `/novel/${novelId}/scenes` },
    { name: 'Foreshadows', label: '伏笔', to: `/novel/${novelId}/foreshadows` },
    { name: 'Outlines', label: '大纲', to: `/novel/${novelId}/outlines` },
    { name: 'Map', label: '地图', to: `/novel/${novelId}/map` },
    { name: 'Relationships', label: '人物关系', to: `/novel/${novelId}/relationships` },
    { name: 'Tags', label: '标签', to: `/novel/${novelId}/tags` }
  ]
})

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  if (novelStore.novels.length === 0) {
    void novelStore.fetchNovels().catch(error => console.error('加载小说列表失败:', error))
  }
})

onUnmounted(() => {
  document.removeEventListener('click', handleClickOutside)
})

function goBack() {
  router.push('/')
}

function doSearch() {
  if (searchKeyword.value.trim()) {
    router.push({
      name: 'SearchResults',
      query: { keyword: searchKeyword.value }
    })
  }
}

function exportJson() {
  exportApi.downloadJson()
  showExportMenu.value = false
}

function exportMarkdown() {
  exportApi.downloadMarkdown()
  showExportMenu.value = false
}

function exportCharacters() {
  exportApi.downloadCharactersMarkdown()
  showExportMenu.value = false
}

function exportOutlines() {
  exportApi.downloadOutlinesMarkdown()
  showExportMenu.value = false
}

function exportWorldview() {
  exportApi.downloadWorldviewMarkdown()
  showExportMenu.value = false
}
</script>

<style scoped>
.mobile-tools { display: none; }
.workspace-header nav { display: flex; flex-wrap: nowrap; overflow-x: auto; gap: 6px; scrollbar-width: thin; }
.workspace-header nav .nav-btn { flex: 1 0 auto; white-space: nowrap; }
@media (max-width: 768px) {
  .workspace-header { padding: 12px; border-radius: 12px; margin-bottom: 18px; }
  .workspace-header .header-top { display: flex; flex-direction: row; flex-wrap: wrap; align-items: center; gap: 10px; }
  .workspace-header .header-left { flex: 1; min-width: 0; display: flex; flex-direction: row; gap: 8px; align-items: center; }
  .workspace-header .header-left h1 { text-align: left; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 17px; margin: 0; }
  .workspace-header .btn-back { font-size: 17px; padding: 7px 10px; flex-shrink: 0; width: auto; }
  .workspace-header .back-label { display: none; }
  .mobile-tools { display: block; flex-shrink: 0; border: 1px solid #ffffff40; background: #ffffff10; color: #fff; border-radius: 8px; padding: 8px; font-size: 12px; }
  .workspace-header .header-right { display: none; width: 100%; }
  .workspace-header .header-right.expanded { display: flex; flex-direction: row; flex-wrap: wrap; gap: 8px; }
  .workspace-header .global-search { width: 100%; }
  .workspace-header .btn-settings, .workspace-header .export-dropdown { flex: 1; width: auto; }
  .workspace-header .export-dropdown > button { width: 100%; }
  .workspace-header nav { margin-top: 12px; padding-bottom: 4px; }
  .workspace-header nav .nav-btn { font-size: 13px; flex: 0 0 auto; padding: 9px 14px; }
}
</style>
