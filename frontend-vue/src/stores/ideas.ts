import { computed, ref, watch } from 'vue'
import { defineStore } from 'pinia'
import { writingDeskApi } from '@/api/writingDesk'
import { writingApi } from '@/api/writing'
import type { Idea, WritingDesk } from '@/types/writingDesk'
import type { ChapterMeta } from '@/types/writing'
import { notebookKey } from '@/utils/studio'
import { createLegacyIdeas, ideaSignature } from '@/utils/ideas'
import { useWritingDeskStore } from '@/stores/writingDesk'

interface IdeaDraft { idea: Idea; baseline: string }
interface LocalRecord { draft: IdeaDraft | null; pending: WritingDesk | null }
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
const storageKey = (id: number) => `ink-ideas-draft-${id}`
const failureMessage = (e: any) => e?.response?.status === 409
  ? '灵感或创作便签已在另一处更新。请读取最新版本并比较，当前草稿仍保留。'
  : e?.response?.data?.message || e?.message || '保存失败，请重试。'

export const useIdeasStore = defineStore('ideas', () => {
  const deskStore = useWritingDeskStore()
  const novelId = ref(0), data = ref<WritingDesk | null>(null)
  const chapters = ref<ChapterMeta[]>([])
  const draft = ref<IdeaDraft | null>(null), pending = ref<WritingDesk | null>(null)
  const loading = ref(false), saving = ref(false), error = ref(''), storageError = ref('')
  const legacyText = ref(''), legacyError = ref(''), compared = ref(false)
  const rawRecovery = ref('')
  const localReadBlocked = ref(false)
  const exportedSnapshot = ref('')
  const memory = new Map<number, { record: LocalRecord; raw: string; storageError: string; readBlocked: boolean }>()
  let generation = 0, readSequence = 0, restoring = false
  const ideas = computed(() => data.value?.ideas || [])
  const latestIdea = computed(() => ideas.value.find(row => row.uid === draft.value?.idea.uid))
  const conflicted = computed(() => !!draft.value && draft.value.baseline !== ideaSignature(latestIdea.value))
  const legacyIdeas = computed(() => createLegacyIdeas(legacyText.value, novelId.value))
  const unmigrated = computed(() => legacyIdeas.value.filter(row => !ideas.value.some(existing => existing.uid === row.uid || existing.sourceKey === row.sourceKey)))
  const localSignature = () => JSON.stringify({ draft: draft.value, pending: pending.value })
  const unsafe = computed(() => !!storageError.value && (!!draft.value || !!pending.value) && exportedSnapshot.value !== localSignature())
  function markExported() { exportedSnapshot.value = localSignature() }
  const active = (id: number, epoch: number) => novelId.value === id && generation === epoch
  watch(() => deskStore.data, value => {
    if (value && deskStore.novelId === novelId.value && data.value && value.version > data.value.version)
      data.value = clone(value)
  }, { flush: 'sync' })
  function acceptSnapshot(id: number, value: WritingDesk) {
    deskStore.acceptExternal(id, value)
    const shared = deskStore.novelId === id ? deskStore.data : null
    data.value = clone(shared && shared.version > value.version ? shared : value)
  }

  function persist() {
    if (!novelId.value || restoring) return
    const record = clone({ draft: draft.value, pending: pending.value })
    try {
      // A malformed record remains available for export until explicitly recovered.
      if (localReadBlocked.value) throw Error('原草稿尚未能读取，请恢复浏览器存储权限后重新读取。')
      if (rawRecovery.value) throw Error('原草稿记录已保留，请先导出恢复文件。')
      if (!record.draft && !record.pending) localStorage.removeItem(storageKey(novelId.value))
      else localStorage.setItem(storageKey(novelId.value), JSON.stringify(record))
      storageError.value = ''
    } catch (e) {
      storageError.value = e instanceof Error && e.message.includes('原草稿')
        ? e.message : '浏览器草稿暂存失败。请保持页面打开，保存到作品或导出后再离开。'
    }
    memory.set(novelId.value, { record, raw: rawRecovery.value, storageError: storageError.value, readBlocked: localReadBlocked.value })
  }
  watch(draft, persist, { deep: true, flush: 'sync' })

  function readLocal(id: number) {
    restoring = true
    draft.value = null; pending.value = null; rawRecovery.value = ''; localReadBlocked.value = false
    storageError.value = ''; legacyError.value = ''; legacyText.value = ''
    const cached = memory.get(id)
    if (cached) { draft.value = clone(cached.record.draft); pending.value = clone(cached.record.pending); rawRecovery.value = cached.raw; storageError.value = cached.storageError; localReadBlocked.value = cached.readBlocked }
    else {
      try {
        const raw = localStorage.getItem(storageKey(id))
        if (raw) {
          rawRecovery.value = raw
          const local = JSON.parse(raw) as LocalRecord
          if (!local || typeof local !== 'object' ||
            (local.draft && (!validIdea(local.draft.idea) || typeof local.draft.baseline !== 'string')) ||
            (local.pending && (!Number.isInteger(local.pending.version) || !Array.isArray(local.pending.ideas) || !local.pending.ideas.every(validIdea) || !local.pending.mutationId)))
            throw Error('invalid draft')
          draft.value = local.draft || null; pending.value = local.pending || null
          rawRecovery.value = ''
        }
      } catch { localReadBlocked.value = !rawRecovery.value; storageError.value = '未能读取本机草稿。原记录未改动，请重新读取或导出恢复文件。' }
    }
    try { legacyText.value = localStorage.getItem(notebookKey(id)) || '' }
    catch { legacyError.value = '未能读取本机旧便笺，请恢复浏览器存储权限后重新读取。' }
    restoring = false
  }
  function validIdea(row: any): row is Idea {
    return !!row && typeof row.uid === 'string' && typeof row.title === 'string' &&
      typeof row.body === 'string' && typeof row.category === 'string' &&
      Array.isArray(row.chapterUids) && row.chapterUids.every((uid: any) => typeof uid === 'string')
  }
  async function load(id: number) {
    if (novelId.value === id && (data.value || loading.value)) return
    if (novelId.value) persist()
    ++generation; ++readSequence
    novelId.value = id; data.value = null; chapters.value = []; saving.value = false
    exportedSnapshot.value = ''
    compared.value = false; error.value = ''
    readLocal(id)
    if (draft.value || pending.value) persist()
    await reload(false)
  }
  async function reload(forComparison = true) {
    if (saving.value) throw Error('请等待当前保存完成')
    const id = novelId.value, epoch = generation, sequence = ++readSequence
    if (!id) return
    loading.value = true; error.value = ''
    try {
      const [desk, workspace] = await Promise.all([writingDeskApi.get(id), writingApi.workspace(id)])
      if (!active(id, epoch) || sequence !== readSequence) return
      acceptSnapshot(id, desk); chapters.value = workspace.chapters
      if (localReadBlocked.value) {
        try {
          rawRecovery.value = localStorage.getItem(storageKey(id)) || ''
          localReadBlocked.value = false
        } catch { /* Do not write over an unreadable earlier draft. */ }
      }
      if (forComparison) {
        // Only an explicit read releases a request with an unknown outcome.
        pending.value = null
        compared.value = true
        persist()
      }
      try { legacyText.value = localStorage.getItem(notebookKey(id)) || ''; legacyError.value = '' }
      catch { legacyError.value = '未能读取本机旧便笺，原内容未改动。' }
    } catch (e) {
      if (active(id, epoch) && sequence === readSequence) error.value = failureMessage(e)
      throw e
    } finally { if (active(id, epoch) && sequence === readSequence) loading.value = false }
  }
  function edit(idea?: Idea) {
    if (draft.value) throw Error('请先保存或暂存当前草稿，使用“继续草稿”恢复。')
    const now = new Date().toISOString()
    draft.value = { idea: idea ? clone(idea) : { uid: crypto.randomUUID(), title: '', body: '', category: 'other', chapterUids: [], createdAt: now, updatedAt: now }, baseline: ideaSignature(idea) }
    compared.value = false; error.value = ''
  }
  function discardDraft() {
    if (saving.value || pending.value) throw Error('请先确认上次保存结果，再丢弃草稿。')
    draft.value = null; compared.value = false; persist()
  }
  function adoptLatest() {
    if (saving.value || pending.value) throw Error('请先读取最新版本。')
    draft.value = latestIdea.value ? { idea: clone(latestIdea.value), baseline: ideaSignature(latestIdea.value) } : null
    compared.value = false; persist()
  }
  async function send(payload: WritingDesk) {
    const id = novelId.value, epoch = generation
    pending.value = clone(payload); persist()
    saving.value = true; error.value = ''
    try {
      const result = await writingDeskApi.save(id, clone(payload))
      if (!active(id, epoch)) return false
      acceptSnapshot(id, result); pending.value = null
      const accepted = result.ideas?.find(row => row.uid === draft.value?.idea.uid)
      if (draft.value && ideaSignature(accepted) === ideaSignature(draft.value.idea)) draft.value = null
      compared.value = false; persist()
      return true
    } catch (e) {
      if (active(id, epoch)) { error.value = failureMessage(e); persist() }
      throw e
    } finally { if (active(id, epoch)) saving.value = false }
  }
  function requireReady() {
    if (loading.value || saving.value || !data.value) throw Error('请等待灵感载入或保存完成。')
    if (pending.value) throw Error('上次保存结果尚未确认，请重试原请求，或读取最新版本。')
  }
  async function saveDraft(overwrite = false) {
    requireReady()
    if (!draft.value?.idea.body.trim()) throw Error('请写下灵感内容。')
    if (conflicted.value && !(overwrite && compared.value)) throw Error('请先读取最新版本并比较。')
    const value = clone(draft.value.idea)
    value.updatedAt = new Date().toISOString()
    const payload = clone(data.value!)
    payload.ideas = [...ideas.value.filter(row => row.uid !== value.uid), value]
    payload.mutationId = crypto.randomUUID()
    return send(payload)
  }
  async function migrateLegacy() {
    requireReady()
    if (!unmigrated.value.length) return true
    const payload = clone(data.value!)
    payload.ideas = [...ideas.value, ...clone(unmigrated.value)]
    payload.mutationId = crypto.randomUUID()
    return send(payload)
  }
  async function retryPending() {
    if (!pending.value || saving.value || loading.value) return false
    return send(clone(pending.value))
  }
  return { novelId, data, chapters, ideas, draft, pending, loading, saving, error, storageError,
    legacyText, legacyError, legacyIdeas, unmigrated, rawRecovery, compared, conflicted, latestIdea, unsafe,
    load, reload, edit, persist, saveDraft, discardDraft, adoptLatest, migrateLegacy, retryPending, markExported }
})
