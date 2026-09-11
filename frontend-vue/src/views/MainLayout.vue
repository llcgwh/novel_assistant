<template>
  <div class="main-app">
    <div class="container">
      <header>
        <div class="header-top">
          <div class="header-left">
            <button class="btn-back" @click="goBack">← 返回小说列表</button>
            <h1>{{ novelStore.currentNovel?.title || '小说写作助手' }}</h1>
          </div>
          <div class="header-right">
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
        <nav>
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

      <main>
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { exportApi } from '@/api/export'

const router = useRouter()
const route = useRoute()
const novelStore = useNovelStore()

const searchKeyword = ref('')
const showExportMenu = ref(false)
const exportDropdownRef = ref<HTMLElement | null>(null)

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
