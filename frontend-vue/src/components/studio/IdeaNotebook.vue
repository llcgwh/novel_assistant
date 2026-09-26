<template>
  <section class="studio-panel idea-notebook">
    <div class="panel-heading">
      <h3><StudioIcon name="pen" />灵感便笺</h3>
      <button
        class="icon-button"
        aria-label="导出灵感便笺"
        :disabled="!note.trim()"
        @click="download"
      >
        <StudioIcon name="download" />
      </button>
    </div>
    <textarea
      v-model="note"
      maxlength="20000"
      aria-label="灵感便笺"
      placeholder="一个突然浮现的画面，一句还没找到归属的对白…&#10;&#10;先写在这里，让灵感有处安放。"
    ></textarea>
    <div class="notebook-footer">
      <span :class="{ 'text-danger': failed }" role="status">{{
        failed ? '未能保存，请先导出便笺' : '仅保存在此浏览器 · 自动保存'
      }}</span
      ><span>{{ note.length }} 字符</span>
    </div>
  </section>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { notebookKey } from '@/utils/studio'
import StudioIcon from '@/components/common/StudioIcon.vue'
const props = defineProps<{ novelId: number }>()
const key = notebookKey(props.novelId),
  note = ref(''),
  failed = ref(false)
try {
  note.value = localStorage.getItem(key) || ''
} catch {
  failed.value = true
}
watch(
  note,
  (value) => {
    try {
      localStorage.setItem(key, value)
      failed.value = false
    } catch {
      failed.value = true
    }
  },
  { flush: 'sync' },
)
function download() {
  const url = URL.createObjectURL(
    new Blob([note.value], { type: 'text/markdown;charset=utf-8' }),
  )
  const link = document.createElement('a')
  link.href = url
  link.download = `灵感便笺-${props.novelId}.md`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
</script>
