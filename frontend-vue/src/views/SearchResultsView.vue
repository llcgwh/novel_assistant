<template>
  <section class="search-workspace" aria-labelledby="search-heading">
    <div class="search-intro">
      <div>
        <p class="search-eyebrow">创作资料库</p>
        <h2 id="search-heading">让每一条线索，都有迹可循</h2>
        <p class="search-description">在当前小说的人物、场景与世界设定中，找到你需要的那一页。</p>
      </div>
      <span class="search-scope">仅搜索当前小说</span>
    </div>

    <form class="search-form" role="search" @submit.prevent="doSearch">
      <span class="search-symbol" aria-hidden="true">⌕</span>
      <label class="visually-hidden" for="library-query">搜索创作资料</label>
      <input id="library-query" v-model="keyword" type="search" placeholder="输入人物名、地点或设定关键词…" autocomplete="off" />
      <button class="btn-primary" type="submit">搜索资料 <span aria-hidden="true">↗</span></button>
    </form>

    <div class="search-filters" aria-label="按资料类型筛选">
      <button type="button" :aria-pressed="activeCategory === 'all'" @click="activeCategory = 'all'">全部 <span>{{ totalCount }}</span></button>
      <button v-for="group in groups" :key="group.key" type="button" :aria-pressed="activeCategory === group.key" @click="activeCategory = group.key">
        {{ group.label }} <span>{{ group.items.length }}</span>
      </button>
    </div>

    <div class="search-body" :aria-busy="loading">
      <LoadingState v-if="loading" message="正在查找相关资料…" />
      <div v-else-if="error" class="search-error" role="alert">
        <h3>这次搜索没有完成</h3>
        <p>{{ error }}</p>
        <button class="btn-secondary" type="button" @click="search(query)">重新搜索</button>
      </div>
      <template v-else-if="query && totalCount">
        <p class="result-summary" role="status">关于「{{ query }}」共找到 <strong>{{ totalCount }}</strong> 条资料<span v-if="activeCategory !== 'all'"> · 当前分类 {{ visibleCount }} 条</span></p>
        <section v-for="group in visibleGroups" :key="group.key" class="result-section" :aria-label="group.label">
          <div class="result-section-heading"><h3>{{ group.label }}</h3><span>{{ group.items.length }} 条资料</span></div>
          <div class="result-grid">
            <RouterLink v-for="item in group.items" :key="item.id" class="result-card" :to="{ path: `/novel/${route.params.novelId}/${group.path}`, query: group.path === 'writing' ? { chapter: String(item.id), find: query } : { edit: String(item.id) } }">
              <div class="result-card-top"><span class="result-icon" aria-hidden="true">{{ group.icon }}</span><span class="result-type">{{ group.label }}</span><span class="result-arrow" aria-hidden="true">↗</span></div>
              <h4><HighlightedText :text="item.title" :keyword="query" /></h4>
              <p v-if="item.excerpt"><HighlightedText :text="item.excerpt" :keyword="query" /></p>
              <p v-else class="result-no-description">暂未填写详细描述</p>
              <span class="result-open">打开条目 <span aria-hidden="true">→</span></span>
            </RouterLink>
          </div>
        </section>
        <EmptyState v-if="!visibleCount" title="这个分类中还没有匹配资料" message="切换到其他分类，或查看全部搜索结果。">
          <button class="btn-secondary" type="button" @click="activeCategory = 'all'">查看全部结果</button>
        </EmptyState>
      </template>
      <EmptyState v-else-if="query" title="暂时没有找到这条线索" :message="`没有与「${query}」匹配的资料，试试更短的关键词。`" />
      <div v-else class="search-welcome">
        <div class="welcome-emblem" aria-hidden="true">⌕</div>
        <h3>从一个关键词，回到你的故事</h3>
        <p>支持搜索名称和正文内容。找到后，点击卡片即可继续编辑。</p>
        <div class="search-hints"><span>人物与场景</span><span>情节与时间线</span><span>地图与世界观</span></div>
      </div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSearch } from '@/composables/useSearch'
import { searchGroups, type SearchCategory } from '@/utils/searchResults'
import HighlightedText from '@/components/common/HighlightedText.vue'
import LoadingState from '@/components/common/LoadingState.vue'
import EmptyState from '@/components/common/EmptyState.vue'

const route = useRoute()
const router = useRouter()
const keyword = ref('')
const activeCategory = ref<SearchCategory | 'all'>('all')
const { results, loading, query, error, search } = useSearch()
const groups = computed(() => searchGroups(results.value, query.value))
const totalCount = computed(() => groups.value.reduce((sum, group) => sum + group.items.length, 0))
const visibleGroups = computed(() => groups.value.filter(group => group.items.length && (activeCategory.value === 'all' || activeCategory.value === group.key)))
const visibleCount = computed(() => visibleGroups.value.reduce((sum, group) => sum + group.items.length, 0))

watch(() => route.query.keyword, value => {
  keyword.value = typeof value === 'string' ? value : ''
  activeCategory.value = 'all'
  void search(keyword.value)
}, { immediate: true })

function doSearch() {
  const value = keyword.value.trim()
  keyword.value = value
  if (value === (route.query.keyword || '')) {
    void search(value)
  } else {
    void router.push({ query: { ...route.query, keyword: value || undefined } })
  }
}
</script>

