<template>
  <div
    class="sheet-contents"
    :class="[`motif-${profile.motif}`, `format-${profile.layout}`]"
  >
    <header class="sheet-chrome">
      <span class="sheet-emblem"><StudioIcon :name="sheetId" /></span
      ><span
        >INK / SHEET <b>{{ serial }}</b></span
      ><span class="sheet-live-light" aria-hidden="true"></span>
    </header>
    <div class="sheet-composition">
      <div class="sheet-title">
        <p>{{ profile.caption }}</p>
        <h2>{{ profile.title }}</h2>
        <span class="sheet-rule"></span>
      </div>

      <template v-if="sheetId === 'overview'">
        <div class="dimensional-book" aria-hidden="true">
          <div class="book-halo"></div>
          <div class="book-plane plane-three"></div>
          <div class="book-plane plane-two"></div>
          <div class="book-plane plane-one">
            <StudioIcon name="spark" /><span>THE NEXT<br />CHAPTER</span><i></i
            ><i></i><i></i>
          </div>
          <span class="orbit-star star-one">✦</span
          ><span class="orbit-star star-two">✧</span>
        </div>
        <div class="sheet-overview-caption">
          <strong>{{ novel.currentNovel?.title || '你的故事' }}</strong
          ><span>点击页签，展开故事的另一面。</span>
        </div>
      </template>

      <template v-else-if="sheetId === 'settings'">
        <div class="sheet-theme-dial" aria-hidden="true">
          <StudioIcon :name="studio.theme === 'dark' ? 'moon' : 'sun'" />
        </div>
        <div class="sheet-settings-controls">
          <button @click="studio.toggleTheme">
            {{ studio.theme === 'dark' ? '夜航 · 切换晨雾' : '晨雾 · 切换夜航'
            }}<StudioIcon name="arrow" /></button
          ><button
            :aria-pressed="studio.spatialMotion"
            @click="studio.spatialMotion = !studio.spatialMotion"
          >
            空间转场<span>{{
              studio.spatialMotion ? '开启' : '关闭'
            }}</span></button
          ><small>跟随系统的减少动态效果偏好</small>
        </div>
      </template>

      <template v-else-if="sheetId === 'search'">
        <div class="sheet-radar" aria-hidden="true">
          <i></i><i></i><i></i><span></span>
        </div>
        <div class="sheet-search-links">
          <RouterLink
            v-for="item in ['characters', 'scenes', 'worldview', 'foreshadows']"
            :key="item"
            :to="base + item"
            ><StudioIcon :name="item" />{{ sheetFor(item).label
            }}<span>↗</span></RouterLink
          >
        </div>
      </template>

      <template v-else>
        <div class="sheet-count">
          <strong>{{
            loading ? '—' : String(entries.length).padStart(2, '0')
          }}</strong
          ><span>{{
            sheetId === 'relationships' ? '图谱中的人物' : '当前列表中的资料'
          }}</span>
        </div>
        <div
          v-if="sheetId === 'outlines'"
          class="sheet-chapter-bars"
          aria-label="当前列表的章节状态分布"
        >
          <div v-for="item in chapterStates" :key="item.label">
            <span
              >{{ item.label }} <b>{{ item.count }}</b></span
            ><i
              ><em
                :style="{
                  width: `${entries.length ? (item.count / entries.length) * 100 : 0}%`,
                }"
              ></em
            ></i>
          </div>
        </div>
        <div
          v-else-if="sheetId === 'relationships'"
          class="sheet-constellation"
          aria-hidden="true"
        >
          <svg viewBox="0 0 240 100">
            <path d="M30 60 95 20 150 80 210 30 30 60 150 80M95 20 210 30" />
            <circle cx="30" cy="60" r="7" />
            <circle cx="95" cy="20" r="10" />
            <circle cx="150" cy="80" r="8" />
            <circle cx="210" cy="30" r="6" /></svg
          ><span
            >{{ relationships.relationships.length }} 条关系 ·
            {{ groups.groups.length }} 个关系组</span
          >
        </div>
        <div
          class="sheet-index"
          :class="{
            'scene-contact-sheet': sheetId === 'scenes',
            'portrait-contact-sheet': sheetId === 'characters',
            'event-index': sheetId === 'timeline',
          }"
        >
          <p v-if="loading" class="sheet-empty">正在展开这一页…</p>
          <p v-else-if="!entries.length" class="sheet-empty">
            还没有资料。<br />在页面中写下第一笔，它就会在这里出现。
          </p>
          <RouterLink
            v-for="(entry, index) in entries.slice(0, limit)"
            :key="entry.id"
            :to="entryLink(entry.id)"
            class="sheet-index-item"
            @click="reopenEntry($event, entry.id)"
          >
            <span
              v-if="sheetId === 'characters' || sheetId === 'relationships'"
              class="sheet-avatar"
              ><img v-if="entry.image" :src="entry.image" alt="" /><span
                v-else
                >{{ entry.title.slice(0, 1) }}</span
              ></span
            >
            <span v-else-if="sheetId === 'scenes'" class="sheet-scene-image"
              ><img v-if="entry.image" :src="entry.image" alt="" /><StudioIcon
                v-else
                name="scenes"
            /></span>
            <span v-else class="sheet-index-number">{{
              String(index + 1).padStart(2, '0')
            }}</span>
            <span class="sheet-index-copy"
              ><strong>{{ entry.title }}</strong
              ><small>{{ entry.detail || '打开这条资料' }}</small></span
            ><span class="sheet-index-arrow">↗</span>
          </RouterLink>
        </div>
      </template>
    </div>
    <footer class="sheet-footer">
      <RouterLink
        :to="base + adjacentSheet(sheetId, -1).id"
        aria-label="上一张折页"
        ><StudioIcon name="arrow" /></RouterLink
      ><span
        ><b>{{ profile.label }}</b> / {{ serial }}</span
      ><RouterLink
        :to="base + adjacentSheet(sheetId, 1).id"
        aria-label="下一张折页"
        ><StudioIcon name="arrow"
      /></RouterLink>
    </footer>
  </div>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import StudioIcon from '@/components/common/StudioIcon.vue'
