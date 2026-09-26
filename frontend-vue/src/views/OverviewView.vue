<template>
  <div class="overview-page">
    <div class="overview-heading">
      <div>
        <p class="eyebrow">YOUR CREATIVE UNIVERSE</p>
        <h1>创作总览<span class="heading-dot">.</span></h1>
      </div>
      <span class="date-label">{{ today }} <span class="live-dot"></span></span>
    </div>
    <section class="story-hero">
      <div class="hero-copy">
        <p class="eyebrow"><span class="live-dot"></span> 故事，正在发生</p>
        <h2>让想象有迹可循，<br />让世界<em>跃然纸上。</em></h2>
        <p class="hero-description">
          {{
            novel.currentNovel?.description ||
            '每一个鲜活的人物，每一条未解的线索，都在等待你写下下一页。'
          }}
        </p>
        <div class="hero-actions">
          <RouterLink :to="base + 'outlines'" class="btn-primary"
            >继续构思<StudioIcon name="arrow" /></RouterLink
          ><button class="text-button" @click="studio.commandsOpen = true">
            探索创作空间 <span>↗</span>
          </button>
        </div>
        <div class="hero-footnote">
          <span>当前作品</span
          ><strong>{{ novel.currentNovel?.title || '正在加载…' }}</strong
          ><span class="book-status">{{
            novel.currentNovel?.genre || '自由创作'
          }}</span>
        </div>
      </div>
      <SheetDock />
    </section>
    <div v-if="failed.length" class="studio-warning" role="alert">
      {{ failed.join('、') }}加载失败，对应统计暂不显示。<button
        :disabled="loading"
        @click="load"
      >
        重新加载
      </button>
    </div>
    <div class="stat-grid" :aria-busy="loading">
      <RouterLink
        v-for="metric in metrics"
        :key="metric.path"
        :to="base + metric.path"
        class="stat-card"
        :class="metric.tone"
        ><div class="stat-top">
          <span>{{ metric.label }}</span
          ><StudioIcon :name="metric.path" />
        </div>
        <strong>{{
          metric.count === null ? '—' : metric.count.toString().padStart(2, '0')
        }}</strong>
        <div class="stat-bottom">
          <span>{{ metric.note }}</span
          ><span>↗</span>
        </div></RouterLink
      >
    </div>
    <div class="overview-columns">
      <div class="overview-main">
        <section class="studio-panel">
          <div class="panel-heading">
            <h3>继续上次的灵感</h3>
            <RouterLink :to="base + 'search'" class="text-link"
              >查找资料 <span>↗</span></RouterLink
            >
          </div>
          <div v-if="loading" class="recent-placeholder">
            正在整理你的创作资料…
          </div>
          <div v-else-if="!recent.length" class="recent-placeholder">
            故事还在酝酿。先从下方的人物或世界设定开始吧。
          </div>
          <RouterLink
            v-for="item in recent"
            :key="item.path + item.id"
            class="recent-row"
            :to="{ path: base + item.path, query: { edit: String(item.id) } }"
            ><span class="recent-icon"><StudioIcon :name="item.path" /></span
            ><span class="recent-title"
              >{{ item.name || item.title
              }}<small>{{ item.label }}</small></span
            ><span class="recent-date">{{
              dateLabel(item.updatedAt || item.createdAt)
            }}</span
            ><StudioIcon name="arrow"
          /></RouterLink>
        </section>
        <section class="world-section">
          <div class="panel-heading">
            <h3>搭建你的故事宇宙</h3>
            <span class="tiny-label">BUILD YOUR WORLD</span>
          </div>
          <div class="world-grid">
            <RouterLink
              v-for="item in worldModules"
              :key="item.path"
              :to="base + item.path"
              class="world-tile"
              ><StudioIcon :name="item.path" />
              <div>
                <h4>{{ item.name }}</h4>
                <p>{{ item.hint }}</p>
              </div>
              <span>↗</span></RouterLink
            >
          </div>
        </section>
        <section class="creative-nudge">
          <StudioIcon name="spark" />
          <div>
            <span class="tiny-label">今天，试着多想一步</span>
            <p>{{ prompts[promptIndex] }}</p>
          </div>
          <button
            class="icon-button"
            aria-label="换一个创作提问"
            @click="promptIndex = (promptIndex + 1) % prompts.length"
          >
            ↻
          </button>
        </section>
      </div>
      <aside class="overview-rail">
        <section class="studio-panel chapter-progress">
          <div class="panel-heading">
            <h3>章节进度</h3>
            <StudioIcon name="outlines" />
          </div>
          <div class="chapter-progress-body">
            <div
              class="progress-ring"
              :style="{ '--progress': `${progress}%` }"
            >
              <span
                >{{ counts.outlines == null ? '—' : `${progress}%`
                }}<small>大纲完成</small></span
              >
            </div>
            <div>
              <strong
                >{{ completed }}
                <span>/ {{ counts.outlines ?? '—' }}</span></strong
              >
              <p>已完成的章节大纲</p>
              <RouterLink :to="base + 'outlines'" class="text-link"
                >推进下一章 →</RouterLink
              >
            </div>
          </div>
        </section>
        <IdeaNotebook :novel-id="novelId" /><FocusTimer :novel-id="novelId" />
      </aside>
    </div>
    <footer class="studio-page-footer">
      <span>INK STUDIO · 墨境</span><span>把心中的世界，写成眼前的故事。</span>
    </footer>
  </div>
