<template>
  <BaseModal title="人物状态与秘密知情账" wide :busy="desk.saving" :dirty="draftDirty" close-warning="尚未保存到作品，离开会保留本机草稿。" discard-label="保留草稿并离开" @close="close">
    <div class="story-panel">
      <p>记录人物随剧情变化的状态，以及每个人知道、相信或怀疑什么。全部由作者记录；人物档案保持独立。</p>
      <p class="story-caption">故事顺序表示事情发生的先后；叙述顺序表示读者看到的先后。不确定时留空，系统不会替你推断。</p>
      <p v-if="error || desk.error" class="story-error" role="alert">{{ error || desk.error }}</p>
      <p v-if="storageError" class="story-error" role="alert">{{ storageError }} 请导出草稿再关闭。</p>
      <div v-if="rawRecovery" class="story-toolbar"><span class="story-error">本机原始草稿无法识别，原件保留。</span><button type="button" class="btn-secondary" @click="download(rawRecovery, `故事状态原始草稿-${novelId}.json`)">导出原始草稿</button><button type="button" class="btn-secondary" @click="discardRecovery">丢弃损坏草稿</button></div>
      <p v-if="resourceError" class="story-error" role="alert">{{ resourceError }}</p>
      <div class="story-toolbar">
        <button class="btn-secondary" type="button" :disabled="busy" @click="reload">重新读取并比较</button>
        <button class="btn-secondary" type="button" :disabled="busy || !!draft || !!rawRecovery || !desk.data" @click="begin('state')">记录人物状态</button>
        <button class="btn-secondary" type="button" :disabled="busy || !!draft || !!rawRecovery || !desk.data" @click="begin('secret')">建立秘密条目</button>
        <button class="btn-secondary" type="button" :disabled="!desk.data" @click="downloadLedger">导出账本</button>
      </div>
      <p v-if="busy && !desk.saving" role="status">正在读取当前作品资料…</p>
      <form v-if="draft" class="story-form" @submit.prevent="save">
        <h3>{{ editorTitle }}</h3>
        <p v-if="uncertain" class="story-error">上次保存结果需要确认。可原样重试，或重新读取后比较；草稿保留。</p>
        <fieldset :disabled="busy || uncertain">
          <template v-if="draft.kind === 'secret'">
            <label>秘密标题<input v-model="draft.title" required maxlength="200" /></label>
            <label>作者确认的事实<textarea v-model="draft.truth" required maxlength="6000" rows="3" placeholder="事实只属于作者视角，不表示人物或读者已经知道。" /></label>
          </template>
          <template v-else>
            <p v-if="draft.secretUid">秘密：{{ story.secrets.find(s => s.uid === draft?.secretUid)?.title }}</p>
            <label v-if="draft.kind !== 'reader'">人物<BaseSelect v-model="draft.characterId" aria-label="人物" :options="personOptions" /></label>
            <template v-if="draft.kind === 'state'">
              <label>状态项目<input v-model="draft.field" list="story-state-fields" required maxlength="100" placeholder="如：所在地、伤势、目标、身份" /><datalist id="story-state-fields"><option v-for="field in ['所在地', '伤势', '情绪', '当前目标', '身份', '持有物']" :key="field">{{ field }}</option></datalist></label>
              <label>此时的状态<textarea v-model="draft.value" required maxlength="4000" rows="3" /></label>
            </template>
            <template v-else-if="draft.kind === 'knowledge'">
              <label>人物知情状态<BaseSelect v-model="draft.knowledgeState" aria-label="人物知情状态" :options="knowledgeOptions" /></label>
              <label>人物此时知道 / 相信 / 怀疑的具体内容<textarea v-model="draft.belief" required maxlength="4000" rows="3" placeholder="可以与作者事实不同。记为“不知道”时，说明尚不知道哪部分。" /></label>
            </template>
            <template v-else>
              <label>读者揭示状态<BaseSelect v-model="draft.readerState" aria-label="读者揭示状态" :options="readerOptions" /></label>
              <label>读者看到的内容 / 范围<textarea v-model="draft.detail" required maxlength="4000" rows="3" placeholder="读者已经知道，不代表书中人物知道。" /></label>
            </template>
            <div class="story-columns"><label>故事顺序<input v-model="draft.storyOrder" type="number" min="0" step="1" placeholder="留空：未注明" /></label><label>叙述顺序<input v-model="draft.narrativeOrder" type="number" min="0" step="1" placeholder="留空：未注明" /></label></div>
            <label>来源章节<BaseSelect v-model="draft.source.chapterUid" aria-label="来源章节" :options="chapterOptions" @change="chooseChapter" /></label>
            <label v-if="draft.source.chapterUid">来源段落<BaseSelect v-model="draft.source.blockId" aria-label="来源段落" :options="blockOptions" :disabled="blocksLoading" @change="chooseBlock" /></label>
            <blockquote v-if="draft.source.excerpt">{{ draft.source.excerpt }}</blockquote>
            <label>来源事件<BaseSelect :model-value="draft.source.eventId ?? ''" aria-label="来源事件" :options="eventOptions" @change="chooseEvent" /></label>
            <label>作者说明<textarea v-model="draft.source.note" maxlength="2000" rows="2" placeholder="没有章节或事件来源时，请说明记录依据。" /></label>
          </template>
        </fieldset>
        <p class="story-caption">输入自动暂存于本机。保存后随作品备份与同步；历史修改通过撤回并追加更正保留依据。</p>
        <div class="story-toolbar"><button type="submit" class="btn-primary" :disabled="busy || !desk.data">{{ uncertain ? '原样重试保存' : '保存作者记录' }}</button><button type="button" class="btn-secondary" @click="downloadDraft">导出草稿</button><button type="button" class="btn-secondary" :disabled="busy || uncertain" @click="discard">丢弃草稿</button></div>
      </form>
      <div class="story-filters"><label>人物筛选<BaseSelect v-model="personFilter" aria-label="人物筛选" :options="personFilterOptions" /></label><label>历史排序<BaseSelect v-model="axis" aria-label="历史排序" :options="[{ value: 'storyOrder', label: '故事顺序' }, { value: 'narrativeOrder', label: '叙述顺序' }]" /></label><label>查看故事顺序时点<input v-model="at" type="number" min="0" step="1" placeholder="留空：全部历史" /></label></div>
      <h3>人物动态状态</h3>
      <p v-if="at !== ''" class="story-caption">显示该故事时点每项最近的记录。同一顺序的多条记录同时保留；未注明顺序的记录单独标注，不能判断是否适用于此时。</p>
      <p v-if="!visibleStates.length" class="story-caption">暂无符合条件的状态记录。</p>
      <p v-if="stateConflicts.size" class="story-error" role="alert">冲突：同一人物、同一状态项目在同一故事顺序出现不同值。请核对依据，撤回错误记录或追加更正。</p>
      <StoryRecordCard v-for="row in visibleStates" :key="row.uid" :record="row" :detail="row.value" :novel-id="novelId" :chapter-exists="hasChapter(row)" :event-exists="hasEvent(row)" :busy="busy || uncertain" @navigate="navigateSource" @retract="toggle('state', '', row.uid)">{{ row.characterName }} · {{ row.field }}{{ stateConflicts.has(row.uid) ? '（冲突）' : '' }}{{ row.storyOrder === null ? '（顺序未知，未纳入时点判断）' : '' }}{{ hasPerson(row.characterId) ? '' : '（人物已移除）' }}</StoryRecordCard>
      <h3>秘密与知情历史</h3>
      <p class="story-caption">分别选择人物所处的故事时点与读者读到的叙述时点。查询只采用作者已记录的信息；没有记录不等于“不知道”。</p>
      <div class="story-columns"><label>人物认知时点（故事顺序）<input v-model="knowledgePoint" type="number" min="0" step="1" placeholder="留空：全部人物认知历史" /></label><label>读者知情时点（叙述顺序）<input v-model="readerPoint" type="number" min="0" step="1" placeholder="留空：全部读者揭示历史" /></label></div>
      <p v-if="!story.secrets.length" class="story-caption">建立一条作者确认的秘密，再逐条记录人物认知和读者揭示。</p>
      <section v-for="secret in story.secrets" :key="secret.uid" class="story-secret">
        <div class="story-toolbar"><h4>{{ secret.title }}{{ secret.archived ? '（已归档）' : '' }}</h4><button type="button" class="btn-small" :disabled="busy || uncertain" @click="archive(secret.uid)">{{ secret.archived ? '恢复条目' : '归档条目' }}</button></div>
        <p class="story-truth"><b>作者事实：</b>{{ secret.truth }}</p>
        <div class="story-toolbar"><button type="button" class="btn-secondary" :disabled="busy || !!draft || secret.archived" @click="begin('knowledge', secret.uid)">记录人物认知</button><button type="button" class="btn-secondary" :disabled="busy || !!draft || secret.archived" @click="begin('reader', secret.uid)">记录读者揭示</button></div>
        <h5>人物知道什么{{ knowledgePoint === '' ? ' · 全部历史' : ` · 故事顺序 ${knowledgePoint}` }}</h5>
        <p v-if="knowledgeView(secret).conflicts.size" class="story-error">冲突：同一人物在同一故事顺序有不同认知记录，尚不能确定采用哪条。</p>
        <p v-if="!knowledgeView(secret).rows.length" class="story-caption">此范围没有作者记录，无法判定人物认知。</p>
        <StoryRecordCard v-for="row in knowledgeView(secret).rows" :key="row.uid" :record="row" :detail="row.belief" :novel-id="novelId" :chapter-exists="hasChapter(row)" :event-exists="hasEvent(row)" :busy="busy || uncertain" @navigate="navigateSource" @retract="toggle('knowledge', secret.uid, row.uid)">{{ row.characterName }} · {{ knowledgeLabels[row.state] }}{{ knowledgeView(secret).conflicts.has(row.uid) ? '（冲突）' : '' }}{{ row.storyOrder === null ? '（故事顺序未知，未纳入时点判断）' : '' }}{{ hasPerson(row.characterId) ? '' : '（人物已移除）' }}</StoryRecordCard>
        <h5>读者看到什么{{ readerPoint === '' ? ' · 全部历史' : ` · 叙述顺序 ${readerPoint}` }}</h5>
        <p v-if="readerView(secret).conflicts.size" class="story-error">冲突：同一叙述顺序有不同读者知情记录，尚不能确定揭示范围。</p>
        <p v-if="!readerView(secret).rows.length" class="story-caption">此范围没有作者记录，无法判定读者知情。</p>
        <StoryRecordCard v-for="row in readerView(secret).rows" :key="row.uid" :record="row" :detail="row.detail" :novel-id="novelId" :chapter-exists="hasChapter(row)" :event-exists="hasEvent(row)" :busy="busy || uncertain" @navigate="navigateSource" @retract="toggle('reader', secret.uid, row.uid)">读者 · {{ readerLabels[row.state] }}{{ readerView(secret).conflicts.has(row.uid) ? '（冲突）' : '' }}{{ row.narrativeOrder === null ? '（叙述顺序未知，未纳入时点判断）' : '' }}</StoryRecordCard>
      </section>
    </div>
    <template #actions="{ close: requestClose }"><button type="button" class="btn-secondary" @click="requestClose">关闭，保留草稿</button></template>
  </BaseModal>
