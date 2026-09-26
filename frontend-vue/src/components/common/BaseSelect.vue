<template>
  <button
    ref="trigger"
    v-bind="$attrs"
    type="button"
    class="base-select"
    :class="{ 'is-open': open, 'is-placeholder': !selected }"
    role="combobox"
    aria-haspopup="listbox"
    :aria-label="($attrs['aria-label'] as string) || placeholder || undefined"
    :aria-expanded="open"
    :aria-controls="open ? menuId : undefined"
    :aria-activedescendant="
      open && activeIndex >= 0 ? `${menuId}-${activeIndex}` : undefined
    "
    :disabled="disabled"
    @click="open ? close() : show()"
    @keydown="keydown"
    @blur="close"
  >
    <span class="base-select-value" :title="selected?.label">{{
      selected?.label || placeholder || '请选择'
    }}</span>
    <svg class="base-select-chevron" viewBox="0 0 20 20" aria-hidden="true">
      <path d="m5 7.5 5 5 5-5" />
    </svg>
  </button>
  <Teleport to="body">
    <ul
      v-if="open"
      :id="menuId"
      ref="menu"
      class="base-select-menu"
      role="listbox"
      :aria-label="($attrs['aria-label'] as string) || placeholder || '可选项'"
      :style="placement"
      @pointerdown.prevent
    >
      <li
        v-for="(option, index) in options"
        :id="`${menuId}-${index}`"
        :key="`${typeof option.value}:${option.value}`"
        role="option"
        :aria-selected="option.value === modelValue"
        :aria-disabled="option.disabled || undefined"
        :class="{
          'is-active': index === activeIndex,
          'is-selected': option.value === modelValue,
          'is-disabled': option.disabled,
        }"
        @pointermove="!option.disabled && (activeIndex = index)"
        @click="choose(index)"
      >
        <svg class="base-select-check" viewBox="0 0 20 20" aria-hidden="true">
          <path v-if="option.value === modelValue" d="m4 10 4 4 8-8" />
        </svg>
        <span>{{ option.label }}</span>
      </li>
      <li v-if="!options.length" class="base-select-empty" role="presentation">
        暂无可选项
      </li>
    </ul>
  </Teleport>
