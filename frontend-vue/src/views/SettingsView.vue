<template>
  <div class="settings-view">
    <div class="view-header">
      <h2>⚙️ 全局设置</h2>
      <button v-if="inNovel" class="btn-back" @click="goBack">← 返回</button>
    </div>

    <div class="settings-section">
      <h3>🖼️ 全局背景</h3>
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

    <!-- WebDAV 同步设置 -->
    <div v-if="inNovel" class="settings-section">
      <h3>☁️ WebDAV 同步</h3>
      <p class="section-desc">配置 WebDAV 服务器实现多终端数据同步。支持 NextCloud、ownCloud、群晖 NAS 等。</p>

      <div class="setting-row">
        <div class="setting-label">服务器地址</div>
        <div class="setting-control">
          <input
            v-model="webdavForm.serverUrl"
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
              @change="saveWebDavConfig"
            />
            <span class="toggle-slider"></span>
            <span class="toggle-label">{{ webdavForm.autoSync ? '已开启' : '已关闭' }}</span>
          </label>
        </div>
      </div>

      <!-- 连接测试 -->
      <div class="setting-row">
        <div class="setting-label">连接测试</div>
        <div class="setting-control">
          <button
            class="btn-secondary"
            :disabled="connectionStatus === 'connecting' || !webdavForm.serverUrl"
            @click="testConnection"
          >
            {{ connectionStatus === 'connecting' ? '连接中...' : '🔗 测试连接' }}
          </button>
          <span v-if="connectionStatus === 'success'" class="status-text success">✅ 连接成功</span>
          <span v-if="connectionStatus === 'error'" class="status-text error">❌ {{ connectionMessage }}</span>
        </div>
      </div>

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
            :disabled="syncInProgress || !syncStatus.configured"
            @click="syncNow"
          >
            {{ syncInProgress ? '同步中...' : '☁️ 立即同步（上传）' }}
          </button>
          <button
            class="btn-secondary"
            :disabled="syncInProgress || !syncStatus.configured"
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
      @confirm="restoreFromCloud"
    >
      <div v-if="remoteFilesLoading" class="loading">加载中...</div>
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
          <button
            class="btn-delete-file"
            title="删除此备份"
            @click.stop="deleteCloudFile(file.name)"
          >🗑️</button>
        </div>
      </div>
    </BaseModal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch, computed, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import ImageUpload from '@/components/common/ImageUpload.vue'
import BaseModal from '@/components/common/BaseModal.vue'
import { webdavApi } from '@/api/webdav'
import type { RemoteFile } from '@/types/webdav'

const appStore = useAppStore()
const router = useRouter()
const route = useRoute()

const inNovel = computed(() => !!route.params.novelId)

const bgImage = ref(appStore.settings.backgroundImage)
const bgOpacity = ref(appStore.settings.backgroundOpacity)

watch(bgImage, (val) => {
  appStore.updateSetting('backgroundImage', val)
})

watch(bgOpacity, (val) => {
  appStore.updateSetting('backgroundOpacity', val)
})

function clearBackground() {
  bgImage.value = ''
  bgOpacity.value = 0.55
}

function goBack() {
  const novelId = route.params.novelId
  if (novelId) {
    router.push(`/novel/${novelId}/timeline`)
  } else {
    router.push('/')
  }
}

// ===== WebDAV State =====
const webdavForm = reactive({
  serverUrl: '',
  username: '',
  password: '',
  autoSync: false
})

const connectionStatus = ref<'idle' | 'connecting' | 'success' | 'error'>('idle')
const connectionMessage = ref('')
const syncStatus = reactive({
  configured: false,
  serverUrl: '',
  lastSyncTime: null as string | null
})
const syncInProgress = ref(false)
const syncMessage = ref('')
const syncError = ref(false)
const showRemoteFiles = ref(false)
const remoteFiles = ref<RemoteFile[]>([])
const remoteFilesLoading = ref(false)
const selectedFile = ref('')

// Track whether there's a saved password in the database
const hasPasswordSaved = ref(false)

onMounted(async () => {
  if (inNovel.value) {
    try {
      const status = await webdavApi.getStatus()
      webdavForm.serverUrl = status.serverUrl || ''
      webdavForm.username = status.username || ''
      webdavForm.password = ''
      webdavForm.autoSync = status.autoSync
      hasPasswordSaved.value = status.hasPassword
      syncStatus.configured = status.configured
      syncStatus.serverUrl = status.serverUrl || ''
      syncStatus.lastSyncTime = status.lastSyncTime
    } catch (e) {
      // Settings not configured yet
    }
  }
})

