<template>
  <section class="reader-workspace" aria-label="读者模式">
    <header class="reader-topbar">
      <div>
        <span class="reader-eyebrow">READER / 读者模式</span>
        <p>放下创作资料，重新遇见故事。</p>
      </div>
      <button type="button" class="btn-secondary" @click="requestClose">
        返回编辑 ↗
      </button>
    </header>
    <nav class="reader-toolbar" aria-label="阅读操作">
      <BaseSelect
        v-model="mode"
        aria-label="阅读方式"
        :options="modeOptions"
        class="reader-mode-select"
      />
      <button
        type="button"
        :disabled="chapterIndex < 1 || pending"
        @click="changeChapter(-1)"
      >
        ← 上一章
      </button>
      <span :title="activeChapter.title"
        >{{ Math.max(1, chapterIndex + 1) }} / {{ chapters.length }} 章</span
      >
      <button
        type="button"
        :disabled="
          chapterIndex < 0 || chapterIndex >= chapters.length - 1 || pending
        "
        @click="changeChapter(1)"
      >
        下一章 →
      </button>
      <span class="reader-toolbar-space"></span>
      <button
        type="button"
        aria-label="缩小阅读字号"
        :disabled="fontSize <= 16"
        @click="fontSize -= 2"
      >
        A−
      </button>
      <button
        type="button"
        aria-label="放大阅读字号"
        :disabled="fontSize >= 28"
        @click="fontSize += 2"
      >
        A＋
      </button>
      <button
        type="button"
        :aria-pressed="Boolean(activeBookmark)"
        :disabled="pending || !desk.data || !activeBlock"
        @click="toggleBookmark"
      >
        {{ activeBookmark ? '已加书签' : '＋ 书签' }}
      </button>
      <button
        type="button"
        :aria-expanded="notesOpen"
        @click="notesOpen = !notesOpen"
      >
        批注与书签 <small>{{ bookmarks.length + chapterTasks.length }}</small>
      </button>
    </nav>
    <div v-if="error || desk.error" class="reader-error" role="alert">
      <span>{{ desk.error || error }}</span
      ><button
        type="button"
        class="btn-secondary"
        :disabled="pending || desk.loading"
        @click="reloadDesk"
      >
        重新载入便签
      </button>
    </div>
    <p v-if="notice" class="reader-notice" role="status">{{ notice }}</p>
    <div v-if="draftStorageError" class="reader-error" role="alert">
      <span>{{ draftStorageError }}</span>
      <button
        v-if="pendingLocalDrafts.size"
        type="button"
        class="btn-secondary"
        @click="returnToUnsaved"
      >
        查看未保存批注（{{ pendingLocalDrafts.size }} 章）
      </button>
    </div>
    <div v-if="pendingDestination" class="reader-confirm" role="alert">
      <span
        >有
        {{
          pendingLocalDrafts.size
        }}
        章批注未能保存在本机。请返回对应章节保存，或明确放弃这些草稿后离开。</span
      >
      <button
        type="button"
        class="btn-secondary"
        @click="pendingDestination = null"
      >
        继续批注
      </button>
      <button type="button" class="btn-secondary" @click="returnToUnsaved">
        返回未保存批注
      </button>
      <button type="button" class="btn-secondary" @click="discardAndLeave">
        放弃这些草稿并离开
      </button>
    </div>
    <div class="reader-layout" :class="{ 'with-notes': notesOpen }">
      <ReaderViewport
        ref="viewport"
        :novel-id="contextNovel"
        :book-uid="contextBook"
        :initial-chapter="chapter"
        :initial-block="initialBlock"
        :chapters="chapters"
        :volumes="writer.workspace?.volumes || []"
        :mode="mode"
        :font-size="fontSize"
        @position="readingPosition"
        @selection="captureSelection"
        @error="notice = $event"
      />
      <aside v-if="notesOpen" class="reader-notes" aria-label="阅读批注与书签">
        <div class="reader-note-title">
          <h3>留下阅读感受</h3>
          <button
            type="button"
            aria-label="收起阅读批注与书签"
            @click="notesOpen = false"
          >
            ×
          </button>
        </div>
        <p class="reader-hint">在正文选中一句话，再记录哪里需要打磨。</p>
        <p class="reader-note-chapter">{{ activeChapter.title }}</p>
        <blockquote
          v-if="noteAnchor?.excerpt || selectedExcerpt || sourceExcerpt"
        >
          {{ noteAnchor?.excerpt || selectedExcerpt || sourceExcerpt }}
        </blockquote>
        <p v-else class="reader-hint">批注将关联当前章节。</p>
        <label
          >修订分类<BaseSelect
            v-model="category"
            aria-label="阅读批注分类"
            :options="revisionCategories"
            :disabled="pending"
        /></label>
        <label
          >阅读批注<textarea
            v-model="note"
            rows="4"
            maxlength="4000"
            placeholder="例如：这里的对白太直白，可以藏一点。"
            :disabled="pending"
          />
        </label>
        <div
          v-if="remoteNote && note.trim()"
          class="reader-remote"
          role="alert"
        >
          <p>这条批注已有保存版本。请比较后决定保留哪份。</p>
          <blockquote>{{ remoteNote.body }}</blockquote>
          <button type="button" class="btn-secondary" @click="clearNote">
            采用已保存版本</button
          ><button type="button" class="btn-secondary" @click="separateNote">
            当前内容另存一条
          </button>
        </div>
        <button
          type="button"
          class="btn-primary"
          :disabled="
            !note.trim() || pending || !desk.data || Boolean(remoteNote)
          "
          @click="saveNote"
        >
          {{ pending ? '保存中…' : '加入修订清单' }}
        </button>
        <p class="reader-hint">
          未提交的批注暂存于此浏览器；加入修订清单后随作品保存。
        </p>
        <h3 class="reader-list-heading">
          本章书签 <small>{{ bookmarks.length }}</small>
        </h3>
        <p v-if="!bookmarks.length" class="reader-hint">
          点击段落，再用上方「＋ 书签」留下位置。
        </p>
        <div
          v-for="bookmark in bookmarks"
          :key="bookmark.uid"
          class="reader-item"
        >
          <button type="button" @click="goToBlock(bookmark.blockId)">
            {{ bookmark.label || bookmark.excerpt || '章节书签' }}
          </button>
          <button
            type="button"
            class="reader-remove"
            aria-label="删除书签"
            :disabled="pending"
            @click="removeBookmark(bookmark.uid)"
          >
            ×
          </button>
        </div>
        <h3 class="reader-list-heading">
          本章待修订 <small>{{ chapterTasks.length }}</small>
        </h3>
        <p v-if="!chapterTasks.length" class="reader-hint">
          阅读时的批注会汇入修订工作台。
        </p>
        <button
          v-for="task in chapterTasks"
          :key="task.uid"
          type="button"
          class="reader-task"
          @click="goToBlock(task.blockId)"
        >
          <small>{{ revisionCategoryLabel(task.category) }}</small
          ><span>{{ task.body }}</span>
        </button>
      </aside>
    </div>
    <footer class="reader-footer">
      <span>{{
        selectedExcerpt
          ? '已选中文字，可在「批注与书签」中记录感受'
          : '点选段落标记位置，返回编辑时回到这里'
      }}</span
      ><button
        type="button"
        :aria-expanded="notesOpen"
        @click="notesOpen = !notesOpen"
      >
        {{ selectedExcerpt ? '批注所选文字' : '写下批注' }} ↗
      </button>
    </footer>
  </section>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  ref,
  shallowRef,
  watch,
} from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import BaseSelect from '@/components/common/BaseSelect.vue'
import ReaderViewport from './ReaderViewport.vue'
import { useWritingStore } from '@/stores/writing'
import { useWritingDeskStore } from '@/stores/writingDesk'
import type { Chapter } from '@/types/writing'
import type { ReaderBookmark, RevisionTask } from '@/types/writingDesk'
import { blockList, documentText } from '@/utils/writing'
import {
  decodeReaderDraft,
  readerDraftKey,
  type ReaderNoteDraft,
} from '@/utils/readerDraft'
import {
  findDocumentBlock,
  readingOrder,
  revisionCategories,
  revisionCategoryLabel,
} from '@/utils/revisionDesk'
import type { ReaderLocation, ReaderMode } from '@/utils/readerLayout'

