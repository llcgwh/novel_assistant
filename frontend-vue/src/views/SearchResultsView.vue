<template>
  <div>
    <div class="view-header">
      <h2>搜索结果</h2>
      <div class="view-actions">
        <input
          v-model="keyword"
          type="text"
          class="search-input"
          placeholder="搜索..."
          @keyup.enter="doSearch"
        />
        <button class="btn-primary" @click="doSearch">搜索</button>
      </div>
    </div>

    <LoadingState v-if="loading" message="搜索中..." />

    <div v-else-if="hasResults" class="search-results-container">
      <p class="search-summary">找到 {{ totalCount }} 个结果</p>

      <!-- 人物 -->
      <div v-if="results.characters?.length" class="search-category">
        <h3>人物 ({{ results.characters.length }})</h3>
        <div class="search-items">
          <div v-for="char in results.characters" :key="'c-' + char.id" class="search-item" @click="goTo('characters')">
            <h4>{{ char.name }}</h4>
            <p v-if="char.role">{{ char.role }}</p>
          </div>
        </div>
      </div>

      <!-- 场景 -->
      <div v-if="results.scenes?.length" class="search-category">
        <h3>场景 ({{ results.scenes.length }})</h3>
        <div class="search-items">
          <div v-for="scene in results.scenes" :key="'s-' + scene.id" class="search-item" @click="goTo('scenes')">
            <h4>{{ scene.name }}</h4>
            <p v-if="scene.location">{{ scene.location }}</p>
          </div>
        </div>
      </div>

      <!-- 伏笔 -->
      <div v-if="results.foreshadows?.length" class="search-category">
        <h3>伏笔 ({{ results.foreshadows.length }})</h3>
        <div class="search-items">
          <div v-for="fs in results.foreshadows" :key="'f-' + fs.id" class="search-item" @click="goTo('foreshadows')">
            <h4>{{ fs.title }}</h4>
            <p v-if="fs.content">{{ fs.content?.slice(0, 100) }}...</p>
          </div>
        </div>
      </div>

      <!-- 大纲 -->
      <div v-if="results.outlines?.length" class="search-category">
        <h3>大纲 ({{ results.outlines.length }})</h3>
        <div class="search-items">
          <div v-for="ol in results.outlines" :key="'o-' + ol.id" class="search-item" @click="goTo('outlines')">
            <h4>{{ ol.title }}</h4>
            <p v-if="ol.content">{{ ol.content?.slice(0, 100) }}...</p>
          </div>
        </div>
      </div>

      <!-- 时间轴事件 -->
      <div v-if="results.timelineEvents?.length" class="search-category">
        <h3>时间轴事件 ({{ results.timelineEvents.length }})</h3>
        <div class="search-items">
          <div v-for="event in results.timelineEvents" :key="'t-' + event.id" class="search-item" @click="goTo('timeline')">
            <h4>{{ event.title }}</h4>
            <p v-if="event.description">{{ event.description?.slice(0, 100) }}...</p>
          </div>
        </div>
      </div>

      <!-- 地图位置 -->
      <div v-if="results.mapLocations?.length" class="search-category">
        <h3>地图位置 ({{ results.mapLocations.length }})</h3>
        <div class="search-items">
          <div v-for="loc in results.mapLocations" :key="'m-' + loc.id" class="search-item" @click="goTo('map')">
            <h4>{{ loc.name }}</h4>
            <p v-if="loc.locationType">{{ loc.locationType }}</p>
          </div>
        </div>
      </div>
    </div>

    <EmptyState
      v-else-if="searched"
      title="未找到结果"
      message="尝试使用其他关键词搜索"
    />

    <EmptyState
      v-else
      title="输入关键词搜索"
      message="搜索人物、场景、伏笔、大纲、时间轴事件等"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { searchApi } from '@/api/search'
import type { SearchResult } from '@/types'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const keyword = ref('')
const loading = ref(false)
const searched = ref(false)
const results = ref<SearchResult>({
  characters: [], scenes: [], foreshadows: [],
  outlines: [], timelineEvents: [], mapLocations: [], tags: []
})

const hasResults = computed(() => {
  return (results.value.characters?.length || 0) +
         (results.value.scenes?.length || 0) +
         (results.value.foreshadows?.length || 0) +
         (results.value.outlines?.length || 0) +
         (results.value.timelineEvents?.length || 0) +
         (results.value.mapLocations?.length || 0) > 0
})

const totalCount = computed(() => {
  return (results.value.characters?.length || 0) +
         (results.value.scenes?.length || 0) +
         (results.value.foreshadows?.length || 0) +
         (results.value.outlines?.length || 0) +
         (results.value.timelineEvents?.length || 0) +
         (results.value.mapLocations?.length || 0)
})

onMounted(() => {
  if (route.query.keyword) {
    keyword.value = route.query.keyword as string
    doSearch()
  }
})

watch(() => route.query.keyword, (newKeyword) => {
  if (newKeyword && newKeyword !== keyword.value) {
    keyword.value = newKeyword as string
    doSearch()
  }
})

async function doSearch() {
  if (!keyword.value.trim()) return
  loading.value = true
  searched.value = true
  try {
    results.value = await searchApi.global(keyword.value)
  } catch (error) {
    console.error('搜索失败:', error)
  } finally {
    loading.value = false
  }
}

function goTo(view: string) {
  const novelId = route.params.novelId
  router.push(`/novel/${novelId}/${view}`)
}
</script>
