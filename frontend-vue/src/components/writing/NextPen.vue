<template>
  <section
    class="next-pen"
    :class="{ 'has-note': saved }"
    aria-label="下一笔便签"
  >
    <div class="next-pen-bar">
      <button
        v-if="saved"
        type="button"
        class="next-pen-heading"
        :aria-expanded="expanded"
        aria-controls="next-pen-details"
        @click="expanded = !expanded"
      >
        <span class="next-pen-mark" aria-hidden="true">↳</span>
        <span
          ><b>下一笔</b
          ><small>{{
            saved.nextScene || saved.question || saved.opening
          }}</small></span
        >
        <span class="next-pen-toggle" aria-hidden="true">{{
          expanded ? '−' : '+'
        }}</span>
      </button>
      <div v-else class="next-pen-empty">
        <span class="next-pen-mark" aria-hidden="true">↳</span>
        <span
          ><b>下一笔</b
          ><small>{{
            desk.loading ? '正在取回便签…' : '给下次落笔，留一个入口。'
          }}</small></span
        >
      </div>
      <button
        type="button"
        class="next-pen-action"
        :disabled="unavailable || (!writer.current && !saved && !localDraft)"
        @click="openEditor"
      >
        {{ localDraft ? '继续本机草稿' : saved ? '更新便签' : '留下一笔' }}
      </button>
      <button
        v-if="saved"
        type="button"
        class="next-pen-resume"
        :disabled="missingChapter"
        @click="jump"
      >
        接着写 <span aria-hidden="true">↗</span>
      </button>
    </div>
    <div
      v-if="saved && expanded"
      id="next-pen-details"
      class="next-pen-details"
    >
      <p class="next-pen-source">
        {{ sourceTitle(saved.chapterUid) }}<span> · 已保存，随作品同步</span>
      </p>
      <p v-if="missingChapter" class="next-pen-warning">
        原章节已移入回收站或不存在，便签内容仍然保留。
      </p>
      <p v-else-if="missingBlock" class="next-pen-warning">
        原段落已移除，仍可回到章节。
      </p>
      <dl>
        <template v-if="saved.nextScene"
          ><dt>下一幕</dt>
          <dd>{{ saved.nextScene }}</dd></template
        >
        <template v-if="saved.question"
          ><dt>卡住的问题</dt>
          <dd>{{ saved.question }}</dd></template
        >
        <template v-if="saved.opening"
          ><dt>留住一句</dt>
          <dd>{{ saved.opening }}</dd></template
        >
      </dl>
      <blockquote v-if="saved.excerpt">{{ saved.excerpt }}</blockquote>
      <div class="next-pen-foot">
        <small>上次留下的位置</small
        ><button type="button" :disabled="unavailable" @click="clearNote">
          清除便签
        </button>
      </div>
    </div>
    <p
      v-if="outsideError || (!desk.loading && !desk.data && desk.error)"
      class="next-pen-warning"
      role="alert"
    >
      {{ outsideError || desk.error }}
      <button type="button" :disabled="unavailable" @click="reloadCard">
        重新读取
      </button>
    </p>
    <p v-if="localDraft && !editing" class="next-pen-local">
      有一份仅存于本机的未保存草稿。
    </p>
  </section>

  <BaseModal
    v-if="editing"
    title="给下一次落笔"
    :busy="pending"
    @close="closeEditor"
  >
    <div class="next-pen-form">
      <p class="next-pen-intro">
        随手留下其中一项就好，下次回来可以从这里接着写。
      </p>
      <div class="next-pen-anchor">
        <span
          >记在 <b>{{ sourceTitle(form.chapterUid) }}</b></span
        >
        <button type="button" :disabled="!writer.current" @click="relocate">
          改记在当前位置
        </button>
        <blockquote v-if="form.excerpt">{{ form.excerpt }}</blockquote>
      </div>
      <label
        >下一幕发生什么<textarea
          v-model="form.nextScene"
          rows="3"
          maxlength="3000"
          placeholder="例如：她带着信，去找已经失踪的守塔人。"
        />
      </label>
      <label
        >卡住的问题<textarea
          v-model="form.question"
          rows="2"
          maxlength="3000"
          placeholder="例如：他为什么选择此时说谎？"
        />
      </label>
      <label
        >想保留的一句话<textarea
          v-model="form.opening"
          rows="2"
          maxlength="3000"
          placeholder="一句对白、一个意象，或还没写完的开头。"
        />
      </label>
      <p class="next-pen-local" role="status">
        {{
          draftStorageError || '输入会暂存于本机；点击保存后才会随作品同步。'
        }}
      </p>
      <div v-if="conflicted" class="next-pen-conflict" role="alert">
        <p>
          {{
            compared
              ? '最新便签已读回。你的输入仍保留，请比较后决定。'
              : '保存结果需要确认。你的输入已保留，请先读取最新便签再决定。'
          }}
        </p>
        <button
          v-if="!compared"
          type="button"
          :disabled="pending"
          @click="compareLatest"
        >
          读取最新便签以比较
        </button>
        <template v-else>
          <blockquote>
            {{
              saved
                ? [saved.nextScene, saved.question, saved.opening]
                    .filter(Boolean)
                    .join('\n')
                : '最新版本中没有便签。'
            }}
          </blockquote>
          <button type="button" @click="adoptSaved">采用最新便签</button>
        </template>
      </div>
      <p v-if="formError" class="next-pen-warning" role="alert">
        {{ formError }}
      </p>
    </div>
    <template #actions>
      <div class="next-pen-form-actions">
        <button type="button" class="btn-secondary" @click="discardDraft">
          丢弃本机草稿
        </button>
        <button type="button" class="btn-secondary" @click="closeEditor">
          稍后再写
        </button>
        <button
          type="button"
          class="btn-primary"
          :disabled="!canSave"
          @click="saveNote"
        >
          {{
            pending
              ? '保存中…'
              : conflicted && compared
                ? '用此草稿更新便签'
                : '保存便签'
          }}
        </button>
      </div>
    </template>
  </BaseModal>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { useWritingStore } from '@/stores/writing'
