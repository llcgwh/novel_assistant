<template>
  <BaseModal
    :busy="busy"
    :wide="mode === 'stats' || mode === 'history'"
    :title="titles[mode]"
    @close="$emit('close')"
  >
    <template v-if="mode === 'export'">
      <div class="writer-options">
        <label
          >格式<BaseSelect
            v-model="format"
            aria-label="导出格式"
            :options="formatOptions" /></label
        ><label
          >范围<BaseSelect
            v-model="scope"
            aria-label="导出范围"
            :options="scopeOptions"
        /></label>
      </div>
      <div v-if="scope === 'selected'" class="writer-export-checks">
        <label v-for="chapter in chapters" :key="chapter.uid"
          ><input v-model="selected" type="checkbox" :value="chapter.uid" />{{
            chapter.title
          }}</label
        >
      </div>
      <label class="writer-check"
        ><input
          v-model="includeNotes"
          type="checkbox"
        />附加创作笔记、关联资料与灵感</label
      >
      <template v-if="format === 'docx'"
        ><label class="writer-check"
          ><input v-model="titlePage" type="checkbox" />扉页</label
        ><label class="writer-check"
          ><input v-model="toc" type="checkbox" />目录（在 Word
          中更新域显示页码）</label
        ><label class="writer-check"
          ><input v-model="pageBreak" type="checkbox" />每章另起一页</label
        ></template
      >
      <p>
        正文导出保留篇卷与章节顺序。附加灵感时，全书包含全部素材，部分章节包含与所选章节关联的素材。完整资料、图片和历史版本请使用设置页的含图片备份。
      </p>
    </template>
    <template v-else-if="mode === 'import'">
      <BaseFilePicker
        class="writer-import-file"
        label="选择旧稿"
        accept=".txt,.md,.markdown,text/plain,text/markdown"
        hint="TXT 或 Markdown · 最大 4 MB"
        :disabled="busy"
        :busy="readingImport"
        busy-label="正在读取旧稿…"
        @change="readImport"
      />
      <label
        >导入篇卷<BaseSelect
          v-model="importVolume"
          aria-label="导入篇卷"
          :options="volumeOptions"
      /></label>
      <p>
        识别“第…章”、序章、番外和 Markdown
        标题。以下章节会作为新稿加入，不替换已有内容。
      </p>
      <div class="writer-import-preview">
        <article v-for="(chapter, index) in imported" :key="chapter.uid">
          <input
            v-model="chapter.title"
            :aria-label="'导入章节' + (index + 1) + '标题'"
          /><small
            >{{ wordCount(documentText(chapter.doc)) }} 字 ·
            {{ importedCount > index ? '已导入' : '待导入' }}</small
          >
          <p>{{ documentText(chapter.doc).slice(0, 100) }}</p>
        </article>
      </div>
    </template>
    <template v-else-if="mode === 'history'">
      <p>自动历史约每 5 分钟保留一次；手动存档、拆分、合并及恢复前也会留档。</p>
      <BaseSelect
        aria-label="选择历史版本"
        v-model="historyId"
        :options="historyOptions"
        :disabled="busy"
        @change="loadRevision(Number($event))"
      />
      <p v-if="historyNotice" class="history-notice" role="status">
        {{ historyNotice }}
      </p>
      <HistoryCompare
        v-if="revision && writer.current"
        :key="historyId"
        :current="writer.current.doc"
        :historical="revision.doc"
        :busy="busy"
        @adopt="adoptBlock"
      />
    </template>
    <template v-else-if="mode === 'search'">
      <form class="writer-find writer-book-search" @submit.prevent="search">
        <input
          v-model="searchText"
          placeholder="搜索全书正文与章名"
          aria-label="搜索全书正文与章名"
        /><button type="submit" class="btn-primary">搜索</button>
      </form>
      <div class="writer-search-results">
        <button v-for="row in results" :key="row.uid" @click="jump(row.uid)">
          <strong>{{ row.title }}</strong>
          <p>{{ row.excerpt }}</p>
          <small>{{ row.wordCount }} 字 · 定位原文 ↗</small>
        </button>
        <p v-if="searched && !results.length">没有匹配章节</p>
      </div>
    </template>
    <template v-else-if="mode === 'stats'">
      <div class="writer-stats-grid">
        <article>
          <small>全书正文</small><b>{{ writer.totalWords.toLocaleString() }}</b
          ><span>字 · {{ chapters.length }} 章</span>
        </article>
        <article>
          <small>{{ currentVolumeTitle }}</small
          ><b>{{ volumeWords?.toLocaleString() ?? '—' }}</b>
          <span>{{
            writer.current ? '字 · 排除回收站' : '打开章节后查看所在篇卷'
          }}</span>
        </article>
        <article>
          <small>当前章节</small
          ><b>{{ writer.current?.wordCount.toLocaleString() ?? '—' }}</b>
          <span>{{
            writer.current
              ? `字 · 含标点 ${currentCharacters.toLocaleString()} 字符（不含空白）`
              : '尚未打开章节'
          }}</span>
        </article>
        <article>
          <small>今日净增</small
          ><b>{{ today.net > 0 ? '+' : '' }}{{ today.net }}</b
          ><span>手输 {{ today.typed }} · 粘贴 {{ today.pasted }}</span>
        </article>
        <article>
          <small>今日活跃时间</small><b>{{ Math.round(today.seconds / 60) }}</b
          ><span
            >分钟 · 平均
            {{
              today.seconds ? Math.round((today.typed / today.seconds) * 60) : 0
            }}
            字/分</span
          >
        </article>
        <article>
          <small>峰值速率</small><b>{{ peak }}</b
          ><span>字/分 · 完整 60 秒窗口</span>
        </article>
        <article>
          <small>今日修订保存</small><b>{{ todayActivity?.revisionSaves ?? 0 }}</b>
          <span>正文文本有变化并成功保存 · 不等于净增字数</span>
        </article>
        <article>
          <small>今日定稿章节</small><b>{{ new Set(todayActivity?.completedChapterUids || []).size }}</b>
          <span>进入定稿的章节按日去重</span>
        </article>
        <article>
          <small>今日专注</small><b>{{ Math.floor((todayActivity?.focusSeconds ?? 0) / 60) }} <small>分</small></b>
          <span>完成 {{ todayActivity?.focusCompleted ?? 0 }} 段 · 与正文输入活跃时间分别统计</span>
        </article>
      </div>
      <label
        >每日目标<input
          v-model.number="dailyGoal"
          type="number"
          min="0"
          max="1000000" /></label
      ><progress
        :value="Math.max(0, today.net)"
        :max="todayActivity?.goal || 1"
      ></progress>
      <p>
        {{
          todayActivity?.goal
            ? Math.round((Math.max(0, today.net) / todayActivity.goal) * 100) + '%'
            : todayActivity?.goal === 0 ? '未设置目标' : '未记录目标'
        }}
        · 今日进度
      </p>
      <p>保存每日目标后更新今天与后续新日期，历史日期保留各自目标。</p>
      <p v-if="writer.statsError" role="alert">{{ writer.statsError }} <button type="button" class="btn-secondary" @click="reloadStats">重试统计</button></p>
      <WritingHeatmap :sessions="sessions" :stats="writer.workspace?.stats" />
      <p>
        汉字按字，英文按词，数字按组；标点与空白不计。粘贴、撤销和恢复不抬高速率。正文不包含回收站、资料和便笺。
      </p>
    </template>
    <template v-else-if="mode === 'connections'">
      <p>沿着人物、事件和伏笔，看看章节如何互相呼应。</p>
      <label
        >追踪资料<BaseSelect
          v-model="traceKey"
          aria-label="追踪资料"
          :options="traceOptions"
      /></label>
      <div class="writer-chapter-flow">
        <article v-for="chapter in traced" :key="chapter.uid">
          <button @click="jump(chapter.uid)">
            <small
              >{{ chapterStatuses[chapter.status] }} ·
              {{ chapter.wordCount }} 字</small
            >
            <h3>{{ chapter.title }}</h3>
          </button>
          <div class="writer-flow-tags">
            <button
              v-for="link in chapter.links"
              :key="link.uid"
              :class="{ active: traceKey === link.type + ':' + link.targetId }"
              @click="traceKey = link.type + ':' + link.targetId"
            >
              {{ link.title }} · {{ linkRoles[link.role] }}
            </button>
          </div>
        </article>
      </div>
      <h3>值得留意</h3>
      <p v-if="!unwritten.length && !unresolved.length">
        关联检查暂未发现待处理项。
      </p>
      <p v-for="item in unwritten" :key="'o' + item.id">
        尚未关联正文的大纲：<RouterLink
          :to="`/novel/${writer.novelId}/outlines?edit=${item.id}`"
          >{{ item.name }}</RouterLink
        >
      </p>
      <p v-for="item in unresolved" :key="'f' + item.id">
        待回收伏笔：<RouterLink
          :to="`/novel/${writer.novelId}/foreshadows?edit=${item.id}`"
          >{{ item.name }}</RouterLink
        >
      </p>
    </template>
    <p v-if="error" class="writer-warning" role="alert">{{ error }}</p>
    <template #actions
      ><button class="btn-secondary" :disabled="busy" @click="$emit('close')">
        关闭</button
      ><button
        v-if="['export', 'import', 'history', 'stats'].includes(mode)"
        class="btn-primary"
        :disabled="
          busy ||
          (mode === 'history' && !revision) ||
          (mode === 'import' && !imported.length)
        "
        @click="perform"
      >
        {{
          busy
            ? '处理中…'
            : mode === 'export'
              ? '导出文稿'
              : mode === 'import'
                ? '确认导入'
                : mode === 'stats'
                  ? '保存每日目标'
                  : '恢复此版本'
        }}
      </button></template
    >
  </BaseModal>
