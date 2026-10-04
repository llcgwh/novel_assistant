<template>
  <div class="settings-view">
    <div class="view-header">
      <h2>设置与备份</h2>
      <button v-if="inNovel" class="btn-back" @click="goBack">← 返回</button>
      <RouterLink v-else to="/" class="btn-back">← 作品书架</RouterLink>
    </div>

    <div class="settings-section">
      <h3>工作室外观</h3><p>夜航适合沉浸构思，晨雾适合日间阅读。</p>
      <div class="theme-choices"><button class="theme-choice" :aria-pressed="studio.theme === 'dark'" @click="studio.theme = 'dark'"><span class="theme-swatch night"></span>夜航 · 深墨与青光</button><button class="theme-choice" :aria-pressed="studio.theme === 'light'" @click="studio.theme = 'light'"><span class="theme-swatch day"></span>晨雾 · 纸白与松绿</button></div>
    </div>
    <div v-if="inNovel" class="settings-section">
      <h3>作品导出</h3><p>把当前作品的资料带到你喜欢的写作环境。</p><div class="export-actions"><button class="btn-secondary" @click="exportApi.downloadMarkdown()">完整 Markdown</button><button class="btn-secondary" @click="exportApi.downloadJson()">数据 JSON</button><button class="btn-secondary" @click="exportApi.downloadCharactersMarkdown()">人物档案</button><button class="btn-secondary" @click="exportApi.downloadOutlinesMarkdown()">章节大纲</button><button class="btn-secondary" @click="exportApi.downloadWorldviewMarkdown()">世界设定</button></div>
    </div>
    <div v-if="inNovel" class="settings-section">
      <h3>备份与恢复</h3>
      <p>包含小说数据和已上传图片。恢复前会保留当前作品的完整副本，再替换当前小说数据及封面；外部图片链接和浏览器外观设置不打包。</p>
      <p>每张图片最多 8 MiB，图片总计最多 32 MiB，备份文件最多 48 MiB。</p>
      <div class="backup-actions">
        <button class="btn-primary" :disabled="backupBusy || syncInProgress" @click="downloadLocalBackup">下载含图片备份</button>
        <BaseFilePicker
          label="从备份恢复"
          accept=".json,application/json"
          hint="JSON 备份 · 最大 48 MiB"
          :busy="backupBusy || syncInProgress"
          busy-label="正在恢复…"
          @change="restoreLocalBackup"
        />
      </div>
      <p role="status">{{ backupMessage }}</p>
      <RouterLink v-if="restoredCopyId" :to="`/novel/${restoredCopyId}/overview`" class="btn-secondary">查看恢复前副本 #{{ restoredCopyId }}</RouterLink>
    </div>

    <div class="settings-section">
      <h3>全局背景</h3>
      <div class="setting-row">
        <div class="setting-label">背景图片</div>
        <div class="setting-control">
          <ImageUpload
            v-model="bgImage"
            image-type="background"
            placeholder="点击上传全局背景图片"
          />
        </div>
      </div>
      <div class="setting-row" v-if="bgImage">
        <div class="setting-label">覆盖透明度</div>
        <div class="setting-control">
          <input
            type="range"
            min="0"
            max="100"
            :value="Math.round(bgOpacity * 100)"
            @input="bgOpacity = Number(($event.target as HTMLInputElement).value) / 100"
            class="opacity-slider"
          />
          <span class="opacity-value">{{ Math.round(bgOpacity * 100) }}%</span>
        </div>
      </div>
      <div class="setting-row" v-if="bgImage">
        <button class="btn-danger" @click="clearBackground">清除背景图片</button>
      </div>
    </div>

    <AppearanceTransfer />

    <!-- WebDAV 同步设置 -->
    <div v-if="inNovel" class="settings-section">
      <h3>WebDAV 同步</h3>
      <p class="section-desc">配置 WebDAV 服务器实现多终端数据同步。支持 NextCloud、ownCloud、群晖 NAS 等。</p>

      <div class="setting-row">
        <div class="setting-label">服务器地址</div>
        <div class="setting-control">
          <input
            v-model="webdavForm.serverUrl"
            :disabled="configSaving || syncInProgress || backupBusy"
            type="text"
            placeholder="https://your-server.com/remote.php/dav/files/username/"
            @blur="saveWebDavConfig"
          />
        </div>
      </div>
      <div class="setting-row">
        <div class="setting-label">用户名</div>
        <div class="setting-control">
          <input
            v-model="webdavForm.username"
            :disabled="configSaving || syncInProgress || backupBusy"
            type="text"
            placeholder="WebDAV 用户名"
            @blur="saveWebDavConfig"
          />
        </div>
      </div>
      <div class="setting-row">
        <div class="setting-label">密码</div>
        <div class="setting-control">
          <input
            v-model="webdavForm.password"
            :disabled="configSaving || syncInProgress || backupBusy"
            type="password"
            :placeholder="hasPasswordSaved ? '密码已保存，留空则不修改' : 'WebDAV 密码'"
            @blur="saveWebDavConfig"
          />
          <span v-if="hasPasswordSaved" class="status-text success" style="margin-left:8px;">🔒 已保存</span>
        </div>
      </div>
      <div class="setting-row">
        <div class="setting-label">自动同步</div>
        <div class="setting-control">
          <label class="toggle-switch">
            <input
              type="checkbox"
              v-model="webdavForm.autoSync"
              :disabled="configSaving || syncInProgress || backupBusy"
              @change="saveWebDavConfig"
            />
            <span class="toggle-slider"></span>
            <span class="toggle-label">{{ webdavForm.autoSync ? '已开启' : '已关闭' }}</span>
          </label>
          <p class="status-text">后端运行时约每 5 分钟上传有改动的完整作品。云端分歧请在「写作工作台 → 云端同步」比较处理。</p>
        </div>
      </div>

      <!-- 连接测试 -->
      <div class="setting-row">
        <div class="setting-label">连接测试</div>
        <div class="setting-control">
          <button
            class="btn-secondary"
            :disabled="connectionStatus === 'connecting' || configSaving || !webdavForm.serverUrl"
            @click="testConnection"
          >
            {{ connectionStatus === 'connecting' ? '连接中...' : '🔗 测试连接' }}
          </button>
          <span v-if="connectionStatus === 'success'" class="status-text success">✅ 连接成功</span>
          <span v-if="connectionStatus === 'error'" class="status-text error">❌ {{ connectionMessage }}</span>
        </div>
      </div>

      <p v-if="configError" class="status-text error" role="alert">{{ configError }} <button type="button" class="btn-secondary" :disabled="configSaving" @click="saveWebDavConfig">重试保存配置</button></p>
      <p v-if="statusError" class="status-text error" role="alert">{{ statusError }} <button type="button" class="btn-secondary" @click="loadStatus">重新读取配置</button></p>
      <!-- 同步状态 -->
      <div v-if="syncStatus.lastSyncTime" class="setting-row">
        <div class="setting-label">上次同步</div>
        <div class="setting-control">
          <span class="status-text">{{ formatTime(syncStatus.lastSyncTime) }}</span>
        </div>
      </div>

      <!-- 同步操作 -->
      <div class="setting-row sync-actions">
        <div class="setting-label">同步操作</div>
        <div class="setting-control sync-buttons">
          <button
            class="btn-primary"
            :disabled="syncInProgress || backupBusy || configSaving || !syncStatus.configured"
            @click="syncNow"
          >
            {{ syncInProgress ? '同步中...' : '☁️ 立即同步（上传）' }}
          </button>
          <button
            class="btn-secondary"
            :disabled="syncInProgress || backupBusy || configSaving || !syncStatus.configured"
            @click="showRemoteFiles = true"
          >
            📂 从云端恢复
          </button>
        </div>
      </div>
      <div v-if="syncMessage" class="setting-row">
        <div class="setting-label"></div>
        <div class="setting-control">
          <span class="status-text" :class="syncError ? 'error' : 'success'">{{ syncMessage }}</span>
        </div>
      </div>
    </div>

    <BackupMaintenance v-if="inNovel" :key="currentNovelId" :novel-id="currentNovelId" :refresh-key="maintenanceRefresh" />

    <div class="settings-section">
      <h3>🏠 导航</h3>
      <div class="setting-row">
        <router-link v-if="!inNovel" to="/" class="btn-secondary" style="text-decoration:none;">
          ← 返回首页
        </router-link>
        <span v-else class="setting-label">当前小说设置已保存</span>
      </div>
    </div>

    <!-- 远程文件选择模态框 -->
    <BaseModal
      v-if="showRemoteFiles"
      title="云端备份文件"
      @close="showRemoteFiles = false"
      :busy="syncInProgress"
    >
      <div v-if="remoteFilesLoading" class="loading">加载中...</div>
      <p v-else-if="remoteFilesError" class="status-text error" role="alert">{{ remoteFilesError }} <button type="button" class="btn-secondary" @click="loadRemoteFiles">重试读取</button></p>
      <div v-else-if="remoteFiles.length === 0" class="empty-hint">
        云端没有找到备份文件
      </div>
      <div v-else class="remote-files-list">
        <div
          v-for="file in remoteFiles"
          :key="file.path"
          class="remote-file-item"
          :class="{ selected: selectedFile === file.name }"
        >
          <label class="file-label">
            <input
              type="radio"
              :value="file.name"
              v-model="selectedFile"
            />
            <div class="file-info">
              <span class="file-name">📄 {{ file.name }}</span>
              <span class="file-meta">
                {{ formatFileSize(file.size) }}
                <span v-if="file.modified"> · {{ formatTime(file.modified) }}</span>
              </span>
            </div>
          </label>
        </div>
      </div>
      <p class="remote-hint">清理远端历史请使用下方“同步与备份记录”，先预览保留基线与删除列表。</p>
      <p v-if="syncError && syncMessage" class="status-text error" role="alert">{{ syncMessage }}</p>
      <template #actions>
        <button type="button" class="btn-secondary" :disabled="syncInProgress" @click="showRemoteFiles = false">取消</button>
        <button type="button" class="btn-primary" :disabled="syncInProgress || remoteFilesLoading || !selectedFile" @click="restoreFromCloud">{{ syncInProgress ? '正在恢复…' : '恢复选中备份' }}</button>
      </template>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import AppearanceTransfer from '@/components/settings/AppearanceTransfer.vue'
