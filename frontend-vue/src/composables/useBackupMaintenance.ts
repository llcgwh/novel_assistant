import { computed, ref } from 'vue'
import { backupMaintenanceApi } from '@/api/backupMaintenance'
import type { BackupOperation, BackupStorage, BackupRetention, CleanupPreview, CleanupResult, CleanupSubmission } from '@/types/backupMaintenance'

function message(error: unknown, fallback: string): string {
  const failure = error as { response?: { data?: { message?: string } }; message?: string }
  return failure?.response?.data?.message || failure?.message || fallback
}
function status(error: unknown): number | undefined { return (error as { response?: { status?: number } })?.response?.status }

// Each instance belongs to one mounted novel. Invalidation also handles A → B → A.
export function useBackupMaintenance(novelId: number, api = backupMaintenanceApi, receiptStorage?: Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>) {
  const operations = ref<BackupOperation[]>([]), nextBefore = ref<number | null>(null)
  const historyBusy = ref(false), historyError = ref('')
  const storage = ref<BackupStorage | null>(null), storageBusy = ref(false), storageError = ref('')
  const policy = ref<BackupRetention | null>(null), keepLast = ref(20), keepDays = ref(30)
  const policyBusy = ref(false), policyError = ref(''), policyMessage = ref('')
  const preview = ref<CleanupPreview | null>(null), previewBusy = ref(false), confirmed = ref(false)
  const cleanupError = ref(''), cleanupMessage = ref(''), result = ref<CleanupResult | null>(null)
  const executeBusy = ref(false), uncertain = ref(false), now = ref(Date.now())
  let disposed = false, historySequence = 0, storageSequence = 0, policySequence = 0, previewSequence = 0
  let submission: CleanupSubmission | null = null
  const receiptPrefix = `backup-cleanup-pending:${novelId}:`
  const receiptError = ref('')
  const receipts = () => receiptStorage || globalThis.localStorage
  function restoreReceipt() {
    if (submission) return
    try {
      const storage = receipts()
      if (!storage) return
      const keys = Array.from({ length: storage.length }, (_, i) => storage.key(i)).filter((key): key is string => !!key?.startsWith(receiptPrefix)).sort()
      for (const key of keys) {
        const raw = storage.getItem(key)
        if (!raw) continue
        const parsed = JSON.parse(raw)
        if (typeof parsed.token !== 'string' || !/^[0-9a-f-]{36}$/i.test(parsed.requestId) || parsed.confirmed !== true || key !== receiptPrefix + parsed.requestId) throw new Error('Invalid cleanup receipt')
        submission = { token: parsed.token, requestId: parsed.requestId, confirmed: true }
        uncertain.value = true
        cleanupMessage.value = '有上次尚未确认的清理操作，请读取本次结果。重试沿用原操作编号。'
        receiptError.value = ''
        return
      }
      receiptError.value = ''
    } catch { receiptError.value = '本机清理回执无法读取，原记录仍保留。请先查看永久操作记录核对结果，并恢复浏览器存储后重试。' }
  }
  function clearReceipt(request: CleanupSubmission) {
    try {
      const storage = receipts(), key = receiptPrefix + request.requestId
      const raw = storage?.getItem(key)
      if (raw) {
        const value = JSON.parse(raw)
        if (value.token === request.token && value.requestId === request.requestId) storage.removeItem(key)
      }
    } catch { /* A stale receipt only replays an already completed operation. */ }
  }
  restoreReceipt()
  const policyValid = computed(() => Number.isInteger(keepLast.value) && keepLast.value >= 2 && keepLast.value <= 5000 && Number.isInteger(keepDays.value) && keepDays.value >= 1 && keepDays.value <= 36500)
  const policyDirty = computed(() => !policy.value || keepLast.value !== policy.value.keepLast || keepDays.value !== policy.value.keepDays)
  const expired = computed(() => !!preview.value && (!Number.isFinite(Date.parse(preview.value.expiresAt)) || now.value >= Date.parse(preview.value.expiresAt)))
  const canExecute = computed(() => !!preview.value?.candidates.length && confirmed.value && !expired.value && !executeBusy.value && !previewBusy.value && !policyBusy.value && !policyDirty.value && preview.value?.policy.version === policy.value?.version && !result.value && !uncertain.value && !receiptError.value)

  async function loadHistory(more = false) {
    if (disposed || (more && (historyBusy.value || nextBefore.value === null))) return
    const sequence = ++historySequence, before = more ? nextBefore.value! : undefined
    historyBusy.value = true; historyError.value = ''
    try {
      const page = await api.operations(novelId, before)
      if (disposed || sequence !== historySequence) return
      const all = more ? [...operations.value, ...page.items] : page.items
      operations.value = [...new Map(all.map(item => [item.id, item])).values()]
      nextBefore.value = page.nextBefore ?? null
    } catch (error) { if (!disposed && sequence === historySequence) historyError.value = message(error, '操作记录读取失败，请重试') }
    finally { if (!disposed && sequence === historySequence) historyBusy.value = false }
  }
  async function loadStorage() {
    if (disposed) return
    const sequence = ++storageSequence
    storageBusy.value = true; storageError.value = ''
    try {
      const value = await api.storage(novelId)
      if (!disposed && sequence === storageSequence) storage.value = value
    } catch (error) { if (!disposed && sequence === storageSequence) storageError.value = message(error, '空间占用读取失败，请检查 WebDAV 配置后重试') }
    finally { if (!disposed && sequence === storageSequence) { storageBusy.value = false; await loadHistory() } }
  }
  async function loadPolicy() {
    if (disposed) return
    const sequence = ++policySequence
    policyBusy.value = true; policyError.value = ''
    try {
      const value = await api.retention(novelId)
      if (disposed || sequence !== policySequence) return
      if (preview.value && preview.value.policy.version !== value.version) confirmed.value = false
      policy.value = value; keepLast.value = value.keepLast; keepDays.value = value.keepDays
    } catch (error) { if (!disposed && sequence === policySequence) policyError.value = message(error, '保留策略读取失败，请重试') }
    finally { if (!disposed && sequence === policySequence) policyBusy.value = false }
  }
  async function savePolicy() {
    if (disposed || policyBusy.value || executeBusy.value || uncertain.value || !policy.value || !policyValid.value || !policyDirty.value) return
    const sequence = ++policySequence
    const update = { keepLast: keepLast.value, keepDays: keepDays.value, version: policy.value.version }
    policyBusy.value = true; policyError.value = ''; policyMessage.value = ''
    await cancelPreview(false)
    if (disposed || sequence !== policySequence) return
    try {
      const value = await api.saveRetention(novelId, update)
      if (disposed || sequence !== policySequence) return
      policy.value = value; keepLast.value = value.keepLast; keepDays.value = value.keepDays
      policyMessage.value = '保留策略已保存。不会自动清理，删除前仍需预览并确认。'
    } catch (error) {
      if (disposed || sequence !== policySequence) return
      if (status(error) === 409) {
        await loadPolicy()
        if (!disposed) policyError.value = policyError.value
          ? `其他设备已修改保留策略，但最新策略读取失败：${policyError.value}。请刷新后重试。`
          : '其他设备已修改保留策略，已重新读取。请核对当前值后重新编辑并保存。'
      } else policyError.value = message(error, '策略保存失败，请重试')
    } finally { if (!disposed && sequence === policySequence) policyBusy.value = false }
  }
  async function createPreview() {
    if (disposed) return
    restoreReceipt()
    if (receiptError.value) return
    if (disposed || previewBusy.value || executeBusy.value || uncertain.value || policyBusy.value || !policy.value || policyDirty.value) return
    const sequence = ++previewSequence
    preview.value = null; submission = null; confirmed.value = false; result.value = null
    previewBusy.value = true; cleanupError.value = ''; cleanupMessage.value = ''
    try {
      const value = await api.preview(novelId)
      if (disposed || sequence !== previewSequence) {
        void api.cancel(novelId, value.token).catch(() => {})
        return
      }
      const edited = policyDirty.value
      preview.value = value; now.value = Date.now()
      if (!edited) { policy.value = value.policy; keepLast.value = value.policy.keepLast; keepDays.value = value.policy.keepDays }
    } catch (error) { if (!disposed && sequence === previewSequence) cleanupError.value = message(error, '清理预览失败，未开始删除，请重试') }
    finally {
      if (!disposed && sequence === previewSequence) { previewBusy.value = false; void loadHistory() }
    }
  }
  async function cancelPreview(showMessage = true) {
    if (executeBusy.value || uncertain.value) return
    const sequence = ++previewSequence
    const token = preview.value?.token
    preview.value = null; previewBusy.value = false; confirmed.value = false; submission = null; result.value = null
    cleanupError.value = ''
    if (showMessage) cleanupMessage.value = '已取消清理，未提交删除。'
    if (!token || disposed) return
    try { await api.cancel(novelId, token) }
    catch (error) { if (!disposed && sequence === previewSequence) cleanupError.value = `${message(error, '服务器取消回执读取失败')}。本次未提交删除，预览已关闭。` }
    finally { if (!disposed) void loadHistory() }
  }
  async function execute() {
    if (disposed || executeBusy.value) return
    if (!submission) {
      restoreReceipt()
      // A different tab may have left a pending request since this preview was opened.
      if (submission) return
    }
    if (receiptError.value) return
    now.value = Date.now()
    if (!submission) {
      if (!canExecute.value || !preview.value) return
      const next: CleanupSubmission = { token: preview.value.token, requestId: crypto.randomUUID(), confirmed: true }
      try {
        const storage = receipts()
        if (!storage) throw new Error('浏览器存储不可用')
        storage.setItem(receiptPrefix + next.requestId, JSON.stringify(next))
      } catch { cleanupError.value = '无法保存本机清理操作编号，未提交删除。请恢复浏览器存储后重试。'; return }
      submission = next
    } else if (!uncertain.value) return
    const request = submission
    executeBusy.value = true; cleanupError.value = ''; cleanupMessage.value = ''
    try {
      const value = await api.execute(novelId, request)
      if (disposed || submission !== request) return
      result.value = value
      uncertain.value = value.status === 'RUNNING'
      if (uncertain.value) cleanupMessage.value = '服务器仍在处理本次清理。请稍后读取结果，重试会沿用同一操作编号。'
      else {
        confirmed.value = false; cleanupMessage.value = value.message
        preview.value = null; submission = null; clearReceipt(request)
      }
    } catch (error) {
      if (disposed || submission !== request) return
      if (status(error) === 409 || status(error) === 400 || status(error) === 404) {
        preview.value = null; confirmed.value = false; submission = null; uncertain.value = false; clearReceipt(request)
        cleanupError.value = `${message(error, '预览已失效')}。请重新读取预览后确认。`
      } else {
        uncertain.value = true
        cleanupError.value = `${message(error, '未收到清理结果')}。结果尚未确认，请读取本次结果；会沿用同一操作编号，不另行删除。`
      }
    } finally {
      if (!disposed) { executeBusy.value = false; void loadStorage() }
    }
  }
  async function closeUnknownTracking() {
    if (disposed || executeBusy.value || !uncertain.value || !submission) return
    const request = submission
    try {
      const storage = receipts()
      // Keep the identity for manual reconciliation without offering to replay it as new work.
      storage.setItem(`backup-cleanup-closed:${novelId}:${request.requestId}`, JSON.stringify(request))
      clearReceipt(request)
      if (storage.getItem(receiptPrefix + request.requestId)) throw new Error('回执仍处于待核对状态')
    } catch { receiptError.value = '无法保存结束追踪的记录，本次操作编号仍保留，请恢复浏览器存储后重试。'; return }
    submission = null; uncertain.value = false; preview.value = null; result.value = null; confirmed.value = false
    cleanupError.value = ''; cleanupMessage.value = '已结束本次追踪，结果仍未知；原操作记录会保留。请重新预览核对远端文件，原确认不会再次执行。'
    restoreReceipt()
    if (!uncertain.value) await createPreview()
  }
  async function refresh() {
    if (disposed) return
    restoreReceipt()
    if (!executeBusy.value && !uncertain.value) await cancelPreview(false)
    await Promise.all([loadStorage(), ...(policyBusy.value ? [] : [loadPolicy()])])
  }
  function policyEdited() { confirmed.value = false; policyMessage.value = '' }
  function tick() { now.value = Date.now(); if (expired.value && !uncertain.value) confirmed.value = false }
  function dispose() { disposed = true; ++previewSequence; confirmed.value = false }
  return { operations, nextBefore, historyBusy, historyError, storage, storageBusy, storageError, policy, keepLast, keepDays, policyBusy, policyError, policyMessage, policyValid, policyDirty, receiptError, preview, previewBusy, confirmed, cleanupError, cleanupMessage, result, executeBusy, uncertain, expired, canExecute, loadHistory, loadStorage, loadPolicy, savePolicy, createPreview, cancelPreview, execute, closeUnknownTracking, refresh, policyEdited, tick, dispose }
}