</template>
<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import BaseFilePicker from '@/components/common/BaseFilePicker.vue'
import WritingHeatmap from '@/components/writing/WritingHeatmap.vue'
import HistoryCompare from '@/components/writing/HistoryCompare.vue'
import {
  adoptHistorySelection,
  restoreHistorySnapshot,
} from '@/utils/historyAdoption'
import type { HistoryChange } from '@/utils/historyDiff'
import { useWritingStore } from '@/stores/writing'
import { writingApi } from '@/api/writing'
import {
  documentText,
  wordCount,
  parseManuscript,
  localDate,
  chapterStatuses,
  linkRoles,
} from '@/utils/writing'
import type { Chapter, DocNode } from '@/types/writing'
import {
  characterCountWithPunctuation,
  currentVolumeWords,
} from '@/utils/writingStatistics'
const props = defineProps<{ mode: string }>(),
  emit = defineEmits<{ close: [] }>(),
  writer = useWritingStore(),
  router = useRouter()
const novelId = writer.novelId
let disposed = false
onBeforeUnmount(() => {
  disposed = true
})
function ensureActive() {
  if (disposed || writer.novelId !== novelId)
    throw Error('作品已切换，已停止后续操作')
}
const titles: Record<string, string> = {
  export: '让故事走出工作室',
  import: '把旧稿带进来',
  history: '稿纸记得每一次修改',
  search: '在全书中寻找',
  stats: '你的创作足迹',
  connections: '章节之间，自有回响',
}
const format = ref('docx'),
  scope = ref('all'),
  selected = ref<string[]>([]),
  includeNotes = ref(false),
  titlePage = ref(true),
  toc = ref(false),
  pageBreak = ref(true),
  error = ref(''),
  busy = ref(false)
