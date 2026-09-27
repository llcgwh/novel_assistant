<template>
  <section
    ref="root"
    class="reader-viewport"
    :class="`reader-${mode}`"
    :style="{ '--reader-size': fontSize + 'px' }"
    aria-label="只读阅读正文"
  >
    <div v-if="loadError" class="reader-load-error" role="alert">
      <span>{{ loadError }}</span
      ><button type="button" class="btn-secondary" @click="retryFailed">
        重试载入
      </button>
    </div>
    <div v-if="mode === 'paged'" class="reader-pagination">
      <div
        ref="pageWindow"
        class="reader-page-window"
        tabindex="0"
        role="document"
        aria-label="分页只读正文"
        @pointerup="captureSelection"
        @keyup="captureSelection"
      >
        <div
          v-if="focusChapter"
          ref="pageFlow"
          class="reader-page-flow"
          :style="{
            width: pageWidth + 'px',
            height: pageHeight + 'px',
            columnWidth: pageWidth + 'px',
            columnGap: pageGap + 'px',
            transform: `translateX(${-pageIndex * (pageWidth + pageGap)}px)`,
          }"
          :data-reader-chapter="focusChapter.uid"
        >
          <header class="reader-chapter-heading">
            <span>{{ volumeTitle(focusChapter) }}</span>
            <h2>{{ focusChapter.title }}</h2>
            <p>{{ focusChapter.wordCount.toLocaleString() }} 字</p>
          </header>
          <ReadonlyManuscript :doc="focusChapter.doc" />
          <p class="reader-end">— 本章完 —</p>
        </div>
      </div>
      <nav class="reader-page-controls" aria-label="翻页">
        <button
          type="button"
          :disabled="moving || (pageIndex === 0 && currentIndex < 1)"
          @click="turnPage(-1)"
        >
          ← 上一页</button
        ><span
          >{{ pageIndex + 1 }} / {{ pageCount }} 页 <small>· 本章</small></span
        ><button
          type="button"
          :disabled="
            moving ||
            (pageIndex === pageCount - 1 && currentIndex >= chapters.length - 1)
          "
          @click="turnPage(1)"
        >
          下一页 →
        </button>
      </nav>
    </div>
    <div
      v-else
      ref="scrollPanel"
      class="reader-scroll"
      @scroll.passive="onScroll"
      @pointerup="captureSelection"
      @keyup="captureSelection"
    >
      <button
        v-if="mode === 'continuous' && firstIndex > 0"
        type="button"
        class="reader-load-previous"
        :disabled="moving"
        @click="extend(-1)"
      >
        {{ moving ? '正在接续…' : '↑ 载入前一章' }}
      </button>
      <div
        v-for="slot in displaySlots"
        :key="slot.uid"
        :data-reader-slot="slot.uid"
        class="reader-chapter-slot"
        :style="
          !rendered.has(slot.uid) ? { height: slot.height + 'px' } : undefined
        "
      >
        <article
          v-if="rendered.has(slot.uid) && cache.get(slot.uid)"
          class="reader-scroll-page"
          :data-reader-chapter="slot.uid"
          tabindex="0"
          role="document"
          :aria-label="cache.get(slot.uid)!.title + ' · 只读正文'"
        >
          <header class="reader-chapter-heading">
            <span>{{ volumeTitle(cache.get(slot.uid)!) }}</span>
            <h2>{{ cache.get(slot.uid)!.title }}</h2>
            <p>
              {{ cache.get(slot.uid)!.wordCount.toLocaleString() }} 字 ·
              {{ mode === 'continuous' ? '全书连续阅读' : '逐章阅读' }}
            </p>
          </header>
          <ReadonlyManuscript :doc="cache.get(slot.uid)!.doc" />
          <p class="reader-end">— 本章完 —</p>
          <button
            v-if="mode === 'chapter' && currentIndex < chapters.length - 1"
            type="button"
            class="reader-next btn-secondary"
            :disabled="moving"
            @click="moveChapter(1)"
          >
            继续阅读 · {{ chapters[currentIndex + 1]?.title }} →
          </button>
        </article>
        <div v-else class="reader-placeholder" aria-label="按需载入章节">
          <span>{{ titleOf(slot.uid) }}</span
          ><small>滚动到这里时载入正文</small
          ><button
            v-if="failedUid === slot.uid"
            type="button"
            class="btn-secondary"
            @click="retryFailed"
          >
            重试载入
          </button>
        </div>
      </div>
      <div v-if="mode === 'continuous'" class="reader-stream-tail">
        <button
          v-if="lastIndex < chapters.length - 1"
          type="button"
          class="btn-secondary"
          :disabled="moving"
          @click="extend(1)"
        >
          {{ moving ? '正在接续下一章…' : '接续下一章 ↓' }}</button
        ><span v-else>— 已到全书末尾 —</span
        ><small>正文按需载入 · 邻近章节保留在页面中</small>
      </div>
    </div>
    <div v-if="moving" class="reader-loading" role="status">正在翻开章节…</div>
  </section>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  shallowReactive,
  watch,
} from 'vue'
import ReadonlyManuscript from './ReadonlyManuscript'
import { writingApi } from '@/api/writing'
import { ReaderChapterCache } from '@/utils/readerChapterCache'
import type { Chapter, ChapterMeta, Volume } from '@/types/writing'
import { blockList } from '@/utils/writing'
import { readingLeafBlockIds } from '@/utils/revisionDesk'
import {
  boundedReadingWindow,
  ignoresPageKey,
  pageCountForWidth,
  pageForOffset,
  type ReaderMode,
} from '@/utils/readerLayout'