const props = defineProps<{ chapter: Chapter; initialBlock?: string }>()
const emit = defineEmits<{
  close: [target: ReaderLocation]
  navigate: [target: ReaderLocation]
  position: [target: ReaderLocation]
}>()
const writer = useWritingStore(),
  desk = useWritingDeskStore()
const contextNovel = writer.novelId,
  contextBook = writer.workspace?.uid || ''
const viewport = ref<InstanceType<typeof ReaderViewport> | null>(null)
const activeChapter = shallowRef(props.chapter)
const activeBlock = ref(
  props.initialBlock || blockList(props.chapter.doc)[0]?.id || '',
)
const mode = ref<ReaderMode>('chapter'),
  fontSize = ref(20)
const modeOptions = [
  { value: 'chapter', label: '逐章阅读' },
  { value: 'continuous', label: '全书连续' },
  { value: 'paged', label: '分页阅读' },
]
const preferenceKey = 'ink-reader-preferences-v1'
try {
  const preferences = JSON.parse(localStorage.getItem(preferenceKey) || '{}')
  if (modeOptions.some((option) => option.value === preferences.mode))
    mode.value = preferences.mode
  if (
    Number.isInteger(preferences.fontSize) &&
    preferences.fontSize >= 16 &&
    preferences.fontSize <= 28
  )
    fontSize.value = preferences.fontSize
} catch {}
const selectedExcerpt = ref(''),
  selectedBlock = ref(''),
  notesOpen = ref(false)