const formatOptions = [
  { value: 'docx', label: 'Word 文档 .docx' },
  { value: 'txt', label: '纯文本 .txt' },
  { value: 'md', label: 'Markdown .md' },
]
const scopeOptions = computed(() => [
  { value: 'all', label: '整部作品' },
  { value: 'chapter', label: '当前章节', disabled: !writer.current },
  { value: 'volume', label: '当前篇卷', disabled: !writer.current?.volumeId },
  { value: 'selected', label: '选择章节' },
])
const volumeOptions = computed(() => [
  { value: '', label: '未分卷' },
  ...(writer.workspace?.volumes || []).map((volume) => ({
    value: volume.uid,
    label: volume.title,
  })),
])
const imported = ref<{ uid: string; title: string; doc: DocNode }[]>([]),
  importVolume = ref(writer.current?.volumeId || ''),
  importedCount = ref(0)
const readingImport = ref(false)
const history = ref<
    { id: number; revision: number; label: string; createdAt: string }[]
  >([]),
  revision = ref<Chapter | null>(null),
  searchText = ref(''),
  searched = ref(false),
  results = ref<any[]>([]),
  traceKey = ref('')
const historyId = ref<string | number>('')
const historyChapterUid = writer.current?.uid
const historyNotice = ref('')
watch(
  () => writer.current?.uid,
  (uid) => {
    if (props.mode === 'history' && uid !== historyChapterUid) {
      revisionRequest++
      revision.value = null
      historyId.value = ''
      history.value = []
      historyNotice.value = ''
      error.value = '章节已切换，请关闭后重新打开历史'
    }
  },
)
const historyOptions = computed(() => [
  { value: '', label: '选择版本进行比较' },
  ...history.value.map((row) => ({
    value: row.id,
    label: `${row.createdAt.replace('T', ' ').slice(0, 19)} · ${row.label} · v${row.revision}`,
  })),
])
const dailyGoal = ref(writer.workspace?.preferences.dailyGoal ?? 2000),
  chapters = computed(
    () => writer.workspace?.chapters.filter((c) => !c.deleted) || [],
  )