import BackupMaintenance from '@/components/settings/BackupMaintenance.vue'
import { ref, reactive, watch, computed, onBeforeUnmount } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useStudioStore } from '@/stores/studio'
import { exportApi } from '@/api/export'
import ImageUpload from '@/components/common/ImageUpload.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import BaseFilePicker from '@/components/common/BaseFilePicker.vue'
import { webdavApi } from '@/api/webdav'
import { backupApi } from '@/api/backup'
import type { RemoteFile, WebDavConfig } from '@/types/webdav'

const appStore = useAppStore(), studio = useStudioStore(), router = useRouter(), route = useRoute()
const currentNovelId = computed(() => Number(route.params.novelId) || 0)
const inNovel = computed(() => currentNovelId.value > 0)
const backupBusy = ref(false), backupMessage = ref(''), maintenanceRefresh = ref(0), restoredCopyId = ref<number | null>(null)
const webdavForm = reactive({ serverUrl: '', username: '', password: '', autoSync: false })
const connectionStatus = ref<'idle' | 'connecting' | 'success' | 'error'>('idle')
const connectionMessage = ref(''), configSaving = ref(false), configError = ref(''), statusError = ref('')
const syncStatus = reactive({ configured: false, serverUrl: '', lastSyncTime: null as string | null })
const syncInProgress = ref(false), syncMessage = ref(''), syncError = ref(false)
const showRemoteFiles = ref(false), remoteFiles = ref<RemoteFile[]>([]), remoteFilesLoading = ref(false)
const selectedFile = ref(''), remoteFilesError = ref(''), hasPasswordSaved = ref(false)
let generation = 0, statusRequest = 0, filesRequest = 0, disposed = false
function context() { return { novelId: currentNovelId.value, generation } }
function active(ctx: ReturnType<typeof context>) { return !disposed && ctx.generation === generation && ctx.novelId === currentNovelId.value }
function failureMessage(error: unknown, fallback: string) {
  const e = error as { response?: { data?: { message?: string } }; message?: string }
  return e?.response?.data?.message || e?.message || fallback
}

