<template>
  <Teleport to="body">
    <dialog ref="panel" class="modal-content backup-cleanup-dialog" :aria-label="title" :aria-busy="busy" @cancel.prevent="close" @click="backdropClick">
      <h2>{{ title }}</h2>
      <fieldset class="cleanup-fields" :disabled="busy" :inert="busy"><slot /></fieldset>
      <fieldset class="modal-actions cleanup-fields" :disabled="busy"><slot name="actions" /></fieldset>
    </dialog>
  </Teleport>
</template>
<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { useDialog } from '@/composables/useDialog'
const props = withDefaults(defineProps<{ title: string; busy?: boolean }>(), { busy: false })
const emit = defineEmits<{ close: [] }>()
const panel = ref<HTMLDialogElement | null>(null)
function close() { if (!props.busy) emit('close') }
function backdropClick(event: MouseEvent) {
  const dialog = panel.value
  if (!dialog || event.target !== dialog) return
  const bounds = dialog.getBoundingClientRect()
  if (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom) close()
}
// Close the native top layer before useDialog restores focus to the page.
onBeforeUnmount(() => panel.value?.close())
useDialog(panel, () => props.busy, close)
onMounted(() => panel.value?.showModal())
</script>
<style scoped>
.backup-cleanup-dialog {
  position: fixed;
  inset: 0;
  margin: auto;
  width: min(960px, calc(100vw - 32px));
  max-width: calc(100vw - 32px);
  height: fit-content;
  max-height: calc(100dvh - 48px);
  box-sizing: border-box;
  overflow-y: auto;
  overscroll-behavior: contain;
  color: var(--text);
}
.backup-cleanup-dialog::backdrop { background: #03090dd1; backdrop-filter: blur(12px); }
.backup-cleanup-dialog:focus { outline: none; }
.backup-cleanup-dialog :focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.cleanup-fields { border: 0; padding: 0; min-width: 0; margin: 0; }
.cleanup-fields:disabled { pointer-events: none; }
.modal-actions { margin-top: 24px; }
@media (max-width: 600px) {
  .backup-cleanup-dialog { max-height: calc(100dvh - 24px); }
  .modal-actions { flex-wrap: wrap; }
  .modal-actions :slotted(button) { flex: 1 1 120px; }
}
</style>
