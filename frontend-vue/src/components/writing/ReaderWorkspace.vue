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
      <button
        type="button"
        :disabled="chapterIndex < 1 || pending"
        @click="changeChapter(-1)"
      >
        ← 上一章
      </button>
      <span
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
    <p v-if="draftStorageError" class="reader-error" role="alert">
      {{ draftStorageError }}
    </p>
    <div v-if="pendingDestination" class="reader-confirm" role="alert">
      <span>批注还未保存，先保存它，或放弃这条批注后离开。</span>
      <button
        type="button"
        class="btn-secondary"
        @click="pendingDestination = null"
      >
        继续批注
      </button>
      <button type="button" class="btn-secondary" @click="discardAndLeave">
        放弃并离开
      </button>
    </div>
    <div class="reader-layout" :class="{ 'with-notes': notesOpen }">
      <div
        ref="scrollPanel"
        class="reader-scroll"
        @scroll.passive="rememberPosition"
      >
        <article
          class="reader-page"
          :style="{ '--reader-size': fontSize + 'px' }"
        >
          <header class="reader-chapter-heading">
            <span>{{ volumeTitle || '正文' }}</span>
            <h2>{{ chapter.title }}</h2>
            <p>
              {{ chapter.wordCount.toLocaleString() }} 字 · 当前章节连续阅读
            </p>
          </header>
          <div
            ref="prose"
            tabindex="0"
            role="document"
            aria-label="只读章节正文"
            @pointerup="captureSelection"
            @keyup="captureSelection"
          >
            <ReadonlyManuscript :doc="chapter.doc" />
          </div>
          <p class="reader-end">— 本章完 —</p>
          <button
            v-if="chapterIndex >= 0 && chapterIndex < chapters.length - 1"
            type="button"
            class="reader-next btn-secondary"
            :disabled="pending"
            @click="changeChapter(1)"
          >
            继续阅读 · {{ chapters[chapterIndex + 1]?.title }} →
          </button>
        </article>
      </div>
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
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import ReadonlyManuscript from './ReadonlyManuscript'
import { useWritingStore } from '@/stores/writing'
import { useWritingDeskStore } from '@/stores/writingDesk'
import type { Chapter } from '@/types/writing'
import type { ReaderBookmark, RevisionTask } from '@/types/writingDesk'
import { blockList, documentText } from '@/utils/writing'
import { decodeReaderDraft, readerDraftKey } from '@/utils/readerDraft'
import {
  findDocumentBlock,
  readingLeafBlockIds,
  readingOrder,
  revisionCategories,
  revisionCategoryLabel,
} from '@/utils/revisionDesk'

