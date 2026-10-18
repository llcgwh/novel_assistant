<template>
  <Teleport :to="teleportTo || 'body'">
    <div class="modal discard-changes-overlay" @click.self="continueEditing">
      <section ref="panel" class="modal-content discard-changes-dialog" role="alertdialog" aria-modal="true" aria-label="离开当前编辑？" :aria-busy="busy" tabindex="-1">
        <h2>离开当前编辑？</h2>
        <p>{{ closeWarning || '当前修改尚未保存，离开后将丢失这些修改。' }}</p>
        <div class="modal-actions">
          <button type="button" class="btn-primary" :disabled="busy" @click="continueEditing">继续编辑</button>
          <button type="button" class="btn-secondary" :disabled="busy" @click="discard">{{ discardLabel || '放弃修改' }}</button>
        </div>
      </section>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useDialog } from '@/composables/useDialog'
const props = withDefaults(defineProps<{
  busy?: boolean
  closeWarning?: string
  discardLabel?: string
  teleportTo?: string | HTMLElement
}>(), { busy: false })
const emit = defineEmits<{ continue: []; discard: [] }>()
const panel = ref<HTMLElement | null>(null)
function continueEditing() { if (!props.busy) emit('continue') }
function discard() { if (!props.busy) emit('discard') }
useDialog(panel, () => props.busy, continueEditing)
</script>

<style scoped>
.discard-changes-overlay { z-index: 140; }
.discard-changes-dialog { width: min(460px, calc(100vw - 32px)); max-width: 460px; }
.discard-changes-dialog p { color: var(--muted); line-height: 1.75; margin: 0; }
.discard-changes-dialog:focus { outline: none; }
.discard-changes-dialog :focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.discard-changes-dialog .modal-actions { flex-wrap: wrap; gap: 10px; }
</style>
