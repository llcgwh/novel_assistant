<template>
  <div class="image-upload" :class="{ 'image-upload-large': large }">
    <div v-if="previewUrl" class="image-preview">
      <img :src="previewUrl" :alt="previewAlt || '图片预览'" />
      <button type="button" class="remove-image-btn" :disabled="uploading" @click="removeImage" title="移除图片">&times;</button>
    </div>
    <button v-else type="button" class="image-upload-area" :disabled="uploading" @click="triggerUpload">
      <span class="upload-placeholder">
        <span class="upload-icon"><StudioIcon name="scenes"/></span>
        {{ placeholder || '点击上传图片' }}
      </span>
    </button>
    <div v-if="previewUrl" class="image-preview-actions">
      <button type="button" class="btn-secondary replace-image-btn" :disabled="uploading" @click="triggerUpload">更换图片</button>
      <a v-if="originalImageUrl && !uploading" class="btn-secondary" :href="originalImageUrl" target="_blank" rel="noopener noreferrer">查看原图 ↗</a>
    </div>
    <input
      ref="fileInput"
      type="file"
      :accept="IMAGE_ACCEPT"
      :disabled="uploading"
      style="display: none"
      @change="handleFileChange"
    />
    <p class="image-upload-hint">{{ IMAGE_FORMATS }} · 单张最多 {{ imageSizeLimit(maxBytes) }}<span v-if="imageType === 'background'"> · 仅保存在本浏览器</span></p>
    <div v-if="uploading" class="upload-progress" role="status">上传中…</div>
    <p v-if="errorMessage" class="image-upload-error" role="alert">{{ errorMessage }}</p>
  </div>
</template>

<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { imagesApi } from '@/api/images'
import StudioIcon from '@/components/common/StudioIcon.vue'
import { getImageMimeType, getImageUploadError, IMAGE_ACCEPT, IMAGE_FORMATS, imageSizeLimit, MAX_BACKGROUND_BYTES, MAX_IMAGE_BYTES, validateImageFile } from '@/utils/imageUpload'

const props = defineProps<{
  modelValue?: string  // 已有的图片 URL
  novelId?: number
  imageType?: string   // 图片类型: novel_cover, character_portrait, scene_image, background
  placeholder?: string
  large?: boolean
  previewAlt?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
  uploading: [value: boolean]
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const previewUrl = ref<string>(props.modelValue || '')
const uploading = ref(false)
const errorMessage = ref('')
const maxBytes = computed(() => props.imageType === 'background' ? MAX_BACKGROUND_BYTES : MAX_IMAGE_BYTES)
const originalImageUrl = computed(() => /^(https?:|blob:|\/)/i.test(previewUrl.value) ? previewUrl.value : '')

watch(() => props.modelValue, (val) => {
  previewUrl.value = val || ''
  errorMessage.value = ''
})

function triggerUpload() {
  if (uploading.value) return
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || uploading.value) return

  errorMessage.value = ''
  try { validateImageFile(file, maxBytes.value) }
  catch (error) {
    errorMessage.value = getImageUploadError(error, maxBytes.value)
    input.value = ''
    return
  }

  // 本地预览
  const localPreview = URL.createObjectURL(file)
  previewUrl.value = localPreview

  uploading.value = true
  emit('uploading', true)
  try {
    let fileUrl: string
    if (props.imageType === 'background') {
      fileUrl = await new Promise<string>((resolve, reject) => {
        const mime = getImageMimeType(file)
        const image = file.type === mime ? file : new File([file], file.name, { type: mime, lastModified: file.lastModified })
        const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = () => reject(new Error('无法读取图片，请重新选择')); reader.readAsDataURL(image)
      })
    } else {
      const result = await imagesApi.upload(file, props.imageType || 'other', props.novelId)
      fileUrl = imagesApi.getFileUrl(result.id, props.novelId)
    }
    previewUrl.value = fileUrl
    emit('update:modelValue', fileUrl)
  } catch (error) {
    previewUrl.value = props.modelValue || ''
    errorMessage.value = getImageUploadError(error, maxBytes.value)
  } finally {
    URL.revokeObjectURL(localPreview)
    uploading.value = false
    emit('uploading', false)
    // 清理 input
    if (input) input.value = ''
  }
}

function removeImage() {
  if (uploading.value) return
  errorMessage.value = ''
  previewUrl.value = ''
  emit('update:modelValue', '')
}
</script>

<style scoped>
.image-upload { min-width: 0; }
.image-preview-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-top: 10px; }
.image-preview-actions a { display: inline-flex; text-decoration: none; }
.image-upload-large .image-preview { display: flex; align-items: center; justify-content: center; height: min(56dvh, 560px); max-height: none; min-height: 240px; background: var(--field); border: 1px solid var(--line); }
.image-upload-large .image-preview img { display: block; width: 100%; height: 100%; max-height: none; object-fit: contain; }
.image-upload-large .image-upload-area { min-height: 280px; }
@media (max-width: 1199px) {
  .image-upload-large .image-preview { height: min(42dvh, 420px); min-height: 200px; }
  .image-upload-large .image-upload-area { min-height: 200px; }
}
.image-upload-hint, .image-upload-error { margin: 8px 0 0; font-size: 12px; line-height: 1.6; overflow-wrap: anywhere; }
.image-upload-hint { color: var(--muted); }
.image-upload-error { color: var(--danger); }
</style>
