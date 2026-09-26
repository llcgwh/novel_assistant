<template>
  <div class="file-picker" :aria-busy="busy || undefined">
    <button
      class="btn-secondary file-picker__button"
      type="button"
      :disabled="disabled || busy"
      :aria-describedby="detailsId"
      @click="chooseFile"
    >
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="1.65"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M14 3H5v18h14V8l-5-5Zm0 0v5h5M12 17v-6m-3 3 3-3 3 3" />
      </svg>
      <span>{{ busy ? busyLabel : label }}</span>
    </button>
    <div :id="detailsId" class="file-picker__details" aria-live="polite">
      <span v-if="fileName" class="file-picker__name" :title="fileName">
        {{ fileName }}
      </span>
      <span class="file-picker__hint">{{ hint }}</span>
    </div>
    <input
      ref="input"
      class="file-picker__input"
      type="file"
      :accept="accept"
      :disabled="disabled || busy"
      :aria-label="label"
      aria-hidden="true"
      tabindex="-1"
      @change="onChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, useId } from 'vue'

const props = withDefaults(
  defineProps<{
    label: string
    accept: string
    hint: string
    disabled?: boolean
    busy?: boolean
    busyLabel?: string
  }>(),
  { disabled: false, busy: false, busyLabel: '处理中…' },
)
const emit = defineEmits<{ change: [event: Event] }>()
const input = ref<HTMLInputElement | null>(null)
const fileName = ref('')
const detailsId = useId()

function chooseFile() {
  if (props.disabled || props.busy) return
  input.value?.click()
}

function onChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (!target.files?.length || props.disabled || props.busy) return
  fileName.value = target.files[0].name
  emit('change', event)
  // Keep the filename above while allowing the same file to be selected again.
  target.value = ''
}
</script>

<style scoped>
.file-picker {
  position: relative;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 12px;
  flex: 1 1 300px;
  min-width: 0;
  max-width: 100%;
}
.file-picker .file-picker__button {
  flex: 0 0 auto;
  max-width: 100%;
  min-height: 40px;
  white-space: normal;
  text-align: left;
}
.file-picker__button svg {
  width: 17px;
  height: 17px;
  flex: 0 0 17px;
  color: var(--accent);
}
.file-picker__details {
  flex: 1 1 150px;
  min-width: 0;
  font-size: 12px;
  line-height: 1.6;
}
.file-picker__name {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
  color: var(--text);
}
.file-picker__hint {
  color: var(--muted);
  overflow-wrap: anywhere;
}
.file-picker > input.file-picker__input {
  position: absolute;
  width: 1px;
  height: 1px;
  max-width: 1px;
  padding: 0;
  margin: -1px;
  border: 0;
  opacity: 0;
  overflow: hidden;
  clip-path: inset(50%);
  pointer-events: none;
}
@media (max-width: 600px) {
  .file-picker,
  .file-picker .file-picker__button {
    width: 100%;
  }
  .file-picker .file-picker__button {
    justify-content: center;
  }
  .file-picker__details {
    flex-basis: 100%;
  }
}
</style>
