<template>
  <div class="base-select" ref="selectRef">
    <div class="select-trigger" @click="open = !open" :class="{ open }">
      <span class="selected-text" :class="{ placeholder: !selectedLabel }">
        {{ selectedLabel || placeholder }}
      </span>
      <span class="arrow" :class="{ flipped: open }">▾</span>
    </div>
    <div v-if="open" class="select-dropdown">
      <div
        v-for="option in options"
        :key="option.value"
        class="select-option"
        :class="{ active: modelValue === option.value }"
        @click="select(option.value)"
      >
        <span v-if="option.color" class="option-color" :style="{ background: option.color }"></span>
        {{ option.label }}
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'

interface SelectOption {
  value: string
  label: string
  color?: string
}

const props = defineProps<{
  modelValue: string
  options: SelectOption[]
  placeholder?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: string]
}>()

const open = ref(false)
const selectRef = ref<HTMLElement | null>(null)

const selectedLabel = computed(() => {
  const opt = props.options.find(o => o.value === props.modelValue)
  return opt ? opt.label : ''
})

function select(value: string) {
  emit('update:modelValue', value)
  open.value = false
}

function onClickOutside(e: MouseEvent) {
  if (selectRef.value && !selectRef.value.contains(e.target as Node)) {
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onClickOutside))
onUnmounted(() => document.removeEventListener('click', onClickOutside))
</script>

<style scoped>
.base-select {
  position: relative;
  width: 100%;
  user-select: none;
}

.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid rgba(0, 0, 0, 0.1);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.6);
  backdrop-filter: blur(12px);
  cursor: pointer;
  transition: all 0.3s ease;
  font-size: 14px;
  color: #2d3436;
}

.select-trigger:hover {
  background: rgba(255, 255, 255, 0.8);
  border-color: rgba(0, 0, 0, 0.18);
}

.select-trigger.open {
  border-color: #6c5ce7;
  background: rgba(255, 255, 255, 0.9);
  box-shadow: 0 0 0 3px rgba(108, 92, 231, 0.15);
}

.selected-text {
  flex: 1;
  font-size: 14px;
}

.selected-text.placeholder {
  color: #7f8c8d;
}

.arrow {
  font-size: 12px;
  color: #7f8c8d;
  transition: transform 0.25s ease;
  flex-shrink: 0;
}

.arrow.flipped {
  transform: rotate(180deg);
}

.select-dropdown {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(24px);
  border: 1px solid rgba(0, 0, 0, 0.12);
  border-radius: 12px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.15);
  z-index: 600;
  max-height: 220px;
  overflow-y: auto;
  padding: 6px;
}

.select-option {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  border-radius: 6px;
  font-size: 14px;
  color: #2d3436;
  cursor: pointer;
  transition: background 0.15s ease;
}

.select-option:hover {
  background: rgba(108, 92, 231, 0.08);
  color: #6c5ce7;
}

.select-option.active {
  background: rgba(108, 92, 231, 0.15);
  color: #6c5ce7;
  font-weight: 600;
}

.option-color {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  flex-shrink: 0;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.15);
}
</style>