const note = ref(''),
  noteUid = ref<string>(crypto.randomUUID()),
  noteCreatedAt = ref(new Date().toISOString())
const noteChapterUid = ref(props.chapter.uid)
const noteAnchor = ref<{ blockId: string; excerpt: string } | null>(null)
const category = ref<RevisionTask['category']>('wording')
const bookmarkDraft = ref<ReaderBookmark | null>(null)
const pending = ref(false),
  error = ref(''),
  notice = ref(''),
  draftStorageError = ref('')
const pendingDestination = ref<ReaderLocation | null>(null)
const localDrafts = new Map<string, ReaderNoteDraft>()
const pendingLocalDrafts = ref(new Set<string>())
let restoringDraft = false,
  restoreGeneration = 0,
  disposed = false
const contextActive = () =>
  !disposed &&
  writer.novelId === contextNovel &&
  writer.workspace?.uid === contextBook
const keyFor = (uid: string) => readerDraftKey(contextNovel, contextBook, uid)
const chapters = computed(() =>
  readingOrder(
    writer.workspace?.chapters || [],
    writer.workspace?.volumes || [],
  ),
)
const chapterIndex = computed(() =>
  chapters.value.findIndex(
    (chapter) => chapter.uid === activeChapter.value.uid,
  ),
)
const bookmarks = computed(() =>
  (desk.data?.bookmarks || []).filter(
    (bookmark) => bookmark.chapterUid === activeChapter.value.uid,
  ),
)
const activeBookmark = computed(() =>
  bookmarks.value.find((bookmark) => bookmark.blockId === activeBlock.value),
)
const chapterTasks = computed(() =>
  (desk.data?.tasks || []).filter(
    (task) =>
      task.chapterUid === activeChapter.value.uid && task.status === 'open',
  ),
)
const sourceExcerpt = computed(() => {
  const block = findDocumentBlock(activeChapter.value.doc, activeBlock.value)
  return block ? documentText(block).trim().slice(0, 280) : ''
})
const remoteNote = computed(() =>
  desk.data?.tasks.find((task) => task.uid === noteUid.value),
)
function message(cause: unknown) {
  return cause instanceof Error
    ? cause.message
    : '保存失败，请稍后重试。输入内容已保留。'
}
async function perform(action: () => Promise<void>, success: string) {
  if (pending.value || !contextActive()) return
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    if (contextActive()) notice.value = success
  } catch (cause) {
    if (contextActive()) error.value = message(cause)
  } finally {
    if (!disposed) pending.value = false
  }
}
function snapshotDraft(): ReaderNoteDraft {
  return {
    uid: noteUid.value,
    createdAt: noteCreatedAt.value,
    body: note.value,
    category: category.value,
    anchor: noteAnchor.value ? { ...noteAnchor.value } : null,
  }
}
function persistDraft() {
  if (restoringDraft) return pendingLocalDrafts.value.size === 0
  const uid = noteChapterUid.value
  const draft = note.value.trim() ? snapshotDraft() : undefined
  if (draft) localDrafts.set(uid, draft)
  else localDrafts.delete(uid)
  try {
    if (draft) localStorage.setItem(keyFor(uid), JSON.stringify(draft))
    else localStorage.removeItem(keyFor(uid))
    pendingLocalDrafts.value.delete(uid)
    updateDraftWarning()
    return pendingLocalDrafts.value.size === 0
  } catch {
    if (draft) pendingLocalDrafts.value.add(uid)
    else pendingLocalDrafts.value.delete(uid)
    updateDraftWarning()
    return false
  }
}
function updateDraftWarning() {
  draftStorageError.value = pendingLocalDrafts.value.size
    ? `有 ${pendingLocalDrafts.value.size} 章批注仅保留在本次阅读内存中。请返回对应章节加入修订清单，再离开。`
    : ''
}
function discardStoredDraft(chapterUid: string) {
  localDrafts.delete(chapterUid)
  pendingLocalDrafts.value.delete(chapterUid)
  updateDraftWarning()
  try {
    localStorage.removeItem(keyFor(chapterUid))
  } catch {}
}
async function restoreDraft(chapterUid: string) {
  const turn = ++restoreGeneration
  restoringDraft = true
  noteChapterUid.value = chapterUid
  let saved = localDrafts.get(chapterUid)
  try {
    if (!saved) {
      const raw = localStorage.getItem(keyFor(chapterUid))
      saved = raw ? decodeReaderDraft(raw) : undefined
    }
  } catch {
    draftStorageError.value =
      '此浏览器暂时无法读取批注草稿，请先保存新批注再离开。'
  }
  note.value = saved?.body || ''
  category.value = saved?.category || 'wording'
  noteAnchor.value = saved?.anchor || null
  noteUid.value = saved?.uid || crypto.randomUUID()
  noteCreatedAt.value = saved?.createdAt || new Date().toISOString()
  selectedExcerpt.value = ''
  selectedBlock.value = ''
  if (saved) {
    notesOpen.value = true
    notice.value = '已找回本章尚未提交的阅读批注。'
  }
  await nextTick()
  if (turn === restoreGeneration) restoringDraft = false
}
function readingPosition(value: { chapter: Chapter; blockId: string }) {
  if (!contextActive()) return
  if (activeChapter.value.uid !== value.chapter.uid) {
    persistDraft()
    activeChapter.value = value.chapter
    activeBlock.value = value.blockId
    bookmarkDraft.value = null
    void restoreDraft(value.chapter.uid)
  } else {
    activeChapter.value = value.chapter
    activeBlock.value = value.blockId
  }
  emit('position', {
    chapterUid: value.chapter.uid,
    blockId: value.blockId || undefined,
  })
}
function captureSelection(value: {
  chapter: Chapter
  blockId: string
  excerpt: string
}) {
  readingPosition(value)
  if (!note.value.trim()) {
    selectedExcerpt.value = value.excerpt
    selectedBlock.value = value.excerpt ? value.blockId : ''
  }
}
async function goToBlock(blockId: string) {
  await viewport.value?.jump(activeChapter.value.uid, blockId)
}
async function changeChapter(direction: number) {
  persistDraft()
  await viewport.value?.moveChapter(direction)
}
async function toggleBookmark() {
  if (activeBookmark.value) return removeBookmark(activeBookmark.value.uid)
  if (
    !bookmarkDraft.value ||
    bookmarkDraft.value.chapterUid !== activeChapter.value.uid ||
    bookmarkDraft.value.blockId !== activeBlock.value
  ) {
    const excerpt = sourceExcerpt.value
    bookmarkDraft.value = {
      uid: crypto.randomUUID(),
      chapterUid: activeChapter.value.uid,
      blockId: activeBlock.value,
      excerpt,
      label: excerpt.slice(0, 42) || '阅读位置',
      createdAt: new Date().toISOString(),
    }
  }
  const bookmark = { ...bookmarkDraft.value }
  await perform(async () => {
    await desk.saveBookmark(bookmark)
    if (bookmarkDraft.value?.uid === bookmark.uid) bookmarkDraft.value = null
  }, '书签已保存。')
}
async function removeBookmark(uid: string) {
  await perform(() => desk.deleteBookmark(uid), '书签已移除。')
}
function clearNote() {
  discardStoredDraft(noteChapterUid.value)
  note.value = ''
  selectedExcerpt.value = ''
  selectedBlock.value = ''
  noteAnchor.value = null
  noteUid.value = crypto.randomUUID()
  noteCreatedAt.value = new Date().toISOString()
  pendingDestination.value = null
}
function separateNote() {
  noteUid.value = crypto.randomUUID()
  noteCreatedAt.value = new Date().toISOString()
}
async function saveNote() {
  if (!note.value.trim() || remoteNote.value) return
  const task: RevisionTask = {
    uid: noteUid.value,
    chapterUid: noteChapterUid.value,
    ...(noteAnchor.value || {
      blockId: selectedBlock.value || activeBlock.value,
      excerpt: selectedExcerpt.value || sourceExcerpt.value,
    }),
    body: note.value.trim(),
    category: category.value,
    priority: 'normal',
    status: 'open',
    createdAt: noteCreatedAt.value,
    updatedAt: new Date().toISOString(),
  }
  await perform(async () => {
    await desk.saveTask(task)
    if (!contextActive()) return
    discardStoredDraft(task.chapterUid)
    if (noteUid.value === task.uid && noteChapterUid.value === task.chapterUid)
      clearNote()
  }, '已加入修订清单，稍后可以逐条处理。')
}
async function reloadDesk() {
  await perform(async () => {
    await desk.reload()
    const saved = remoteNote.value
    if (
      saved &&
      saved.body === note.value.trim() &&
      saved.category === category.value &&
      saved.blockId === noteAnchor.value?.blockId &&
      saved.excerpt === noteAnchor.value?.excerpt
    )
      clearNote()
  }, '便签已重新载入；已保存的相同批注已确认，其余输入仍保留。')
}
function requestClose() {
  const target = {
    chapterUid: activeChapter.value.uid,
    blockId: activeBlock.value || undefined,
  }
  persistDraft()
  if (pendingLocalDrafts.value.size) {
    pendingDestination.value = target
    return
  }
  emit('close', target)
}
async function returnToUnsaved() {
  persistDraft()
  const uid = pendingLocalDrafts.value.values().next().value
  if (!uid) return
  const blockId = localDrafts.get(uid)?.anchor?.blockId || ''
  pendingDestination.value = null
  notesOpen.value = true
  await viewport.value?.jump(uid, blockId)
}
function discardAndLeave() {
  const target = pendingDestination.value
  const discardCurrent = pendingLocalDrafts.value.has(noteChapterUid.value)
  for (const uid of [...pendingLocalDrafts.value]) discardStoredDraft(uid)
  if (discardCurrent) clearNote()
  pendingDestination.value = null
  if (target) emit('close', target)
}
function protectUnsaved(event: BeforeUnloadEvent) {
  persistDraft()
  if (pendingLocalDrafts.value.size) {
    event.preventDefault()
    event.returnValue = ''
  }
}
onBeforeRouteLeave(() => {
  persistDraft()
  if (!pendingLocalDrafts.value.size) return true
  pendingDestination.value = {
    chapterUid: activeChapter.value.uid,
    blockId: activeBlock.value || undefined,
  }
  return false
})
watch(note, (value, previous) => {
  if (restoringDraft) return
  if (value.trim() && !previous.trim())
    noteAnchor.value = {
      blockId: selectedBlock.value || activeBlock.value,
      excerpt: selectedExcerpt.value || sourceExcerpt.value,
    }
  if (!value.trim()) noteAnchor.value = null
})
watch([note, category, noteAnchor, noteUid], persistDraft, {
  deep: true,
  flush: 'post',
})
watch([mode, fontSize], () => {
  try {
    localStorage.setItem(
      preferenceKey,
      JSON.stringify({ mode: mode.value, fontSize: fontSize.value }),
    )
  } catch {}
})
void restoreDraft(props.chapter.uid)
window.addEventListener('beforeunload', protectUnsaved)
onBeforeUnmount(() => {
  persistDraft()
  disposed = true
  restoreGeneration++
  window.removeEventListener('beforeunload', protectUnsaved)
})
</script>