import { useWritingDeskStore } from '@/stores/writingDesk'
import type { NextPen } from '@/types/writingDesk'
import { blockList } from '@/utils/writing'
import {
  nextPenAnchor,
  nextPenHasContent,
  nextPenSignature,
  readNextPenDraft,
  type NextPenDraft,
} from '@/utils/nextPen'

const emit = defineEmits<{
  jump: [target: { chapterUid: string; blockId: string }]
}>()
const writer = useWritingStore()
const desk = useWritingDeskStore()
const expanded = ref(false)
const editing = ref(false)
const working = ref(false)
const outsideError = ref('')
const formError = ref('')
const draftStorageError = ref('')
const conflicted = ref(false)
const compared = ref(false)
const baseline = ref('')
const localDraft = ref<NextPenDraft | null>(null)
const blank = (): NextPen => ({
  chapterUid: '',
  blockId: '',
  excerpt: '',
  nextScene: '',
  question: '',
  opening: '',
  updatedAt: '',
})
const form = reactive<NextPen>(blank())
const saved = computed(() => desk.data?.nextPen || null)
const pending = computed(() => working.value || desk.saving)
const unavailable = computed(() => pending.value || desk.loading)
const canSave = computed(
  () =>
    !!desk.data &&
    !!form.chapterUid &&
    nextPenHasContent(form) &&
    !unavailable.value &&
    (!conflicted.value || compared.value),
)
const draftKey = computed(() =>
  writer.workspace?.uid
    ? `ink-next-pen-draft:${writer.novelId}:${writer.workspace.uid}`
    : '',
)
const missingChapter = computed(
  () =>
    !!saved.value &&
    !writer.workspace?.chapters.some(
      (chapter) => chapter.uid === saved.value!.chapterUid && !chapter.deleted,
    ),
)
const missingBlock = computed(
  () =>
    !!saved.value?.blockId &&
    writer.current?.uid === saved.value.chapterUid &&
    !blockList(writer.current.doc).some(
      (block) => block.id === saved.value!.blockId,
    ),
)

watch(
  draftKey,
  (key) => {
    editing.value = false
    localDraft.value = null
    formError.value = outsideError.value = ''
    try {
      if (key) localDraft.value = readNextPenDraft(localStorage.getItem(key))
    } catch {
      /* Storage is optional. */
    }
  },
  { immediate: true },
)
watch(form, () => {
  if (editing.value) persistDraft()
})

