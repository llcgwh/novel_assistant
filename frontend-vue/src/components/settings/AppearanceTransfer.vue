<template>
  <section class="settings-section appearance-transfer">
    <h3>外观偏好迁移</h3>
    <p>单独备份背景图片、透明度和当前小说的地图背景选择。外部图片保留链接；导入会替换本浏览器的外观设置。</p>
    <p>图片支持 {{ IMAGE_FORMATS }}；界面背景最多 {{ imageSizeLimit(MAX_BACKGROUND_BYTES) }}，地图背景最多 {{ imageSizeLimit(MAX_IMAGE_BYTES) }}。</p>
    <div class="appearance-actions">
      <button class="btn-secondary" :disabled="busy" @click="exportAppearance">导出外观偏好</button>
      <BaseFilePicker
        label="导入外观偏好"
        accept=".json,application/json"
        hint="JSON 外观文件 · 最大 16 MiB"
        :busy="importBusy"
        :disabled="busy"
        busy-label="正在导入…"
        @change="importAppearance"
      />
    </div>
    <p v-if="error" class="appearance-error" role="alert">{{ error }}</p>
    <p v-if="message" role="status">{{ message }}</p>
  </section>
</template>
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { getApiBaseUrl, getNovelContextVersion, request } from '@/api/request'
import { imagesApi } from '@/api/images'
import { parseAppearanceBackup } from '@/utils/appearance'
import { IMAGE_FORMATS, MAX_BACKGROUND_BYTES, MAX_IMAGE_BYTES, imageSizeLimit, validateImageFile, getImageUploadError } from '@/utils/imageUpload'
import BaseFilePicker from '@/components/common/BaseFilePicker.vue'
const route = useRoute()
const app = useAppStore()
const busy = ref(false)
const importBusy = ref(false)
const message = ref('')
const error = ref('')
let operation = 0, disposed = false
watch(() => route.params.novelId, () => { operation++; busy.value = false; importBusy.value = false; message.value = ''; error.value = '' })
onBeforeUnmount(() => { disposed = true; operation++ })
function imageFile(data: string, maxBytes: number): File {
  const mime = data.slice(5, data.indexOf(';'))
  let bytes: Uint8Array
  try { bytes = Uint8Array.from(atob(data.slice(data.indexOf(',') + 1)), c => c.charCodeAt(0)) }
  catch { throw new Error('背景图片数据无法读取，请重新导出外观文件') }
  const file = new File([bytes], 'background.' + mime.split('/')[1], { type: mime })
  validateImageFile(file, maxBytes)
  return file
}
async function embeddedImage(url: string, maxBytes: number): Promise<string> {
  if (url.startsWith('data:')) { imageFile(url, maxBytes); return url }
  if (!/^https?:.*\/api\/novels\/\d+\/images\/\d+\/file$/.test(url) && !url.startsWith('/api/novels/')) return url
  const path = new URL(url, location.origin).pathname.replace(/^\/api/, '')
  const blob: Blob = await request.get(path, { responseType: 'blob' })
  const mime = blob.type.replace('image/x-ms-bmp', 'image/bmp')
  const image = new File([blob], 'background.' + mime.split('/')[1], { type: mime })
  validateImageFile(image, maxBytes)
  return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(new Error('无法读取背景图片，请重试')); reader.readAsDataURL(image) })
}
async function exportAppearance() {
  if (busy.value) return
  const token = ++operation, context = getNovelContextVersion(), novelId = Number(route.params.novelId) || 0
  const current = () => !disposed && token === operation && context === getNovelContextVersion() && novelId === (Number(route.params.novelId) || 0)
  busy.value = true; message.value = ''; error.value = ''
  try {
    const settings = { ...app.settings }
    const selected = novelId ? localStorage.getItem(`mapBackground_${novelId}`) : null
    const mapBackground = selected === 'none' ? 'none' : selected && /^\d+$/.test(selected)
      ? await embeddedImage(`${getApiBaseUrl()}/novels/${novelId}/images/${selected}/file`, MAX_IMAGE_BYTES) : 'auto'
    if (!current()) return
    settings.backgroundImage = await embeddedImage(settings.backgroundImage, MAX_BACKGROUND_BYTES)
    if (!current()) return
    const blob = new Blob([JSON.stringify({ format: 'novel-appearance-v1', settings, ...(novelId ? { mapBackground } : {}) })], { type: 'application/json' })
    const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'novel-appearance.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    message.value = '外观偏好已导出'
  } catch (failure) { if (current()) error.value = `导出失败：${getImageUploadError(failure, MAX_IMAGE_BYTES).replace(/图片上传/g, '图片读取')}` }
  finally { if (token === operation) busy.value = false }
}
async function importAppearance(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''
  if (!file || busy.value) return
  const token = ++operation, context = getNovelContextVersion(), novelId = Number(route.params.novelId) || 0
  const current = () => !disposed && token === operation && context === getNovelContextVersion() && novelId === (Number(route.params.novelId) || 0)
  busy.value = true; importBusy.value = true; message.value = ''; error.value = ''
  try {
    if (file.size > 16 * 1024 * 1024) throw new Error('外观文件超过 16 MiB')
    const data = parseAppearanceBackup(JSON.parse(await file.text()))
    if (!current()) return
    if (data.settings.backgroundImage.startsWith('data:')) imageFile(data.settings.backgroundImage, MAX_BACKGROUND_BYTES)
    const mapFile = data.mapBackground?.startsWith('data:') ? imageFile(data.mapBackground, MAX_IMAGE_BYTES) : undefined
    if (mapFile && !novelId) throw new Error('请在目标小说的设置中导入地图背景')
    if (!confirm('将替换本浏览器的背景与透明度，并应用文件中的地图背景。继续？')) return
    let selected = data.mapBackground
    if (mapFile) {
      const image = await imagesApi.upload(mapFile, 'map_background', novelId)
      if (!current()) return
      selected = String(image.id)
    }
    if (!current()) return
    const previousSettings = localStorage.getItem('app-settings'), previousMap = novelId ? localStorage.getItem(`mapBackground_${novelId}`) : null
    try {
      localStorage.setItem('app-settings', JSON.stringify(data.settings))
      if (novelId && selected !== undefined) {
        if (selected === 'auto') localStorage.removeItem(`mapBackground_${novelId}`)
        else localStorage.setItem(`mapBackground_${novelId}`, selected)
      }
    } catch (failure) {
      try {
        if (previousSettings === null) localStorage.removeItem('app-settings'); else localStorage.setItem('app-settings', previousSettings)
        if (novelId && selected !== undefined) {
          if (previousMap === null) localStorage.removeItem(`mapBackground_${novelId}`); else localStorage.setItem(`mapBackground_${novelId}`, previousMap)
        }
      } catch { /* Keep the active appearance intact even when browser storage cannot be restored. */ }
      throw failure
    }
    Object.assign(app.settings, data.settings)
    message.value = '外观偏好已恢复；地图背景将在下次打开地图时生效'
  } catch (failure) { if (current()) error.value = getImageUploadError(failure, MAX_IMAGE_BYTES) }
  finally { if (token === operation) { busy.value = false; importBusy.value = false } }
}
</script>
<style scoped>
p { font-size: 14px; line-height: 1.8; color: var(--muted); }
.appearance-error { color: var(--danger); }
.appearance-actions { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 12px; margin-top: 14px; }
.appearance-actions > button { min-height: 40px; }
@media (max-width: 600px) {
  .appearance-actions > button { width: 100%; }
}
</style>
