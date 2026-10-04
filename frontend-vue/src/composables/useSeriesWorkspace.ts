import { computed, ref, watch } from 'vue'
import { seriesApi } from '@/api/series'
import { cloneSeries, emptySeriesPayload, validateSeriesPayload } from '@/utils/series'
import { seriesPackageStore, seriesPackageDigest, type SeriesPackageStore } from '@/utils/seriesPendingPackages'
import type { SeriesKind, SeriesAuthorStatus, SeriesPayload, SeriesState, SeriesTemplate, SeriesTemplateDetails, SeriesRevision, SeriesComparison, SeriesCopy, SeriesPack } from '@/types/series'

export type SeriesOperation = 'createTemplate' | 'publishTemplate' | 'selectHead' | 'archiveTemplate' | 'importLibrary' | 'createWorld' | 'updateWorld' | 'removeWorld' | 'copy' | 'duplicateCopy' | 'updateCopy' | 'archiveCopy' | 'restoreCopy' | 'adopt' | 'review'
export interface SeriesEditorDraft {
  uid: string; novelId: number; type: 'source' | 'copy' | 'world'; targetUid: string; templateUid: string; universeUid: string
  kind: SeriesKind; payload: SeriesPayload; baseline: SeriesPayload; seriesName: string; authorStatus: SeriesAuthorStatus; planet: string; changeNote: string; name: string; description: string
  epoch: string; expectedVersion: number; expectedLockVersion: number; expectedHeadRevisionUid: string
}
interface Pending {
  mutationId: string; novelId: number; action: SeriesOperation; targetUid: string; body: Record<string, unknown>; draftUid: string; draftSnapshot: string; message: string
  packageDigest?: string
}
type LocalStorage = Pick<Storage, 'length' | 'key' | 'getItem' | 'setItem' | 'removeItem'>
const globalActions = new Set<SeriesOperation>(['createTemplate', 'publishTemplate', 'selectHead', 'archiveTemplate', 'importLibrary'])
const allActions = new Set<SeriesOperation>([...globalActions, 'createWorld', 'updateWorld', 'removeWorld', 'copy', 'duplicateCopy', 'updateCopy', 'archiveCopy', 'restoreCopy', 'adopt', 'review'])
const uuid = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
function failure(error: unknown) { const e = error as { response?: { status?: number; data?: { message?: string; code?: string } }; message?: string }; return { status: e?.response?.status, code: e?.response?.data?.code, message: e?.response?.data?.message || e?.message || '请求失败，请重试' } }

