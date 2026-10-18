<template>
  <details class="ai-panel" :open="expanded" @toggle="toggle">
    <summary>✦ {{ scope === 'writing' ? '下一步灵感' : 'AI 创作顾问' }}</summary>
    <div v-if="expanded" class="ai-panel-body">
      <p class="ai-caption">{{ scope === 'writing' ? '讨论下一步的目标、选择和代价；不生成正文、对白或续写。' : '生成候选或核对疑点，由你选择；采用只填入当前编辑草稿。' }}</p>
      <p v-if="configLoading" role="status">正在读取 AI 配置…</p>
      <p v-else-if="!config?.configured">请先在<RouterLink :to="`/novel/${novelId}/settings`">设置与备份</RouterLink>中配置 AI 服务。</p>
      <template v-else>
        <label class="ai-label">这次需要什么<BaseSelect v-model="action" aria-label="这次需要什么" :disabled="busy || collectionLocked || applying" :options="actions.map(item => ({ value: item.action, label: item.label }))" /></label>
        <label class="ai-label">偏好或卡点（可选）<textarea v-model="request" rows="2" maxlength="1500" :disabled="busy" placeholder="例如：保留既有设定，避免新增人物；偏向人物抉择" /></label>
        <details v-if="scope !== 'writing'" class="ai-reference-chapters" :open="referencesExpanded" @toggle.stop="toggleReferences">
          <summary><span class="ai-reference-heading">{{ referencesExpanded ? '▾' : '▸' }} 参考正文章节（可选）</span><span>{{ selectedReferenceUids.length ? `已选 ${selectedReferenceUids.length} 章 · ${referenceWordCount.toLocaleString()} 字` : '默认不选正文' }}</span></summary>
          <div class="ai-reference-body">
            <p class="ai-caption">仅发送勾选章节的整章已保存正文，不包含未保存的写作稿。字数沿用写作台口径：中日韩逐字，英文按词；合计最多 {{ referenceLimit.toLocaleString() }} 字，超限不会截断。</p>
            <div class="ai-actions">
              <button type="button" class="btn-secondary" :disabled="referenceLocked || referencesLoading" @click="loadReferences">{{ referencesLoading ? '正在读取章节…' : '刷新章节列表' }}</button>
              <button type="button" class="btn-secondary" :disabled="referenceLocked || !selectedReferenceUids.length" @click="clearReferences">清空选择</button>
            </div>
            <p v-if="referencesLoaded && !referenceChapters.length" class="ai-caption">本作还没有可选择的已保存正文章节。</p>
            <div v-if="referenceChapters.length" class="ai-reference-list" role="group" aria-label="选择参考正文章节">
              <label v-for="chapter in referenceChapters" :key="chapter.uid" class="ai-reference-row">
                <input type="checkbox" :checked="selectedReferenceUids.includes(chapter.uid)" :disabled="referenceLocked || referencesLoading || !!referencesError" @change="selectReference(chapter.uid, ($event.target as HTMLInputElement).checked)" />
                <span>{{ chapter.title || '未命名章节' }}</span><small>{{ chapter.wordCount.toLocaleString() }} 字</small>
              </label>
            </div>
          </div>
        </details>
        <p v-if="scope !== 'writing' && referencesError" class="ai-error" role="alert">{{ referencesError }} 请展开参考正文章节，刷新列表或清空选择。</p>
        <p v-if="scope !== 'writing' && unavailableReferenceUids.length" class="ai-error" role="alert">已选的 {{ unavailableReferenceUids.length }} 章已不可用，请清空选择后重新勾选。</p>
        <p v-if="scope !== 'writing'" class="ai-caption" :class="{ 'ai-error': referenceOverLimit }" role="status">已选 {{ selectedReferenceUids.length }} 章，合计 {{ referenceWordCount.toLocaleString() }} / {{ referenceLimit.toLocaleString() }} 字。{{ referenceOverLimit ? '已超过上限，请减少勾选后再生成；不会截断正文。' : selectedReferenceUids.length ? '生成时仅参考所选章的已保存正文。' : '本次不发送正文。' }}</p>
        <p class="ai-caption ai-destination">发送给 {{ config.baseUrl }} · {{ config.model }}。{{ scope === 'writing' ? '本次参考本作全部文字设定与有效正文，按当前章末区分进度，含当前编辑稿。保留正文与对白边界复核；不通过时将问题反馈到本次对话，修正一轮，最多发送四次模型请求。' : `本次参考本作文字设定和当前表单${selectedReferenceUids.length ? '，以及你勾选的正文章节' : '，未选择正文'}。仅检查格式和资料引用；不通过时在本次对话中修正一轮，最多发送两次模型请求。` }}</p>
      </template>
      <div class="ai-actions">
        <button v-if="config?.configured && !configLoading" type="button" class="btn-secondary" :disabled="busy || disabled || collectionLocked || applying || referenceBlocked" @click="generate">{{ busy ? '正在构思并检查…' : '生成建议' }}</button>
        <button v-if="busy" type="button" class="btn-secondary" @click="cancel">停止本次请求</button>
        <button v-else type="button" class="btn-secondary" :disabled="configLoading || collectionLocked || applying" @click="loadConfig">{{ configLoading ? '正在读取连接信息…' : '刷新连接信息' }}</button>
      </div>
      <p v-if="disabled && disabledReason" role="status">{{ disabledReason }}</p>
      <p v-if="error" class="ai-error" role="alert">{{ error }}</p>
      <p v-if="message" role="status">{{ message }}</p>
      <div v-if="recoveryRows.length" class="ai-card">
        <strong>待确认的灵感收藏</strong><p class="ai-caption">请求已保留在本机，尚未确认是否保存成功。重试使用原请求，不会重新调用模型。</p>
        <div v-for="row in recoveryRows" :key="row.key"><p>{{ row.title }}</p><button class="btn-secondary" type="button" :disabled="savingIndex !== null" @click="recoverCollection(row)">重试原收藏请求</button></div>
      </div>
      <div v-if="lastAdoption" class="ai-card"><p>采用前：{{ lastAdoption.before || '（空）' }}</p><button type="button" class="btn-secondary" :disabled="applying" @click="undo">撤销本次采用</button></div>
      <div v-if="response" class="ai-results">
        <p v-if="response.correctionApplied" class="ai-notice" role="status">已根据检查反馈修正 1 轮。{{ response.correctionReason }}</p>
        <p class="ai-caption">本次覆盖 {{ response.coverage.settingsCount }} 条设定{{ response.coverage.mode === 'full' ? `；当前章之前 ${response.coverage.chaptersBefore} 章，之后 ${response.coverage.chaptersAfter} 章，并包含当前章` : response.coverage.selectedChapterCount ? `；所选正文 ${response.coverage.selectedChapterCount} 章，共 ${(response.coverage.selectedManuscriptChars ?? 0).toLocaleString()} 字` : '' }}{{ response.coverage.includesUnsavedDraft ? '；包含当前编辑稿' : '' }}。依据本次快照，仍需作者判断。</p>
        <p v-if="stale" class="ai-notice" role="status">{{ scope === 'writing' ? '当前编辑稿已改变。这些灵感基于旧快照，仍可收藏为候选，不代表当前设定。' : '当前编辑内容或核对到的资料已改变。这些是旧快照建议，请重新生成后采用。' }}</p>
        <article v-for="(candidate, index) in response.result.candidates" :key="`candidate-${index}`" class="ai-card">
          <h4>{{ candidate.title }}</h4><p class="ai-candidate">{{ candidateText(candidate) }}</p><p>{{ candidate.reason }}</p>
          <p v-if="candidate.assumptions?.length"><strong>新增假设：</strong>{{ candidate.assumptions.join('；') }}</p>
          <p v-if="candidate.conflicts?.length"><strong>需要核对：</strong>{{ candidate.conflicts.join('；') }}</p>
          <p v-if="references(candidate.sourceRefs).length" class="ai-caption">依据：{{ references(candidate.sourceRefs).join('、') }}</p>
          <details v-if="targetField && String(draft[targetField] || '')"><summary>对照当前内容</summary><p class="ai-candidate">{{ draft[targetField] }}</p></details>
          <button type="button" class="btn-secondary" :disabled="busy || stale || disabled || applying" @click="apply(candidate)">{{ applying ? '正在核对资料…' : `采用到${fieldLabel}草稿` }}</button>
        </article>
        <article v-for="(direction, index) in response.result.directions" :key="`direction-${index}`" class="ai-card">
          <h4>{{ direction.title }}</h4>
          <dl><dt>目标</dt><dd>{{ direction.goal }}</dd><dt>选择</dt><dd>{{ direction.choice }}</dd><dt>后果</dt><dd>{{ direction.consequence }}</dd><dt>代价</dt><dd>{{ direction.risk }}</dd><dt>待决定</dt><dd>{{ direction.question }}</dd></dl>
          <p v-if="direction.assumptions?.length"><strong>新增假设：</strong>{{ direction.assumptions.join('；') }}</p>
          <p v-if="references(direction.sourceRefs).length" class="ai-caption">依据：{{ references(direction.sourceRefs).join('、') }}</p>
          <button type="button" class="btn-secondary" :disabled="disabled || saved.has(index) || savingIndex !== null" @click="saveDirection(direction, index)">{{ saved.has(index) ? '已存入灵感' : savingIndex === index ? '正在保存…' : pending.has(index) ? '重试原收藏请求' : stale ? '存为旧快照灵感' : '存入灵感收件箱' }}</button>
        </article>
        <article v-for="(issue, index) in response.result.issues" :key="`issue-${index}`" class="ai-card"><h4>{{ issue.title }}</h4><p>{{ issue.detail }}</p><p v-if="references(issue.sourceRefs).length" class="ai-caption">依据：{{ references(issue.sourceRefs).join('、') }}</p></article>
        <p v-if="!response.result.candidates.length && !response.result.directions.length && !response.result.issues.length">本次未产生可用建议，资料不足时可以补充偏好或卡点。</p>
        <details><summary>本次参考资料（{{ response.sources.length }}）</summary><ul class="ai-sources"><li v-for="source in response.sources" :key="source.id">{{ source.title }} <small>· {{ source.type }}</small></li></ul></details>
      </div>
    </div>
  </details>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import BaseSelect from '@/components/common/BaseSelect.vue'