function sourceTitle(uid: string) {
  const chapter = writer.workspace?.chapters.find((row) => row.uid === uid)
  return chapter
    ? `${chapter.title}${chapter.deleted ? '（回收站）' : ''}`
    : '原章节不可用'
}
function persistDraft() {
  if (!draftKey.value) return
  try {
    if (
      !nextPenHasContent(form) ||
      nextPenSignature(form) === nextPenSignature(saved.value)
    ) {
      localStorage.removeItem(draftKey.value)
      localDraft.value = null
    } else {
      localDraft.value = { note: { ...form }, baseline: baseline.value }
      localStorage.setItem(draftKey.value, JSON.stringify(localDraft.value))
    }
    draftStorageError.value = ''
  } catch {
    draftStorageError.value = '浏览器暂存不可用，请在离开前保存便签。'
  }
}
function removeDraft() {
  localDraft.value = null
  try {
    if (draftKey.value) localStorage.removeItem(draftKey.value)
  } catch {
    /* Server save remains authoritative. */
  }
}
function openEditor() {
  formError.value = outsideError.value = ''
  conflicted.value = compared.value = false
  const previous = localDraft.value
  Object.assign(form, previous?.note || saved.value || blank())
  baseline.value = previous?.baseline ?? nextPenSignature(saved.value)
  if (!form.chapterUid && writer.current)
    Object.assign(form, nextPenAnchor(writer.current, writer.activeBlock))
  if (baseline.value !== nextPenSignature(saved.value)) conflicted.value = true
  editing.value = true
}
function closeEditor() {
  if (pending.value) return
  persistDraft()
  editing.value = false
}
function discardDraft() {
  if (pending.value) return
  editing.value = false
  removeDraft()
}
function relocate() {
  if (writer.current)
    Object.assign(form, nextPenAnchor(writer.current, writer.activeBlock))
}
function jump() {
  if (saved.value && !missingChapter.value)
    emit('jump', {
      chapterUid: saved.value.chapterUid,
      blockId: missingBlock.value ? '' : saved.value.blockId,
    })
}
function errorMessage(error: unknown) {
  return (
    desk.error ||
    (error instanceof Error ? error.message : '保存未成功，请重试。')
  )
}
async function saveNote() {
  if (!canSave.value) return
  if (baseline.value !== nextPenSignature(saved.value)) {
    conflicted.value = true
    compared.value = false
    return
  }
  const key = draftKey.value
  working.value = true
  formError.value = ''
  try {
    await desk.saveNextPen({
      ...form,
      nextScene: form.nextScene.trim(),
      question: form.question.trim(),
      opening: form.opening.trim(),
      updatedAt: new Date().toISOString(),
    })
    if (key !== draftKey.value) return
    editing.value = false
    expanded.value = true
    removeDraft()
  } catch (error) {
    if (key !== draftKey.value) return
    formError.value = errorMessage(error)
    conflicted.value = true
    compared.value = false
    persistDraft()
  } finally {
    working.value = false
  }
}
async function compareLatest() {
  const key = draftKey.value
  working.value = true
  formError.value = ''
  try {
    await desk.reload()
    if (key !== draftKey.value) return
    baseline.value = nextPenSignature(saved.value)
    compared.value = true
    persistDraft()
  } catch (error) {
    if (key === draftKey.value) formError.value = errorMessage(error)
  } finally {
    working.value = false
  }
}
function adoptSaved() {
  if (saved.value) Object.assign(form, saved.value)
  else {
    Object.assign(form, blank())
    if (writer.current)
      Object.assign(form, nextPenAnchor(writer.current, writer.activeBlock))
  }
  baseline.value = nextPenSignature(saved.value)
  conflicted.value = compared.value = false
  formError.value = ''
  persistDraft()
}
async function clearNote() {
  if (unavailable.value) return
  const key = draftKey.value
  working.value = true
  outsideError.value = ''
  try {
    await desk.saveNextPen(null)
  } catch (error) {
    if (key === draftKey.value) outsideError.value = errorMessage(error)
  } finally {
    working.value = false
  }
}
async function reloadCard() {
  const key = draftKey.value
  working.value = true
  try {
    await desk.reload()
    if (key === draftKey.value) outsideError.value = ''
  } catch (error) {
    if (key === draftKey.value) outsideError.value = errorMessage(error)
  } finally {
    working.value = false
  }
}
</script>

