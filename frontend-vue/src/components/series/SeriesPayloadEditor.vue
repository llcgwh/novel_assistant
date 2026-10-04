<template>
  <fieldset class="series-payload-editor" :disabled="disabled">
    <div v-for="field in fields" :key="field.key" class="series-payload-field">
      <label :for="fieldId(field.key)">{{ field.label }}<span v-if="field.key === 'name'" class="required">（必填）</span></label>
      <input
        v-if="field.key === 'name'"
        :id="fieldId(field.key)"
        type="text"
        :value="modelValue[field.key]"
        :maxlength="field.maxLength"
        :disabled="disabled"
        required
        @input="updateField(field.key, $event)"
      />
      <textarea
        v-else
        :id="fieldId(field.key)"
        :value="modelValue[field.key]"
        :maxlength="field.maxLength"
        :disabled="disabled"
        rows="4"
        @input="updateField(field.key, $event)"
      />
    </div>
  </fieldset>
</template>

<script setup lang="ts">
import { computed, useId } from 'vue'
import type { SeriesKind, SeriesPayload } from '@/types/series'
import { cloneSeries, seriesFields } from '@/utils/series'

const props = withDefaults(defineProps<{
  kind: SeriesKind
  modelValue: SeriesPayload
  disabled?: boolean
  idPrefix?: string
}>(), { disabled: false, idPrefix: '' })
const emit = defineEmits<{ 'update:modelValue': [value: SeriesPayload] }>()
const localId = useId()
const fields = computed(() => seriesFields(props.kind))
const fieldId = (key: string) => `${props.idPrefix || `series-payload-${localId}`}-${props.kind}-${key}`

function updateField(key: string, event: Event) {
  if (props.disabled || !fields.value.some(field => field.key === key)) return
  const value = (event.target as HTMLInputElement | HTMLTextAreaElement | null)?.value
  if (typeof value !== 'string') return
  emit('update:modelValue', { ...cloneSeries(props.modelValue), [key]: value })
}
</script>

<style scoped>
.series-payload-editor { display: grid; gap: 16px; min-width: 0; margin: 0; padding: 0; border: 0; color: var(--text); }
.series-payload-field { display: grid; gap: 7px; min-width: 0; }
.series-payload-field label { font-size: 13px; line-height: 1.6; }
.required { color: var(--muted); font-size: 12px; }
.series-payload-field input,
.series-payload-field textarea { box-sizing: border-box; width: 100%; min-width: 0; max-width: 100%; margin: 0; padding: 10px 12px; border: 1px solid var(--line); border-radius: 9px; background: var(--bg); color: var(--text); font: inherit; font-size: 14px; line-height: 1.7; }
.series-payload-field textarea { resize: vertical; white-space: pre-wrap; overflow-wrap: anywhere; }
.series-payload-field :focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.series-payload-field :disabled { opacity: 0.65; cursor: not-allowed; }
</style>