import { useModalDraftGuard } from '@/composables/modalDrafts'
import { aiApi } from '@/api/ai'
import { writingDeskApi } from '@/api/writingDesk'
import { useWritingDeskStore } from '@/stores/writingDesk'
import { aiActions, aiCandidatePatch, aiCollectionMerge, aiDirectionNote, aiDraftKey, aiFieldLabel, aiTargetField, aiUndoPatch } from '@/utils/ai'
import type { AiAction, AiCandidate, AiConfig, AiContext, AiDirection, AiReferenceChapter, AiResponse, AiScope } from '@/types/ai'
import type { Chapter } from '@/types/writing'
import type { WritingDesk } from '@/types/writingDesk'
const props = withDefaults(defineProps<{ scope: AiScope; targetId?: number; draft: Record<string, unknown>; currentDraft?: Chapter; chapterUid?: string; disabled?: boolean; disabledReason?: string; initiallyOpen?: boolean }>(), { disabled: false, initiallyOpen: false })
const emit = defineEmits<{ apply: [patch: { field: string; value: string }] }>()
const route = useRoute(), desk = useWritingDeskStore()
const novelId = computed(() => Number(route.params.novelId))
const expanded = ref(props.initiallyOpen), configLoading = ref(false), busy = ref(false)
const config = ref<AiConfig | null>(null), response = ref<AiResponse | null>(null)
const request = ref(''), error = ref(''), message = ref('')
const action = ref<AiAction>(aiActions[props.scope][0]!.action)
const actions = computed(() => aiActions[props.scope])
const targetField = computed(() => aiTargetField(action.value))
const fieldLabel = computed(() => aiFieldLabel(action.value))
const selectedReferenceUids = ref<string[]>([]), referenceChapters = ref<AiReferenceChapter[]>([])
const referenceLimit = ref(10000), referencesExpanded = ref(false), referencesLoading = ref(false), referencesLoaded = ref(false), referencesError = ref('')
let referenceGeneration = 0, referenceController: AbortController | undefined
const selectedReferenceKey = computed(() => props.scope === 'writing' ? [] : [...selectedReferenceUids.value].sort())
const currentKey = computed(() => JSON.stringify([aiDraftKey(props.draft, props.targetId, props.chapterUid), selectedReferenceKey.value]))
const invalidSnapshot = ref(false), applying = ref(false)
const stale = computed(() => !!response.value && (invalidSnapshot.value || baseline !== currentKey.value))
const lastAdoption = ref<{ field: string; before: string; applied: string } | null>(null)
let sentContext: AiContext | undefined
let baseline = '', generatedAt = '', generation = 0, activeRequest = '', controller: AbortController | undefined
const saved = ref(new Set<number>()), savingIndex = ref<number | null>(null)
const pending = ref(new Map<number, WritingDesk>())
interface RecoveryRow { key: string; novelId: number; index: number; title: string; payload: WritingDesk }
const recoveryRows = ref<RecoveryRow[]>([])
const collectionLocked = computed(() => savingIndex.value !== null || pending.value.size > 0 || recoveryRows.value.length > 0)
const referenceLocked = computed(() => busy.value || applying.value || collectionLocked.value)
const referenceWordCount = computed(() => referenceChapters.value.reduce((sum, chapter) => sum + (selectedReferenceUids.value.includes(chapter.uid) ? chapter.wordCount : 0), 0))
const unavailableReferenceUids = computed(() => selectedReferenceUids.value.filter(uid => !referenceChapters.value.some(chapter => chapter.uid === uid)))
const referenceOverLimit = computed(() => referenceWordCount.value > referenceLimit.value)
const referenceBlocked = computed(() => props.scope !== 'writing' && selectedReferenceUids.value.length > 0 && (referencesLoading.value || !!referencesError.value || unavailableReferenceUids.value.length > 0 || referenceOverLimit.value))
const initialAction = action.value
useModalDraftGuard(() => {
  if (request.value !== '' || action.value !== initialAction || selectedReferenceUids.value.length > 0 || busy.value || applying.value) return true
  const result = response.value?.result
  if (!result) return false
  return result.directions.some((_direction, index) => !saved.value.has(index)) || result.issues.length > 0 ||
    result.candidates.some(candidate => {
      try {
        const patch = aiCandidatePatch(action.value, candidate)
        return !lastAdoption.value || lastAdoption.value.field !== patch.field || lastAdoption.value.applied !== patch.value || props.draft[patch.field] !== patch.value
      } catch { return true }
    })
})
function toggleReferences(event: Event) {
  referencesExpanded.value = (event.target as HTMLDetailsElement).open
  if (referencesExpanded.value && !referencesLoaded.value && !referencesLoading.value) void loadReferences()
}
async function loadReferences() {
  if (props.scope === 'writing' || referencesLoading.value || referenceLocked.value) return
  const epoch = ++referenceGeneration, id = novelId.value
  referenceController = new AbortController(); referencesLoading.value = true; referencesError.value = ''
  try {
    const value = await aiApi.referenceChapters(id, referenceController.signal)
    if (epoch !== referenceGeneration || id !== novelId.value) return
    referenceChapters.value = value.chapters; referenceLimit.value = value.maxChars; referencesLoaded.value = true
  } catch (e) {
    if (epoch === referenceGeneration && !(e instanceof DOMException && e.name === 'AbortError')) referencesError.value = e instanceof Error ? e.message : '无法读取正文章节，请刷新后重试。'
  } finally { if (epoch === referenceGeneration) { referencesLoading.value = false; referenceController = undefined } }
}
function selectReference(uid: string, checked: boolean) {
  if (referenceLocked.value || referencesLoading.value || referencesError.value || !referenceChapters.value.some(chapter => chapter.uid === uid)) return
  if (checked) { if (!selectedReferenceUids.value.includes(uid)) selectedReferenceUids.value.push(uid) }
  else selectedReferenceUids.value = selectedReferenceUids.value.filter(selected => selected !== uid)
}
function clearReferences() { if (!referenceLocked.value) selectedReferenceUids.value = [] }
function resetReferences() {
  referenceGeneration++; referenceController?.abort(); referenceController = undefined
  selectedReferenceUids.value = []; referenceChapters.value = []; referenceLimit.value = 10000
  referencesExpanded.value = false; referencesLoading.value = false; referencesLoaded.value = false; referencesError.value = ''
}
function readCollections() {
  recoveryRows.value = []
  try {
    const prefix = `ink-ai-pending:${novelId.value}:`
    for (let i = 0; i < localStorage.length; i++) {
      const key = localStorage.key(i)
      if (!key?.startsWith(prefix)) continue
      const row = JSON.parse(localStorage.getItem(key) || '') as RecoveryRow
      if (row.novelId !== novelId.value || !row.payload?.mutationId || typeof row.payload.version !== 'number' || !Array.isArray(row.payload.ideas)) throw Error()
      recoveryRows.value.push({ ...row, key })
    }
  } catch { error.value = '待确认收藏记录无法读取，原记录保留。请勿清理浏览器数据。' }
}
function clearCollection(key: string) { try { localStorage.removeItem(key) } catch { /* Replaying the retained mutation remains safe. */ }; readCollections() }
async function loadConfig() {
  if (configLoading.value) return
  configLoading.value = true; error.value = ''
  try { config.value = await aiApi.config() } catch (e) { fail(e) } finally { configLoading.value = false }
}
function toggle(event: Event) {
  expanded.value = (event.target as HTMLDetailsElement).open
  if (expanded.value && !config.value && !configLoading.value) void loadConfig()
}
function fail(e: unknown) { error.value = e instanceof Error ? e.message : 'AI 操作未完成，请重试。' }
function cancel() {
  generation++
  controller?.abort(); controller = undefined
  if (activeRequest) void aiApi.cancel(activeRequest).catch(() => {})
  activeRequest = ''; busy.value = false
  message.value = '已停止等待本次结果。服务可能已处理请求，费用以服务方记录为准。'
}
function reset() { cancel(); response.value = null; generatedAt = ''; invalidSnapshot.value = false; error.value = ''; message.value = ''; saved.value = new Set(); pending.value = new Map(); savingIndex.value = null; sentContext = undefined }
watch(() => [novelId.value, props.scope, props.targetId, props.chapterUid], () => { reset(); resetReferences(); lastAdoption.value = null; readCollections(); action.value = aiActions[props.scope][0]!.action })
watch(action, reset)
async function generate() {
  if (!config.value?.configured || busy.value || props.disabled || collectionLocked.value || applying.value || referenceBlocked.value) return
  reset()
  const epoch = ++generation, id = novelId.value, snapshot = currentKey.value
  const requestId = crypto.randomUUID()
  activeRequest = requestId; controller = new AbortController(); busy.value = true
  try {
    const context: AiContext = {
      targetId: props.targetId, targetField: targetField.value,
      targetDraft: props.scope === 'writing' ? undefined : JSON.parse(JSON.stringify(props.draft)), chapterUid: props.chapterUid,
      currentDraft: props.scope === 'writing' && props.currentDraft ? JSON.parse(JSON.stringify(props.currentDraft)) : undefined, request: request.value,
      ...(props.scope === 'writing' ? {} : { referenceChapterUids: [...selectedReferenceKey.value] }),
    }
    const result = await aiApi.suggest(id, action.value, context, requestId, controller.signal, config.value.revision)
    if (epoch !== generation || id !== novelId.value) return
    baseline = snapshot; generatedAt = new Date().toISOString(); sentContext = context; response.value = result
  } catch (e) { if (epoch === generation && !(e instanceof DOMException && e.name === 'AbortError')) fail(e) }
  finally { if (epoch === generation) { busy.value = false; activeRequest = ''; controller = undefined } }
}
function references(refs: string[]) { return (refs || []).map(id => response.value?.sources.find(source => source.id === id)?.title).filter((title): title is string => !!title) }
function candidateText(candidate: AiCandidate) { try { return aiCandidatePatch(action.value, candidate).value } catch { return '候选字段不符合当前任务，无法采用。' } }
async function apply(candidate: AiCandidate) {
  if (stale.value || busy.value || props.disabled || applying.value || !response.value || !sentContext) return
  const epoch = generation, key = currentKey.value
  applying.value = true
  try {
    const check = await aiApi.contextCurrent(novelId.value, action.value, sentContext, response.value.snapshotId)
    if (epoch !== generation || key !== currentKey.value || props.disabled) return
    if (check.current !== true) { invalidSnapshot.value = true; throw Error('参考资料已改变，请重新生成候选后采用。') }
    const patch = aiCandidatePatch(action.value, candidate)
    lastAdoption.value = { field: patch.field, before: String(props.draft[patch.field] || ''), applied: patch.value }
    emit('apply', patch); message.value = '已填入编辑草稿，请检查后保存。'
  } catch (e) { if (epoch === generation) fail(e) } finally { applying.value = false }
}
function undo() {
  if (!lastAdoption.value) return
  try { emit('apply', aiUndoPatch(lastAdoption.value, props.draft)); lastAdoption.value = null; message.value = '已恢复采用前的字段内容。' } catch (e) { fail(e) }
}
async function recoverCollection(row: RecoveryRow) {
  if (savingIndex.value !== null) return
  const id = novelId.value, epoch = generation
  savingIndex.value = row.index; error.value = ''
  try {
    const value = await writingDeskApi.save(id, row.payload)
    if (epoch !== generation || id !== novelId.value) return
    desk.acceptExternal(id, value); clearCollection(row.key)
    if (response.value && row.key === `ink-ai-pending:${id}:${response.value.requestId}:${row.index}`) {
      pending.value.delete(row.index); saved.value.add(row.index)
    }
    message.value = '已确认收藏成功。'
  } catch (e: any) {
    if (epoch !== generation) return
    if (e?.response?.status === 409) {
      // A confirmed conflict means this payload was rejected, so a later explicit retry can merge afresh.
      try {
        const latest = await writingDeskApi.get(id)
        if (epoch !== generation || id !== novelId.value) return
        const sourceKey = `ai:${row.key.slice(`ink-ai-pending:${id}:`.length)}`
        const refreshed = { ...row, payload: aiCollectionMerge(latest, row.payload, sourceKey, crypto.randomUUID()) }
        localStorage.setItem(row.key, JSON.stringify(refreshed)); readCollections()
        if (response.value && row.key === `ink-ai-pending:${id}:${response.value.requestId}:${row.index}`) pending.value.set(row.index, refreshed.payload)
        error.value = '作品已更新，已保留现有内容并准备合并。请再次点击重试收藏。'
      } catch { error.value = '无法读取新版本，请保留本机记录后重试。' }
    } else error.value = '收藏结果仍未确认，原请求已保留，可稍后重试。'
  } finally { if (epoch === generation) savingIndex.value = null }
}
async function saveDirection(direction: AiDirection, index: number) {
  if (!response.value || savingIndex.value !== null || saved.value.has(index) || props.disabled) return
  const epoch = generation, id = novelId.value, snapshot = response.value, chapterUid = props.chapterUid, timestamp = generatedAt
  const sourceKey = `ai:${snapshot.requestId}:${index}`, recoveryKey = `ink-ai-pending:${id}:${snapshot.requestId}:${index}`
  const recovered = recoveryRows.value.find(row => row.key === recoveryKey)
  if (recovered) { await recoverCollection(recovered); return }
  savingIndex.value = index; error.value = ''
  let sent = false
  try {
    let payload = pending.value.get(index)
    if (!payload) {
      // Collect the author's chosen snapshot as a candidate, not a current fact.
      // Checking the whole setting snapshot here would make collecting the first
      // direction invalidate the other two, even though no manuscript was changed.
      const latest = await writingDeskApi.get(id)
      if (epoch !== generation || id !== novelId.value || props.disabled) return
      if (latest.ideas?.some(idea => idea.sourceKey === sourceKey)) { saved.value.add(index); return }
      const now = new Date().toISOString()
      payload = { ...latest, ideas: [...(latest.ideas || []), { uid: crypto.randomUUID(), title: direction.title, body: aiDirectionNote(direction, snapshot.model || '', references(direction.sourceRefs), { id: snapshot.snapshotId, generatedAt: timestamp }), category: 'plot', chapterUids: chapterUid ? [chapterUid] : [], createdAt: now, updatedAt: now, sourceKey }], mutationId: crypto.randomUUID() }
      try { localStorage.setItem(recoveryKey, JSON.stringify({ key: recoveryKey, novelId: id, index, title: direction.title, payload } satisfies RecoveryRow)) }
      catch { throw Error('无法暂存收藏请求，尚未发送。请恢复浏览器存储后重试。') }
      pending.value.set(index, payload)
    }
    sent = true
    const value = await writingDeskApi.save(id, payload)
    if (epoch !== generation || id !== novelId.value) return
    desk.acceptExternal(id, value); saved.value.add(index); pending.value.delete(index)
    clearCollection(recoveryKey)
    message.value = '已存入灵感收件箱，作为候选保留，未写入正文。'
  } catch (e: any) {
    if (epoch !== generation) return
    if (sent && e?.response?.status === 409) { pending.value.delete(index); clearCollection(recoveryKey); error.value = '创作便签已更新，当前建议仍保留。再次点击收藏将读取新版本后合并。' }
    else if (!sent) { error.value = `收藏尚未发送。${e instanceof Error ? e.message : '无法读取或核对最新资料，请重试。'}` }
    else { readCollections(); error.value = '收藏结果尚未确认。可重试原收藏请求，不必重新生成。' }
  } finally { if (epoch === generation) savingIndex.value = null }
}
onBeforeUnmount(() => { generation++; referenceGeneration++; referenceController?.abort(); controller?.abort(); if (activeRequest) void aiApi.cancel(activeRequest).catch(() => {}) })
if (props.initiallyOpen) void loadConfig()
readCollections()
</script>