<style scoped>
.next-pen {
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
  margin: 0 0 14px;
  overflow: hidden;
}
.next-pen-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  min-width: 0;
}
.next-pen-heading,
.next-pen-empty {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
  flex: 1;
  text-align: left;
}
.next-pen-heading {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--text);
  cursor: pointer;
}
.next-pen-heading > span:nth-child(2),
.next-pen-empty > span:nth-child(2) {
  display: flex;
  align-items: baseline;
  gap: 12px;
  min-width: 0;
}
.next-pen b {
  font-size: 13px;
  white-space: nowrap;
}
.next-pen small {
  color: var(--muted);
  font-size: 12px;
}
.next-pen-heading small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 42ch;
}
.next-pen-mark {
  font-family: var(--serif);
  font-size: 25px;
  color: var(--accent);
}
.next-pen-toggle {
  color: var(--muted);
  padding: 0 4px;
}
.next-pen-action,
.next-pen-resume {
  border-radius: 7px;
  padding: 7px 10px;
  white-space: nowrap;
  font-size: 12px;
}
.next-pen-action {
  background: transparent;
  color: var(--muted);
  border: 1px solid var(--line);
}
.next-pen-resume {
  background: var(--accent-soft);
  color: var(--accent);
  border: 1px solid var(--line);
}
.next-pen-details {
  border-top: 1px solid var(--line);
  padding: 13px 17px;
}
.next-pen-source {
  font-size: 12px;
  margin: 0 0 12px;
}
.next-pen-source span,
.next-pen-foot small {
  color: var(--muted);
}
.next-pen-details dl {
  display: grid;
  grid-template-columns: 75px minmax(0, 1fr);
  gap: 8px 14px;
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}
.next-pen-details dt {
  color: var(--muted);
}
.next-pen-details dd {
  margin: 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.next-pen blockquote,
.next-pen-form blockquote {
  padding-left: 12px;
  border-left: 2px solid var(--line);
  margin: 14px 0 0;
  color: var(--muted);
  font: 13px/1.8 var(--serif);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.next-pen-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 10px;
}
.next-pen-foot button,
.next-pen-anchor button,
.next-pen-conflict button,
.next-pen-warning button {
  border: 0;
  background: transparent;
  color: var(--accent);
  padding: 6px 0;
  font-size: 12px;
  text-decoration: underline;
  text-underline-offset: 3px;
}
.next-pen-warning {
  color: var(--danger);
  font-size: 12px;
  line-height: 1.7;
  margin: 10px 14px;
}
.next-pen-warning button {
  margin-left: 12px;
}
.next-pen-local {
  font-size: 11px;
  line-height: 1.7;
  color: var(--muted);
  margin: 0 14px 10px;
}
.next-pen-form {
  display: grid;
  gap: 17px;
}
.next-pen-intro {
  margin: 0;
  color: var(--muted);
  line-height: 1.7;
  font-size: 13px;
}
.next-pen-anchor {
  padding: 11px 14px;
  background: var(--accent-soft);
  border: 1px solid var(--line);
  border-radius: 9px;
  font-size: 12px;
}
.next-pen-anchor > span {
  display: inline-block;
  margin-right: 15px;
}
.next-pen-anchor blockquote {
  margin-top: 6px;
}
.next-pen-form label {
  display: grid;
  gap: 7px;
  font-size: 13px;
}
.next-pen-form textarea {
  width: 100%;
  resize: vertical;
  min-height: 72px;
  padding: 10px 12px;
  line-height: 1.7;
  border-radius: 8px;
  background: var(--panel-raised);
  border: 1px solid var(--line);
  color: var(--text);
  font: inherit;
}
.next-pen-form .next-pen-local,
.next-pen-form .next-pen-warning {
  margin: 0;
}
.next-pen-conflict {
  padding: 12px 14px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--accent-soft);
  font-size: 12px;
  line-height: 1.7;
}
.next-pen-conflict p {
  margin: 0;
}
.next-pen-form-actions {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  width: 100%;
}
.next-pen-form-actions button {
  font-size: 12px;
}
.next-pen button:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
}
.next-pen button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}
@media (max-width: 620px) {
  .next-pen-bar {
    flex-wrap: wrap;
    gap: 8px;
    padding: 10px 12px;
  }
  .next-pen-heading,
  .next-pen-empty {
    flex-basis: 100%;
  }
  .next-pen-heading > span:nth-child(2),
  .next-pen-empty > span:nth-child(2) {
    flex: 1;
  }
  .next-pen-heading small {
    max-width: none;
  }
  .next-pen-empty small {
    font-size: 11px;
  }
  .next-pen-action {
    margin-left: 29px;
  }
  .next-pen-details dl {
    grid-template-columns: 1fr;
    gap: 3px;
  }
  .next-pen-details dd {
    margin-bottom: 8px;
  }
}
</style>
