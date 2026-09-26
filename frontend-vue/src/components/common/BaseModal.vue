<template>
  <Teleport to="body">
    <div class="modal" @click.self="close">
      <div ref="panel" class="modal-content" role="dialog" aria-modal="true" :aria-label="title" :aria-busy="pending" tabindex="-1">
        <h2>{{ title }}</h2>
        <fieldset class="modal-fields" :disabled="pending" :inert="pending">
          <slot></slot>
        </fieldset>
        <fieldset class="modal-actions modal-fields" :disabled="pending">
          <slot name="actions" :busy="pending">
            <button type="button" class="btn-secondary" :disabled="pending" @click="close">取消</button>
            <button type="button" class="btn-primary" :disabled="pending" @click="confirm">{{ pending ? '处理中…' : '确定' }}</button>
          </slot>
        </fieldset>
        <p v-if="submitError" class="modal-error" role="alert">{{ submitError }}</p>
      </div>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useDialog } from '@/composables/useDialog'
const props = withDefaults(defineProps<{
  title: string
  busy?: boolean
  submit?: () => unknown | Promise<unknown>
}>(), { busy: false })
const emit = defineEmits<{ close: []; confirm: [] }>()
const panel = ref<HTMLElement | null>(null)
const submitting = ref(false)
const submitError = ref('')
const pending = computed(() => props.busy || submitting.value)
function close() { if (!pending.value) emit('close') }
async function confirm() {
  if (pending.value) return
  submitting.value = true
  submitError.value = ''
  try {
    if (props.submit) await props.submit()
    else emit('confirm')
  } catch { submitError.value = '操作失败，请重试。' }
  finally { submitting.value = false }
}
useDialog(panel, () => pending.value, close)
</script>

<style scoped>
.modal-fields { border: 0; padding: 0; min-width: 0; }
.modal-fields:not(.modal-actions) { margin: 0; }
.modal-fields:disabled { pointer-events: none; }
.modal-content:focus { outline: none; }
.modal-content :focus-visible { outline: 2px solid #8871cf; outline-offset: 3px; }
.modal-error { color: #a02735; font-size: 14px; margin-top: 12px; }
</style>