<style scoped>
.reader-workspace {
  display: flex;
  flex-direction: column;
  min-height: 550px;
  height: calc(100dvh - 150px);
  max-height: 1200px;
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 22px;
  overflow: hidden;
  box-shadow: var(--shadow);
}
.reader-topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 22px 28px 16px;
}
.reader-eyebrow {
  color: var(--accent);
  font-size: 10px;
  letter-spacing: 0.16em;
}
.reader-topbar p {
  margin: 7px 0 0;
  color: var(--muted);
  font-family: var(--serif);
  font-size: 16px;
}
.reader-topbar button {
  white-space: nowrap;
}
.reader-toolbar {
  display: flex;
  gap: 7px;
  align-items: center;
  flex-wrap: wrap;
  padding: 10px 24px;
  border-block: 1px solid var(--line);
}
.reader-toolbar > span {
  font-size: 11px;
  color: var(--muted);
}
.reader-toolbar-space {
  flex: 1;
}
.reader-toolbar :deep(.reader-mode-select) {
  --select-width: 138px;
  --select-height: 34px;
  --select-font-size: 12px;
  width: 138px;
  flex-basis: 138px;
  flex-shrink: 0;
}
.reader-note-chapter {
  color: var(--accent);
  font-size: 11px;
  line-height: 1.6;
}
.reader-toolbar button:not(.base-select),
.reader-footer button,
.reader-note-title button {
  border: 1px solid transparent;
  background: transparent;
  color: var(--muted);
  padding: 7px 9px;
  border-radius: 8px;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}