async function downloadLocalBackup() {
  if (!inNovel.value || backupBusy.value || syncInProgress.value) return
  const ctx = context()
  backupBusy.value = true; backupMessage.value = '正在生成完整备份…'
  try {
    const blob = await backupApi.download(ctx.novelId)
    if (!active(ctx)) return
    const url = URL.createObjectURL(blob), link = document.createElement('a')
    link.href = url; link.download = `novel-${ctx.novelId}.backup.json`; link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
    backupMessage.value = '备份已生成，请确认浏览器已保存下载文件。'
  } catch (error) {
    if (active(ctx)) backupMessage.value = failureMessage(error, '备份下载失败，请重试')
  } finally { if (active(ctx)) { backupBusy.value = false; maintenanceRefresh.value++ } }
}
async function restoreLocalBackup(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]
  input.value = ''
  if (!file || !inNovel.value || backupBusy.value || syncInProgress.value) return
  if (file.size > 48 * 1024 * 1024) { backupMessage.value = '备份文件超过 48 MiB'; return }
  if (!confirm('恢复前会保留当前作品的完整副本，再替换当前小说数据及封面。确定继续？')) return
  const ctx = context()
  backupBusy.value = true; backupMessage.value = '正在恢复…'
  try {
    const result = await backupApi.restore(ctx.novelId, file)
    if (!active(ctx)) return
    rememberRestore(ctx.novelId, result)
    try { localStorage.removeItem(`mapBackground_${ctx.novelId}`) } catch { /* Restore succeeded even if browser storage is unavailable. */ }
    window.location.reload()
  } catch (error) {
    if (active(ctx)) backupMessage.value = failureMessage(error, '恢复失败，请检查备份文件或网络连接')
  } finally { if (active(ctx)) { backupBusy.value = false; maintenanceRefresh.value++ } }
}

