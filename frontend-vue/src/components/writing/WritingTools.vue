<template>
  <BaseModal
    :busy="busy"
    :wide="mode === 'stats'"
    :title="titles[mode]"
    @close="$emit('close')"
  >
    <template v-if="mode === 'export'">
      <div class="writer-options">
        <label
          >格式<select v-model="format">
            <option value="docx">Word 文档 .docx</option>
            <option value="txt">纯文本 .txt</option>
            <option value="md">Markdown .md</option>
          </select></label
        ><label
          >范围<select v-model="scope">
            <option value="all">整部作品</option>
            <option value="chapter" :disabled="!writer.current">
              当前章节
            </option>
            <option value="volume" :disabled="!writer.current?.volumeId">
              当前篇卷
            </option>
            <option value="selected">选择章节</option>
          </select></label
        >
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
        />附加创作笔记与关联资料</label
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
        正文导出保留篇卷与章节顺序。完整资料、图片和历史版本请使用设置页的含图片备份。
      </p>
    </template>
    <template v-else-if="mode === 'import'">
      <input
        type="file"
        accept=".txt,.md,.markdown,text/plain,text/markdown"
        aria-label="选择旧稿"
        @change="readImport"
      />
      <label
        >导入篇卷<select v-model="importVolume">
          <option value="">未分卷</option>
          <option
            v-for="volume in writer.workspace?.volumes"
            :key="volume.uid"
            :value="volume.uid"
          >
            {{ volume.title }}
          </option>
        </select></label
      >
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
      <select
        aria-label="选择历史版本"
        @change="
          loadRevision(Number(($event.target as HTMLSelectElement).value))
        "
      >
        <option value="">选择版本进行比较</option>
        <option v-for="row in history" :key="row.id" :value="row.id">
          {{ row.createdAt.replace('T', ' ').slice(0, 19) }} · {{ row.label }} ·
          v{{ row.revision }}
        </option>
      </select>
      <div v-if="revision" class="writer-compare">
        <label
          >当前正文 · {{ writer.current?.wordCount }} 字<textarea
            readonly
            :value="documentText(writer.current!.doc)"
            rows="14"
          /></label
        ><label
          >历史正文 · {{ wordCount(documentText(revision.doc)) }} 字<textarea
            readonly
            :value="documentText(revision.doc)"
            rows="14"
          />
        </label>
      </div>
    </template>
    <template v-else-if="mode === 'search'">
      <form class="writer-find" @submit.prevent="search">
        <input
          v-model="searchText"
          placeholder="搜索全书正文与章名"
          aria-label="搜索全书正文与章名"
        /><button>搜索</button>
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
      </div>
      <label
        >每日目标<input
          v-model.number="dailyGoal"
          type="number"
          min="0"
          max="1000000" /></label
      ><progress
        :value="Math.max(0, today.net)"
        :max="dailyGoal || 1"
      ></progress>
      <p>
        {{
          dailyGoal
            ? Math.round((Math.max(0, today.net) / dailyGoal) * 100) + '%'
            : '未设置目标'
        }}
        · 今日进度
      </p>
      <WritingHeatmap :sessions="sessions" :daily-goal="dailyGoal" />
      <p>
        汉字按字，英文按词，数字按组；标点与空白不计。粘贴、撤销和恢复不抬高速率。正文不包含回收站、资料和便笺。
      </p>
    </template>
    <template v-else-if="mode === 'connections'">
      <p>沿着人物、事件和伏笔，看看章节如何互相呼应。</p>
      <label
        >追踪资料<select v-model="traceKey">
          <option value="">所有已关联资料</option>
          <option
            v-for="resource in linkedResources"
            :key="resource.key"
            :value="resource.key"
          >
            {{ resource.label }}
          </option>
        </select></label
      >
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
import { computed, ref, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import BaseModal from '@/components/common/BaseModal.vue'
import WritingHeatmap from '@/components/writing/WritingHeatmap.vue'
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
const imported = ref<{ uid: string; title: string; doc: DocNode }[]>([]),
  importVolume = ref(writer.current?.volumeId || ''),
  importedCount = ref(0)
const history = ref<
    { id: number; revision: number; label: string; createdAt: string }[]
  >([]),
  revision = ref<Chapter | null>(null),
  searchText = ref(''),
  searched = ref(false),
  results = ref<any[]>([]),
  traceKey = ref('')
const dailyGoal = ref(writer.workspace?.preferences.dailyGoal ?? 2000),
  chapters = computed(
    () => writer.workspace?.chapters.filter((c) => !c.deleted) || [],
  )
const sessions = computed(() => writer.workspace?.sessions || [])
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
    .filter((s) => s.date === localDate())
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
  if (!file) return
  if (file.size > 4 * 1024 * 1024) {
    error.value = '请将文稿分为每个不超过 4 MB 的文件再导入'
    return
  }
  const text = await file.text()
  imported.value = parseManuscript(text, file.name).map((c) => ({
    ...c,
    uid: crypto.randomUUID(),
  }))
  importedCount.value = 0
}
let revisionRequest = 0
async function loadRevision(id: number) {
  const requestId = ++revisionRequest
  revision.value = null
  error.value = ''
  if (!id || !writer.current) return
  try {
    const selected = await writingApi.revision(
      writer.novelId,
      writer.current.uid,
      id,
    )
    if (requestId === revisionRequest) revision.value = selected
  } catch {
    if (requestId === revisionRequest) error.value = '版本读取失败，请重新选择'
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
      const old = revision.value
      const chapterUid = writer.current.uid
      if (!(await writer.flush(true)))
        throw Error('请先保存当前修改或处理冲突，再恢复历史版本')
      ensureActive()
      if (writer.current?.uid !== chapterUid)
        throw Error('章节已切换，请重新选择历史版本')
      Object.assign(writer.current, {
        doc: old.doc,
        links: old.links,
        notes: old.notes,
        summary: old.summary,
        title: old.title,
      })
      writer.changed()
      if (!(await writer.flush(true)))
        throw Error('恢复内容尚未保存，请处理保存状态')
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
</script>
