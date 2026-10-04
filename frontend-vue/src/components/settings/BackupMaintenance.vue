<template>
  <section class="settings-section backup-maintenance" aria-label="同步与备份记录">
    <div class="maintenance-heading"><div><h3>同步与备份记录</h3><p>操作记录保存在本机服务数据库，不自动删除。浏览器未提交的草稿不属于备份。</p></div><button type="button" class="btn-secondary" :disabled="state.executeBusy || state.previewBusy || state.policyBusy" @click="state.refresh">刷新记录与空间</button></div>
    <section class="maintenance-block" aria-label="远端空间占用">
      <h4>远端空间占用</h4>
      <p v-if="state.storageBusy" role="status">正在读取远端空间…</p>
      <p v-if="state.storageError" class="maintenance-error" role="alert">{{ state.storageError }} <button type="button" class="btn-secondary" :disabled="state.storageBusy" @click="state.loadStorage">重试读取空间</button></p>
      <template v-if="state.storage">
        <dl class="storage-grid">
          <div><dt>当前作品快照</dt><dd>{{ formatBytes(state.storage.snapshotBytes) }} <small>／{{ state.storage.snapshotCount }} 份</small></dd></div>
          <div><dt>共享旧版备份（全部保留）</dt><dd>{{ formatBytes(state.storage.legacyBytes) }} <small>／{{ state.storage.legacyCount }} 份</small></dd></div>
          <div><dt>版本连接信息及其他文件</dt><dd>{{ formatBytes(state.storage.metadataBytes) }}</dd></div>
          <div><dt>以上已知大小合计</dt><dd>{{ formatBytes(state.storage.totalBytes) }}</dd></div>
        </dl>
        <p v-if="state.storage.unknownSizeCount">另有 {{ state.storage.unknownSizeCount }} 个文件大小未知，合计不代表全部占用。</p>
        <p v-if="state.storage.quotaAvailableBytes != null || state.storage.quotaUsedBytes != null">服务器报告的账户配额：已用 {{ formatBytes(state.storage.quotaUsedBytes) }}，可用 {{ formatBytes(state.storage.quotaAvailableBytes) }}。配额可能包含其他作品与文件。</p>
        <p v-else>服务器未提供账户配额，无法判断远端剩余空间。</p>
        <p class="maintenance-caption">旧版备份位于共享目录，无法可靠判定所属作品，因此不参与清理。版本连接信息与未识别文件也会保留。</p>
      </template>
    </section>
    <section class="maintenance-block" aria-label="远端历史保留策略">
      <h4>远端历史保留策略</h4><p>按远端修改时间保留最新份数与最近天数内的全部版本；时间无法确认的文件保留。始终保留分支头、恢复基线和版本连接信息。只在你预览并确认后清理，不自动删除。</p>
      <form class="retention-form" @submit.prevent="state.savePolicy">
        <label>至少保留最新<input v-model.number="state.keepLast" type="number" min="2" max="5000" step="1" :disabled="state.policyBusy || state.executeBusy || state.uncertain || !state.policy" @input="state.policyEdited" /><span>份（2–5000）</span></label>
        <label>保留最近<input v-model.number="state.keepDays" type="number" min="1" max="36500" step="1" :disabled="state.policyBusy || state.executeBusy || state.uncertain || !state.policy" @input="state.policyEdited" /><span>天（1–36500）</span></label>
        <button type="submit" class="btn-secondary" :disabled="state.policyBusy || state.executeBusy || state.uncertain || !state.policyValid || !state.policyDirty || !state.policy">{{ state.policyBusy ? '读取／保存中…' : '保存保留策略' }}</button>
      </form>
      <p v-if="!state.policyValid" class="maintenance-error">请填写范围内的整数。</p>
      <p v-if="state.policyMessage" role="status">{{ state.policyMessage }}</p>
      <p v-if="state.policyError" class="maintenance-error" role="alert">{{ state.policyError }} <button v-if="!state.policy" type="button" class="btn-secondary" :disabled="state.policyBusy" @click="state.loadPolicy">重试读取策略</button></p>
      <p v-if="state.policyDirty && state.policy" class="maintenance-caption">策略有未保存更改，保存后可生成新的清理预览。</p>
      <div class="maintenance-actions"><button type="button" class="btn-secondary" :disabled="state.previewBusy || state.executeBusy || state.uncertain || state.policyBusy || state.policyDirty || !state.policy" @click="state.createPreview">{{ state.previewBusy ? '正在核对远端文件…' : '预览远端清理' }}</button><button v-if="state.previewBusy" type="button" class="btn-secondary" @click="state.cancelPreview()">取消预览</button></div>
      <p v-if="state.receiptError" class="maintenance-error" role="alert">{{ state.receiptError }}</p><p v-if="state.cleanupError" class="maintenance-error" role="alert">{{ state.cleanupError }}</p><p v-if="state.cleanupMessage" role="status">{{ state.cleanupMessage }}</p>
      <button v-if="state.uncertain" type="button" class="btn-secondary" :disabled="state.executeBusy" @click="state.execute">{{ state.executeBusy ? '正在读取本次结果…' : '读取本次清理结果' }}</button>
      <div v-if="state.uncertain" class="uncertain-recovery"><p>若服务器曾中断且结果一直待核对，可以结束本次追踪并重新预览。原确认不会再次执行，日志中的未知结果会保留。</p><button type="button" class="btn-secondary" :disabled="state.executeBusy" @click="closeUnknownTracking">结束本次追踪并重新核对</button></div>
      <div v-if="state.result && state.result.status !== 'RUNNING'" class="cleanup-result" role="status">
        <p><strong>{{ statusLabel(state.result.status) }}</strong> · 确认删除 {{ state.result.deleted.length }} 份，释放 {{ formatBytes(state.result.deletedBytes) }}。操作编号 {{ state.result.operationId }}。</p>
        <p v-if="state.result.unlockWarning" class="maintenance-error">{{ state.result.unlockWarning }}</p><p v-if="state.result.connectionWarning" class="maintenance-error">{{ state.result.connectionWarning }}</p>
        <p v-if="state.result.status !== 'SUCCEEDED'">请重新预览核对远端现状；再次读取本次结果不会继续删除。</p>
        <details v-if="state.result.deleted.length"><summary>已删除文件</summary><ul><li v-for="file in state.result.deleted" :key="file"><code>{{ file }}</code></li></ul></details>
        <details v-if="state.result.uncertainFiles?.length" open><summary>结果未确认的文件</summary><p>删除请求可能已执行，服务器未返回确认。重新预览可核对当前状态。</p><ul><li v-for="file in state.result.uncertainFiles" :key="file"><code>{{ file }}</code></li></ul></details>
      </div>
    </section>
    <section class="maintenance-block" aria-label="永久操作记录">
      <div class="maintenance-heading"><h4>永久操作记录</h4><button type="button" class="btn-secondary" :disabled="state.historyBusy" @click="state.loadHistory()">刷新操作记录</button></div>
      <p class="maintenance-caption">记录属于当前作品与服务数据库。远端快照可由其他设备产生；这里不汇总另一台独立服务器的操作日志。</p>
      <p v-if="state.historyError" class="maintenance-error" role="alert">{{ state.historyError }} <button type="button" class="btn-secondary" :disabled="state.historyBusy" @click="state.loadHistory()">重试读取记录</button></p>
      <p v-if="state.historyBusy" role="status">正在读取操作记录…</p><p v-else-if="!state.operations.length && !state.historyError">当前作品尚无操作记录。</p>
      <ol v-if="state.operations.length" class="operation-list"><li v-for="operation in state.operations" :key="operation.id">
        <div class="operation-line"><strong>{{ operationLabel(operation.type) }}</strong><span :class="{ 'maintenance-error': operation.status === 'FAILED' || operation.status === 'PARTIAL' }">{{ statusLabel(operation.status) }}</span><time>{{ formatTime(operation.startedAt) }}</time></div><p v-if="operation.message">{{ operation.message }}</p>
        <details><summary>查看{{ operation.status === 'FAILED' || operation.status === 'PARTIAL' ? '失败' : '操作' }}详情 · #{{ operation.id }}</summary><dl class="operation-details"><template v-if="operation.stage"><dt>执行阶段</dt><dd>{{ operation.stage }}</dd></template><template v-if="operation.finishedAt"><dt>结束时间</dt><dd>{{ formatTime(operation.finishedAt) }}</dd></template><template v-if="operation.errorType"><dt>错误类型</dt><dd>{{ operation.errorType }}</dd></template><template v-if="operation.httpStatus"><dt>响应状态</dt><dd>{{ operation.httpStatus }}</dd></template></dl><pre v-if="operation.details">{{ detailText(operation.details) }}</pre><p v-else>没有附加详情。</p></details>
      </li></ol><button v-if="state.nextBefore !== null" type="button" class="btn-secondary" :disabled="state.historyBusy" @click="state.loadHistory(true)">读取更早记录</button>
    </section>
    <BackupCleanupDialog v-if="state.preview" title="确认远端清理" :busy="state.executeBusy" @close="closePreview">
      <p>仅清理当前云端作品 <code>{{ state.preview.bookUid }}</code>。按此预览删除的文件无法撤销。</p><p>本次保留策略：最新 {{ state.preview.policy.keepLast }} 份，以及最近 {{ state.preview.policy.keepDays }} 天。预览有效至 {{ formatTime(state.preview.expiresAt) }}。</p>
      <p><strong>拟删除 {{ state.preview.candidates.length }} 份 · 预计释放 {{ formatBytes(state.preview.candidateBytes) }}</strong></p><p>保留 {{ state.preview.retained.length }} 项 · {{ formatBytes(state.preview.retainedBytes) }}。恢复基线、分支与其他保留原因见下方列表。</p><p>{{ state.preview.message }}</p>
      <h4>拟删除列表</h4><p v-if="!state.preview.candidates.length">没有符合清理条件的历史文件，不会删除任何备份。</p><ul v-else class="cleanup-files"><li v-for="file in state.preview.candidates" :key="file.file"><code>{{ file.file }}</code><span>{{ formatBytes(file.size) }} · {{ formatTime(file.time) }}</span><small>{{ file.reason }}</small></li></ul>
      <details class="retained-files" open><summary>保留列表与恢复基线（{{ state.preview.retained.length }} 项）</summary><ul class="cleanup-files"><li v-for="file in state.preview.retained" :key="file.file"><code>{{ file.file }}</code><span>{{ formatBytes(file.size) }} · {{ formatTime(file.time) }}</span><strong>{{ file.reason }}</strong></li></ul></details><p>另保留 {{ state.preview.legacyProtectedCount }} 份共享旧版备份（{{ formatBytes(state.preview.legacyProtectedBytes) }}）；清理前将写入 {{ state.preview.bridgeMarkers.length }} 份版本连接信息，维持历史版本关系。</p>
      <p v-if="state.expired" class="maintenance-error" role="alert">预览已过期，请取消后重新预览。</p><p v-if="state.policyDirty || state.preview.policy.version !== state.policy?.version" class="maintenance-error">保留策略已编辑或更新，本次确认不可使用。请取消后保存策略并重新预览。</p><p v-if="state.cleanupError" class="maintenance-error" role="alert">{{ state.cleanupError }}</p><p v-if="state.uncertain" role="status">本次操作结果尚未确认。请读取同一操作的结果；此时不会建立新的删除请求。</p>
      <label v-if="state.preview.candidates.length && !state.uncertain" class="cleanup-confirm"><input v-model="state.confirmed" type="checkbox" :disabled="state.expired || state.policyDirty || state.preview.policy.version !== state.policy?.version" />我已核对删除列表与保留的恢复基线，确认删除这 {{ state.preview.candidates.length }} 份远端历史。</label>
      <template #actions><button type="button" class="btn-secondary" :disabled="state.executeBusy" @click="closePreview">{{ state.uncertain ? '关闭并保留操作编号' : '取消，不删除' }}</button><button v-if="state.uncertain" type="button" class="btn-primary" :disabled="state.executeBusy" @click="state.execute">读取本次清理结果</button><button v-else type="button" class="btn-danger" :disabled="!state.canExecute" @click="state.execute">{{ state.executeBusy ? '正在核对并清理…' : '确认删除预览中的文件' }}</button></template>
    </BackupCleanupDialog>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, reactive, watch } from 'vue'
