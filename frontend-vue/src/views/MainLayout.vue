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
            <div class="export-dropdown">
              <button class="btn-secondary" @click="showExportMenu = !showExportMenu">
                导出 ▼
              </button>
              <div v-if="showExportMenu" class="dropdown-menu">
                <a href="#" @click.prevent="exportJson">导出 JSON</a>
                <a href="#" @click.prevent="exportMarkdown">导出 Markdown</a>
                <a href="#" @click.prevent="exportCharacters">导出人物 Markdown</a>
                <a href="#" @click.prevent="exportOutlines">导出大纲 Markdown</a>
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
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { exportApi } from '@/api/export'
import { setCurrentNovelId } from '@/api/request'

const router = useRouter()
const route = useRoute()
const novelStore = useNovelStore()

const searchKeyword = ref('')
const showExportMenu = ref(false)

const navItems = computed(() => {
  const novelId = route.params.novelId
  return [
    { name: 'Timeline', label: '时间轴', to: `/novel/${novelId}/timeline` },
    { name: 'Characters', label: '人物', to: `/novel/${novelId}/characters` },
    { name: 'Scenes', label: '场景', to: `/novel/${novelId}/scenes` },
    { name: 'Foreshadows', label: '伏笔', to: `/novel/${novelId}/foreshadows` },
    { name: 'Outlines', label: '大纲', to: `/novel/${novelId}/outlines` },
    { name: 'Map', label: '地图', to: `/novel/${novelId}/map` },
    { name: 'Relationships', label: '人物关系', to: `/novel/${novelId}/relationships` },
    { name: 'Tags', label: '标签', to: `/novel/${novelId}/tags` }
  ]
})

onMounted(async () => {
  const novelId = Number(route.params.novelId)
  if (novelId) {
    novelStore.setCurrentNovel(novelId)
    setCurrentNovelId(novelId)
    if (novelStore.novels.length === 0) {
      await novelStore.fetchNovels()
    }
  }
})

watch(() => route.params.novelId, (newId) => {
  if (newId) {
    const id = Number(newId)
    novelStore.setCurrentNovel(id)
    setCurrentNovelId(id)
  }
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
</script>