</template>
<script setup lang="ts" generic="T extends string | number | null | undefined">
import { computed, nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'
import {
  selectMenuBox,
  moveSelectIndex,
  matchSelectOption,
  type SelectOption,
} from '@/utils/select'
defineOptions({ inheritAttrs: false })
const props = defineProps<{
  modelValue?: T
  options: readonly SelectOption[]
  placeholder?: string
  disabled?: boolean
}>()
const emit = defineEmits<{
  'update:modelValue': [value: T]
  change: [value: T]
}>()
const trigger = ref<HTMLButtonElement | null>(null),
  menu = ref<HTMLUListElement | null>(null)
const open = ref(false),
  activeIndex = ref(-1)
const menuId = `ink-select-${useId()}`
const placement = ref<Record<string, string>>({})
const selected = computed(() =>
  props.options.find((option) => option.value === props.modelValue),
)
let search = '',
  lastKeyAt = 0
function close() {
  open.value = false
  search = ''
  document.removeEventListener('pointerdown', outside, true)
  window.removeEventListener('resize', close)
  document.removeEventListener('scroll', scrolled, true)
}
async function revealActive() {
  await nextTick()
  menu.value
    ?.querySelector<HTMLElement>(`[id="${menuId}-${activeIndex.value}"]`)
    ?.scrollIntoView({ block: 'nearest' })
}
function show(edge?: 'first' | 'last') {
  if (props.disabled || !trigger.value || trigger.value.matches(':disabled'))
    return
  const box = selectMenuBox(
    trigger.value.getBoundingClientRect(),
    window.innerWidth,
    window.innerHeight,
    props.options.length,
  )
  placement.value = {
    left: `${box.left}px`,
    top: `${box.top}px`,
    width: `${box.width}px`,
    maxHeight: `${box.height}px`,
  }
  const current = props.options.findIndex(
    (option) => option.value === props.modelValue && !option.disabled,
  )
  activeIndex.value =
    edge === 'last'
      ? moveSelectIndex(props.options, -1, -1)
      : edge === 'first' || current < 0
        ? moveSelectIndex(props.options, -1, 1)
        : current
  open.value = true
  document.addEventListener('pointerdown', outside, true)
  window.addEventListener('resize', close)
  document.addEventListener('scroll', scrolled, true)
  void revealActive()
}
function choose(index: number) {
  const option = props.options[index]
  if (
    !option ||
    option.disabled ||
    props.disabled ||
    trigger.value?.matches(':disabled')
  )
    return
  close()
  emit('update:modelValue', option.value as T)
  emit('change', option.value as T)
}
function outside(event: PointerEvent) {
  if (
    !trigger.value?.contains(event.target as Node) &&
    !menu.value?.contains(event.target as Node)
  )
    close()
}
function scrolled(event: Event) {
  if (menu.value?.contains(event.target as Node) || !trigger.value) return
  const rect = trigger.value.getBoundingClientRect()
  if (rect.bottom <= 0 || rect.top >= innerHeight) {
    close()
    return
  }
  const box = selectMenuBox(rect, innerWidth, innerHeight, props.options.length)
  placement.value = {
    left: `${box.left}px`,
    top: `${box.top}px`,
    width: `${box.width}px`,
    maxHeight: `${box.height}px`,
  }
}
function keydown(event: KeyboardEvent) {
  if (event.key === 'Escape' && open.value) {
    event.preventDefault()
    event.stopPropagation()
    close()
  } else if (event.key === 'Tab') close()
  else if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    if (!open.value)
      show(event.key === 'ArrowUp' && !selected.value ? 'last' : undefined)
    else {
      activeIndex.value = moveSelectIndex(
        props.options,
        activeIndex.value,
        event.key === 'ArrowDown' ? 1 : -1,
      )
      void revealActive()
    }
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault()
    show(event.key === 'Home' ? 'first' : 'last')
  } else if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    if (open.value) choose(activeIndex.value)
    else show()
  } else if (
    event.key.length === 1 &&
    !event.ctrlKey &&
    !event.metaKey &&
    !event.altKey &&
    !event.isComposing
  ) {
    event.preventDefault()
    const now = Date.now()
    search = now - lastKeyAt > 700 ? event.key : search + event.key
    lastKeyAt = now
    if (!open.value) show()
    const next = matchSelectOption(props.options, search, activeIndex.value)
    if (next >= 0) {
      activeIndex.value = next
      void revealActive()
    }
  }
}
watch(
  () => props.disabled,
  (value) => {
    if (value) close()
  },
)
watch(
  () => JSON.stringify(props.options),
  () => {
    if (open.value) close()
  },
)
onBeforeUnmount(close)
</script>
<style scoped>
.base-select {
  position: relative;
  display: inline-flex;
  align-items: center;
  width: var(--select-width, 100%);
  max-width: 100%;
  min-width: 0;
  min-height: var(--select-height, 40px);
  padding: var(--select-padding-y, 10px) 42px var(--select-padding-y, 10px) 14px;
  border: 1px solid var(--line);
  border-radius: 9px;
  background: var(--field);
  color: var(--text);
  font: inherit;
  font-size: var(--select-font-size, 13px);
  line-height: 1.4;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s,
    background 0.15s;
}
.base-select-value {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.base-select-chevron {
  position: absolute;
  right: 14px;
  top: 50%;
  width: 16px;
  height: 16px;
  transform: translateY(-50%);
  color: var(--muted);
  pointer-events: none;
}
.base-select-chevron,
.base-select-check {
  fill: none;
  stroke: currentColor;
  stroke-width: 1.6;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.base-select:hover:not(:disabled),
.base-select.is-open {
  border-color: var(--accent);
}
.base-select:focus-visible {
  outline: 2px solid var(--accent-soft);
  outline-offset: 3px;
  border-color: var(--accent);
}
.base-select:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.base-select.is-placeholder {
  color: var(--muted);
}
.base-select.is-open .base-select-chevron {
  transform: translateY(-50%) rotate(180deg);
}
.base-select-menu {
  position: fixed;
  z-index: 2500;
  box-sizing: border-box;
  overflow-y: auto;
  overscroll-behavior: contain;
  margin: 0;
  padding: 6px;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: var(--panel);
  color: var(--text);
  box-shadow:
    0 16px 46px #0003,
    0 2px 6px #0001;
  font: 13px/1.5 var(--sans, system-ui, sans-serif);
}
.base-select-menu > li {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 7px;
  cursor: pointer;
  overflow-wrap: anywhere;
}
.base-select-check {
  width: 15px;
  height: 15px;
  flex: 0 0 15px;
}
.base-select-menu > .is-selected {
  color: var(--accent);
}
.base-select-menu > .is-active {
  background: var(--accent-soft);
  border-color: var(--line);
}
.base-select-menu > .is-disabled,
.base-select-menu > .base-select-empty {
  color: var(--faint);
  cursor: default;
}
@media (prefers-reduced-motion: reduce) {
  .base-select {
    transition: none;
  }
}
</style>