export function useSeriesWorkspace(novelId: number, api = seriesApi, local?: LocalStorage, packageStore: SeriesPackageStore = seriesPackageStore) {
  const data = ref<SeriesState | null>(null), templates = ref<SeriesTemplate[]>([]), details = ref<SeriesTemplateDetails | null>(null)
  const worldUid = ref(''), selectedTemplateUid = ref(''), revisionUid = ref(''), selectedCopyUid = ref(''), planetFilter = ref('')
  const loading = ref(false), detailLoading = ref(false), comparisonBusy = ref(false), busy = ref(false)
  const error = ref(''), message = ref(''), storageError = ref(''), recoveryError = ref('')
  const draft = ref<SeriesEditorDraft | null>(null), drafts = ref<SeriesEditorDraft[]>([]), editorOpen = ref(false), pending = ref<Pending | null>(null)
  const comparison = ref<SeriesComparison | null>(null), selectedFields = ref<string[]>([]), sourceVersions = ref<SeriesRevision[]>([])
  const lastMutation = ref<{ action: SeriesOperation; result: unknown; body: Record<string, unknown> } | null>(null)
  const draftPrefix = `series-draft:${novelId}:`, pendingPrefix = `series-pending:${novelId}:`
  const storage = () => local || globalThis.localStorage
  let disposed = false, stateSequence = 0, catalogSequence = 0, detailSequence = 0, comparisonSequence = 0, sourceSequence = 0, restoring = false
  const currentCopy = computed(() => data.value?.copies.find(copy => copy.uid === selectedCopyUid.value && copy.universeUid === worldUid.value) || null)
  const currentRevision = computed(() => details.value?.revisions.find(revision => revision.uid === revisionUid.value) || null)
  const latestDraftPayload = computed(() => {
    const d = draft.value
    if (!d) return null
    if (d.type === 'copy') return data.value?.copies.find(copy => copy.uid === d.targetUid)?.content || null
    if (d.type === 'source') return details.value?.template.uid === d.targetUid ? details.value.revisions.find(row => row.uid === details.value!.template.headRevisionUid)?.payload || null : null
    return null
  })
  const latestDraftWorld = computed(() => draft.value?.type === 'world' ? data.value?.worlds.find(world => world.uid === draft.value?.targetUid) || null : null)
  const latestDraftCopy = computed(() => draft.value?.type === 'copy' ? data.value?.copies.find(copy => copy.uid === draft.value?.targetUid) || null : null)
  const latestDraftRevision = computed(() => draft.value?.type === 'source' && details.value?.template.uid === draft.value.targetUid ? details.value.revisions.find(revision => revision.uid === details.value!.template.headRevisionUid) || null : null)
  const draftEpochChanged = computed(() => !!draft.value && draft.value.type !== 'source' && !!draft.value.epoch && draft.value.epoch !== data.value?.epoch)
  const draftTargetMissing = computed(() => {
    const d = draft.value
    if (!d?.targetUid || !data.value || d.type === 'source') return false
    return d.type === 'world' ? !data.value.worlds.some(row => row.uid === d.targetUid) : !data.value.copies.some(row => row.uid === d.targetUid)
  })
  const draftConflict = computed(() => {
    const d = draft.value
    if (!d) return false
    if (d.type !== 'source') return !!data.value && (draftTargetMissing.value || d.epoch !== data.value.epoch || d.expectedVersion !== data.value.version)
    const t = templates.value.find(row => row.uid === d.targetUid)
    return !!d.targetUid && (!t || t.lockVersion !== d.expectedLockVersion || t.headRevisionUid !== d.expectedHeadRevisionUid)
  })
  const unsafelyStored = computed(() => !!storageError.value && (!!draft.value || !!pending.value))
  const canMutate = computed(() => !busy.value && !pending.value && !recoveryError.value)

  function keys(prefix: string) { const s = storage(); return Array.from({ length: s.length }, (_, i) => s.key(i)).filter((key): key is string => !!key?.startsWith(prefix)) }
  function validDraft(value: any): value is SeriesEditorDraft {
    if (value?.novelId !== novelId || !uuid(value.uid) || !['source','copy','world'].includes(value.type) || !['calendar','location','race','organization','character'].includes(value.kind)) return false
    const fields = Object.keys(emptySeriesPayload(value.kind))
    // Browser drafts can be incomplete or over the server's size limit. Only
    // validate their shape here so an author can reopen and correct their text.
    return !!value.payload && typeof value.payload === 'object' && !Array.isArray(value.payload) && Object.keys(value.payload).length === fields.length && fields.every(key => typeof value.payload[key] === 'string') && typeof value.targetUid === 'string' && typeof value.epoch === 'string' && Number.isSafeInteger(value.expectedVersion)
  }
  function recover() {
    if (disposed) return
    try {
      const saved: SeriesEditorDraft[] = []
      for (const key of keys(draftPrefix)) {
        const raw = storage().getItem(key); if (!raw) continue
        const value = JSON.parse(raw)
        if (!validDraft(value)) throw Error('无法读取草稿')
        saved.push(value)
      }
      drafts.value = saved
      if (!pending.value) for (const key of keys(pendingPrefix).sort()) {
        const raw = storage().getItem(key); if (!raw) continue
        const value = JSON.parse(raw)
        if (value?.novelId !== novelId || !uuid(value.mutationId) || !allActions.has(value.action) || value.body?.mutationId !== value.mutationId || typeof value.targetUid !== 'string') throw Error('无法读取待确认提交')
        pending.value = value; break
      }
      recoveryError.value = ''
    } catch { recoveryError.value = '本机草稿或待确认请求无法读取，原件仍保留。请先导出本机恢复记录，恢复浏览器存储后重试。' }
  }
  function persistDraft() {
    if (disposed) return false
    if (restoring || !draft.value) return true
    try {
      if (recoveryError.value) throw Error('原记录尚未读取')
      storage().setItem(draftPrefix + draft.value.uid, JSON.stringify(draft.value))
      const index = drafts.value.findIndex(row => row.uid === draft.value!.uid)
      if (index < 0) drafts.value.push(cloneSeries(draft.value)); else drafts.value[index] = cloneSeries(draft.value)
      storageError.value = ''
      return true
    } catch { storageError.value = '本机草稿暂存失败，请保持页面打开，导出草稿或恢复浏览器存储后再离开。'; return false }
  }
  const stopDraftWatch = watch(draft, persistDraft, { deep: true, flush: 'sync' })
  function recoveryText() {
    const original: Record<string, string> = {}
    try { for (const key of [...keys(draftPrefix), ...keys(pendingPrefix)]) original[key] = storage().getItem(key) || '' } catch { /* Current in-memory work remains exportable. */ }
    return JSON.stringify({ format: 'novel-series-local-recovery-v1', novelId, draft: draft.value, pending: pending.value, original }, null, 2)
  }
  function clearPending(record: Pending) {
    try {
      storage().removeItem(pendingPrefix + record.mutationId)
      if (record.packageDigest) void packageStore.remove(record.mutationId).catch(() => { /* An unreferenced package can safely remain. */ })
    } catch { storageError.value = '提交已完成，但本机回执清理失败；下次读取会安全核对同一请求。' }
  }
  async function pendingPackage(record = pending.value) {
    if (!record?.packageDigest) return record?.body.package as SeriesPack | undefined
    const pack = await packageStore.get(record.mutationId)
    if (!pack || await seriesPackageDigest(pack) !== record.packageDigest) throw Error('原母本包暂存无法读取，请重新选择同一原文件恢复待确认导入。原操作编号仍保留')
    return pack
  }
  async function restorePendingPackage(pack: SeriesPack) {
    const record = pending.value
    if (disposed || busy.value || record?.action !== 'importLibrary' || !record.packageDigest) return false
    try {
      if (await seriesPackageDigest(pack) !== record.packageDigest) throw Error('这不是原提交的母本包，请选择同一原文件；不会替换待确认内容。')
      await packageStore.put(record.mutationId, cloneSeries(pack))
      if (disposed || pending.value?.mutationId !== record.mutationId) return false
      error.value = ''; message.value = '原母本包已恢复，可以按原操作编号重试核对。'; return true
    } catch (e) { if (!disposed) error.value = failure(e).message; return false }
  }
  function removeDraft(uid: string) {
    try { storage().removeItem(draftPrefix + uid) } catch { storageError.value = '本机草稿删除失败，原件仍保留'; return false }
    drafts.value = drafts.value.filter(row => row.uid !== uid)
    if (draft.value?.uid === uid) { draft.value = null; editorOpen.value = false }
    return true
  }
  function discardDraft(uid: string) {
    if (busy.value || pending.value?.draftUid === uid) return false
    return removeDraft(uid)
  }
  function edit(value: SeriesEditorDraft) {
    if (busy.value || disposed) return false
    if (!persistDraft()) { editorOpen.value = true; error.value = '当前草稿尚未成功暂存，已保留原输入。请恢复本机存储后再切换草稿。'; return false }
    draft.value = cloneSeries(value); editorOpen.value = true; error.value = ''; persistDraft()
    if (value.type === 'source' && value.targetUid && details.value?.template.uid !== value.targetUid) void loadTemplate(value.targetUid)
    return true
  }
  function newDraft(type: SeriesEditorDraft['type'], kind: SeriesKind = 'calendar'): SeriesEditorDraft {
    return { uid: crypto.randomUUID(), novelId, type, targetUid: '', templateUid: crypto.randomUUID(), universeUid: worldUid.value, kind, payload: emptySeriesPayload(kind), baseline: emptySeriesPayload(kind), seriesName: '', authorStatus: 'draft', planet: '', changeNote: '', name: '', description: '', epoch: data.value?.epoch || '', expectedVersion: data.value?.version || 0, expectedLockVersion: 0, expectedHeadRevisionUid: '' }
  }
  function editSource(revision?: SeriesRevision) {
    if (!revision) { edit(newDraft('source')); return }
    const template = templates.value.find(row => row.uid === revision.templateUid)
    if (!template) { error.value = '母本不存在，请刷新列表'; return }
    edit({ ...newDraft('source', revision.kind), targetUid: template.uid, templateUid: template.uid, payload: cloneSeries(revision.payload), baseline: cloneSeries(revision.payload), seriesName: revision.seriesName, authorStatus: revision.authorStatus, expectedLockVersion: template.lockVersion, expectedHeadRevisionUid: template.headRevisionUid })
  }
  function editCopy(copy: SeriesCopy) {
    edit({ ...newDraft('copy', copy.kind), targetUid: copy.uid, templateUid: copy.origin.templateUid, universeUid: copy.universeUid, payload: cloneSeries(copy.content), baseline: cloneSeries(copy.content), authorStatus: copy.authorStatus, planet: copy.planet })
  }
  function editWorld(uid?: string) {
    const world = data.value?.worlds.find(row => row.uid === uid)
    edit({ ...newDraft('world'), targetUid: world?.uid || '', universeUid: world?.uid || crypto.randomUUID(), name: world?.name || '', description: world?.description || '' })
  }
  function invalidateComparison(clearSources = true) { ++comparisonSequence; ++sourceSequence; comparison.value = null; selectedFields.value = []; if (clearSources) sourceVersions.value = []; comparisonBusy.value = false }
  function selectWorld(uid: string) {
    if (!data.value?.worlds.some(world => world.uid === uid) || busy.value) return
    worldUid.value = uid; selectedCopyUid.value = ''; planetFilter.value = ''; editorOpen.value = false
    ++detailSequence; detailLoading.value = false; invalidateComparison()
  }
  function selectCopy(uid: string) {
    if (busy.value) return
    selectedCopyUid.value = uid; invalidateComparison()
    const copy = currentCopy.value
    if (copy) void loadSourceVersions(copy)
  }
  async function loadState() {
    const sequence = ++stateSequence
    const value = await api.state(novelId)
    if (disposed || sequence !== stateSequence) return false
    data.value = value
    if (!value.worlds.some(world => world.uid === worldUid.value)) { worldUid.value = value.worlds[0]?.uid || ''; selectedCopyUid.value = ''; invalidateComparison() }
    if (comparison.value && (comparison.value.epoch !== value.epoch || comparison.value.expectedVersion !== value.version)) { comparison.value = null; selectedFields.value = [] }
    return true
  }
  async function loadCatalog() {
    const sequence = ++catalogSequence, value = await api.listTemplates(true)
    if (disposed || sequence !== catalogSequence) return false
    templates.value = value.items; return true
  }
  async function loadTemplate(uid: string) {
    const sequence = ++detailSequence
    selectedTemplateUid.value = uid; details.value = null; revisionUid.value = ''; detailLoading.value = true; error.value = ''
    try {
      const value = await api.template(uid)
      if (disposed || sequence !== detailSequence || selectedTemplateUid.value !== uid) return false
      details.value = value; revisionUid.value = value.template.headRevisionUid; return true
    } catch (e) { if (!disposed && sequence === detailSequence) error.value = failure(e).message; return false }
    finally { if (!disposed && sequence === detailSequence) detailLoading.value = false }
  }
  async function loadSourceVersions(copy: SeriesCopy) {
    const sequence = ++sourceSequence, selectedWorld = worldUid.value
    sourceVersions.value = data.value?.sourceRevisions.filter(row => row.templateUid === copy.origin.templateUid) || []
    try {
      const value = await api.template(copy.origin.templateUid)
      if (disposed || sequence !== sourceSequence || selectedCopyUid.value !== copy.uid || worldUid.value !== selectedWorld) return
      sourceVersions.value = [...new Map([...sourceVersions.value, ...value.revisions].map(row => [row.uid,row])).values()]
    } catch { if (!disposed && sequence === sourceSequence) message.value = '共享母本暂不可读取，仍可使用本作保留的来源版本。' }
  }
  async function refresh() {
    if (disposed || busy.value) return false
    loading.value = true; error.value = ''; recover()
    try { await Promise.all([loadState(), loadCatalog()]); if (disposed) return false; if (selectedTemplateUid.value) await loadTemplate(selectedTemplateUid.value); if (currentCopy.value) await loadSourceVersions(currentCopy.value); return true }
    catch (e) { if (!disposed) error.value = failure(e).message; return false }
    finally { if (!disposed) loading.value = false }
  }
  async function compare(revision: string) {
    const copy = currentCopy.value
    if (!copy || !revision || busy.value) return
    const sequence = ++comparisonSequence, universe = worldUid.value
    comparison.value = null; selectedFields.value = []; comparisonBusy.value = true; error.value = ''
    try {
      const value = await api.compare(novelId, copy.uid, revision)
      if (disposed || sequence !== comparisonSequence || selectedCopyUid.value !== copy.uid || worldUid.value !== universe) return
      if (data.value?.epoch !== value.epoch || data.value?.version !== value.expectedVersion) { error.value = '本作设定已变化，请刷新后重新比较。'; return }
      comparison.value = value
    } catch (e) { if (!disposed && sequence === comparisonSequence) error.value = failure(e).message }
    finally { if (!disposed && sequence === comparisonSequence) comparisonBusy.value = false }
  }
  function novelBody(extra: Record<string, unknown>, snapshot = data.value) {
    if (!snapshot) throw Error('请先读取本作设定')
    return { epoch: snapshot.epoch, expectedVersion: snapshot.version, ...cloneSeries(extra) }
  }
  async function invoke(record: Pending): Promise<any> {
    const body = cloneSeries(record.body) as any, uid = record.targetUid
    switch (record.action) {
      case 'createTemplate': return api.createTemplate(body)
      case 'publishTemplate': return api.publishTemplate(uid, body)
      case 'selectHead': return api.selectHead(uid, body)
      case 'archiveTemplate': return api.archiveTemplate(uid, body)
      case 'importLibrary': body.package = await pendingPackage(record); return api.importLibrary(body)
      case 'createWorld': return api.createWorld(novelId, body)
      case 'updateWorld': return api.updateWorld(novelId, uid, body)
      case 'removeWorld': return api.removeWorld(novelId, uid, body)
      case 'copy': return api.copy(novelId, body)
      case 'duplicateCopy': return api.duplicateCopy(novelId, uid, body)
      case 'updateCopy': return api.updateCopy(novelId, uid, body)
      case 'archiveCopy': return api.archiveCopy(novelId, uid, body)
      case 'restoreCopy': return api.restoreCopy(novelId, uid, body)
      case 'adopt': return api.adopt(novelId, uid, body)
      case 'review': return api.review(novelId, uid, body)
    }
  }
  async function sendPending() {
    if (disposed || busy.value || !pending.value || recoveryError.value) return false
    const record = cloneSeries(pending.value)
    busy.value = true; error.value = ''; message.value = ''
    ++stateSequence; ++catalogSequence; invalidateComparison(false)
    const selectedAtStart = selectedTemplateUid.value, detailAtStart = ++detailSequence
    detailLoading.value = false
    let accepted = false
    try {
      const result = await invoke(record)
      accepted = true
      if (disposed || pending.value?.mutationId !== record.mutationId) return false
      // Always re-read, including receipt replays: the original submission may predate other changes.
      if (globalActions.has(record.action)) {
        await loadCatalog()
        const uid = result.template?.uid || selectedTemplateUid.value
        if (uid) {
          const detail = await api.template(uid)
          if (disposed) return false
          if (detailAtStart === detailSequence && selectedTemplateUid.value === selectedAtStart) {
            details.value = detail; selectedTemplateUid.value = uid; revisionUid.value = result.revision?.uid || detail.template.headRevisionUid
          }
        }
      } else await loadState()
      if (disposed || pending.value?.mutationId !== record.mutationId) return false
      clearPending(record); pending.value = null; lastMutation.value = { action: record.action, result, body: cloneSeries(record.body) }
      if (record.draftUid) {
        try {
          const raw = storage().getItem(draftPrefix + record.draftUid)
          const unchangedInMemory = draft.value?.uid !== record.draftUid || JSON.stringify(draft.value) === record.draftSnapshot
          // A second page may have edited this same recovered draft meanwhile.
          if (raw === record.draftSnapshot && unchangedInMemory) removeDraft(record.draftUid)
          else if (unchangedInMemory && draft.value?.uid === record.draftUid) { draft.value = null; editorOpen.value = false }
        } catch { /* Keep the draft if cleanup is unavailable. */ }
      }
      message.value = record.message + (result.replayed ? '（已核对原提交，并读取当前状态）' : '')
      invalidateComparison(false); if (currentCopy.value) void loadSourceVersions(currentCopy.value); recover(); return true
    } catch (e) {
      if (disposed || pending.value?.mutationId !== record.mutationId) return false
      const detail = failure(e)
      if (!accepted && [400,404,409,413].includes(detail.status || 0)) { clearPending(record); pending.value = null }
      error.value = detail.message + (detail.status === 409 ? '。请读取最新状态并比较，当前草稿仍保留。' : pending.value ? '。原请求与操作编号已保留，请重试确认；其他修改暂停。' : '。当前草稿仍保留。')
      return false
    } finally { if (!disposed) busy.value = false }
  }
  async function mutate(action: SeriesOperation, targetUid: string, body: Record<string, unknown>, successMessage: string, draftUid = '') {
    if (disposed || !canMutate.value) { error.value = '请先核对上次待确认提交。'; return false }
    recover(); if (pending.value || recoveryError.value) { error.value = '发现本机待确认请求，请先重试核对。'; return false }
    const mutationId = crypto.randomUUID()
    const record: Pending = { mutationId, novelId, action, targetUid, body: { ...cloneSeries(body), mutationId }, draftUid, draftSnapshot: draft.value?.uid === draftUid ? JSON.stringify(draft.value) : '', message: successMessage }
    busy.value = true
    try {
      if (action === 'importLibrary') {
        const pack = record.body.package as SeriesPack
        record.packageDigest = await seriesPackageDigest(pack)
        await packageStore.put(mutationId, pack)
        delete record.body.package
      }
      storage().setItem(pendingPrefix + mutationId, JSON.stringify(record)); storageError.value = ''
    } catch {
      if (record.packageDigest) void packageStore.remove(mutationId).catch(() => {})
      if (!disposed) storageError.value = '无法保存待确认请求，尚未提交。请恢复浏览器存储或导出草稿。'
      return false
    } finally { if (!disposed) busy.value = false }
    if (disposed) return false
    pending.value = record; return sendPending()
  }
  async function saveDraft() {
    const d = draft.value
    if (!d || busy.value || pending.value) return false
    if (draftConflict.value) { error.value = '草稿基线已变化，请先比较最新状态；不会自动覆盖。'; return false }
    if (d.type !== 'world') { const invalid = validateSeriesPayload(d.kind, d.payload); if (invalid) { error.value = invalid; return false } }
    if (d.type === 'source') {
      if (!d.seriesName.trim()) { error.value = '请填写系列名称'; return false }
      const fields = { seriesName: d.seriesName, payload: cloneSeries(d.payload), authorStatus: d.authorStatus, changeNote: d.changeNote }
      return d.targetUid ? mutate('publishTemplate', d.targetUid, { ...fields, expectedLockVersion: d.expectedLockVersion, expectedHeadRevisionUid: d.expectedHeadRevisionUid }, '母本新版已保存；本作副本保持独立。', d.uid)
        : mutate('createTemplate', '', { ...fields, templateUid: d.templateUid, kind: d.kind }, '母本首版已保存。', d.uid)
    }
    const base = { epoch: d.epoch, expectedVersion: d.expectedVersion }
    if (d.type === 'copy') return mutate('updateCopy', d.targetUid, { ...base, content: cloneSeries(d.payload), authorStatus: d.authorStatus, planet: d.planet }, '本作副本已保存，当前状态已留入历史。', d.uid)
    if (!d.name.trim()) { error.value = '请填写世界名称'; return false }
    return d.targetUid ? mutate('updateWorld', d.targetUid, { ...base, name: d.name, description: d.description }, '世界设置已保存。', d.uid)
      : mutate('createWorld', '', { ...base, worldUid: d.universeUid, name: d.name, description: d.description }, '新的平行世界已建立。', d.uid)
  }
  function rebaseDraft() {
    const d = draft.value
    if (!d || busy.value || pending.value || draftEpochChanged.value || draftTargetMissing.value) return false
    if (d.type === 'source') {
      const t = templates.value.find(row => row.uid === d.targetUid)
      if (!t || !latestDraftPayload.value) return false
      if (details.value?.template.lockVersion !== t.lockVersion || details.value.template.headRevisionUid !== t.headRevisionUid) { error.value = '母本列表与详情版本不一致，请再次读取最新状态后比较。'; return false }
      d.expectedLockVersion = t.lockVersion; d.expectedHeadRevisionUid = t.headRevisionUid; d.baseline = cloneSeries(latestDraftPayload.value)
    } else if (data.value) { d.epoch = data.value.epoch; d.expectedVersion = data.value.version; if (latestDraftPayload.value) d.baseline = cloneSeries(latestDraftPayload.value) }
    persistDraft(); error.value = ''; return true
  }
  async function adopt(reviewOnly = false) {
    const c = comparison.value
    if (!c || c.copyUid !== currentCopy.value?.uid || c.epoch !== data.value?.epoch || c.expectedVersion !== data.value?.version) { error.value = '比较已失效，请重新比较。'; return false }
    if (!reviewOnly && !selectedFields.value.length) { error.value = '请明确勾选要采用的字段，或选择仅记录已比较。'; return false }
    const chosen = [...selectedFields.value]
    const body = { epoch: c.epoch, expectedVersion: c.expectedVersion, copyHash: c.copyHash, baselineRevisionUid: c.baselineRevisionUid, revisionUid: c.revision.uid, ...(reviewOnly ? {} : { selectedFields: chosen }) }
    return mutate(reviewOnly ? 'review' : 'adopt', c.copyUid, body, `已比较v${c.revision.number}，采用${reviewOnly ? 0 : chosen.length}字段／保留${c.fields.length - (reviewOnly ? 0 : chosen.length)}字段。`)
  }
  function dispose() { persistDraft(); disposed = true; ++stateSequence; ++catalogSequence; ++detailSequence; invalidateComparison(); stopDraftWatch() }
  recover()
  return { novelId, data, templates, details, worldUid, selectedTemplateUid, revisionUid, selectedCopyUid, planetFilter, loading, detailLoading, comparisonBusy, busy, error, message, storageError, recoveryError, draft, drafts, editorOpen, pending, comparison, selectedFields, sourceVersions, lastMutation, currentCopy, currentRevision, latestDraftPayload, latestDraftWorld, latestDraftCopy, latestDraftRevision, draftConflict, draftEpochChanged, draftTargetMissing, unsafelyStored, canMutate, refresh, loadState, loadCatalog, loadTemplate, loadSourceVersions, selectWorld, selectCopy, compare, invalidateComparison, novelBody, mutate, retry: sendPending, edit, editSource, editCopy, editWorld, newDraft, saveDraft, rebaseDraft, discardDraft, persistDraft, recover, recoveryText, pendingPackage, restorePendingPackage, adopt, dispose }
}