const props = defineProps<{ chapter: Chapter; initialBlock?: string }>()
const emit = defineEmits<{
  close: [blockId: string]
  navigate: [target: { chapterUid: string; blockId?: string }]
}>()
const writer = useWritingStore()
const desk = useWritingDeskStore()
const scrollPanel = ref<HTMLElement | null>(null)
const prose = ref<HTMLElement | null>(null)
const activeBlock = ref('')
const selectedExcerpt = ref('')
const selectedBlock = ref('')
const notesOpen = ref(false)
const fontSize = ref(20)
const note = ref('')
const noteUid = ref<string>(crypto.randomUUID())
const noteCreatedAt = ref(new Date().toISOString())
const noteAnchor = ref<{ blockId: string; excerpt: string } | null>(null)
const bookmarkDraft = ref<ReaderBookmark | null>(null)
const draftStorageError = ref('')
const localDraftKey = readerDraftKey(
  writer.novelId,
  writer.workspace?.uid || '',
  props.chapter.uid,
)
let restoringDraft = false
const category = ref<RevisionTask['category']>('wording')
const pending = ref(false)
const error = ref('')
const notice = ref('')
const pendingDestination = ref<{ chapterUid?: string } | null>(null)
let scrollFrame = 0
let explicitScroll = false
const chapters = computed(() =>
  readingOrder(
    writer.workspace?.chapters || [],
    writer.workspace?.volumes || [],
  ),
)
const chapterIndex = computed(() =>
  chapters.value.findIndex((chapter) => chapter.uid === props.chapter.uid),
)
const volumeTitle = computed(
  () =>
    writer.workspace?.volumes.find(
      (volume) => volume.uid === props.chapter.volumeId,
    )?.title,
)
const bookmarks = computed(() =>
  (desk.data?.bookmarks || []).filter(
    (bookmark) => bookmark.chapterUid === props.chapter.uid,
  ),
)
const activeBookmark = computed(() =>
  bookmarks.value.find((bookmark) => bookmark.blockId === activeBlock.value),
)
const chapterTasks = computed(() =>
  (desk.data?.tasks || []).filter(
    (task) => task.chapterUid === props.chapter.uid && task.status === 'open',
  ),
)
const sourceExcerpt = computed(() => {
  const block = findDocumentBlock(props.chapter.doc, activeBlock.value)
  return block ? documentText(block).trim().slice(0, 280) : ''
})
const readingLeaves = computed(() => readingLeafBlockIds(props.chapter.doc))
const remoteNote = computed(() =>
  desk.data?.tasks.find((task) => task.uid === noteUid.value),
)
function describeError(cause: unknown) {
  return cause instanceof Error
    ? cause.message
    : '保存失败，请稍后重试。输入内容已保留。'
}
async function perform(action: () => Promise<void>, success: string) {
  if (pending.value) return
  pending.value = true
  error.value = ''
  notice.value = ''
  try {
    await action()
    notice.value = success
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    pending.value = false
  }
}
function blocks() {
  return Array.from(
    prose.value?.querySelectorAll<HTMLElement>('[data-reader-block]') || [],
  )
}
async function goToBlock(id: string) {
  await nextTick()
  const element = blocks().find((node) => node.dataset.readerBlock === id)
  if (!element) {
    notice.value = '关联段落已变化，仍可在本章中查看原文。'
    return
  }
  explicitScroll = true
  element.scrollIntoView({ block: 'start', behavior: 'auto' })
  activeBlock.value = id
  if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches)
    element.animate?.(
      [
        { backgroundColor: 'var(--accent-soft)' },
        { backgroundColor: 'transparent' },
      ],
      { duration: 650 },
    )
}
function rememberPosition() {
  if (scrollFrame) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    if (explicitScroll) {
      explicitScroll = false
      return
    }
    const panelTop = (scrollPanel.value?.getBoundingClientRect().top || 0) + 36
    const visible = blocks().find(
      (element) =>
        readingLeaves.value.has(element.dataset.readerBlock || '') &&
        element.getBoundingClientRect().bottom > panelTop,
    )
    if (visible?.dataset.readerBlock)
      activeBlock.value = visible.dataset.readerBlock
  })
}
function captureSelection(event: Event) {
  const target =
    event.target instanceof Element
      ? event.target.closest<HTMLElement>('[data-reader-block]')
      : null
  if (target?.dataset.readerBlock)
    activeBlock.value = target.dataset.readerBlock
  const selection = window.getSelection()
  if (
    !selection?.rangeCount ||
    !prose.value?.contains(selection.anchorNode) ||
    !prose.value?.contains(selection.focusNode)
  )
    return
  const anchor =
    selection.anchorNode instanceof Element
      ? selection.anchorNode
      : selection.anchorNode?.parentElement
  const id =
    anchor?.closest<HTMLElement>('[data-reader-block]')?.dataset.readerBlock ||
    activeBlock.value
  if (selection.toString().trim() && !note.value.trim()) {
    selectedExcerpt.value = selection.toString().trim().slice(0, 1000)
    selectedBlock.value = id
    activeBlock.value = id
  } else if (!note.value.trim()) {
    selectedExcerpt.value = ''
    selectedBlock.value = ''
  }
}
async function toggleBookmark() {
  const existing = activeBookmark.value
  if (existing) return removeBookmark(existing.uid)
  if (
    !bookmarkDraft.value ||
    bookmarkDraft.value.blockId !== activeBlock.value
  ) {
    const excerpt = sourceExcerpt.value
    bookmarkDraft.value = {
      uid: crypto.randomUUID(),
      chapterUid: props.chapter.uid,
      blockId: activeBlock.value,
      excerpt,
      label: excerpt.slice(0, 42) || '阅读位置',
      createdAt: new Date().toISOString(),
    }
  }
  const bookmark = { ...bookmarkDraft.value }
  await perform(async () => {
    await desk.saveBookmark(bookmark)
    bookmarkDraft.value = null
  }, '书签已保存。')
}
async function removeBookmark(uid: string) {
  await perform(() => desk.deleteBookmark(uid), '书签已移除。')
}
async function saveNote() {
  if (!note.value.trim() || remoteNote.value) return
  const now = new Date().toISOString()
  const anchor = noteAnchor.value || {
    blockId: selectedBlock.value || activeBlock.value,
    excerpt: selectedExcerpt.value || sourceExcerpt.value,
  }
  const task: RevisionTask = {
    uid: noteUid.value,
    chapterUid: props.chapter.uid,
    ...anchor,
    body: note.value.trim(),
    category: category.value,
    priority: 'normal',
    status: 'open',
    createdAt: noteCreatedAt.value,
    updatedAt: now,
  }
  await perform(async () => {
    await desk.saveTask(task)
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
function clearNote() {
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
function persistDraft() {
  if (restoringDraft) return
  try {
    if (note.value.trim())
      localStorage.setItem(
        localDraftKey,
        JSON.stringify({
          uid: noteUid.value,
          createdAt: noteCreatedAt.value,
          body: note.value,
          category: category.value,
          anchor: noteAnchor.value,
        }),
      )
    else localStorage.removeItem(localDraftKey)
    draftStorageError.value = ''
  } catch {
    draftStorageError.value =
      '此浏览器暂时无法保存批注草稿，请先加入修订清单再离开。'
  }
}
function protectUnsaved(event: BeforeUnloadEvent) {
  if (note.value.trim() && draftStorageError.value) {
    event.preventDefault()
    event.returnValue = ''
  }
}
async function restoreDraft() {
  restoringDraft = true
  try {
    const raw = localStorage.getItem(localDraftKey)
    const saved = raw ? decodeReaderDraft(raw) : undefined
    if (saved) {
      note.value = saved.body
      category.value = saved.category
      noteAnchor.value = saved.anchor
      noteUid.value = saved.uid
      noteCreatedAt.value = saved.createdAt
      notesOpen.value = true
      notice.value = '已找回本浏览器尚未提交的阅读批注。'
    }
  } catch {
    draftStorageError.value =
      '此浏览器暂时无法读取批注草稿，请先保存新批注再离开。'
  }
  await nextTick()
  restoringDraft = false
}
function leave(target: { chapterUid?: string }) {
  if (pending.value) return
  if (note.value.trim()) {
    pendingDestination.value = target
    notesOpen.value = true
    return
  }
  if (target.chapterUid) emit('navigate', { chapterUid: target.chapterUid })
  else emit('close', activeBlock.value)
}
function requestClose() {
  leave({})
}
function changeChapter(direction: number) {
  const target = chapters.value[chapterIndex.value + direction]
  if (target) leave({ chapterUid: target.uid })
}
function discardAndLeave() {
  const destination = pendingDestination.value
  pendingDestination.value = null
  note.value = ''
  if (destination) leave(destination)
}
watch(
  () => props.chapter.uid,
  async () => {
    selectedExcerpt.value = ''
    selectedBlock.value = ''
    notice.value = ''
    error.value = ''
    activeBlock.value =
      props.initialBlock || blockList(props.chapter.doc)[0]?.id || ''
    await nextTick()
    if (props.initialBlock) await goToBlock(props.initialBlock)
    else if (scrollPanel.value) scrollPanel.value.scrollTop = 0
  },
  { immediate: true },
)
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
void restoreDraft()
window.addEventListener('beforeunload', protectUnsaved)
onBeforeUnmount(() => {
  cancelAnimationFrame(scrollFrame)
  persistDraft()
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
.reader-toolbar button,
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
.reader-toolbar button:hover,
.reader-toolbar button[aria-pressed='true'],
.reader-toolbar button[aria-expanded='true'],
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
  .reader-toolbar button {
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