import { sheetFor, adjacentSheet, sheets, type SheetId } from '@/utils/sheets'
import { useStudioStore } from '@/stores/studio'
import { useNovelStore } from '@/stores/novel'
import { useCharactersStore } from '@/stores/characters'
import { useOutlinesStore } from '@/stores/outlines'
import { useTimelineStore } from '@/stores/timeline'
import { useForeshadowsStore } from '@/stores/foreshadows'
import { useWorldviewStore } from '@/stores/worldview'
import { useScenesStore } from '@/stores/scenes'
import { useRelationshipsStore } from '@/stores/relationships'
import { useRelationshipGroupsStore } from '@/stores/relationshipGroups'
import { useMapStore } from '@/stores/map'
import { useTagsStore } from '@/stores/tags'
const props = defineProps<{ sheetId: SheetId }>()
const route = useRoute(),
  router = useRouter(),
  studio = useStudioStore(),
  novel = useNovelStore()
const characters = useCharactersStore(),
  outlines = useOutlinesStore(),
  timeline = useTimelineStore(),
  foreshadows = useForeshadowsStore(),
  worldview = useWorldviewStore(),
  scenes = useScenesStore(),
  relationships = useRelationshipsStore(),
  groups = useRelationshipGroupsStore(),
  map = useMapStore(),
  tags = useTagsStore()
const profile = computed(() => sheetFor(props.sheetId))
const serial = computed(() =>
  String(sheets.findIndex((sheet) => sheet.id === props.sheetId) + 1).padStart(
    2,
    '0',
  ),
)
const base = computed(() => `/novel/${route.params.novelId}/`)
const limit = computed(() => (profile.value.layout === 'wide' ? 3 : 4))
const entries = computed<
  { id: number; title: string; detail?: string; image?: string }[]
>(() => {
  switch (props.sheetId) {
    case 'characters':
    case 'relationships':
      return characters.characters.map((item) => ({
        id: item.id,
        title: item.name,
        detail: item.role,
        image: item.portraitImage,
      }))
    case 'outlines':
      return outlines.outlines.map((item) => ({
        id: item.id,
        title: item.title,
        detail: item.chapterNumber ? `第 ${item.chapterNumber} 章` : '章节构思',
      }))
    case 'timeline':
      return timeline.events.map((item) => ({
        id: item.id,
        title: item.title,
        detail: item.eventTime,
      }))
    case 'foreshadows':
      return foreshadows.foreshadows.map((item) => ({
        id: item.id,
        title: item.title,
        detail: {
          pending: '等待揭示',
          revealed: '已经回响',
          abandoned: '暂时搁置',
        }[item.status],
      }))
    case 'worldview':
      return worldview.entries.map((item) => ({
        id: item.id,
        title: item.name,
        detail: item.content,
      }))
    case 'scenes':
      return scenes.scenes.map((item) => ({
        id: item.id,
        title: item.name,
        detail: item.atmosphere || item.location,
        image: item.sceneImage,
      }))
    case 'map':
      return map.locations.map((item) => ({
        id: item.id,
        title: item.name,
        detail: item.locationType,
      }))
    case 'tags':
      return tags.tags.map((item) => ({
        id: item.id,
        title: item.name,
        detail: item.description,
      }))
    default:
      return []
  }
})
const loading = computed(() => {
  const states: Partial<Record<SheetId, boolean>> = {
    characters: characters.loading,
    relationships: characters.loading,
    outlines: outlines.loading,
    timeline: timeline.loading,
    foreshadows: foreshadows.loading,
    worldview: worldview.loading,
    scenes: scenes.loading,
    map: map.loading,
    tags: tags.loading,
  }
  return states[props.sheetId] ?? false
})
const chapterStates = computed(() =>
  [
    ['planning', '构思'],
    ['writing', '写作'],
    ['completed', '完成'],
  ].map(([status, label]) => ({
    label,
    count: outlines.outlines.filter((item) => item.status === status).length,
  })),
)
function entryLink(id: number) {
  return {
    path:
      base.value +
      (props.sheetId === 'relationships' ? 'characters' : props.sheetId),
    query: { edit: String(id) },
  }
}
async function reopenEntry(event: MouseEvent, id: number) {
  const target = entryLink(id)
  if (
    event.metaKey ||
    event.ctrlKey ||
    event.shiftKey ||
    event.altKey ||
    event.button !== 0 ||
    route.path !== target.path ||
    route.query.edit !== String(id)
  )
    return
  event.preventDefault()
  await router.replace({
    path: route.path,
    query: { ...route.query, edit: undefined },
  })
  await router.replace(target)
}
</script>
