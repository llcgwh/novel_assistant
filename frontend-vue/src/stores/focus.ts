import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { writingApi } from '@/api/writing'
import type { WritingStats } from '@/types/writing'
import { createFocusState, readFocusState, tickFocus, startFocus, pauseFocus, resetFocus, focusReceiptKey } from '@/utils/focusTimer'

export const useFocusStore = defineStore('focus', () => {
  const novelId = ref(0), state = ref(createFocusState()), localSafe = ref(true), storageError = ref(''), syncError = ref(''), saving = ref(false), blocked = ref(false), stats = ref<WritingStats | null>(null)
  const running = computed(() => state.value.deadline > 0)
  let interval: ReturnType<typeof setInterval> | undefined
  let generation = 0
  let lastStored = ''
  let lastAttempt = 0
  const key = (id = novelId.value) => `ink-studio-timer-${id}`
  const errorMessage = (e: any) => e?.response?.data?.message || e?.message || '请重试'

  function reconcileExternal() {
    if (!novelId.value || !localSafe.value || blocked.value) return
    const raw = localStorage.getItem(key())
    if (!raw || raw === lastStored) return
    const external = readFocusState(raw)
    // This tab may still be awaiting acknowledgement of older receipts.
    // Adopt the latest running period while retaining every unacknowledged payload.
    const pending = new Map(external.pending.map((receipt) => [focusReceiptKey(receipt), receipt]))
    for (const receipt of state.value.pending) {
      const signature = focusReceiptKey(receipt)
      if (!pending.has(signature)) pending.set(signature, receipt)
    }
    external.pending = [...pending.values()]
    if (new Set(external.pending.map((receipt) => receipt.uid)).size < external.pending.length)
      syncError.value = '不同窗口记录了同一专注时段的不同结束结果，两份记录均已保留；请重试核对服务器结果。'
    state.value = external
    lastStored = raw
  }

  function persist() {
    if (!novelId.value || blocked.value) return false
    try {
      const serialized = JSON.stringify(state.value)
      if (serialized !== lastStored || !localSafe.value) localStorage.setItem(key(), serialized)
      lastStored = serialized
      storageError.value = ''
      localSafe.value = true
      return true
    } catch {
      localSafe.value = false
      storageError.value = '专注记录尚未存入本机，请保持页面打开并重试保存。'
      return false
    }
  }

  async function sync() {
    if (!novelId.value || blocked.value || saving.value || !state.value.pending.length) return
    const id = novelId.value, epoch = generation
    saving.value = true
    lastAttempt = Date.now()
    try {
      const receipts = JSON.parse(JSON.stringify(state.value.pending)) as typeof state.value.pending
      let conflictMessage = ''
      for (const receipt of receipts) {
        if (epoch !== generation) return
        let result: WritingStats
        try {
          result = await writingApi.focus(id, receipt)
        } catch (e: any) {
          if (epoch !== generation) return
          if (e?.response?.status === 409) {
            conflictMessage = '专注记录与服务器终态冲突，无法用同一时段编号覆盖。冲突原件已保留，可导出恢复；其他时段继续上传。'
            continue
          }
          throw e
        }
        if (epoch !== generation) return
        reconcileExternal()
        if (!stats.value || result.version >= stats.value.version) stats.value = result
        state.value.pending = state.value.pending.filter((item) => focusReceiptKey(item) !== focusReceiptKey(receipt))
        persist()
      }
      syncError.value = conflictMessage || (state.value.pending.length ? syncError.value : '')
    } catch (e) {
      if (epoch === generation) syncError.value = `专注统计尚未上传：${errorMessage(e)}。原记录已保留，可重试。`
    } finally {
      if (epoch === generation) saving.value = false
    }
  }

  function tick(now = Date.now()) {
    if (blocked.value || !novelId.value) return
    try { reconcileExternal() } catch { /* Keep the in-memory period and let persistence report failures. */ }
    tickFocus(state.value, now)
    persist()
    if (state.value.pending.length && !syncError.value && now - lastAttempt > 5000) void sync()
  }

  function load(id: number) {
    if (id === novelId.value && !blocked.value) { tick(); return }
    if (novelId.value && !blocked.value && !persist()) return false
    generation++
    novelId.value = id
    stats.value = null
    syncError.value = ''
    saving.value = false
    lastAttempt = 0
    lastStored = ''
    try {
      const raw = localStorage.getItem(key())
      state.value = readFocusState(raw)
      lastStored = raw || JSON.stringify(state.value)
      blocked.value = false
      localSafe.value = true
      tick()
      if (typeof window !== 'undefined' && !interval) {
        interval = setInterval(() => tick(), 500)
        window.addEventListener('beforeunload', beforeUnload)
        window.addEventListener('online', retry)
        window.addEventListener('storage', changedElsewhere)
      }
    } catch (e) {
      blocked.value = true
      // The unreadable original was not changed; there is no unsaved new period.
      localSafe.value = true
      storageError.value = `${errorMessage(e)} 请保留页面并重试读取。`
    }
    return !blocked.value
  }

  function toggle() {
    if (blocked.value) return
    try { reconcileExternal() } catch { /* Preserve this tab's current period. */ }
    if (running.value) pauseFocus(state.value)
    else startFocus(state.value)
    persist()
    void sync()
  }
  function reset(duration = state.value.duration) {
    if (blocked.value) return
    try { reconcileExternal() } catch { /* Preserve this tab's current period. */ }
    resetFocus(state.value, Date.now(), duration)
    persist()
    void sync()
  }
  function retry() {
    if (blocked.value) load(novelId.value)
    else { persist(); void sync() }
  }
  function exportData() {
    let original: string | null = null
    if (blocked.value) { try { original = localStorage.getItem(key()) } catch {} }
    return JSON.stringify({ schemaVersion: 1, novelId: novelId.value, exportedAt: new Date().toISOString(), state: state.value, ...(original ? { original } : {}) }, null, 2)
  }
  function download() {
    const url = URL.createObjectURL(new Blob([exportData()], { type: 'application/json;charset=utf-8' }))
    const link = document.createElement('a')
    link.href = url
    link.download = `专注记录-${novelId.value}.json`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  }
  function beforeUnload(event: BeforeUnloadEvent) {
    tick()
    if (!localSafe.value) { event.preventDefault(); event.returnValue = '' }
  }
  function changedElsewhere(event: StorageEvent) {
    if (event.key !== key() || !localSafe.value || !event.newValue) return
    try {
      reconcileExternal()
      tick()
    } catch { /* Preserve this tab's valid state if another writer is incompatible. */ }
  }
  function $reset() {
    generation++
    clearInterval(interval)
    interval = undefined
    if (typeof window !== 'undefined') {
      window.removeEventListener('beforeunload', beforeUnload)
      window.removeEventListener('online', retry)
      window.removeEventListener('storage', changedElsewhere)
    }
    novelId.value = 0
    state.value = createFocusState()
    localSafe.value = true
    blocked.value = false
    stats.value = null
    saving.value = false
    storageError.value = ''
    syncError.value = ''
    lastStored = ''
  }
  return { novelId, state, running, localSafe, storageError, syncError, saving, blocked, stats, load, tick, toggle, reset, retry, persist, sync, exportData, download, $reset }
})
