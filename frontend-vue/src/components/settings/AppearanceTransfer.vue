<template>
  <section class="settings-section appearance-transfer">
    <h3>外观偏好迁移</h3>
    <p>单独备份背景图片、透明度和当前小说的地图背景选择。外部图片保留链接；导入会替换本浏览器的外观设置。</p>
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
    <p role="status">{{ message }}</p>
  </section>
</template>
<script setup lang="ts">
import { ref } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { getApiBaseUrl, request } from '@/api/request'
import { imagesApi } from '@/api/images'
import { parseAppearanceBackup } from '@/utils/appearance'
import BaseFilePicker from '@/components/common/BaseFilePicker.vue'
const route = useRoute()
const app = useAppStore()
const busy = ref(false)
const importBusy = ref(false)
const message = ref('')
async function embeddedImage(url: string): Promise<string> {
  if (!/^https?:.*\/api\/novels\/\d+\/images\/\d+\/file$/.test(url) && !url.startsWith('/api/novels/')) return url
  const path = new URL(url, location.origin).pathname.replace(/^\/api/, '')
  const blob: Blob = await request.get(path, { responseType: 'blob' })
  if (blob.size > 2 * 1024 * 1024) throw new Error('外观背景超过 2 MiB，请先压缩图片')
  return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(blob) })
}
async function exportAppearance() {
  if (busy.value) return
  busy.value = true; message.value = ''
  try {
    const novelId = Number(route.params.novelId)
    const selected = novelId ? localStorage.getItem(`mapBackground_${novelId}`) : null
    const mapBackground = selected === 'none' ? 'none' : selected && /^\d+$/.test(selected)
      ? await embeddedImage(`${getApiBaseUrl()}/novels/${novelId}/images/${selected}/file`) : 'auto'
    const settings = { ...app.settings, backgroundImage: await embeddedImage(app.settings.backgroundImage) }
    const blob = new Blob([JSON.stringify({ format: 'novel-appearance-v1', settings, ...(novelId ? { mapBackground } : {}) })], { type: 'application/json' })
    const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'novel-appearance.json'; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
    message.value = '外观偏好已导出'
  } catch (error) { message.value = error instanceof Error ? error.message : '导出失败' }
  finally { busy.value = false }
}
async function importAppearance(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''
  if (!file || busy.value) return
  busy.value = true; importBusy.value = true; message.value = ''
  try {
    if (file.size > 16 * 1024 * 1024) throw new Error('外观文件超过 16 MiB')
    const data = parseAppearanceBackup(JSON.parse(await file.text()))
    if (!confirm('将替换本浏览器的背景与透明度，并应用文件中的地图背景。继续？')) return
    const novelId = Number(route.params.novelId)
    let selected = data.mapBackground
    if (selected?.startsWith('data:')) {
      if (!novelId) throw new Error('请在目标小说的设置中导入地图背景')
      const mime = selected.slice(5, selected.indexOf(';'))
      const bytes = Uint8Array.from(atob(selected.slice(selected.indexOf(',') + 1)), c => c.charCodeAt(0))
      const image = await imagesApi.upload(new File([bytes], 'map.' + mime.split('/')[1], { type: mime }), 'map_background')
      selected = String(image.id)
    }
    localStorage.setItem('app-settings', JSON.stringify(data.settings))
    if (novelId && selected !== undefined) {
      if (selected === 'auto') localStorage.removeItem(`mapBackground_${novelId}`)
      else localStorage.setItem(`mapBackground_${novelId}`, selected)
    }
    Object.assign(app.settings, data.settings)
    message.value = '外观偏好已恢复；地图背景将在下次打开地图时生效'
  } catch (error) { message.value = error instanceof Error ? error.message : '导入失败，请检查文件和浏览器存储空间' }
  finally { busy.value = false; importBusy.value = false }
}
</script>
<style scoped>
p { font-size: 14px; line-height: 1.8; color: var(--muted); }
.appearance-actions { display: flex; align-items: flex-start; flex-wrap: wrap; gap: 12px; margin-top: 14px; }
.appearance-actions > button { min-height: 40px; }
@media (max-width: 600px) {
  .appearance-actions > button { width: 100%; }
}
</style>