import BackupCleanupDialog from '@/components/settings/BackupCleanupDialog.vue'
import { useBackupMaintenance } from '@/composables/useBackupMaintenance'
const props = defineProps<{ novelId: number; refreshKey?: number }>()
const state = reactive(useBackupMaintenance(props.novelId))
watch(() => props.refreshKey, () => { void state.refresh() }, { immediate: true })
const timer = setInterval(state.tick, 1000)
onBeforeUnmount(() => { clearInterval(timer); state.dispose() })
function closeUnknownTracking() { if (confirm('本次清理结果仍未知。结束追踪后会生成新的预览供核对，原确认不会再次执行；不会自动确认新的删除。确定继续？')) void state.closeUnknownTracking() }
function closePreview() { if (state.uncertain) state.preview = null; else void state.cancelPreview() }
function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes) || bytes < 0) return '未知'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']; let value = bytes, index = 0
  while (value >= 1024 && index < units.length - 1) { value /= 1024; index++ }
  return `${value.toFixed(index ? 1 : 0)} ${units[index]}`
}
function formatTime(value?: string | null): string {
  if (!value) return '时间未知'
  const compact = value.match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})$/)
  if (compact) return `${compact[1]}-${compact[2]}-${compact[3]} ${compact[4]}:${compact[5]}:${compact[6]}`
  const date = new Date(value)
  return Number.isNaN(date.valueOf()) ? value : date.toLocaleString('zh-CN')
}
function operationLabel(type: string): string { return ({ CLOUD_PUSH: '上传云端作品', CLOUD_PULL: '恢复云端作品', CLOUD_PREVIEW: '比较云端版本', CLOUD_LIST: '读取云端版本', BACKUP_EXPORT: '生成本机备份', BACKUP_RESTORE: '恢复本机备份', WEBDAV_UPLOAD: '上传旧版备份', WEBDAV_RESTORE: '恢复旧版备份', WEBDAV_LIST: '读取旧版备份', CONNECTION_TEST: '测试连接', STORAGE: '读取远端空间', CLEANUP_PREVIEW: '预览远端清理', CLEANUP: '执行远端清理', CLEANUP_CANCEL: '取消远端清理' } as Record<string, string>)[type] || type }
function statusLabel(value: string): string { return ({ RUNNING: '处理中／待核对', SUCCEEDED: '成功', FAILED: '失败', PARTIAL: '部分完成', CANCELLED: '已取消' } as Record<string, string>)[value] || value }
function detailText(value: unknown): string { return typeof value === 'string' ? value : JSON.stringify(value, null, 2) }
</script>
<style scoped>
.backup-maintenance { min-width: 0; }
.maintenance-heading, .maintenance-actions, .operation-line { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.maintenance-heading h3, .maintenance-heading h4 { margin: 0; }
.maintenance-heading > div { flex: 1 1 300px; }
.maintenance-block { border-top: 1px solid var(--line); margin-top: 22px; padding-top: 18px; }
h4 { margin: 0 0 12px; font-size: 15px; }
p { line-height: 1.6; overflow-wrap: anywhere; }
.maintenance-caption { color: var(--muted); font-size: 12px; }
.maintenance-error { color: var(--danger); }
.storage-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; margin: 16px 0; }
.storage-grid div { border: 1px solid var(--line); border-radius: 12px; padding: 14px; min-width: 0; }
dt, small { color: var(--muted); font-size: 12px; }
dd { margin: 6px 0 0; overflow-wrap: anywhere; }
.retention-form { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 14px; margin: 16px 0; }
.retention-form label { display: grid; gap: 8px; font-size: 13px; flex: 1 1 150px; max-width: 240px; }
.retention-form input { width: 100%; min-width: 0; padding: 10px 12px; box-sizing: border-box; border: 1px solid var(--line); border-radius: 8px; background: var(--panel); color: var(--text); }
.retention-form label span { color: var(--muted); font-size: 12px; }
.operation-list { list-style: none; padding: 0; display: grid; gap: 12px; }
.operation-list > li { border: 1px solid var(--line); padding: 14px; border-radius: 12px; min-width: 0; }
.operation-line { justify-content: flex-start; font-size: 13px; }
.operation-line time { color: var(--muted); margin-left: auto; }
.operation-list p { margin: 10px 0; }
details { margin-top: 12px; }
summary { cursor: pointer; line-height: 1.6; }
pre, code { white-space: pre-wrap; word-break: break-word; overflow-wrap: anywhere; font-size: 12px; }
pre { max-height: 260px; overflow: auto; background: var(--panel); border-radius: 8px; padding: 12px; }
.operation-details { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 6px 12px; }
.operation-details dd { margin: 0; font-size: 12px; }
.cleanup-files { list-style: none; padding: 0; max-height: 250px; overflow-y: auto; display: grid; gap: 8px; }
.cleanup-files li { display: grid; gap: 5px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; min-width: 0; }
.cleanup-files span { color: var(--muted); }
.cleanup-confirm { display: flex; align-items: flex-start; gap: 10px; line-height: 1.7; margin-top: 18px; }
.cleanup-confirm input { margin-top: 6px; flex-shrink: 0; }
.cleanup-result { border-left: 3px solid var(--accent); padding-left: 12px; margin-top: 14px; }
@media (max-width: 600px) { .storage-grid { grid-template-columns: minmax(0, 1fr); } .retention-form label { max-width: none; } .maintenance-heading > button, .maintenance-actions > button, .retention-form > button { width: 100%; } .operation-line time { margin-left: 0; flex-basis: 100%; } }
</style>
