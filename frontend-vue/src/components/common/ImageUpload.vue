<template>
  <div class="image-upload">
    <div v-if="previewUrl" class="image-preview">
      <img :src="previewUrl" alt="预览" />
      <button type="button" class="remove-image-btn" :disabled="uploading" @click="removeImage" title="移除图片">&times;</button>
    </div>
    <button v-else type="button" class="image-upload-area" :disabled="uploading" @click="triggerUpload">
      <span class="upload-placeholder">
        <span class="upload-icon"><StudioIcon name="scenes"/></span>
        {{ placeholder || '点击上传图片' }}
      </span>
    </button>
    <input
      ref="fileInput"
      type="file"
      accept="image/*"
      style="display: none"
      @change="handleFileChange"
    />
    <div v-if="uploading" class="upload-progress">上传中...</div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { imagesApi } from '@/api/images'
import StudioIcon from '@/components/common/StudioIcon.vue'

const props = defineProps<{
  modelValue?: string  // 已有的图片 URL
  novelId?: number
  imageType?: string   // 图片类型: novel_cover, character_portrait, scene_image, background
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const fileInput = ref<HTMLInputElement | null>(null)
const previewUrl = ref<string>(props.modelValue || '')
const uploading = ref(false)

watch(() => props.modelValue, (val) => {
  previewUrl.value = val || ''
})

function triggerUpload() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file || uploading.value) return

  // 本地预览
  const localPreview = URL.createObjectURL(file)
  previewUrl.value = localPreview

  uploading.value = true
  try {
    let fileUrl: string
    if (props.imageType === 'background') {
      if (file.size > 2 * 1024 * 1024 || !/^image\/(png|jpeg|gif|webp|bmp)$/.test(file.type)) throw new Error('请使用 2 MiB 以内的 PNG、JPEG、GIF 或 WebP 图片')
      fileUrl = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(file)
      })
    } else {
      const result = await imagesApi.upload(file, props.imageType || 'other', props.novelId)
      fileUrl = imagesApi.getFileUrl(result.id, props.novelId)
    }
    previewUrl.value = fileUrl
    emit('update:modelValue', fileUrl)
  } catch (error) {
    console.error('Image upload failed:', error)
    previewUrl.value = props.modelValue || ''
    alert(error instanceof Error ? error.message : '图片上传失败，请重试')
  } finally {
    URL.revokeObjectURL(localPreview)
    uploading.value = false
    // 清理 input
    if (input) input.value = ''
  }
}

function removeImage() {
  previewUrl.value = ''
  emit('update:modelValue', '')
}
</script>
