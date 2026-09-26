import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { writingApi } from '@/api/writing'
import { request } from '@/api/request'
import type {
  Chapter,
  WritingWorkspace,
  DraftRecord,
  Resource,
  SessionRecord,
} from '@/types/writing'
import {
  emptyDoc,
  fingerprint,
  documentText,
  wordCount,
  resourceNames,
} from '@/utils/writing'
import { saveDraft, deleteDraft, listDrafts } from '@/utils/writingDrafts'
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value))
export const useWritingStore = defineStore('writing', () => {
  const novelId = ref(0),
    workspace = ref<WritingWorkspace | null>(null),
    current = ref<Chapter | null>(null),
    resources = ref<Resource[]>([]),
    resourceErrors = ref<string[]>([])
  const loading = ref(false),
    saving = ref(false),
    dirty = ref(false),
    error = ref(''),
    localError = ref(''),
    localSafe = ref(true),
    recovery = ref<DraftRecord | null>(null),
    conflict = ref<Chapter | null>(null),
    drafts = ref<DraftRecord[]>([])
  const activeBlock = ref(''),
    contextText = ref(''),
    selectedText = ref(''),
    contextTab = ref('context'),
    pinned = ref<number[]>([]),
    ignored = ref<number[]>([])
  let generation = 0,
    selectionGeneration = 0,
    timer: ReturnType<typeof setTimeout> | undefined,
    savePromise: Promise<boolean> | undefined,
    localPromise: Promise<void> = Promise.resolve(),
    pendingWrite: Chapter | undefined
  let baseline = '',
    tabId = '',
    localSequence = 0
  try {
    tabId = sessionStorage.getItem('ink-writing-tab') || crypto.randomUUID()
    sessionStorage.setItem('ink-writing-tab', tabId)
  } catch {
    tabId = crypto.randomUUID()
  }
  const totalWords = computed(
    () =>
      workspace.value?.chapters
        .filter((c) => !c.deleted)
        .reduce(
          (n, c) =>
            n +
            (current.value?.uid === c.uid
              ? current.value.wordCount
              : c.wordCount),
          0,
        ) || 0,
  )
  const mentions = computed(() =>
    resources.value
      .filter(
        (r) =>
          r.type === 'characters' &&
          !ignored.value.includes(r.id) &&
          [r.name, ...(r.aliases || [])].some(
            (name) =>
              name.trim().length > 0 && contextText.value.includes(name),
          ),
      )
      .slice(0, 8),
  )
  function key(uid: string) {
    return `${novelId.value}:${workspace.value!.uid}:${uid}:${tabId}`
  }
  function isContext(id: number, epoch: number) {
    return novelId.value === id && generation === epoch
  }
  function requireContext(id: number, epoch: number) {
    if (!isContext(id, epoch)) throw Error('作品已切换，已停止后续操作')
  }
  async function load(id: number) {
    const epoch = ++generation
    novelId.value = id
    loading.value = true
    error.value = ''
    try {
      const data = await writingApi.workspace(id)
      if (epoch !== generation) return
      workspace.value = data
      const found = await listDrafts(data.uid, id).catch(() => [])
      if (!isContext(id, epoch)) return
      drafts.value = found
      void loadResources(id, epoch)
    } catch (e) {
      if (epoch === generation) error.value = message(e)
    } finally {
      if (epoch === generation) loading.value = false
    }
  }
  async function refresh() {
    const id = novelId.value,
      epoch = generation
    const data = await writingApi.workspace(id)
    if (epoch === generation) workspace.value = data
  }
  async function loadResources(id = novelId.value, epoch = generation) {
    const groups = await Promise.allSettled(
      Object.keys(resourceNames).map((type) =>
        request.get<any, any[]>(
          `/novels/${id}/${type === 'map' ? 'map-locations' : type === 'timeline' ? 'timeline-events' : type}`,
        ),
      ),
    )
    if (epoch !== generation) return
    const rows: Resource[] = []
    const failures: string[] = []
    groups.forEach((result, i) => {
      const type = Object.keys(resourceNames)[i]
      if (result.status === 'rejected') {
        failures.push(resourceNames[type])
        return
      }
      for (const raw of result.value) {
        rows.push({
          id: raw.id,
          type,
          name: raw.name || raw.title,
          detail:
            raw.personality ||
            raw.description ||
            raw.content ||
            raw.eventTime ||
            '',
          image: raw.portraitImage || raw.sceneImage,
          aliases: workspace.value?.preferences.aliases?.[raw.id] || [],
          raw,
        })
      }
    })
    resources.value = rows
    resourceErrors.value = failures
  }
  function message(e: any) {
    return e?.response?.data?.message || e?.message || '操作失败，请重试'
  }
  function syncMeta(c: Chapter) {
    if (!workspace.value) return
    const index = workspace.value.chapters.findIndex((row) => row.uid === c.uid)
    const { doc, notes, ...meta } = clone(c)
    if (index < 0) workspace.value.chapters.push(meta)
    else workspace.value.chapters[index] = meta
  }
  function adopt(c: Chapter) {
    current.value = clone(c)
    baseline = fingerprint(c)
    dirty.value = false
    pendingWrite = undefined
    syncMeta(c)
  }
  async function select(uid: string) {
    const id = novelId.value,
      epoch = generation
    if (
      current.value?.uid === uid &&
      (dirty.value ||
        recovery.value ||
        conflict.value ||
        workspace.value?.chapters.find((c) => c.uid === uid)?.revision ===
          current.value.revision)
    )
      return true
    if (!(await flush()) && !localSafe.value) return false
    if (!isContext(id, epoch)) return false
    const turn = ++selectionGeneration
    loading.value = true
    error.value = ''
    clearTimeout(timer)
    try {
      const server = await writingApi.chapter(id, uid)
      if (epoch !== generation || turn !== selectionGeneration) return false
      current.value = server
      baseline = fingerprint(server)
      dirty.value = false
      conflict.value = null
      pendingWrite = undefined
      recovery.value = null
      localSafe.value = true
      activeBlock.value = ''
      contextText.value = ''
      selectedText.value = ''
      const found = await listDrafts(workspace.value!.uid, novelId.value)
      if (epoch !== generation || turn !== selectionGeneration) return false
      drafts.value = found
      const local = found.find(
        (d) => d.chapter.uid === uid && fingerprint(d.chapter) !== baseline,
      )
      if (local) recovery.value = local
      try {
        localStorage.setItem(`ink-writing-last-${workspace.value!.uid}`, uid)
      } catch {}
      return true
    } catch (e) {
      if (epoch === generation) error.value = message(e)
      return false
    } finally {
      if (epoch === generation && turn === selectionGeneration)
        loading.value = false
    }
  }
  function changed() {
    if (!current.value) return
    current.value.wordCount = wordCount(documentText(current.value.doc))
    dirty.value = fingerprint(current.value) !== baseline
    syncMeta(current.value)
    void persistLocal()
    clearTimeout(timer)
    if (dirty.value && !recovery.value && !conflict.value)
      timer = setTimeout(() => void flush(), 1200)
  }
  function persistLocal() {
    if (!current.value || !workspace.value) return Promise.resolve()
    // Until the user chooses a version, current contains the server copy.
    // Writing it under this tab's key would destroy the recoverable draft.
    if (recovery.value) return localPromise
    const record: DraftRecord = {
      key: key(current.value.uid),
      bookUid: workspace.value.uid,
      novelId: novelId.value,
      chapter: clone(current.value),
      updatedAt: Date.now(),
      tabId,
    }
    const epoch = generation,
      sequence = ++localSequence
    localSafe.value = false
    localPromise = localPromise
      .catch(() => {})
      .then(() => saveDraft(record))
      .then(() => {
        if (epoch === generation && sequence === localSequence) {
          localSafe.value = true
          localError.value = ''
        }
      })
      .catch(() => {
        if (epoch === generation && sequence === localSequence) {
          localError.value =
            '浏览器草稿保存失败，请保持页面打开并立即导出正文。'
          localSafe.value = false
        }
      })
    return localPromise
  }
  function flush(checkpoint = false): Promise<boolean> {
    const epoch = generation,
      id = novelId.value
    clearTimeout(timer)
    if (savePromise)
      return savePromise.then((ok) =>
        !isContext(id, epoch)
          ? false
          : ok && (dirty.value || checkpoint)
            ? flush(checkpoint)
            : ok,
      )
    if (recovery.value || conflict.value)
      return persistLocal().then(() => false)
    if (!current.value || (!dirty.value && !checkpoint))
      return Promise.resolve(true)
    const uid = current.value.uid,
      draftKey = key(uid)
    saving.value = true
    error.value = ''
    const run = async () => {
      try {
        await persistLocal()
        if (!isContext(id, epoch) || current.value?.uid !== uid) return false
        const snapshot = pendingWrite || {
          ...clone(current.value),
          mutationId: crypto.randomUUID(),
        }
        pendingWrite = snapshot
        const saved = await writingApi.save(id, { ...snapshot, checkpoint })
        if (!isContext(id, epoch) || current.value?.uid !== uid) return false
        baseline = fingerprint(saved)
        pendingWrite = undefined
        current.value.revision = saved.revision
        current.value.updatedAt = saved.updatedAt
        current.value.mutationId = saved.mutationId
        dirty.value = fingerprint(current.value) !== baseline
        syncMeta(current.value)
        if (!dirty.value) {
          const sequence = localSequence
          // Serialize deletion with local writes. An edit queued after this
          // acknowledgement must remain recoverable even if its upload fails.
          localPromise = localPromise.then(async () => {
            if (
              isContext(id, epoch) &&
              current.value?.uid === uid &&
              sequence === localSequence &&
              !dirty.value
            ) {
              await deleteDraft(draftKey).catch(() => {})
              if (isContext(id, epoch) && sequence === localSequence)
                localSafe.value = true
            }
          })
          await localPromise
        } else await persistLocal()
        return isContext(id, epoch) && current.value?.uid === uid
      } catch (e: any) {
        if (epoch === generation) {
          error.value = message(e)
          if (e?.response?.status === 409) {
            pendingWrite = undefined
            const server = await writingApi.chapter(id, uid).catch(() => null)
            if (isContext(id, epoch) && current.value?.uid === uid)
              conflict.value = server
          }
        }
        return false
      } finally {
        if (epoch === generation) saving.value = false
      }
    }
    const pending = run().finally(() => {
      if (savePromise === pending) savePromise = undefined
    })
    savePromise = pending
    return pending.then((ok) =>
      !isContext(id, epoch)
        ? false
        : ok && dirty.value
          ? flush(checkpoint)
          : ok,
    )
  }
  async function recover(useLocal: boolean) {
    if (!current.value || !recovery.value) return
    const found = recovery.value,
      id = novelId.value,
      epoch = generation,
      uid = current.value.uid,
      draftKey = key(uid)
    recovery.value = null
    if (useLocal) {
      const server = clone(current.value)
      current.value = clone(found.chapter)
      current.value.revision = server.revision
      baseline = fingerprint(server)
      if (found.chapter.revision !== server.revision) conflict.value = server
      changed()
      await persistLocal()
      if (!isContext(id, epoch) || current.value?.uid !== uid) return
      if (localSafe.value && found.key !== draftKey)
        await deleteDraft(found.key)
    } else await deleteDraft(found.key)
  }
  async function resolveConflict(choice: 'server' | 'local' | 'copy') {
    if (!current.value || !conflict.value) return
    const server = clone(conflict.value),
      local = clone(current.value),
      id = novelId.value,
      epoch = generation,
      draftKey = key(local.uid)
    pendingWrite = undefined
    if (choice === 'copy') {
      const created = await writingApi.create(id, {
        ...local,
        uid: crypto.randomUUID(),
        title: `${local.title}（冲突副本）`,
        deleted: false,
      })
      requireContext(id, epoch)
      await refresh()
      requireContext(id, epoch)
      if (
        current.value?.uid !== local.uid ||
        fingerprint(current.value) !== fingerprint(local)
      )
        throw Error('冲突副本已创建；当前稿件又有变化，请重新比较')
      conflict.value = null
      current.value = server
      baseline = fingerprint(server)
      dirty.value = false
      await deleteDraft(draftKey)
      requireContext(id, epoch)
      await select(created.uid)
    } else if (choice === 'server') {
      conflict.value = null
      current.value = server
      baseline = fingerprint(server)
      dirty.value = false
      syncMeta(server)
      await deleteDraft(draftKey)
    } else {
      conflict.value = null
      current.value.revision = server.revision
      baseline = fingerprint(server)
      changed()
      await flush(true)
    }
  }
  async function create(
    title: string,
    volumeId: string | null = null,
    extras: Partial<Chapter> = {},
  ) {
    const id = novelId.value,
      epoch = generation
    if (!(await flush()) && !localSafe.value) throw Error(localError.value)
    requireContext(id, epoch)
    const row = await writingApi.create(id, {
      uid: crypto.randomUUID(),
      doc: emptyDoc(),
      links: [],
      ...extras,
      title,
      volumeId,
    })
    requireContext(id, epoch)
    await refresh()
    requireContext(id, epoch)
    await select(row.uid)
    requireContext(id, epoch)
    return row
  }
  async function structure(
    volumes = workspace.value!.volumes,
    chapters = workspace.value!.chapters,
  ) {
    const id = novelId.value,
      epoch = generation
    if (!(await flush())) throw Error('请先处理未保存的正文')
    requireContext(id, epoch)
    const before = current.value ? clone(current.value) : null
    const data = await writingApi.structure(id, {
      structureVersion: workspace.value!.structureVersion,
      volumes,
      chapters,
    })
    requireContext(id, epoch)
    workspace.value = data
    if (before && current.value?.uid === before.uid) {
      const server = await writingApi.chapter(id, before.uid)
      requireContext(id, epoch)
      if (
        current.value?.uid !== before.uid ||
        current.value.revision !== before.revision
      )
        return
      // Structure responses contain metadata only. Never attach their newer
      // revision to old prose without checking the complete server chapter.
      if (
        fingerprint({ ...server, volumeId: before.volumeId }) !==
        fingerprint(before)
      ) {
        conflict.value = server
        error.value = '服务器正文已变化，请比较并处理冲突'
        clearTimeout(timer)
        await persistLocal()
      } else {
        Object.assign(current.value, {
          volumeId:
            current.value.volumeId === before.volumeId
              ? server.volumeId
              : current.value.volumeId,
          position: server.position,
          revision: server.revision,
        })
        baseline = fingerprint(server)
        dirty.value = fingerprint(current.value) !== baseline
        syncMeta(current.value)
        if (dirty.value) await persistLocal()
      }
    }
  }
  async function preferences(value: Record<string, any>) {
    const id = novelId.value,
      epoch = generation
    const data = await writingApi.preferences(id, {
      structureVersion: workspace.value!.structureVersion,
      preferences: value,
    })
    requireContext(id, epoch)
    workspace.value = data
    await loadResources(id, epoch)
  }
  async function saveSession(record: SessionRecord, id = novelId.value) {
    const epoch = generation
    await writingApi.session(id, clone(record))
    if (isContext(id, epoch) && workspace.value) {
      const i = workspace.value.sessions.findIndex((r) => r.uid === record.uid)
      if (i >= 0) workspace.value.sessions[i] = clone(record)
      else workspace.value.sessions.push(clone(record))
    }
  }
  function $reset() {
    generation++
    selectionGeneration++
    clearTimeout(timer)
    novelId.value = 0
    workspace.value = null
    current.value = null
    resources.value = []
    loading.value = false
    saving.value = false
    dirty.value = false
    error.value = ''
    localError.value = ''
    localSafe.value = true
    conflict.value = null
    recovery.value = null
    drafts.value = []
    pinned.value = []
    ignored.value = []
    pendingWrite = undefined
    savePromise = undefined
    baseline = ''
  }
  return {
    novelId,
    workspace,
    current,
    resources,
    resourceErrors,
    loading,
    saving,
    dirty,
    error,
    localError,
    localSafe,
    recovery,
    conflict,
    drafts,
    activeBlock,
    contextText,
    selectedText,
    contextTab,
    pinned,
    ignored,
    totalWords,
    mentions,
    load,
    refresh,
    loadResources,
    select,
    changed,
    persistLocal,
    flush,
    recover,
    resolveConflict,
    create,
    structure,
    preferences,
    saveSession,
    adopt,
    $reset,
  }
})