const props = defineProps<{
  novelId: number
  bookUid: string
  initialChapter: Chapter
  initialBlock?: string
  chapters: ChapterMeta[]
  volumes: Volume[]
  mode: ReaderMode
  fontSize: number
}>()
const emit = defineEmits<{
  position: [value: { chapter: Chapter; blockId: string }]
  selection: [value: { chapter: Chapter; blockId: string; excerpt: string }]
  error: [message: string]
}>()
const root = ref<HTMLElement | null>(null),
  scrollPanel = ref<HTMLElement | null>(null),
  pageWindow = ref<HTMLElement | null>(null),
  pageFlow = ref<HTMLElement | null>(null)
const cache = shallowReactive(
  new Map<string, Chapter>([[props.initialChapter.uid, props.initialChapter]]),
)
const slots = ref([{ uid: props.initialChapter.uid, height: 500 }])
const rendered = ref(new Set([props.initialChapter.uid]))
const location = reactive({
  chapterUid: props.initialChapter.uid,
  blockId:
    props.initialBlock || blockList(props.initialChapter.doc)[0]?.id || '',
})
const focusUid = ref(props.initialChapter.uid)
const focusChapter = computed(() => cache.get(focusUid.value))
const displaySlots = computed(() =>
  props.mode === 'continuous'
    ? slots.value
    : [{ uid: focusUid.value, height: 500 }],
)
const currentIndex = computed(() =>
  props.chapters.findIndex((chapter) => chapter.uid === location.chapterUid),
)
const firstIndex = computed(() =>
  props.chapters.findIndex((chapter) => chapter.uid === slots.value[0]?.uid),
)
const lastIndex = computed(() =>
  props.chapters.findIndex(
    (chapter) => chapter.uid === slots.value.at(-1)?.uid,
  ),
)
const moving = ref(false),
  loadError = ref(''),
  failedUid = ref('')
const pageWidth = ref(600),
  pageHeight = ref(500),
  pageIndex = ref(0),
  pageCount = ref(1),
  pageGap = 40
const contextNovel = props.novelId,
  contextBook = props.bookUid
let disposed = false,
  epoch = 0,
  scrollFrame = 0,
  layoutFrame = 0,
  arrangingEpoch = -1,
  rebuilding = false,
  resizeObserver: ResizeObserver | undefined
let lastSize = '',
  ignoreScroll = false
const valid = () =>
  !disposed && props.novelId === contextNovel && props.bookUid === contextBook
