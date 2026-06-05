<template>
  <div class="image-upload">
    <div v-if="previewUrl" class="image-preview">
      <img :src="previewUrl" alt="预览" />
      <button class="remove-image-btn" @click="removeImage" title="移除图片">&times;</button>
    </div>
    <div v-else class="image-upload-area" @click="triggerUpload">
      <span class="upload-placeholder">
        <span class="upload-icon">🖼️</span>
        {{ placeholder || '点击上传图片' }}
      </span>
    </div>
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

const props = defineProps<{
  modelValue?: string  // 已有的图片 URL
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
  if (!file) return

  // 本地预览
  const localPreview = URL.createObjectURL(file)
  previewUrl.value = localPreview

  uploading.value = true
  try {
    const result = await imagesApi.upload(file, props.imageType || 'other')
    const fileUrl = imagesApi.getFileUrl(result.id)
    emit('update:modelValue', fileUrl)
  } catch (error) {
    console.error('Image upload failed:', error)
    previewUrl.value = props.modelValue || ''
    alert('图片上传失败，请重试')
  } finally {
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