.reader-toolbar button:not(.base-select):hover,
.reader-toolbar button:not(.base-select)[aria-pressed='true'],
.reader-toolbar button:not(.base-select)[aria-expanded='true'],
.reader-footer button:hover {
  color: var(--accent);
  background: var(--accent-soft);
  border-color: var(--line);
}
.reader-toolbar button:disabled {
  opacity: 0.35;
  cursor: default;
}
.reader-layout {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr);
}
.reader-layout.with-notes {
  grid-template-columns: minmax(0, 1fr) 290px;
}
.reader-scroll {
  overflow: auto;
  min-height: 0;
  scroll-behavior: auto;
}
.reader-page {
  max-width: 770px;
  margin: 0 auto;
  padding: 55px 44px 70px;
}
.reader-chapter-heading {
  margin: 0 0 38px;
  text-align: center;
}
.reader-chapter-heading > span {
  color: var(--accent);
  font-size: 11px;
  letter-spacing: 0.18em;
}
.reader-chapter-heading h2 {
  font-family: var(--serif);
  font-size: clamp(24px, 3vw, 34px);
  line-height: 1.55;
  font-weight: 500;
  margin: 12px 0;
  overflow-wrap: anywhere;
}
.reader-chapter-heading p {
  color: var(--faint);
  font-size: 11px;
}
.reader-page :deep(.reader-prose) {
  font-family: var(--serif);
  font-size: var(--reader-size);
  line-height: 2.05;
  overflow-wrap: anywhere;
}
.reader-page :deep(.reader-prose p) {
  margin: 0.9em 0;
  text-indent: 2em;
}
.reader-page :deep(.reader-prose h1),
.reader-page :deep(.reader-prose h2),
.reader-page :deep(.reader-prose h3) {
  line-height: 1.6;
}
.reader-page :deep(.reader-prose blockquote) {
  border-left: 2px solid var(--accent);
  margin-inline: 0;
  padding-left: 20px;
  color: var(--muted);
}
.reader-page :deep(.reader-prose hr) {
  border: 0;
  border-top: 1px solid var(--line);
  margin: 2em 15%;
}
.reader-page :deep(.reader-prose pre) {
  white-space: pre-wrap;
}
.reader-end {
  margin: 52px 0 22px;
  text-align: center;
  color: var(--faint);
  font-size: 12px;
  letter-spacing: 0.2em;
}
.reader-next {
  display: block;
  margin: auto;
  max-width: 100%;
  white-space: normal;
}
.reader-notes {
  border-left: 1px solid var(--line);
  padding: 22px 18px;
  overflow-y: auto;
  background: var(--field);
}
.reader-note-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.reader-notes h3 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
}
.reader-hint {
  color: var(--faint);
  font-size: 11px;
  line-height: 1.75;
}
.reader-notes blockquote {
  padding: 10px 12px;
  margin: 12px 0;
  border-left: 2px solid var(--accent);
  background: var(--accent-soft);
  font-family: var(--serif);
  font-size: 12px;
  color: var(--muted);
  line-height: 1.8;
  max-height: 135px;
  overflow: auto;
  overflow-wrap: anywhere;
}
.reader-notes label {
  display: grid;
  gap: 6px;
  margin: 12px 0;
  color: var(--muted);
  font-size: 11px;
}
.reader-notes textarea {
  width: 100%;
  resize: vertical;
  font: inherit;
  line-height: 1.8;
}
.reader-notes > .btn-primary {
  width: 100%;
  font-size: 12px;
}
.reader-notes .reader-list-heading {
  margin: 26px 0 12px;
}
.reader-list-heading small {
  font-size: 10px;
  color: var(--faint);
}
.reader-item {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--line);
}
.reader-item button,
.reader-task {
  padding: 10px 0;
  color: var(--muted);
  background: transparent;
  border: 0;
  font: inherit;
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.reader-item > button:first-child {
  flex: 1;
  min-width: 0;
}
.reader-item .reader-remove {
  padding: 8px;
}
.reader-task {
  display: grid;
  gap: 4px;
  width: 100%;
  border-bottom: 1px solid var(--line);
}
.reader-task small {
  font-size: 10px;
  color: var(--accent);
}
.reader-error,
.reader-notice {
  margin: 0;
  padding: 10px 24px;
  color: var(--danger);
  font-size: 12px;
}
.reader-error {
  display: flex;
  gap: 12px;
  align-items: center;
  flex-wrap: wrap;
}
.reader-error > span {
  flex: 1;
}
.reader-error button {
  font-size: 11px;
  padding: 7px 10px;
}
.reader-notice {
  color: var(--accent);
}
.reader-remote {
  border: 1px solid var(--warm);
  padding: 10px;
  border-radius: 8px;
  color: var(--warm);
  font-size: 11px;
  line-height: 1.7;
  margin-bottom: 12px;
}
.reader-remote button {
  padding: 7px;
  font-size: 11px;
  margin: 3px;
}
.reader-confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 12px 24px;
  background: var(--accent-soft);
  font-size: 12px;
}
.reader-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 8px 24px;
  border-top: 1px solid var(--line);
  color: var(--faint);
  font-size: 10px;
}
.reader-footer button {
  flex-shrink: 0;
  color: var(--accent);
}
@media (max-width: 900px) {
  .reader-layout.with-notes {
    grid-template-columns: minmax(0, 1fr) 250px;
  }
  .reader-page {
    padding-inline: 28px;
  }
}
@media (max-width: 650px) {
  .reader-workspace {
    height: calc(100dvh - 116px);
    min-height: 490px;
    border-radius: 16px;
  }
  .reader-topbar {
    padding: 17px 16px;
  }
  .reader-topbar p {
    font-size: 12px;
  }
  .reader-topbar button {
    padding: 9px 12px;
    font-size: 12px;
  }
  .reader-toolbar {
    padding: 8px;
    gap: 2px;
  }
  .reader-toolbar-space {
    display: none;
  }
  .reader-toolbar button:not(.base-select) {
    font-size: 11px;
  }
  .reader-page {
    padding: 35px 22px 45px;
  }
  .reader-layout.with-notes {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: minmax(130px, 1fr) minmax(190px, 40%);
  }
  .reader-notes {
    border-left: 0;
    border-top: 1px solid var(--line);
    padding: 16px;
  }
  .reader-footer {
    padding: 8px 12px;
  }
  .reader-footer > span {
    max-width: 58%;
    line-height: 1.6;
  }
}
</style>