</template>
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { isNavigationFailure, NavigationFailureType, useRouter, type RouteLocationRaw } from 'vue-router'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseSelect from '@/components/common/BaseSelect.vue'
import StoryRecordCard from './StoryRecordCard.vue'
import { useWritingDeskStore } from '@/stores/writingDesk'
import { useWritingStore } from '@/stores/writing'
import { request } from '@/api/request'
import { writingApi } from '@/api/writing'
import { blockList } from '@/utils/writing'
import { appendOnce, characterStateAt, emptyStorySource, emptyStoryState, knowledgeLabels, orderedHistory, readerLabels, readStoryDraft, recordConflicts, recordsAt, storyOrder } from '@/utils/storyState'
import type { Character } from '@/types/character'
import type { ChapterMeta } from '@/types/writing'
import type { TimelineEvent } from '@/types/timeline'
import type { StoryDraft as Draft, StoryDraftKind as Kind, StoryRecord, StorySecret, StoryState } from '@/types/storyState'

const props = defineProps<{ novelId: number; initialCharacterId?: number; navigateChapter?: (target: { chapterUid: string; blockId?: string }) => Promise<boolean> }>()
const emit = defineEmits<{ close: [] }>()
const desk = useWritingDeskStore(), writer = useWritingStore(), router = useRouter(), draft = ref<Draft | null>(null), uncertain = ref(false)
const draftBaseline = ref('')
const draftDirty = computed(() => !!draft.value && JSON.stringify(draft.value) !== draftBaseline.value)
const navigating = ref(false)
const rawRecovery = ref('')
let exportedDraft = ''
let storageReadFailed = false
const error = ref(''), resourceError = ref(''), storageError = ref(''), resourcesLoading = ref(false)
const people = ref<Character[]>([]), chapters = ref<ChapterMeta[]>([]), events = ref<TimelineEvent[]>([])
const blocks = ref<{ id: string; text: string }[]>([]), blocksLoading = ref(false)
const personOptions = computed(() => [{ value: '', label: '请选择人物' }, ...people.value.map(person => ({ value: String(person.id), label: person.name }))])
const personFilterOptions = computed(() => [{ value: '', label: '全部人物' }, ...people.value.map(person => ({ value: String(person.id), label: person.name }))])
const chapterOptions = computed(() => [{ value: '', label: '未关联章节' }, ...chapters.value.filter(chapter => !chapter.deleted).map(chapter => ({ value: chapter.uid, label: chapter.title }))])
const blockOptions = computed(() => [{ value: '', label: '整章' }, ...blocks.value.map(block => ({ value: block.id, label: block.text.slice(0, 100) || '空段落' }))])
const eventOptions = computed(() => [{ value: '', label: '未关联事件' }, ...events.value.map(event => ({ value: event.id, label: event.title }))])
const knowledgeOptions = Object.entries(knowledgeLabels).map(([value, label]) => ({ value, label }))
const readerOptions = Object.entries(readerLabels).map(([value, label]) => ({ value, label }))
const personFilter = ref(props.initialCharacterId ? String(props.initialCharacterId) : ''), axis = ref<'storyOrder' | 'narrativeOrder'>('storyOrder'), at = ref<string | number>('')
const knowledgePoint = ref<string | number>(''), readerPoint = ref<string | number>('')
const story = computed(() => desk.novelId === props.novelId ? desk.data?.storyState || emptyStoryState() : emptyStoryState())
const busy = computed(() => desk.loading || desk.saving || resourcesLoading.value || blocksLoading.value || navigating.value)
const editorTitle = computed(() => ({ state: '记录一个时点的人物状态', secret: '建立作者确认的秘密', knowledge: '记录人物此时的认知', reader: '记录读者此时的知情范围' })[draft.value?.kind || 'state'])
const visibleStates = computed(() => {
  let rows = story.value.states.filter(row => !personFilter.value || String(row.characterId) === personFilter.value)
  if (at.value !== '') { try { const result = characterStateAt(rows, storyOrder(at.value)!); rows = [...result.current, ...result.undated] } catch { return [] } }
  return orderedHistory(rows, axis.value)
})
const stateConflicts = computed(() => recordConflicts(visibleStates.value, 'storyOrder', row => `${row.characterId ?? row.uid}:${row.field}`, row => row.value))
function knowledgeView(secret: StorySecret) {
  let rows = secret.knowledge.filter(row => !personFilter.value || String(row.characterId) === personFilter.value)
  if (knowledgePoint.value !== '') {
    try { const view = recordsAt(rows, 'storyOrder', storyOrder(knowledgePoint.value)!, row => String(row.characterId ?? row.uid)); rows = [...view.current, ...view.undated] } catch { rows = [] }
  }
  return { rows: orderedHistory(rows, 'storyOrder'), conflicts: recordConflicts(rows, 'storyOrder', row => String(row.characterId ?? row.uid), row => JSON.stringify([row.state, row.belief])) }
}
function readerView(secret: StorySecret) {
  let rows = secret.reader
  if (readerPoint.value !== '') { try { const view = recordsAt(rows, 'narrativeOrder', storyOrder(readerPoint.value)!, () => 'reader'); rows = [...view.current, ...view.undated] } catch { rows = [] } }
  return { rows: orderedHistory(rows, 'narrativeOrder'), conflicts: recordConflicts(rows, 'narrativeOrder', () => 'reader', row => JSON.stringify([row.state, row.detail])) }
}
let epoch = 0, blockEpoch = 0, alive = true
const key = () => `ink:story-state-draft:${props.novelId}`
function persist() {
  if (rawRecovery.value || storageReadFailed) return
  try { if (draft.value) localStorage.setItem(key(), JSON.stringify(draft.value)); else localStorage.removeItem(key()); storageError.value = '' }
  catch { storageError.value = '本机草稿暂存失败。' }
}
watch(draft, persist, { deep: true })
const hasPerson = (id: number | null) => people.value.some(person => person.id === id)
const hasChapter = (row: StoryRecord) => chapters.value.some(chapter => chapter.uid === row.source.chapterUid && !chapter.deleted)
const hasEvent = (row: StoryRecord) => events.value.some(event => event.id === row.source.eventId)
function begin(kind: Kind, secretUid = '') {
  if (draft.value || rawRecovery.value) return
  draft.value = { version: 1, novelId: props.novelId, kind, uid: crypto.randomUUID(), recordedAt: new Date().toISOString(), secretUid, characterId: personFilter.value, field: '', value: '', title: '', truth: '', belief: '', detail: '', knowledgeState: 'unknown', readerState: 'hidden', storyOrder: '', narrativeOrder: '', source: emptyStorySource() }
  draftBaseline.value = JSON.stringify(draft.value)
  error.value = ''; uncertain.value = false; blocks.value = []
}
async function resources() {
  const token = ++epoch, id = props.novelId
  resourcesLoading.value = true
  const results = await Promise.allSettled([request.get<any, Character[]>(`/novels/${id}/characters`), writingApi.workspace(id), request.get<any, TimelineEvent[]>(`/novels/${id}/timeline-events`)])
  if (!alive || token !== epoch || id !== props.novelId) return
  people.value = results[0].status === 'fulfilled' ? results[0].value : []
  chapters.value = results[1].status === 'fulfilled' ? results[1].value.chapters : []
  events.value = results[2].status === 'fulfilled' ? results[2].value : []
  resourceError.value = results.some(r => r.status === 'rejected') ? '部分来源资料未载入，请重新读取后核对关联。' : ''
  resourcesLoading.value = false
}
async function reload() {
  error.value = ''
  try { if (desk.novelId !== props.novelId) await desk.load(props.novelId); else await desk.reload(); uncertain.value = false; await resources(); if (draft.value?.source.chapterUid) await loadBlocks() }
  catch (e) { error.value = failure(e) }
}
function failure(e: any) { return e?.response?.data?.message || e?.message || '操作失败，输入仍保留。' }
async function loadBlocks() {
  const chapterUid = draft.value?.source.chapterUid, id = props.novelId, token = ++blockEpoch
  blocks.value = []; if (!chapterUid) return
  blocksLoading.value = true
  try { const chapter = await writingApi.chapter(id, chapterUid); if (alive && token === blockEpoch && id === props.novelId) blocks.value = blockList(chapter.doc) }
  catch (e) { if (alive && token === blockEpoch) error.value = failure(e) }
  finally { if (alive && token === blockEpoch) blocksLoading.value = false }
}
function chooseChapter() { if (draft.value) { draft.value.source.blockId = ''; draft.value.source.excerpt = '' }; void loadBlocks() }
function chooseBlock() { if (draft.value) draft.value.source.excerpt = (blocks.value.find(b => b.id === draft.value!.source.blockId)?.text || '').slice(0, 1000) }
function chooseEvent(value: number | string) {
  if (!draft.value) return
  const id = typeof value === 'number' ? value : null
  draft.value.source.eventId = id || null; draft.value.source.eventTitle = events.value.find(e => e.id === id)?.title || ''
}
async function save() {
  if (!draft.value || busy.value) return
  const form = JSON.parse(JSON.stringify(draft.value)) as Draft
  try {
    if (form.kind === 'secret') {
      if (!form.title.trim() || !form.truth.trim()) throw Error('请填写秘密标题与作者事实。')
      await desk.mutateStoryState(state => appendOnce(state.secrets, { uid: form.uid, title: form.title, truth: form.truth, recordedAt: form.recordedAt, archived: false, knowledge: [], reader: [] }))
    } else {
      const common = { uid: form.uid, storyOrder: storyOrder(form.storyOrder), narrativeOrder: storyOrder(form.narrativeOrder), source: form.source, recordedAt: form.recordedAt, retracted: false }
      if (!form.source.chapterUid && !form.source.eventId && !form.source.note.trim()) throw Error('请记录来源章节、事件或作者说明。')
      const person = people.value.find(p => p.id === Number(form.characterId))
      if (form.kind !== 'reader' && !person) throw Error('请选择本作品中仍存在的人物。')
      if (form.kind === 'state' && (!form.field.trim() || !form.value.trim())) throw Error('请填写状态项目与内容。')
      if (form.kind === 'knowledge' && !form.belief.trim()) throw Error('请填写人物认知的具体内容。')
      if (form.kind === 'reader' && !form.detail.trim()) throw Error('请填写读者知道的范围。')
      await desk.mutateStoryState(state => {
        if (form.kind === 'state') appendOnce(state.states, { ...common, characterId: person!.id, characterName: person!.name, field: form.field, value: form.value })
        else {
          const secret = state.secrets.find(s => s.uid === form.secretUid)
          if (!secret || secret.archived) throw Error('秘密条目不存在或已归档，请重新读取。')
          if (form.kind === 'knowledge') appendOnce(secret.knowledge, { ...common, characterId: person!.id, characterName: person!.name, state: form.knowledgeState, belief: form.belief })
          else appendOnce(secret.reader, { ...common, state: form.readerState, detail: form.detail })
        }
      })
    }
    if (!alive || props.novelId !== form.novelId) return
    draft.value = null; uncertain.value = false; error.value = ''; persist()
  } catch (e: any) { if (alive && props.novelId === form.novelId) { error.value = failure(e); uncertain.value = !!desk.error } }
}
async function change(update: (state: StoryState) => void) { try { await desk.mutateStoryState(update); error.value = '' } catch (e) { error.value = failure(e); uncertain.value = true } }
function toggle(kind: 'state' | 'knowledge' | 'reader', secretUid: string, uid: string) {
  void change(state => { const rows = kind === 'state' ? state.states : state.secrets.find(s => s.uid === secretUid)?.[kind]; const row = rows?.find(r => r.uid === uid); if (!row) throw Error('记录已不存在，请重新读取。'); row.retracted = !row.retracted })
}
function archive(uid: string) { void change(state => { const secret = state.secrets.find(s => s.uid === uid); if (!secret) throw Error('条目已不存在，请重新读取。'); secret.archived = !secret.archived }) }
function discard() { if (confirm('丢弃这份尚未保存的作者记录？')) { draft.value = null; error.value = ''; persist() } }
function download(value: unknown, name: string) { const url = URL.createObjectURL(new Blob([JSON.stringify(value, null, 2)], { type: 'application/json' })); const link = document.createElement('a'); link.href = url; link.download = name; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000) }
function downloadDraft() { download(draft.value, `故事状态草稿-${props.novelId}.json`); exportedDraft = JSON.stringify(draft.value) }
function downloadLedger() { download(story.value, `人物状态与知情账-${props.novelId}.json`) }
function discardRecovery() { if (confirm('丢弃无法识别的原始草稿？建议先导出保留原件。')) { rawRecovery.value = ''; persist() } }
function close() { persist(); if (storageError.value && draft.value && exportedDraft !== JSON.stringify(draft.value)) return; emit('close') }
async function navigateSource(target: RouteLocationRaw) {
  if (busy.value || uncertain.value) { error.value = '请先等待当前操作完成，或确认上次保存结果，再查看来源。'; return }
  persist()
  if (storageError.value && draft.value && exportedDraft !== JSON.stringify(draft.value)) {
    error.value = '当前草稿尚未安全暂存，请先保存或导出，再查看来源。'
    return
  }
  const id = props.novelId, destination = router.resolve(target), expected = destination.fullPath
  navigating.value = true; error.value = ''
  try {
    const writingPath = `/novel/${id}/writing`
    if (router.currentRoute.value.path === writingPath && destination.path === writingPath && writer.novelId === id) {
      if (writer.saving || writer.recovery || writer.conflict) { error.value = '请先完成正文保存或处理稿件冲突，再查看来源。'; return }
      const chapterUid = String(destination.query.chapter || '')
      const opened = chapterUid && props.navigateChapter && await props.navigateChapter({ chapterUid, blockId: String(destination.query.block || '') || undefined })
      if (!alive || props.novelId !== id) return
      if (opened && router.currentRoute.value.fullPath === expected) emit('close')
      else error.value = '来源章节尚未安全打开，当前面板与草稿仍保留。'
      return
    }
    const result = await router.push(target)
    if (!alive || props.novelId !== id) return
    const reached = router.currentRoute.value.fullPath === expected
    if (reached && (!result || isNavigationFailure(result, NavigationFailureType.duplicated))) emit('close')
    else error.value = '尚未跳转到来源，当前面板与草稿仍保留。'
  } catch { if (alive && props.novelId === id) error.value = '来源跳转未完成，当前面板与草稿仍保留。' }
  finally { if (alive && props.novelId === id) navigating.value = false }
}
function preventLoss(event: BeforeUnloadEvent) { if (desk.saving || (draft.value && storageError.value && exportedDraft !== JSON.stringify(draft.value))) { event.preventDefault(); event.returnValue = '' } }
onMounted(async () => {
  let raw = ''
  try {
    raw = localStorage.getItem(key()) || ''
    if (raw) draft.value = readStoryDraft(raw, props.novelId)
  } catch { rawRecovery.value = raw; storageReadFailed = !raw; storageError.value = '本机草稿无法读取，原记录未删除。' }
  try { if (desk.novelId !== props.novelId || !desk.data) await desk.load(props.novelId); await resources(); if (draft.value?.source.chapterUid) await loadBlocks() } catch (e) { error.value = failure(e) }
  if (alive) window.addEventListener('beforeunload', preventLoss)
})
onBeforeUnmount(() => { alive = false; epoch++; blockEpoch++; window.removeEventListener('beforeunload', preventLoss) })
</script>
<style scoped>
.story-panel { display: grid; gap: 10px; }
.story-caption { color: var(--text-secondary, #777); font-size: 13px; }
.story-toolbar, .story-filters, .story-columns { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.story-form, .story-secret { border: 1px solid var(--border-color, #ddd); border-radius: 12px; padding: 16px; }
.story-form fieldset { border: 0; padding: 0; display: grid; gap: 12px; }
.story-form label, .story-filters label, .story-columns label { display: grid; gap: 5px; flex: 1; min-width: 150px; }
.story-panel input, .story-panel textarea { width: 100%; box-sizing: border-box; background: var(--bg-primary, #fff); color: var(--text-primary, #333); border: 1px solid var(--border-color, #ddd); padding: 9px; border-radius: 7px; font: inherit; }
.story-panel textarea { resize: vertical; }
.story-panel h3, .story-panel h4, .story-panel h5 { margin: 8px 0; }
.story-error { color: var(--danger, #b34343); }
.story-truth, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
blockquote { margin: 0; padding: 8px 12px; border-left: 3px solid var(--accent, #977847); }
</style>
