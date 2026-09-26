<template>
  <aside class="studio-sidebar">
    <RouterLink to="/" class="studio-brand" aria-label="墨境，返回作品书架"
      ><span class="brand-mark"><StudioIcon name="book" /></span
      ><span>墨境<small>INK STUDIO</small></span></RouterLink
    >
    <RouterLink to="/" class="current-book"
      ><span class="book-monogram">{{
        novel.currentNovel?.title?.slice(0, 1) || '书'
      }}</span
      ><span
        >{{ novel.currentNovel?.title || '正在打开作品'
        }}<small>切换作品 ↗</small></span
      ></RouterLink
    >
    <nav aria-label="创作模块">
      <template v-for="group in groups" :key="group"
        ><p class="nav-label">{{ group }}</p>
        <RouterLink
          v-for="item in studioModules.filter((item) => item.group === group)"
          :key="item.path"
          :to="base + item.path"
          class="studio-nav-item"
          active-class="active"
          ><StudioIcon :name="item.path" /><span>{{ item.name }}</span
          ><span class="nav-dot"></span></RouterLink
      ></template>
    </nav>
    <div class="sidebar-bottom">
      <RouterLink :to="base + 'settings'" class="studio-nav-item"
        ><StudioIcon name="settings" />设置与备份</RouterLink
      >
      <p><span class="live-dot"></span> 每一个世界，始于一笔。</p>
    </div>
  </aside>
  <header class="studio-topbar">
    <RouterLink to="/" class="icon-button mobile-home" aria-label="返回作品书架"
      ><StudioIcon name="book"
    /></RouterLink>
    <div class="studio-breadcrumb">
      <RouterLink to="/">作品书架</RouterLink><span>/</span
      ><strong>{{
        current?.name ||
        (route.name === 'SearchResults' ? '全局搜索' : '设置与备份')
      }}</strong>
    </div>
    <select
      class="mobile-navigation"
      aria-label="切换创作模块"
      :value="String(route.path.split('/').at(-1))"
      @change="router.push(base + ($event.target as HTMLSelectElement).value)"
    >
      <option v-for="item in studioModules" :key="item.path" :value="item.path">
        {{ item.name }}
      </option>
      <option value="search">全局搜索</option>
      <option value="settings">设置与备份</option>
    </select>
    <div class="topbar-actions">
      <button
        class="command-trigger"
        aria-label="搜索与快捷指令"
        @click="studio.commandsOpen = true"
      >
        <StudioIcon name="search" /><span>搜索与快捷指令</span
        ><kbd>⌘ K</kbd></button
      ><button
        class="icon-button"
        :title="studio.focused ? '退出专注模式' : '专注模式'"
        :aria-label="studio.focused ? '退出专注模式' : '专注模式'"
        :aria-pressed="studio.focused"
        @click="studio.focused = !studio.focused"
      >
        <StudioIcon name="focus" /></button
      ><button
        class="icon-button"
        :aria-label="studio.theme === 'dark' ? '切换晨雾主题' : '切换夜航主题'"
        @click="studio.toggleTheme"
      >
        <StudioIcon :name="studio.theme === 'dark' ? 'sun' : 'moon'" />
      </button>
    </div>
  </header>
  <CommandPalette v-if="studio.commandsOpen" />
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { useStudioStore } from '@/stores/studio'
import { useAppStore } from '@/stores/app'
import { studioModules } from '@/utils/studio'
import StudioIcon from '@/components/common/StudioIcon.vue'
import CommandPalette from '@/components/studio/CommandPalette.vue'
const route = useRoute(),
  router = useRouter(),
  novel = useNovelStore(),
  studio = useStudioStore(),
  app = useAppStore()
const base = computed(() => `/novel/${route.params.novelId}/`)
const current = computed(() =>
  studioModules.find((item) => route.path === base.value + item.path),
)
const groups = ['工作台', '故事脉络', '世界构建']
function shortcut(event: KeyboardEvent) {
  if (
    (event.metaKey || event.ctrlKey) &&
    event.key.toLowerCase() === 'k' &&
    !document.querySelector('[role="dialog"]')
  ) {
    event.preventDefault()
    studio.commandsOpen = true
  }
}
onMounted(() => {
  document.addEventListener('keydown', shortcut)
  if (!novel.novels.length)
    void novel
      .fetchNovels()
      .catch(() => app.showToast('作品信息加载失败，请回书架重试', 'error'))
})
onUnmounted(() => {
  document.removeEventListener('keydown', shortcut)
  studio.commandsOpen = false
})
</script>