function rememberRestore(novelId: number, result: { message?: string; copyNovelId?: number }) {
  const notice = { message: result.message || '恢复成功，原稿已保留为独立作品副本。', copyNovelId: result.copyNovelId }
  backupMessage.value = notice.message
  restoredCopyId.value = Number.isSafeInteger(notice.copyNovelId) && Number(notice.copyNovelId) > 0 ? notice.copyNovelId! : null
  try { globalThis.sessionStorage?.setItem(`backup-restore-notice:${novelId}`, JSON.stringify(notice)) } catch { /* The permanent server log also retains the safety-copy ID. */ }
}
function readRestoreNotice(novelId: number) {
  try {
    const key = `backup-restore-notice:${novelId}`, raw = globalThis.sessionStorage?.getItem(key)
    if (!raw) return
    const notice = JSON.parse(raw)
    if (typeof notice.message === 'string') backupMessage.value = notice.message
    if (Number.isSafeInteger(notice.copyNovelId) && notice.copyNovelId > 0) restoredCopyId.value = notice.copyNovelId
    globalThis.sessionStorage?.removeItem(key)
  } catch { /* A missing browser notice does not change the completed server restore. */ }
}

const bgImage = ref(appStore.settings.backgroundImage), bgOpacity = ref(appStore.settings.backgroundOpacity)
watch(() => [appStore.settings.backgroundImage, appStore.settings.backgroundOpacity] as const, ([image, opacity]) => { bgImage.value = image; bgOpacity.value = opacity })
watch(bgImage, (val) => appStore.updateSetting('backgroundImage', val))
watch(bgOpacity, (val) => appStore.updateSetting('backgroundOpacity', val))
function clearBackground() { bgImage.value = ''; bgOpacity.value = 0.55 }
function goBack() { router.push(inNovel.value ? `/novel/${currentNovelId.value}/timeline` : '/') }

