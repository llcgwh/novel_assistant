<template>
  <section class="series-field-comparison" aria-label="来源与本作逐字段比较">
    <p class="series-comparison-help">勾选要采用的字段；未勾选的字段保留本作文字。空字符串也是明确的来源内容，采用后会清空本作对应字段。</p>
    <article v-for="field in fields" :key="field.key" class="series-diff-field" :class="{ conflict: field.conflict }">
      <header>
        <h4>{{ seriesFieldLabel(kind, field.key) }}</h4>
        <div class="series-diff-tags">
          <span v-if="field.conflict" class="series-conflict">双方修改且不同</span>
          <span v-if="field.sourceChanged">来源已修改</span>
          <span v-if="field.localChanged">本作已修改</span>
          <span v-if="!field.different">本作与所选版本相同</span>
          <span v-else-if="!field.conflict">本作与所选版本不同</span>
        </div>
      </header>
      <div class="series-diff-columns">
        <section>
          <h5>上次比较基线</h5>
          <p><span v-if="field.base === ''" class="series-empty">（空）</span><span v-else>{{ field.base }}</span></p>
        </section>
        <section>
          <h5>本作内容</h5>
          <p><span v-if="field.local === ''" class="series-empty">（空）</span><span v-else>{{ field.local }}</span></p>
        </section>
        <section>
          <h5>选定母本版本</h5>
          <p><span v-if="field.incoming === ''" class="series-empty">（空）</span><span v-else>{{ field.incoming }}</span></p>
        </section>
      </div>
      <footer>
        <label>
          <input
            type="checkbox"
            :checked="modelValue.includes(field.key)"
            :disabled="disabled"
            @change="selectField(field.key, $event)"
          />
          <span>采用所选版本的{{ seriesFieldLabel(kind, field.key) }}<strong v-if="field.incoming === '' && field.local !== ''">（将清空本作此字段）</strong></span>
        </label>
        <span v-if="!modelValue.includes(field.key)" class="series-kept">保留本作</span>
      </footer>
    </article>
  </section>
</template>

<script setup lang="ts">
import type { SeriesFieldDiff, SeriesKind } from '@/types/series'
import { seriesFieldLabel } from '@/utils/series'

const props = withDefaults(defineProps<{
  kind: SeriesKind
  fields: SeriesFieldDiff[]
  modelValue: string[]
  disabled?: boolean
}>(), { disabled: false })
const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()

function selectField(key: string, event: Event) {
  if (props.disabled || !props.fields.some(field => field.key === key)) return
  const checked = (event.target as HTMLInputElement | null)?.checked
  if (typeof checked !== 'boolean') return
  const selection = new Set(props.modelValue)
  if (checked) selection.add(key)
  else selection.delete(key)
  emit('update:modelValue', [...selection])
}
</script>

<style scoped>
.series-field-comparison { min-width: 0; color: var(--text); }
.series-comparison-help { margin: 0 0 16px; color: var(--muted); font-size: 12px; line-height: 1.8; }
.series-diff-field { min-width: 0; margin-top: 16px; border: 1px solid var(--line); border-radius: 12px; overflow: hidden; }
.series-diff-field.conflict { border-color: var(--warning, var(--accent)); }
.series-diff-field header { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 14px; padding: 12px 14px; background: var(--panel); }
.series-diff-field h4 { margin: 0; font-size: 13px; font-weight: 600; }
.series-diff-tags { display: flex; flex-wrap: wrap; gap: 5px 10px; color: var(--muted); font-size: 11px; line-height: 1.7; }
.series-diff-tags .series-conflict { color: var(--warning, var(--accent)); }
.series-diff-columns { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); }
.series-diff-columns section { min-width: 0; padding: 14px; }
.series-diff-columns section + section { border-left: 1px solid var(--line); }
.series-diff-columns h5 { margin: 0 0 10px; color: var(--muted); font-size: 11px; font-weight: 500; }
.series-diff-columns p { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.9; }
.series-empty { color: var(--muted); }
.series-diff-field footer { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 14px; padding: 10px 14px; border-top: 1px solid var(--line); }
.series-diff-field label { display: flex; align-items: flex-start; gap: 9px; min-width: 0; padding: 5px 0; cursor: pointer; font-size: 12px; line-height: 1.8; }
.series-diff-field label span { min-width: 0; overflow-wrap: anywhere; }
.series-diff-field label strong { color: var(--warning, var(--accent)); font-weight: 500; }
.series-diff-field input { flex: 0 0 auto; width: 17px; height: 17px; margin: 2px 0 0; accent-color: var(--accent); }
.series-diff-field input:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.series-diff-field input:disabled { cursor: not-allowed; }
.series-kept { color: var(--muted); font-size: 11px; }
@media (max-width: 720px) {
  .series-diff-columns { grid-template-columns: minmax(0, 1fr); }
  .series-diff-columns section + section { border-left: 0; border-top: 1px solid var(--line); }
}
</style>