async function saveWebDavConfig() {
  if (!inNovel.value) return
  try {
    const config: any = {
      serverUrl: webdavForm.serverUrl,
      username: webdavForm.username,
      autoSync: webdavForm.autoSync
    }
    // Only send password if user entered a new one
    if (webdavForm.password) {
      config.password = webdavForm.password
      hasPasswordSaved.value = true
    }
    await webdavApi.saveConfig(config)
    syncStatus.configured = !!webdavForm.serverUrl
  } catch (e) {
    console.error('保存 WebDAV 配置失败:', e)
  }
}

async function testConnection() {
  if (!webdavForm.serverUrl) return
  connectionStatus.value = 'connecting'
  connectionMessage.value = ''
  try {
    const result = await webdavApi.testConnection({
      serverUrl: webdavForm.serverUrl,
      username: webdavForm.username,
      password: webdavForm.password
    })
    if (result.success) {
      connectionStatus.value = 'success'
      connectionMessage.value = result.message
    } else {
      connectionStatus.value = 'error'
      connectionMessage.value = result.message
    }
  } catch (e: any) {
    connectionStatus.value = 'error'
    connectionMessage.value = e?.message || '连接测试失败'
  }
  setTimeout(() => { connectionStatus.value = 'idle' }, 5000)
}

async function syncNow() {
  syncInProgress.value = true
  syncMessage.value = ''
  syncError.value = false
  try {
    const result = await webdavApi.syncUpload()
    if (result.success) {
      syncMessage.value = result.message
      syncError.value = false
      syncStatus.lastSyncTime = result.timestamp || null
    } else {
      syncMessage.value = result.message
      syncError.value = true
    }
  } catch (e: any) {
    syncMessage.value = e?.message || '同步失败'
    syncError.value = true
  }
  syncInProgress.value = false
}

async function restoreFromCloud() {
  if (!selectedFile.value) {
    alert('请选择一个备份文件')
    return
  }
  showRemoteFiles.value = false
  syncInProgress.value = true
  syncMessage.value = ''
  syncError.value = false
  try {
    const result = await webdavApi.syncDownload(selectedFile.value)
    if (result.success) {
      syncMessage.value = result.message
      syncError.value = false
      syncStatus.lastSyncTime = result.timestamp || null
    } else {
      syncMessage.value = result.message
      syncError.value = true
    }
  } catch (e: any) {
    syncMessage.value = e?.message || '从云端恢复失败'
    syncError.value = true
  }
  syncInProgress.value = false
  selectedFile.value = ''
}

async function deleteCloudFile(filename: string) {
  if (!confirm(`确定要删除云端备份「${filename}」吗？此操作不可撤销。`)) return
  try {
    const result = await webdavApi.deleteRemoteFile(filename)
    if (result.success) {
      remoteFiles.value = remoteFiles.value.filter(f => f.name !== filename)
      if (selectedFile.value === filename) selectedFile.value = ''
      alert(result.message)
    } else {
      alert(result.message)
    }
  } catch (e: any) {
    alert('删除失败: ' + (e?.message || '未知错误'))
  }
}

// Watch for remote files modal opening
watch(showRemoteFiles, async (val) => {
  if (val) {
    remoteFilesLoading.value = true
    remoteFiles.value = []
    try {
      remoteFiles.value = await webdavApi.getRemoteFiles()
    } catch (e) {
      remoteFiles.value = []
    }
    remoteFilesLoading.value = false
  }
})

function formatTime(timeStr: string): string {
  if (!timeStr) return ''
  try {
    const d = new Date(timeStr)
    return d.toLocaleString('zh-CN')
  } catch {
    return timeStr
  }
}

function formatFileSize(bytes: number): string {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) {
    size /= 1024
    i++
  }
  return size.toFixed(1) + ' ' + units[i]
}
</script>

<style lang="scss" scoped>
@import '@/assets/styles/variables.scss';

.opacity-slider {
  width: 100%;
  max-width: 300px;
  accent-color: #6c5ce7;
}

.opacity-value {
  margin-left: 12px;
  font-weight: 600;
  color: #6c5ce7;
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