</template>
<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRoute } from 'vue-router'
import { request, withNovelId } from '@/api/request'
import { useNovelStore } from '@/stores/novel'
import { useStudioStore } from '@/stores/studio'
import { studioModules, recentRecords, type StudioRecord } from '@/utils/studio'
import StudioIcon from '@/components/common/StudioIcon.vue'
import SheetDock from '@/components/studio/SheetDock.vue'
import IdeaNotebook from '@/components/studio/IdeaNotebook.vue'
import FocusTimer from '@/components/studio/FocusTimer.vue'
const route = useRoute(),
  novel = useNovelStore(),
  studio = useStudioStore()
const novelId = Number(route.params.novelId),
  base = `/novel/${novelId}/`
const today = new Intl.DateTimeFormat('zh-CN', {
  month: 'long',
  day: 'numeric',
  weekday: 'long',
}).format(new Date())
const resources = [
  ['characters', '人物档案', 'characters'],
  ['worldview', '世界设定', 'worldview'],
  ['scenes', '场景画册', 'scenes'],
  ['foreshadows', '伏笔线索', 'foreshadows'],
  ['outlines', '章节大纲', 'outlines'],
  ['timeline', '故事时间线', 'timeline-events'],
  ['map', '世界地图', 'map-locations'],
]
const records = ref<Record<string, StudioRecord[]>>({}),
  counts = ref<Record<string, number | null>>({}),
  loading = ref(true),
  failed = ref<string[]>([])
let alive = true
async function load() {
  if (!alive) return
  loading.value = true
  failed.value = []
  const results = await Promise.allSettled(
    resources.map(([, , endpoint]) => request.get(withNovelId(`/${endpoint}`))),
  )
  if (!alive) return
  results.forEach((result, index) => {
    const [path, label] = resources[index]
    if (result.status === 'fulfilled' && Array.isArray(result.value)) {
      records.value[path] = result.value
      counts.value[path] = result.value.length
    } else {
      delete records.value[path]
      counts.value[path] = null
      failed.value.push(label)
    }
  })
  loading.value = false
}
onMounted(load)
onBeforeUnmount(() => {
  alive = false
})
const completed = computed(
  () =>
    records.value.outlines?.filter(
      (item) => item.status?.toLowerCase() === 'completed',
    ).length || 0,
)
const progress = computed(() =>
  counts.value.outlines
    ? Math.round((completed.value / counts.value.outlines) * 100)
    : 0,
)
const metrics = computed(() => [
  {
    path: 'characters',
    label: '人物档案',
    count: counts.value.characters ?? null,
    note: '每个人都有自己的故事',
    tone: 'mint',
  },
  {
    path: 'worldview',
    label: '世界设定',
    count: counts.value.worldview ?? null,
    note: '构建一个可信的世界',
    tone: 'blue',
  },
  {
    path: 'foreshadows',
    label: '待揭示伏笔',
    count:
      records.value.foreshadows?.filter((item) =>
        ['pending', 'unrevealed'].includes(item.status?.toLowerCase() || ''),
      ).length ?? null,
    note: '等待一个恰好的回响',
    tone: 'amber',
  },
  {
    path: 'outlines',
    label: '章节大纲',
    count: counts.value.outlines ?? null,
    note: `${completed.value} 章大纲已完成`,
    tone: 'rose',
  },
])
const recent = computed(() =>
  recentRecords(
    resources.map(([path, label]) => ({
      path,
      label,
      items: records.value[path] || [],
    })),
  ),
)
const worldModules = studioModules.filter((item) =>
  [
    'characters',
    'worldview',
    'scenes',
    'relationships',
    'timeline',
    'map',
  ].includes(item.path),
)
const promptIndex = ref(0)
const prompts = [
  '如果主角今天失去最依赖的能力，他会如何做出同一个选择？',
  '给一个不起眼的配角，一个足以改变结局的秘密。',
  '挑一条尚未回收的伏笔，让它先以另一种意义出现。',
  '这个世界里，什么被所有人相信，却从未被证明？',
  '让两个立场相反的人，在同一个细节上达成共识。',
]
function dateLabel(value?: string) {
  if (!value || !Number.isFinite(Date.parse(value))) return ''
  return new Date(value).toLocaleDateString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
  })
}
</script>