async function loadStatus() {
  if (!inNovel.value) return
  const ctx = context(), attempt = ++statusRequest
  statusError.value = ''
  try {
    const status = await webdavApi.getStatus(ctx.novelId)
    if (!active(ctx) || attempt !== statusRequest) return
    Object.assign(webdavForm, { serverUrl: status.serverUrl || '', username: status.username || '', password: '', autoSync: status.autoSync })
    hasPasswordSaved.value = status.hasPassword
    Object.assign(syncStatus, { configured: status.configured, serverUrl: status.serverUrl || '', lastSyncTime: status.lastSyncTime })
  } catch (error) { if (active(ctx) && attempt === statusRequest) statusError.value = failureMessage(error, '配置读取失败，请重试') }
}
async function saveWebDavConfig() {
  if (!inNovel.value || configSaving.value || syncInProgress.value || backupBusy.value) return
  const ctx = context(), config: Partial<WebDavConfig> = { serverUrl: webdavForm.serverUrl, username: webdavForm.username, autoSync: webdavForm.autoSync }
  if (webdavForm.password) config.password = webdavForm.password
  ++statusRequest
  configSaving.value = true; configError.value = ''; connectionStatus.value = 'idle'
  try {
    await webdavApi.saveConfig(ctx.novelId, config)
    if (!active(ctx)) return
    if (config.password) { hasPasswordSaved.value = true; webdavForm.password = '' }
    syncStatus.configured = !!config.serverUrl
    syncStatus.serverUrl = config.serverUrl || ''
    statusError.value = ''
    maintenanceRefresh.value++
  } catch (error) { if (active(ctx)) configError.value = failureMessage(error, '保存 WebDAV 配置失败，请重试') }
  finally { if (active(ctx)) configSaving.value = false }
}
async function testConnection() {
  if (!webdavForm.serverUrl || configSaving.value || connectionStatus.value === 'connecting') return
  const ctx = context()
  connectionStatus.value = 'connecting'; connectionMessage.value = ''
  try {
    const result = await webdavApi.testConnection(ctx.novelId, { serverUrl: webdavForm.serverUrl, username: webdavForm.username, password: webdavForm.password })
    if (!active(ctx)) return
    connectionStatus.value = result.success ? 'success' : 'error'; connectionMessage.value = result.message
  } catch (error) { if (active(ctx)) { connectionStatus.value = 'error'; connectionMessage.value = failureMessage(error, '连接测试失败') } }
  finally { if (active(ctx)) maintenanceRefresh.value++ }
}
async function syncNow() {
  if (!inNovel.value || syncInProgress.value || backupBusy.value || configSaving.value) return
  const ctx = context()
  syncInProgress.value = true; syncMessage.value = ''; syncError.value = false
  try {
    const result = await webdavApi.syncUpload(ctx.novelId)
    if (!active(ctx)) return
    syncMessage.value = result.message; syncError.value = !result.success
    if (result.success) syncStatus.lastSyncTime = result.timestamp || null
  } catch (error) { if (active(ctx)) { syncMessage.value = failureMessage(error, '同步失败，请重试'); syncError.value = true } }
  finally { if (active(ctx)) { syncInProgress.value = false; maintenanceRefresh.value++ } }
}
async function restoreFromCloud() {
  if (!selectedFile.value || !showRemoteFiles.value || syncInProgress.value || backupBusy.value || remoteFilesLoading.value) return
  if (!remoteFiles.value.some(file => file.name === selectedFile.value)) return
  if (!confirm('恢复前会保留当前作品的完整副本，再替换当前小说数据及封面。确定继续？')) return
  const ctx = context(), filename = selectedFile.value
  syncInProgress.value = true; syncMessage.value = ''; syncError.value = false
  try {
    const result = await webdavApi.syncDownload(ctx.novelId, filename)
    if (!active(ctx)) return
    syncMessage.value = result.message; syncError.value = !result.success
    if (result.success) {
      showRemoteFiles.value = false
      rememberRestore(ctx.novelId, result)
      syncStatus.lastSyncTime = result.timestamp || null
      try { localStorage.removeItem(`mapBackground_${ctx.novelId}`) } catch { /* Server recovery is already complete. */ }
      window.location.reload()
    }
  } catch (error) { if (active(ctx)) { syncMessage.value = failureMessage(error, '从云端恢复失败，请重试'); syncError.value = true } }
  finally { if (active(ctx)) { syncInProgress.value = false; maintenanceRefresh.value++ } }
}
async function loadRemoteFiles() {
  if (!showRemoteFiles.value || syncInProgress.value) return
  const ctx = context(), attempt = ++filesRequest
  remoteFilesLoading.value = true; remoteFiles.value = []; remoteFilesError.value = ''; selectedFile.value = ''
  try {
    const files = await webdavApi.getRemoteFiles(ctx.novelId)
    if (active(ctx) && showRemoteFiles.value && attempt === filesRequest) remoteFiles.value = files
  } catch (error) { if (active(ctx) && showRemoteFiles.value && attempt === filesRequest) remoteFilesError.value = failureMessage(error, '云端列表读取失败，请重试') }
  finally { if (active(ctx) && attempt === filesRequest) remoteFilesLoading.value = false }
}
watch(showRemoteFiles, (open) => {
  ++filesRequest; selectedFile.value = ''; remoteFiles.value = []; remoteFilesError.value = ''
  if (open) void loadRemoteFiles()
  else remoteFilesLoading.value = false
})
watch(currentNovelId, () => {
  ++generation; ++statusRequest; ++filesRequest
  Object.assign(webdavForm, { serverUrl: '', username: '', password: '', autoSync: false })
  Object.assign(syncStatus, { configured: false, serverUrl: '', lastSyncTime: null })
  backupBusy.value = false; backupMessage.value = ''; restoredCopyId.value = null; configSaving.value = false; configError.value = ''; statusError.value = ''
  connectionStatus.value = 'idle'; connectionMessage.value = ''; hasPasswordSaved.value = false
  syncInProgress.value = false; syncMessage.value = ''; syncError.value = false; showRemoteFiles.value = false
  remoteFiles.value = []; remoteFilesLoading.value = false; remoteFilesError.value = ''; selectedFile.value = ''
  if (inNovel.value) readRestoreNotice(currentNovelId.value)
  void loadStatus()
}, { immediate: true, flush: 'sync' })
onBeforeUnmount(() => { disposed = true; ++generation })
function formatTime(value: string): string { const date = new Date(value); return Number.isNaN(date.valueOf()) ? value : date.toLocaleString('zh-CN') }
function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '未知'
  const units = ['B', 'KiB', 'MiB', 'GiB']; let i = 0, size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return `${size.toFixed(i ? 1 : 0)} ${units[i]}`
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