const titleOf = (uid: string) =>
  props.chapters.find((chapter) => chapter.uid === uid)?.title || '章节'
const volumeTitle = (chapter: Chapter) =>
  props.volumes.find((volume) => volume.uid === chapter.volumeId)?.title ||
  '正文'
function publish(chapter: Chapter, blockId: string) {
  if (!valid()) return
  location.chapterUid = chapter.uid
  location.blockId = blockId
  focusUid.value = chapter.uid
  emit('position', { chapter, blockId })
}
const chapterCache = new ReaderChapterCache({
  novelId: contextNovel,
  bookUid: contextBook,
  load: writingApi.chapter,
  isActive: valid,
  chapters: cache,
  limit: 7,
})
async function obtain(uid: string): Promise<Chapter | undefined> {
  if (!valid()) return
  const turn = epoch
  try {
    const chapter = await chapterCache.get(uid)
    if (failedUid.value === uid) {
      failedUid.value = ''
      loadError.value = ''
    }
    return chapter
  } catch (cause) {
    if (valid() && turn === epoch) {
      failedUid.value = uid
      loadError.value = `「${titleOf(uid)}」载入失败：${cause instanceof Error ? cause.message : '请重试'}`
    }
    return undefined
  }
}
function chapterElement(uid: string) {
  return Array.from(
    root.value?.querySelectorAll<HTMLElement>('[data-reader-chapter]') || [],
  ).find((element) => element.dataset.readerChapter === uid)
}
function blockElement(uid: string, blockId: string) {
  return Array.from(
    chapterElement(uid)?.querySelectorAll<HTMLElement>('[data-reader-block]') ||
      [],
  ).find((element) => element.dataset.readerBlock === blockId)
}
function leafElements(uid: string) {
  const chapter = cache.get(uid)
  if (!chapter) return []
  const ids = readingLeafBlockIds(chapter.doc)
  return Array.from(
    chapterElement(uid)?.querySelectorAll<HTMLElement>('[data-reader-block]') ||
      [],
  ).filter((element) => ids.has(element.dataset.readerBlock || ''))
}
function measureSlots() {
  for (const slot of slots.value) {
    const element = chapterElement(slot.uid)
    if (element)
      slot.height = Math.max(80, element.getBoundingClientRect().height)
  }
}
function trimCache() {
  chapterCache.trim([
    ...rendered.value,
    focusUid.value,
    props.initialChapter.uid,
  ])
}
function scrollAnchor() {
  const panel = scrollPanel.value
  if (!panel) return undefined
  const top = panel.getBoundingClientRect().top
  const slot = Array.from(
    panel.querySelectorAll<HTMLElement>('[data-reader-slot]'),
  ).find((element) => element.getBoundingClientRect().bottom > top + 24)
  const uid = slot?.dataset.readerSlot
  if (!uid || !slot) return undefined
  const block = leafElements(uid).find(
    (element) => element.getBoundingClientRect().bottom > top + 24,
  )
  return {
    uid,
    blockId: block?.dataset.readerBlock || '',
    offset: (block || slot).getBoundingClientRect().top - top,
  }
}
function restoreScrollAnchor(anchor: ReturnType<typeof scrollAnchor>) {
  if (!anchor || !scrollPanel.value) return
  const element = anchor.blockId
    ? blockElement(anchor.uid, anchor.blockId)
    : Array.from(
        scrollPanel.value.querySelectorAll<HTMLElement>('[data-reader-slot]'),
      ).find((slot) => slot.dataset.readerSlot === anchor.uid)
  if (element)
    scrollPanel.value.scrollTop +=
      element.getBoundingClientRect().top -
      scrollPanel.value.getBoundingClientRect().top -
      anchor.offset
}
async function renderWindow(center: number, turn = epoch) {
  const window = boundedReadingWindow(center, slots.value.length)
  const desired = slots.value
    .slice(window.start, window.end)
    .map((slot) => slot.uid)
  await Promise.all(desired.map(obtain))
  if (!valid() || turn !== epoch || props.mode !== 'continuous') {
    trimCache()
    return false
  }
  const anchor = scrollAnchor()
  measureSlots()
  rendered.value = new Set(desired.filter((uid) => cache.has(uid)))
  await nextTick()
  if (!valid() || turn !== epoch || props.mode !== 'continuous') return false
  restoreScrollAnchor(anchor)
  measureSlots()
  trimCache()
  return true
}
function rememberScroll() {
  const panel = scrollPanel.value
  if (!panel || ignoreScroll) return
  const top = panel.getBoundingClientRect().top + 24
  for (const slot of displaySlots.value) {
    const element = chapterElement(slot.uid),
      chapter = cache.get(slot.uid)
    if (!element || !chapter || element.getBoundingClientRect().bottom <= top)
      continue
    const leaf = leafElements(slot.uid).find(
      (node) => node.getBoundingClientRect().bottom > top,
    )
    publish(
      chapter,
      leaf?.dataset.readerBlock || blockList(chapter.doc)[0]?.id || '',
    )
    break
  }
}
async function arrangeStream() {
  const turn = epoch
  if (
    arrangingEpoch === turn ||
    rebuilding ||
    !valid() ||
    props.mode !== 'continuous' ||
    !scrollPanel.value
  )
    return
  arrangingEpoch = turn
  try {
    const panel = scrollPanel.value,
      rect = panel.getBoundingClientRect()
    const elements = Array.from(
      panel.querySelectorAll<HTMLElement>('[data-reader-slot]'),
    )
    const index = Math.max(
      0,
      elements.findIndex(
        (element) => element.getBoundingClientRect().bottom > rect.top + 24,
      ),
    )
    if (
      !(await renderWindow(index, turn)) ||
      turn !== epoch ||
      !valid() ||
      props.mode !== 'continuous'
    )
      return
    rememberScroll()
    const nearBottom =
      panel.scrollHeight - panel.scrollTop - panel.clientHeight <
      panel.clientHeight * 1.2
    if (nearBottom && !failedUid.value) await extend(1)
    else if (panel.scrollTop < 80 && firstIndex.value > 0 && !failedUid.value)
      await extend(-1)
  } finally {
    if (arrangingEpoch === turn) arrangingEpoch = -1
  }
}
function onScroll() {
  if (scrollFrame || rebuilding) return
  scrollFrame = requestAnimationFrame(() => {
    scrollFrame = 0
    rememberScroll()
    void arrangeStream()
  })
}
async function extend(direction: number) {
  if (moving.value || !valid() || props.mode !== 'continuous') return false
  const index = direction > 0 ? lastIndex.value + 1 : firstIndex.value - 1
  const meta = props.chapters[index]
  if (!meta || slots.value.some((slot) => slot.uid === meta.uid)) return false
  const turn = epoch,
    first = slots.value[0]?.uid,
    last = slots.value.at(-1)?.uid
  moving.value = true
  try {
    const chapter = await obtain(meta.uid)
    if (
      !chapter ||
      !valid() ||
      turn !== epoch ||
      props.mode !== 'continuous' ||
      first !== slots.value[0]?.uid ||
      last !== slots.value.at(-1)?.uid
    )
      return false
    const panel = scrollPanel.value!,
      before = panel.scrollHeight,
      top = panel.scrollTop
    ignoreScroll = true
    if (direction > 0) slots.value.push({ uid: chapter.uid, height: 500 })
    else slots.value.unshift({ uid: chapter.uid, height: 500 })
    rendered.value = new Set([...rendered.value, chapter.uid])
    await nextTick()
    if (!valid() || turn !== epoch || props.mode !== 'continuous') return false
    measureSlots()
    if (direction < 0) panel.scrollTop = top + panel.scrollHeight - before
    const center = Math.max(
      0,
      slots.value.findIndex((slot) => slot.uid === location.chapterUid),
    )
    await renderWindow(center, turn)
    return valid() && turn === epoch
  } finally {
    if (turn === epoch) {
      moving.value = false
      requestAnimationFrame(() => {
        ignoreScroll = false
      })
    }
  }
}
async function jump(
  chapterUid: string,
  blockId = '',
  edge: 'first' | 'last' = 'first',
) {
  const turn = ++epoch
  moving.value = true
  rebuilding = true
  try {
    const chapter = await obtain(chapterUid)
    if (!chapter || !valid() || turn !== epoch) return false
    focusUid.value = chapter.uid
    if (props.mode === 'continuous') {
      if (!slots.value.some((slot) => slot.uid === chapter.uid))
        slots.value = [{ uid: chapter.uid, height: 500 }]
      if (
        !(await renderWindow(
          Math.max(
            0,
            slots.value.findIndex((slot) => slot.uid === chapter.uid),
          ),
          turn,
        ))
      )
        return false
    } else rendered.value = new Set([chapter.uid])
    if (!valid() || turn !== epoch) return false
    const anchor = blockId || blockList(chapter.doc)[0]?.id || ''
    publish(chapter, anchor)
    await nextTick()
    if (!valid() || turn !== epoch) return false
    if (props.mode === 'paged') {
      await measurePages(anchor, turn)
      if (!valid() || turn !== epoch) return false
      if (edge === 'last') {
        pageIndex.value = pageCount.value - 1
        await nextTick()
        if (valid() && turn === epoch && props.mode === 'paged') rememberPage()
      }
    } else {
      const element = blockId
        ? blockElement(chapter.uid, blockId)
        : chapterElement(chapter.uid)
      ignoreScroll = true
      if (element && scrollPanel.value)
        scrollPanel.value.scrollTop +=
          element.getBoundingClientRect().top -
          scrollPanel.value.getBoundingClientRect().top -
          22
      else if (blockId) emit('error', '关联段落已变化，已打开对应章节。')
      measureSlots()
      requestAnimationFrame(() => {
        ignoreScroll = false
      })
    }
    trimCache()
    return true
  } finally {
    if (turn === epoch) {
      moving.value = false
      rebuilding = false
    }
  }
}
async function moveChapter(direction: number) {
  const next = props.chapters[currentIndex.value + direction]
  return next ? jump(next.uid) : false
}
function pageOfElement(element: HTMLElement) {
  const range = document.createRange()
  range.selectNodeContents(element)
  const rect =
    Array.from(range.getClientRects()).find((rect) => rect.width > 0) ||
    element.getBoundingClientRect()
  const origin = pageFlow.value?.getBoundingClientRect().left || 0
  return pageForOffset(
    rect.left - origin,
    pageWidth.value,
    pageGap,
    pageCount.value,
  )
}
async function measurePages(blockId = location.blockId, turn = epoch) {
  await nextTick()
  if (
    props.mode !== 'paged' ||
    !pageWindow.value ||
    !pageFlow.value ||
    !valid() ||
    turn !== epoch
  )
    return
  const box = pageWindow.value.getBoundingClientRect()
  pageWidth.value = Math.max(100, Math.floor(box.width))
  pageHeight.value = Math.max(140, Math.floor(box.height))
  await nextTick()
  if (!valid() || turn !== epoch || props.mode !== 'paged' || !pageFlow.value)
    return
  pageCount.value = pageCountForWidth(
    pageFlow.value.scrollWidth,
    pageWidth.value,
    pageGap,
  )
  const block = blockElement(focusUid.value, blockId)
  pageIndex.value = block
    ? pageOfElement(block)
    : Math.min(pageIndex.value, pageCount.value - 1)
  await nextTick()
  if (valid() && turn === epoch && props.mode === 'paged') rememberPage()
}
function rememberPage() {
  const chapter = focusChapter.value,
    panel = pageWindow.value
  if (!chapter || !panel) return
  const view = panel.getBoundingClientRect()
  const leaf = leafElements(chapter.uid).find((element) => {
    const range = document.createRange()
    range.selectNodeContents(element)
    return Array.from(range.getClientRects()).some(
      (rect) =>
        rect.width > 0 &&
        rect.right > view.left + 1 &&
        rect.left < view.right - 1 &&
        rect.bottom > view.top &&
        rect.top < view.bottom,
    )
  })
  publish(
    chapter,
    leaf?.dataset.readerBlock || blockList(chapter.doc)[0]?.id || '',
  )
}
async function turnPage(direction: number) {
  if (moving.value || props.mode !== 'paged') return
  const turn = epoch
  if (
    pageIndex.value + direction >= 0 &&
    pageIndex.value + direction < pageCount.value
  ) {
    pageIndex.value += direction
    await nextTick()
    if (valid() && turn === epoch && props.mode === 'paged') rememberPage()
    return
  }
  const target = props.chapters[currentIndex.value + direction]
  if (target) await jump(target.uid, '', direction < 0 ? 'last' : 'first')
}
function pageKey(event: KeyboardEvent) {
  if (
    props.mode !== 'paged' ||
    event.defaultPrevented ||
    event.metaKey ||
    event.ctrlKey ||
    event.altKey ||
    event.shiftKey ||
    ignoresPageKey(event.target)
  )
    return
  if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
    event.preventDefault()
    void turnPage(event.key === 'ArrowLeft' ? -1 : 1)
  }
}
function captureSelection(event: Event) {
  const selection = window.getSelection(),
    target = event.target instanceof Element ? event.target : null
  const origin =
    selection?.anchorNode instanceof Element
      ? selection.anchorNode
      : selection?.anchorNode?.parentElement
  const chapterNode = (
    selection?.toString().trim() ? origin : target
  )?.closest<HTMLElement>('[data-reader-chapter]')
  const chapter = chapterNode
    ? cache.get(chapterNode.dataset.readerChapter || '')
    : undefined
  if (!chapter || !root.value?.contains(chapterNode!)) return
  const blockId =
    (selection?.toString().trim() ? origin : target)?.closest<HTMLElement>(
      '[data-reader-block]',
    )?.dataset.readerBlock ||
    blockList(chapter.doc)[0]?.id ||
    ''
  publish(chapter, blockId)
  if (
    !selection ||
    !chapterNode?.contains(selection.anchorNode) ||
    !chapterNode.contains(selection.focusNode)
  )
    return
  emit('selection', {
    chapter,
    blockId,
    excerpt: selection.toString().trim().slice(0, 1000),
  })
}
async function retryFailed() {
  const uid = failedUid.value
  if (!uid) return
  loadError.value = ''
  failedUid.value = ''
  const chapter = await obtain(uid)
  if (chapter) {
    if (
      props.mode === 'continuous' &&
      slots.value.some((slot) => slot.uid === uid)
    )
      await arrangeStream()
    else await jump(uid)
  }
}
function scheduleLayout() {
  cancelAnimationFrame(layoutFrame)
  const anchor = { ...location }
  layoutFrame = requestAnimationFrame(() => {
    if (!valid()) return
    if (props.mode === 'paged') void measurePages(anchor.blockId)
    else void jump(anchor.chapterUid, anchor.blockId)
  })
}
watch(
  () => props.mode,
  () => {
    void jump(location.chapterUid, location.blockId)
  },
)
watch(() => props.fontSize, scheduleLayout)
onMounted(async () => {
  await jump(props.initialChapter.uid, props.initialBlock || '')
  if (!valid()) return
  resizeObserver = new ResizeObserver((entries) => {
    const rect = entries[0]?.contentRect
    if (!rect) return
    const size = `${Math.floor(rect.width)}:${Math.floor(rect.height)}`
    if (lastSize && lastSize !== size) scheduleLayout()
    lastSize = size
  })
  if (root.value) resizeObserver.observe(root.value)
  window.addEventListener('keydown', pageKey)
  if (props.mode === 'continuous') void arrangeStream()
})
onBeforeUnmount(() => {
  disposed = true
  epoch++
  cancelAnimationFrame(scrollFrame)
  cancelAnimationFrame(layoutFrame)
  resizeObserver?.disconnect()
  window.removeEventListener('keydown', pageKey)
  chapterCache.dispose()
})
defineExpose({ jump, moveChapter })
</script>