const sessions = computed(() => writer.workspace?.sessions || [])
const todayDate = ref(localDate())
const todayActivity = computed(() => writer.workspace?.stats?.days.find((day) => day.date === todayDate.value))
let dateTimer: ReturnType<typeof setInterval> | undefined
async function reloadStats() {
  if (await writer.ensureStatsDay()) await writer.refreshStats()
}
const volumeWords = computed(() =>
  currentVolumeWords(chapters.value, writer.current),
)
const currentVolumeTitle = computed(() => {
  if (!writer.current) return '当前篇卷'
  if (!writer.current.volumeId) return '未分卷章节合计'
  return (
    '当前卷 · ' +
    (writer.workspace?.volumes.find(
      (volume) => volume.uid === writer.current!.volumeId,
    )?.title || '未命名篇卷')
  )
})
const currentCharacters = computed(() =>
  characterCountWithPunctuation(
    writer.current ? documentText(writer.current.doc) : '',
  ),
)
const today = computed(() =>
  sessions.value
    .filter((s) => s.date === todayDate.value)
    .reduce(
      (a, s) => ({
        net: a.net + s.net,
        typed: a.typed + s.typed,
        pasted: a.pasted + s.pasted,
        seconds: a.seconds + s.activeSeconds,
      }),
      { net: 0, typed: 0, pasted: 0, seconds: 0 },
    ),
)
const peak = computed(() => Math.max(0, ...sessions.value.map((s) => s.peak)))
const linkedResources = computed(() => {
  const seen = new Map<string, string>()
  chapters.value.forEach((c) =>
    c.links.forEach((l) => seen.set(l.type + ':' + l.targetId, l.title)),
  )
  return [...seen].map(([key, label]) => ({ key, label }))
})
const traceOptions = computed(() => [
  { value: '', label: '所有已关联资料' },
  ...linkedResources.value.map((resource) => ({
    value: resource.key,
    label: resource.label,
  })),
])
const traced = computed(() =>
  chapters.value.filter(
    (c) =>
      !traceKey.value ||
      c.links.some((l) => l.type + ':' + l.targetId === traceKey.value),
  ),
)
const unwritten = computed(() =>
  writer.resources.filter(
    (r) =>
      r.type === 'outlines' &&
      !chapters.value.some((c) =>
        c.links.some((l) => l.type === 'outlines' && l.targetId === r.id),
      ),
  ),
)
const unresolved = computed(() =>
  writer.resources.filter(
    (r) =>
      r.type === 'foreshadows' &&
      r.raw.status === 'pending' &&
      !chapters.value.some((c) =>
        c.links.some(
          (l) =>
            l.type === 'foreshadows' &&
            l.targetId === r.id &&
            l.role === 'revealed',
        ),
      ),
  ),
)
async function readImport(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || busy.value || readingImport.value) return
  error.value = ''
  imported.value = []
  importedCount.value = 0
  if (file.size > 4 * 1024 * 1024) {
    error.value = '请将文稿分为每个不超过 4 MB 的文件再导入'
    return
  }
  readingImport.value = true
  try {
    const text = await file.text()
    ensureActive()
    imported.value = parseManuscript(text, file.name).map((c) => ({
      ...c,
      uid: crypto.randomUUID(),
    }))
  } catch (failure) {
    error.value =
      failure instanceof Error
        ? failure.message
        : '旧稿读取失败，请重新选择文件'
  } finally {
    readingImport.value = false
  }
}
let revisionRequest = 0
async function loadRevision(id: number) {
  const requestId = ++revisionRequest
  revision.value = null
  error.value = ''
  historyNotice.value = ''
  if (!id || !writer.current) return
  try {
    const selected = await writingApi.revision(
      writer.novelId,
      writer.current.uid,
      id,
    )
    ensureActive()
    if (writer.current?.uid !== historyChapterUid) return
    if (requestId === revisionRequest) revision.value = selected
  } catch {
    if (requestId === revisionRequest) error.value = '版本读取失败，请重新选择'
  }
}
async function adoptBlock(row: HistoryChange) {
  if (busy.value || !revision.value) return
  busy.value = true
  error.value = ''
  historyNotice.value = ''
  try {
    if (writer.current?.uid !== historyChapterUid)
      throw Error('章节已切换，请重新打开历史')
    await adoptHistorySelection(writer, revision.value.doc, row, ensureActive)
    historyNotice.value = '已采用并保存。采用前的当前稿已留在历史中。'
  } catch (failure: any) {
    error.value =
      failure?.response?.data?.message ||
      failure.message ||
      '采用失败，请检查保存状态'
  } finally {
    busy.value = false
  }
}
async function search() {
  busy.value = true
  error.value = ''
  try {
    results.value = await writingApi.search(writer.novelId, searchText.value)
    searched.value = true
  } catch {
    error.value = '搜索失败，请重试'
  } finally {
    busy.value = false
  }
}
async function jump(uid: string) {
  emit('close')
  await router.push({
    path: `/novel/${writer.novelId}/writing`,
    query: {
      chapter: uid,
      ...(searchText.value ? { find: searchText.value } : {}),
    },
  })
}
async function perform() {
  busy.value = true
  error.value = ''
  try {
    ensureActive()
    if (props.mode === 'export') {
      if (!(await writer.flush())) throw Error('请先保存或处理冲突，再导出')
      ensureActive()
      let uids: string[] = []
      if (scope.value === 'chapter') uids = [writer.current!.uid]
      if (scope.value === 'volume')
        uids = chapters.value
          .filter((c) => c.volumeId === writer.current!.volumeId)
          .map((c) => c.uid)
      if (scope.value === 'selected') {
        uids = selected.value
        if (!uids.length) throw Error('请至少选择一章')
      }
      const blob = await writingApi.export(writer.novelId, {
        format: format.value,
        uids,
        includeNotes: includeNotes.value,
        titlePage: titlePage.value,
        toc: toc.value,
        pageBreak: pageBreak.value,
      })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = `文稿-${new Date().toISOString().slice(0, 10)}.${format.value}`
      a.click()
      setTimeout(() => URL.revokeObjectURL(url), 30000)
    } else if (props.mode === 'import') {
      if (!(await writer.flush())) throw Error('请先保存当前章节')
      ensureActive()
      for (let i = importedCount.value; i < imported.value.length; i++) {
        await writingApi.create(novelId, {
          ...imported.value[i],
          volumeId: importVolume.value || null,
          links: [],
        })
        importedCount.value = i + 1
        ensureActive()
      }
      await writer.refresh()
      ensureActive()
      await writer.select(imported.value[0].uid)
      emit('close')
    } else if (props.mode === 'history' && revision.value && writer.current) {
      await restoreHistorySnapshot(
        writer,
        revision.value,
        historyChapterUid || '',
        ensureActive,
      )
      emit('close')
    } else if (props.mode === 'stats') {
      await writer.preferences({
        ...writer.workspace!.preferences,
        dailyGoal: Math.max(0, Number(dailyGoal.value) || 0),
      })
      emit('close')
    }
  } catch (e: any) {
    error.value = e?.response?.data?.message || e.message || '操作失败，可重试'
  } finally {
    busy.value = false
  }
}
onMounted(async () => {
  if (props.mode === 'stats') {
    void reloadStats()
    dateTimer = setInterval(() => {
      const date = localDate()
      if (todayDate.value !== date) { todayDate.value = date; void reloadStats() }
    }, 10000)
  }
  if (props.mode === 'history' && writer.current) {
    try {
      history.value = await writingApi.revisions(
        writer.novelId,
        writer.current.uid,
      )
    } catch {
      error.value = '历史版本读取失败'
    }
  }
})
onBeforeUnmount(() => clearInterval(dateTimer))
</script>

<style scoped>
.writer-import-file {
  margin-bottom: 18px;
}
.history-notice {
  color: var(--accent);
  font-size: 13px;
}
</style>
