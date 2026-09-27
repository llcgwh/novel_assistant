import { ref } from 'vue'
import { defineStore } from 'pinia'
import { writingDeskApi } from '@/api/writingDesk'
import type {
  WritingDesk,
  NextPen,
  RevisionTask,
  RevisionRound,
  ReaderBookmark,
} from '@/types/writingDesk'

const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
function contentKey(value: WritingDesk) {
  const normalized = clone(value)
  delete normalized.mutationId
  // Forms may refresh their edit timestamp when the user clicks retry.
  if (normalized.nextPen) normalized.nextPen.updatedAt = ''
  for (const task of normalized.tasks) task.updatedAt = ''
  for (const round of normalized.rounds || []) round.updatedAt = ''
  return JSON.stringify(normalized)
}
const message = (failure: any) =>
  failure?.response?.status === 409
    ? '创作便签已在另一处更新。请重新载入后检查差异，再决定保存；当前输入仍保留。'
    : failure?.response?.data?.message ||
      failure?.message ||
      '创作便签保存失败，请重试'

export const useWritingDeskStore = defineStore('writingDesk', () => {
  const novelId = ref(0),
    data = ref<WritingDesk | null>(null)
  const loading = ref(false),
    saving = ref(false),
    error = ref('')
  let generation = 0,
    readSequence = 0
  let pending: WritingDesk | undefined
  let pendingContent = ''
  const active = (id: number, epoch: number) =>
    id === novelId.value && epoch === generation

  async function reload() {
    const id = novelId.value,
      epoch = generation,
      read = ++readSequence
    if (!id) throw Error('尚未打开作品')
    if (saving.value) throw Error('请等待当前便签保存完成')
    loading.value = true
    error.value = ''
    try {
      const value = await writingDeskApi.get(id)
      if (!active(id, epoch) || read !== readSequence)
        throw Error('作品已切换，已停止载入便签')
      data.value = value
      pending = undefined
      pendingContent = ''
    } catch (failure) {
      if (active(id, epoch) && read === readSequence)
        error.value = message(failure)
      throw failure
    } finally {
      if (active(id, epoch) && read === readSequence) loading.value = false
    }
  }
  async function load(id: number) {
    $reset()
    novelId.value = id
    await reload()
  }
  async function mutate(update: (draft: WritingDesk) => void) {
    const id = novelId.value,
      epoch = generation
    if (loading.value || saving.value)
      throw Error('请等待创作便签载入或保存完成')
    if (!data.value || !id) throw Error('创作便签尚未载入，请重试')
    const draft = clone(data.value)
    delete draft.mutationId
    update(draft)
    const content = contentKey(draft)
    // Reuse the exact mutation after a lost response. A different edit requires
    // an explicit reload before the unknown outcome can be safely superseded.
    if (pending && content !== pendingContent)
      throw Error('上一次保存结果尚未确认，请先重新载入便签，再保存当前输入')
    const payload = pending || { ...draft, mutationId: crypto.randomUUID() }
    pending = clone(payload)
    pendingContent = content
    saving.value = true
    error.value = ''
    try {
      const value = await writingDeskApi.save(id, payload)
      if (!active(id, epoch)) throw Error('作品已切换，已停止更新便签')
      data.value = value
      pending = undefined
      pendingContent = ''
    } catch (failure) {
      if (active(id, epoch)) error.value = message(failure)
      throw failure
    } finally {
      if (active(id, epoch)) saving.value = false
    }
  }
  const saveNextPen = (value: NextPen | null) =>
    mutate((draft) => {
      draft.nextPen = clone(value)
    })
  const saveTask = (task: RevisionTask) =>
    mutate((draft) => {
      const at = draft.tasks.findIndex((row) => row.uid === task.uid)
      if (at < 0) draft.tasks.push(clone(task))
      else draft.tasks[at] = clone(task)
    })
  const saveRound = (round: RevisionRound) =>
    mutate((draft) => {
      const rounds = (draft.rounds ||= [])
      const at = rounds.findIndex((row) => row.uid === round.uid)
      if (at < 0) rounds.push(clone(round))
      else rounds[at] = clone(round)
    })
  const deleteTask = (uid: string) =>
    mutate((draft) => {
      draft.tasks = draft.tasks.filter((row) => row.uid !== uid)
    })
  const saveBookmark = (bookmark: ReaderBookmark) =>
    mutate((draft) => {
      const at = draft.bookmarks.findIndex((row) => row.uid === bookmark.uid)
      if (at < 0) draft.bookmarks.push(clone(bookmark))
      else draft.bookmarks[at] = clone(bookmark)
    })
  const deleteBookmark = (uid: string) =>
    mutate((draft) => {
      draft.bookmarks = draft.bookmarks.filter((row) => row.uid !== uid)
    })
  function $reset() {
    generation++
    readSequence++
    novelId.value = 0
    data.value = null
    loading.value = false
    saving.value = false
    error.value = ''
    pending = undefined
    pendingContent = ''
  }
  return {
    novelId,
    data,
    loading,
    saving,
    error,
    load,
    reload,
    saveNextPen,
    saveTask,
    saveRound,
    deleteTask,
    saveBookmark,
    deleteBookmark,
    $reset,
  }
})