<style scoped>
.ai-panel { margin: 12px 0 20px; border: 1px solid var(--line); border-radius: 12px; background: var(--panel); min-width: 0; }
.ai-panel > summary { cursor: pointer; padding: 12px 14px; color: var(--accent); font-weight: 600; }
.ai-panel-body { padding: 0 14px 14px; display: grid; gap: 12px; }
.ai-panel p { margin: 0; line-height: 1.65; overflow-wrap: anywhere; }
.ai-label { display: grid; gap: 6px; }
.ai-label select, .ai-label textarea { width: 100%; min-width: 0; box-sizing: border-box; }
.ai-caption { font-size: 12px; opacity: .8; }
.ai-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.ai-reference-chapters { border: 1px solid var(--line); border-radius: 8px; min-width: 0; }
.ai-reference-chapters > summary { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; cursor: pointer; padding: 10px; }
.ai-reference-chapters > summary > span:not(.ai-reference-heading) { font-size: 12px; color: var(--muted); }
.ai-reference-body { display: grid; gap: 10px; padding: 0 10px 10px; }
.ai-reference-list { max-height: 240px; overflow: auto; display: grid; gap: 4px; }
.ai-reference-row { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 8px; padding: 8px 0; cursor: pointer; }
.ai-reference-row input { width: 16px; height: 16px; margin: 0; accent-color: var(--accent); }
.ai-reference-row span { overflow-wrap: anywhere; }.ai-reference-row small { white-space: nowrap; }
.ai-results { display: grid; gap: 12px; }
.ai-card { border: 1px solid var(--line); border-radius: 8px; padding: 12px; display: grid; gap: 10px; min-width: 0; }
.ai-card h4 { margin: 0; font-size: 15px; }
.ai-card dl { display: grid; grid-template-columns: 4em 1fr; gap: 8px; margin: 0; font-size: 13px; }
.ai-card dd { margin: 0; overflow-wrap: anywhere; }.ai-card dt { opacity: .7; }
.ai-candidate { white-space: pre-wrap; }.ai-card button { justify-self: start; }
.ai-error { color: var(--danger); }.ai-notice { color: var(--accent); }
.ai-sources { max-height: 200px; overflow: auto; padding-left: 20px; font-size: 12px; overflow-wrap: anywhere; }
</style>