.backup-actions {
  display: flex;
  align-items: flex-start;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 14px;

  > button {
    min-height: 40px;
  }
}

@media (max-width: 600px) {
  .backup-actions > button {
    width: 100%;
  }
}

.opacity-slider {
  width: 100%;
  max-width: 300px;
  accent-color: var(--accent);
}

.opacity-value {
  margin-left: 12px;
  font-weight: 600;
  color: var(--accent);
  font-size: 14px;
}

.section-desc {
  color: $text-muted;
  font-size: 13px;
  margin-bottom: 16px;
  padding-bottom: 12px;
  border-bottom: 1px solid $glass-border;
}

.setting-row {
  input[type="text"],
  input[type="password"] {
    width: 100%;
    max-width: 450px;
    padding: 10px 14px;
    border: 1px solid $glass-border;
    border-radius: $border-radius;
    font-size: 14px;
    background: rgba(255, 255, 255, 0.6);
    backdrop-filter: blur($glass-blur);
    transition: all $transition-normal;
    color: $text-primary;

    &:focus {
      outline: none;
      border-color: $primary-color;
      box-shadow: 0 0 0 3px $primary-light;
      background: rgba(255, 255, 255, 0.9);
    }
  }
}

.toggle-switch {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;

  input {
    display: none;
  }

  .toggle-slider {
    width: 44px;
    height: 24px;
    background: #ccc;
    border-radius: 12px;
    position: relative;
    transition: all 0.3s ease;

    &::before {
      content: '';
      position: absolute;
      width: 20px;
      height: 20px;
      border-radius: 50%;
      background: white;
      top: 2px;
      left: 2px;
      transition: all 0.3s ease;
      box-shadow: 0 1px 4px rgba(0, 0, 0, 0.2);
    }
  }

  input:checked + .toggle-slider {
    background: linear-gradient(135deg, $primary-color, lighten($primary-color, 8%));

    &::before {
      transform: translateX(20px);
    }
  }

  .toggle-label {
    font-size: 13px;
    color: $text-secondary;
  }
}

.status-text {
  font-size: 13px;
  margin-left: 10px;

  &.success {
    color: $success-color;
  }

  &.error {
    color: $danger-color;
  }
}

.sync-actions {
  padding-top: 16px;
  border-top: 1px solid $glass-border;
}

.sync-buttons {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.remote-files-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 300px;
  overflow-y: auto;
}

.remote-file-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid $glass-border;
  border-radius: $border-radius;
  transition: all $transition-fast;
  background: rgba(255, 255, 255, 0.3);

  &:hover {
    background: rgba(255, 255, 255, 0.5);
  }

  &.selected {
    border-color: $primary-color;
    background: rgba(108, 92, 231, 0.08);
  }

  .file-label {
    display: flex;
    align-items: center;
    gap: 10px;
    flex: 1;
    cursor: pointer;
    min-width: 0;
  }

  input[type="radio"] {
    accent-color: $primary-color;
    flex-shrink: 0;
  }

  .file-info {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
    overflow: hidden;

    .file-name {
      font-weight: 500;
      font-size: 14px;
      color: $text-primary;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }

    .file-meta {
      font-size: 12px;
      color: $text-muted;
    }
  }

  .btn-delete-file {
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    border: none;
    background: rgba(231, 76, 60, 0.1);
    border-radius: 6px;
    cursor: pointer;
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all $transition-fast;

    &:hover {
      background: rgba(231, 76, 60, 0.25);
      transform: scale(1.1);
    }
  }
}

.empty-hint {
  text-align: center;
  padding: 20px;
  color: $text-muted;
  font-style: italic;
}

@media (max-width: $breakpoint-md) {
  .sync-buttons {
    flex-direction: column;
    width: 100%;

    button {
      width: 100%;
    }
  }
}
</style>