<style scoped>
.search-workspace { max-width: 1120px; margin: 12px auto 48px; color: var(--text); }
.search-intro { display: flex; justify-content: space-between; align-items: flex-start; gap: 20px; margin-bottom: 28px; }
.search-eyebrow { font-size: 12px; color: var(--accent); font-weight: 600; letter-spacing: .16em; margin: 0 0 10px; }
.search-intro h2 { font-size: clamp(22px, 3vw, 30px); line-height: 1.45; margin: 0 0 10px; letter-spacing: -.025em; }
.search-description { color: var(--muted); font-size: 14px; line-height: 1.8; margin: 0; }
.search-scope { white-space: nowrap; color: var(--muted); font-size: 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 30px; padding: 7px 12px; }
.search-form { display: flex; align-items: center; gap: 12px; background: var(--panel); border: 1px solid var(--line); border-radius: 16px; padding: 10px 12px 10px 20px; box-shadow: 0 6px 22px #30264a08; }
.search-form:focus-within { border-color: var(--line); box-shadow: 0 0 0 3px var(--accent-soft); }
.search-symbol { font-size: 30px; color: var(--accent); }
.search-form input { flex: 1; min-width: 0; width: auto; border: 0; outline: none; box-shadow: none; background: transparent; color: var(--text); padding: 10px 0; font-size: 15px; }
.search-form .btn-primary { white-space: nowrap; box-shadow: none; border-radius: 10px; }
.search-filters { display: flex; gap: 8px; flex-wrap: wrap; padding: 20px 0; border-bottom: 1px solid var(--line); margin-bottom: 24px; }
.search-filters button { display: inline-flex; align-items: center; gap: 8px; background: var(--panel); border: 1px solid var(--line); color: var(--muted); border-radius: 9px; padding: 8px 12px; font-size: 13px; cursor: pointer; }
.search-filters button span { font-size: 11px; opacity: .85; }
.search-filters button[aria-pressed="true"] { background: var(--accent); border-color: var(--accent); color: var(--accent-ink); }
.search-filters button:focus-visible, .result-card:focus-visible { outline: 3px solid var(--accent); outline-offset: 3px; }
.result-summary { font-size: 13px; color: var(--muted); line-height: 1.8; margin-bottom: 28px; overflow-wrap: anywhere; }
.result-summary strong { color: var(--accent); }
.result-section { margin-bottom: 30px; }
.result-section-heading { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.result-section-heading h3 { margin: 0; font-size: 16px; }
.result-section-heading > span { font-size: 12px; color: var(--muted); }
.result-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
.result-card { display: flex; flex-direction: column; text-decoration: none; color: inherit; background: var(--panel); border: 1px solid var(--line); border-radius: 14px; padding: 20px; transition: border-color .18s, box-shadow .18s; min-width: 0; }
.result-card:hover { border-color: var(--line); box-shadow: 0 8px 24px #4532590d; }
.result-card-top { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; }
.result-icon { display: grid; place-items: center; width: 28px; height: 28px; font-size: 12px; border-radius: 8px; color: var(--accent); background: var(--panel); }
.result-type { color: var(--muted); font-size: 11px; }
.result-arrow { margin-left: auto; color: var(--accent); }
.result-card h4 { font-size: 16px; line-height: 1.6; margin: 0 0 8px; overflow-wrap: anywhere; }
.result-card p { font-size: 13px; line-height: 1.85; color: var(--muted); margin: 0 0 20px; overflow-wrap: anywhere; white-space: pre-line; }
.result-card .result-no-description { color: var(--muted); }
.result-open { display: flex; justify-content: space-between; color: var(--accent); font-size: 12px; margin-top: auto; padding-top: 14px; border-top: 1px solid var(--line); }
.search-welcome, .search-error { text-align: center; padding: 48px 20px; border-radius: 16px; background: var(--panel); border: 1px dashed var(--line); }
.welcome-emblem { font-size: 42px; color: var(--accent); margin-bottom: 16px; }
.search-welcome h3, .search-error h3 { font-size: 18px; margin-bottom: 12px; }
.search-welcome p, .search-error p { color: var(--muted); font-size: 14px; line-height: 1.9; margin-bottom: 20px; }
.search-hints { display: flex; justify-content: center; flex-wrap: wrap; gap: 8px; }
.search-hints span { color: var(--accent); background: var(--panel); border-radius: 6px; padding: 6px 10px; font-size: 12px; }
.visually-hidden { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
@media (max-width: 960px) { .result-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 600px) {
  .search-intro { display: block; }
  .search-scope { display: inline-block; margin-top: 14px; }
  .result-grid { grid-template-columns: 1fr; }
  .search-form { gap: 8px; padding: 8px 10px; }
  .search-symbol { display: none; }
  .search-form input { font-size: 14px; }
  .search-form .btn-primary { padding: 10px 12px; font-size: 13px; }
  .search-filters { gap: 6px; }
  .search-filters button { padding: 7px 9px; }
  .search-welcome { padding: 32px 16px; }
}
@media (prefers-reduced-motion: reduce) { .result-card { transition: none; } }
</style>