<style scoped>
.reader-viewport {
  position: relative;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.reader-scroll {
  overflow: auto;
  min-height: 0;
  flex: 1;
  overflow-anchor: none;
}
.reader-scroll-page {
  max-width: 770px;
  margin: auto;
  padding: 55px 44px 70px;
}
.reader-chapter-heading {
  margin: 0 0 38px;
  text-align: center;
  break-inside: avoid;
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
.reader-viewport :deep(.reader-prose) {
  font-family: var(--serif);
  font-size: var(--reader-size);
  line-height: 2.05;
  overflow-wrap: anywhere;
}
.reader-viewport :deep(.reader-prose p) {
  margin: 0.9em 0;
  text-indent: 2em;
  orphans: 2;
  widows: 2;
}
.reader-viewport :deep(.reader-prose h1),
.reader-viewport :deep(.reader-prose h2),
.reader-viewport :deep(.reader-prose h3) {
  line-height: 1.6;
  break-after: avoid;
}
.reader-viewport :deep(.reader-prose blockquote) {
  border-left: 2px solid var(--accent);
  margin-inline: 0;
  padding-left: 20px;
  color: var(--muted);
}
.reader-viewport :deep(.reader-prose hr) {
  border: 0;
  border-top: 1px solid var(--line);
  margin: 2em 15%;
}
.reader-viewport :deep(.reader-prose pre) {
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
.reader-placeholder {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: center;
  gap: 10px;
  color: var(--faint);
  font-size: 13px;
  background: var(--accent-soft);
}
.reader-placeholder small {
  font-size: 11px;
}
.reader-stream-tail {
  display: flex;
  align-items: center;
  flex-direction: column;
  gap: 12px;
  padding: 22px 12px 36px;
  color: var(--faint);
  font-size: 12px;
}
.reader-stream-tail small {
  font-size: 10px;
}
.reader-load-previous {
  display: block;
  width: 100%;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: var(--accent-soft);
  color: var(--muted);
  font-size: 11px;
  padding: 12px;
  cursor: pointer;
}
.reader-pagination {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  padding: 24px 38px 0;
}
.reader-page-window {
  flex: 1;
  min-height: 140px;
  width: 100%;
  max-width: 760px;
  margin: auto;
  overflow: hidden;
}
.reader-page-flow {
  column-fill: auto;
  transition: none;
}
.reader-page-flow > .reader-chapter-heading {
  padding-top: 6px;
}
.reader-page-controls {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-top: 1px solid var(--line);
  padding: 12px 0;
  font-size: 12px;
  color: var(--muted);
}
.reader-page-controls button {
  border: 0;
  background: transparent;
  color: var(--accent);
  padding: 9px 4px;
  font: inherit;
  cursor: pointer;
}
.reader-page-controls button:disabled {
  opacity: 0.4;
  cursor: default;
}
.reader-page-controls small {
  color: var(--faint);
  font-size: 10px;
}
.reader-loading {
  position: absolute;
  bottom: 12px;
  left: 50%;
  transform: translateX(-50%);
  padding: 8px 14px;
  border: 1px solid var(--line);
  border-radius: 30px;
  background: var(--panel);
  color: var(--muted);
  font-size: 11px;
  pointer-events: none;
  box-shadow: var(--shadow);
}
.reader-load-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 18px;
  color: var(--danger);
  font-size: 12px;
}
.reader-load-error span {
  flex: 1;
}
.reader-load-error button {
  font-size: 11px;
  padding: 6px 10px;
}
@media (max-width: 900px) {
  .reader-scroll-page {
    padding-inline: 28px;
  }
  .reader-pagination {
    padding-inline: 26px;
  }
}
@media (max-width: 650px) {
  .reader-scroll-page {
    padding: 35px 22px 45px;
  }
  .reader-pagination {
    padding: 16px 20px 0;
  }
  .reader-page-controls {
    gap: 5px;
    font-size: 11px;
  }
  .reader-chapter-heading h2 {
    font-size: 24px;
  }
}
</style>
