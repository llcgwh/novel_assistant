<template>
  <Teleport to="body">
    <div class="modal" @click.self="close">
      <div ref="panel" class="modal-content" :class="{ 'modal-wide': wide }" role="dialog" aria-modal="true" :aria-label="title" :aria-busy="pending" :aria-hidden="discardOpen || undefined" :inert="discardOpen" tabindex="-1" @focusin="rememberFocus">
        <h2>{{ title }}</h2>
        <fieldset class="modal-fields" :disabled="pending" :inert="pending">
          <slot></slot>
        </fieldset>
        <fieldset class="modal-actions modal-fields" :disabled="pending">
          <slot name="actions" :busy="pending" :close="close">
            <button type="button" class="btn-secondary" :disabled="pending" @click="close">取消</button>
            <button type="button" class="btn-primary" :disabled="pending" @click="confirm">{{ pending ? '处理中…' : '确定' }}</button>
          </slot>
        </fieldset>
        <p v-if="submitError" class="modal-error" role="alert">{{ submitError }}</p>
      </div>
    </div>
  </Teleport>
  <DiscardChangesDialog v-if="discardOpen" :busy="pending" :close-warning="closeWarning" :discard-label="discardLabel" @continue="continueEditing" @discard="discardChanges" />
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'
import { useDialog } from '@/composables/useDialog'
import { provideModalDrafts } from '@/composables/modalDrafts'
import { draftFingerprint, draftHasChanges } from '@/utils/modalDraft'
import DiscardChangesDialog from './DiscardChangesDialog.vue'
const props = withDefaults(defineProps<{
  title: string
  wide?: boolean
  busy?: boolean
  submit?: () => unknown | Promise<unknown>
  draft?: unknown
  dirty?: boolean
  closeWarning?: string
  discardLabel?: string
}>(), { busy: false, dirty: undefined })
const emit = defineEmits<{ close: []; confirm: [] }>()
const panel = ref<HTMLElement | null>(null)
const submitting = ref(false)
const submitError = ref('')
const discardOpen = ref(false)
const initialDraft = draftFingerprint(props.draft)
const childChanges = provideModalDrafts()
const hasChanges = computed(() => draftHasChanges(initialDraft, props.draft, props.dirty) || childChanges.value)
let editorFocus: HTMLElement | null = null
const pending = computed(() => props.busy || submitting.value)
function rememberFocus(event: FocusEvent) { editorFocus = event.target as HTMLElement }
function close() {
  if (pending.value || discardOpen.value) return
  if (hasChanges.value) discardOpen.value = true
  else emit('close')
}
async function continueEditing() {
  if (pending.value) return
  discardOpen.value = false
  await nextTick()
  if (editorFocus?.isConnected && !editorFocus.closest('[inert]')) editorFocus.focus()
  else panel.value?.focus()
}
async function discardChanges() {
  if (pending.value || !discardOpen.value) return
  discardOpen.value = false
  // Remove the nested dialog first so the editor becomes the top dialog again
  // and its normal unmount restores the original page opener.
  await nextTick()
  if (!pending.value) emit('close')
}
async function confirm() {
  if (pending.value || discardOpen.value) return
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
.modal-content.modal-wide { width: min(960px, calc(100vw - 32px)); max-width: 960px; }
.modal-content :focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.modal-error { color: var(--danger); font-size: 14px; margin-top: 12px; }
</style>
