<template>
  <div class="canvas-viewport-toolbar" role="group" :aria-label="`${label}视图控制`">
    <div class="canvas-viewport-buttons">
      <button type="button" class="btn-secondary" aria-label="缩小画板" :disabled="scale <= CANVAS_MIN_SCALE" @click="$emit('zoom-out')">−</button>
      <output aria-live="polite" aria-label="缩放比例">{{ Math.round(scale * 100) }}%</output>
      <button type="button" class="btn-secondary" aria-label="放大画板" :disabled="scale >= CANVAS_MAX_SCALE" @click="$emit('zoom-in')">＋</button>
      <button type="button" class="btn-secondary canvas-viewport-reset" @click="$emit('reset')">复位视图</button>
    </div>
    <span class="canvas-viewport-hint">滚轮缩放 · 拖动画板 · 方向键平移</span>
  </div>
</template>
<script setup lang="ts">
import { CANVAS_MIN_SCALE, CANVAS_MAX_SCALE } from '@/utils/canvasViewport'
withDefaults(defineProps<{ scale: number; label?: string }>(), { label: '画板' })
defineEmits<{ 'zoom-in': []; 'zoom-out': []; reset: [] }>()
</script>
<style scoped>
.canvas-viewport-toolbar { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px 16px; min-width: 0; padding: 10px 12px; border-bottom: 1px solid var(--line); background: var(--panel); color: var(--muted); }
.canvas-viewport-buttons { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; min-width: 0; }
.canvas-viewport-buttons button { min-height: 36px; min-width: 36px; padding: 6px 10px; }
.canvas-viewport-buttons button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
.canvas-viewport-buttons output { min-width: 48px; text-align: center; font: 12px var(--font-mono, monospace); color: var(--text); }
.canvas-viewport-hint { font-size: 11px; line-height: 1.5; overflow-wrap: anywhere; }
@media (max-width: 480px) { .canvas-viewport-toolbar { padding: 8px; } .canvas-viewport-hint { flex-basis: 100%; } }
</style>
