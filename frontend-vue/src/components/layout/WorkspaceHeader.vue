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
    <nav
      ref="navigation"
      class="sheet-navigation"
      aria-label="创作模块"
      :style="{
        '--nav-tint': sheetFor(String(route.path.split('/').at(-1))).tint,
      }"
    >
      <span
        class="sheet-nav-marker"
        :style="{
          ...marker,
          transition: studio.spatialMotion ? undefined : 'none',
        }"
        aria-hidden="true"
      ></span>
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
    <BaseSelect
      class="mobile-navigation"
      aria-label="切换创作模块"
      :model-value="String(route.path.split('/').at(-1))"
      :options="navigationOptions"
      @change="router.push(base + $event)"
    />
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
        @pointerdown="studio.preserveThemeFocus"
        @click="studio.toggleTheme"
      >
        <StudioIcon :name="studio.theme === 'dark' ? 'sun' : 'moon'" />
      </button>
    </div>
  </header>
  <CommandPalette v-if="studio.commandsOpen" />
</template>
<script setup lang="ts">
import { computed, onMounted, onUnmounted, nextTick, ref, watch } from 'vue'
import { sheetFor } from '@/utils/sheets'
import { useRoute, useRouter } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { useStudioStore } from '@/stores/studio'
import { useAppStore } from '@/stores/app'
import { studioModules, studioGroups, studioNavigation } from '@/utils/studio'
import StudioIcon from '@/components/common/StudioIcon.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
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
const groups = studioGroups
const navigationOptions = studioNavigation.map((item) => ({
  value: item.path,
  label: item.name,
}))
const navigation = ref<HTMLElement | null>(null)
const marker = ref({ transform: 'translateY(0px)', height: '39px', opacity: 0 })
let markerObserver: ResizeObserver | undefined
async function positionMarker() {
  await nextTick()
  const nav = navigation.value,
    active = nav?.querySelector<HTMLElement>('.studio-nav-item.active')
  if (!nav || !active || !nav.clientWidth) {
    marker.value.opacity = 0
    return
  }
  marker.value = {
    transform: `translateY(${active.getBoundingClientRect().top - nav.getBoundingClientRect().top}px)`,
    height: `${active.offsetHeight}px`,
    opacity: 1,
  }
}
watch(() => route.path, positionMarker, { flush: 'post' })
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
  markerObserver = new ResizeObserver(positionMarker)
  if (navigation.value) markerObserver.observe(navigation.value)
  void positionMarker()
  document.addEventListener('keydown', shortcut)
  if (!novel.novels.length)
    void novel
      .fetchNovels()
      .catch(() => app.showToast('作品信息加载失败，请回书架重试', 'error'))
})
onUnmounted(() => {
  markerObserver?.disconnect()
  document.removeEventListener('keydown', shortcut)
  studio.commandsOpen = false
})
</script>
