<template>
  <section class="writing-heatmap" aria-label="最近 365 天码字日历">
    <header>
      <div>
        <strong>每一天，都留下落笔的痕迹</strong
        ><small>最近 365 天 · {{ positiveDays }} 天正文净增长</small>
      </div>
      <button type="button" class="heatmap-today" @click="goToToday">
        回到今天 <span aria-hidden="true">↗</span>
      </button>
    </header>
    <div class="heatmap-scroll" ref="scroller">
      <div
        class="heatmap-body"
        :style="{ '--heatmap-columns': calendar.columns }"
      >
        <div class="heatmap-months" aria-hidden="true">
          <span
            v-for="month in calendar.months"
            :key="month.date"
            :style="{ gridColumn: month.column }"
            >{{ month.label }}</span
          >
        </div>
        <div class="heatmap-weekdays" aria-hidden="true">
          <span>一</span><span>三</span><span>五</span><span>日</span>
        </div>
        <div
          class="heatmap-grid"
          role="group"
          aria-label="每日净增字数；方向键移动日期，Home 和 End 移到本周首尾"
        >
          <button
            v-for="day in calendar.days"
            :key="day.date"
            type="button"
            class="heatmap-cell"
            :class="{
              'is-today': day.index === 364,
              'is-selected': active.date === day.date,
            }"
            :data-level="heatmapLevel(day.net, dailyGoal)"
            :data-day-index="day.index"
            :style="{ gridColumn: day.column, gridRow: day.row }"
            :tabindex="focusedIndex === day.index ? 0 : -1"
            :aria-label="describe(day)"
            :aria-current="day.index === 364 ? 'date' : undefined"
            :title="describe(day)"
            @mouseenter="hoveredIndex = day.index"
            @mouseleave="hoveredIndex = null"
            @focus="selectDay(day.index)"
            @click="selectDay(day.index)"
            @keydown="navigate($event, day.index)"
          />
        </div>
      </div>
    </div>
    <div class="heatmap-legend" aria-label="颜色图例">
      <span>净增</span><span class="legend-swatch" data-level="0" />
      <span>0</span> <span>少</span
      ><span
        v-for="level in [1, 2, 3, 4]"
        :key="level"
        class="legend-swatch"
        :data-level="level"
      /><span>多</span>
      <span class="legend-negative"
        ><span class="legend-swatch" data-level="-1" /> 净减少</span
      >
      <span class="legend-today"><i /> 今天</span>
    </div>
    <div
      class="heatmap-detail"
      role="status"
      aria-live="polite"
      aria-atomic="true"
    >
      <div>
        <time :datetime="active.date">{{ active.date.replace(/-/g, '.') }}</time
        ><span
          >{{ active.index === 364 ? '今天 · ' : ''
          }}{{
            active.sessions
              ? `${active.chapters} 章 · ${active.sessions} 次写作`
              : '无写作记录'
          }}</span
        >
      </div>
      <strong :class="{ negative: active.net < 0 }"
        >{{ active.net > 0 ? '+' : '' }}{{ active.net.toLocaleString() }}
        <small>字净增</small></strong
      >
      <p>
        手输 {{ active.typed.toLocaleString() }} · 粘贴
        {{ active.pasted.toLocaleString() }} · 活跃
        {{ duration(active.activeSeconds) }}
      </p>
    </div>
    <p class="heatmap-hint">
      悬停、点击或用方向键查看每日详情。绿色按净增达到
      {{ scale.quarter.toLocaleString() }} / {{ scale.half.toLocaleString() }} /
      {{ scale.target.toLocaleString() }} 字加深{{
        dailyGoal > 0
          ? '，最深色表示达到每日目标'
          : '（未设目标，按 2000 字分档）'
      }}；删改造成的净减少用橙色纹理表示，无记录日期记为 0。
    </p>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref } from 'vue'
import type { SessionRecord } from '@/types/writing'
import {
  heatmapFocusIndex,
  heatmapLevel,
  heatmapScale,
  writingHeatmap,
} from '@/utils/writingHeatmap'

const props = defineProps<{ sessions: SessionRecord[]; dailyGoal: number }>()
const today = new Date()
const calendar = computed(() => writingHeatmap(props.sessions, today))
const scale = computed(() => heatmapScale(props.dailyGoal))
const positiveDays = computed(
  () => calendar.value.days.filter((day) => day.net > 0).length,
)
const focusedIndex = ref(364)
const hoveredIndex = ref<number | null>(null)
const active = computed(
  () => calendar.value.days[hoveredIndex.value ?? focusedIndex.value],
)
const scroller = ref<HTMLElement | null>(null)
function duration(seconds: number) {
  return seconds < 60
    ? `${seconds} 秒`
    : `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}
function describe(day: (typeof calendar.value.days)[number]) {
  return `${day.date}${day.index === 364 ? '，今天' : ''}：净增 ${day.net} 字，手输 ${day.typed} 字，粘贴 ${day.pasted} 字，活跃 ${duration(day.activeSeconds)}${day.sessions ? `，${day.chapters} 章，${day.sessions} 次写作` : '，无写作记录'}`
}
function selectDay(index: number) {
  focusedIndex.value = index
  hoveredIndex.value = null
}
async function focusDay(index: number) {
  selectDay(index)
  await nextTick()
  const cell = scroller.value?.querySelector<HTMLButtonElement>(
    `[data-day-index="${index}"]`,
  )
  cell?.focus({ preventScroll: true })
  cell?.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}
function navigate(event: KeyboardEvent, index: number) {
  if (
    ![
      'ArrowLeft',
      'ArrowRight',
      'ArrowUp',
      'ArrowDown',
      'Home',
      'End',
    ].includes(event.key)
  )
    return
  event.preventDefault()
  void focusDay(heatmapFocusIndex(index, event.key, calendar.value.days))
}
function goToToday() {
  void focusDay(364)
}
onMounted(() => {
  if (scroller.value) scroller.value.scrollLeft = scroller.value.scrollWidth
})
</script>

<style scoped>
.writing-heatmap {
  --heat-0: #1c2b2b;
  --heat-1: #194c3b;
  --heat-2: #247c51;
  --heat-3: #45b473;
  --heat-4: #88e6a5;
  --heat-negative: #b68b53;
  --heat-negative-bg: #3e3024;
  --heat-cell: 10px;
  --heat-gap: 3px;
  margin: 22px 0 14px;
  padding: 18px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: var(--field);
  min-width: 0;
}
:global(:root[data-studio-theme='light'] .writing-heatmap) {
  --heat-0: #e4eae4;
  --heat-1: #b7dfb2;
  --heat-2: #73be79;
  --heat-3: #379652;
  --heat-4: #17693c;
  --heat-negative: #936327;
  --heat-negative-bg: #f4e4c9;
}
.writing-heatmap header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 17px;
}
.writing-heatmap header strong {
  display: block;
  color: var(--text);
  font-size: 14px;
  font-weight: 600;
}
.writing-heatmap header small {
  display: block;
  margin-top: 5px;
  font-size: 11px;
  color: var(--muted);
}
.writing-heatmap button.heatmap-today {
  background: transparent;
  border: 0;
  padding: 6px 0 6px 8px;
  color: var(--accent);
  font-size: 11px;
  white-space: nowrap;
  cursor: pointer;
}
.heatmap-scroll {
  overflow-x: auto;
  overflow-y: hidden;
  padding: 3px 2px 8px;
  scrollbar-width: thin;
  scrollbar-color: var(--line) transparent;
}
.heatmap-body {
  display: grid;
  grid-template-columns: 17px 1fr;
  grid-template-rows: 18px auto;
  gap: 3px 6px;
  width: max-content;
}
.heatmap-months {
  grid-column: 2;
  display: grid;
  grid-template-columns: repeat(var(--heatmap-columns), var(--heat-cell));
  column-gap: var(--heat-gap);
  font-size: 10px;
  color: var(--muted);
}
.heatmap-months span {
  width: max-content;
}
.heatmap-weekdays {
  display: grid;
  grid-template-rows: repeat(7, var(--heat-cell));
  row-gap: var(--heat-gap);
  font-size: 9px;
  color: var(--muted);
  line-height: var(--heat-cell);
}
.heatmap-weekdays span:nth-child(2) {
  grid-row: 3;
}
.heatmap-weekdays span:nth-child(3) {
  grid-row: 5;
}
.heatmap-weekdays span:nth-child(4) {
  grid-row: 7;
}
.heatmap-grid {
  display: grid;
  grid-template-columns: repeat(var(--heatmap-columns), var(--heat-cell));
  grid-template-rows: repeat(7, var(--heat-cell));
  gap: var(--heat-gap);
}
.writing-heatmap .heatmap-cell,
.legend-swatch {
  background: var(--heat-0);
  border-radius: 2px;
  border: 1px solid color-mix(in srgb, var(--text) 9%, transparent);
}
.writing-heatmap .heatmap-cell {
  position: relative;
  width: var(--heat-cell);
  min-width: 0;
  height: var(--heat-cell);
  min-height: 0;
  margin: 0;
  padding: 0;
  cursor: pointer;
  transition: outline-color 120ms;
}
.writing-heatmap [data-level='1'] {
  background: var(--heat-1);
}
.writing-heatmap [data-level='2'] {
  background: var(--heat-2);
}
.writing-heatmap [data-level='3'] {
  background: var(--heat-3);
}
.writing-heatmap [data-level='4'] {
  background: var(--heat-4);
}
.writing-heatmap [data-level='-1'] {
  background: repeating-linear-gradient(
    135deg,
    var(--heat-negative-bg) 0 2px,
    var(--heat-negative) 2px 3px
  );
  border-color: var(--heat-negative);
}
.writing-heatmap .heatmap-cell.is-today::after {
  content: '';
  position: absolute;
  width: 3px;
  height: 3px;
  border-radius: 50%;
  background: var(--text);
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  box-shadow: 0 0 0 1px var(--field);
}
.writing-heatmap .heatmap-cell.is-selected,
.writing-heatmap .heatmap-cell:focus-visible {
  outline: 2px solid var(--text);
  outline-offset: 1px;
  z-index: 1;
}
.heatmap-legend {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
  font-size: 10px;
  color: var(--muted);
}
.legend-swatch {
  display: inline-block;
  flex-shrink: 0;
  width: 10px;
  height: 10px;
}
.legend-negative,
.legend-today {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: 9px;
}
.legend-today i {
  display: inline-block;
  width: 4px;
  height: 4px;
  border-radius: 50%;
  background: var(--text);
}
.heatmap-detail {
  margin-top: 16px;
  padding: 12px 14px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel);
  font-variant-numeric: tabular-nums;
}
.heatmap-detail time {
  display: block;
  color: var(--text);
  font-size: 13px;
  font-weight: 600;
}
.heatmap-detail span {
  display: block;
  margin-top: 3px;
  color: var(--muted);
  font-size: 11px;
}
.heatmap-detail strong {
  align-self: center;
  color: var(--accent);
  font-size: 22px;
  letter-spacing: -0.04em;
}
.heatmap-detail strong.negative {
  color: var(--heat-negative);
}
.heatmap-detail small {
  font-size: 10px;
  font-weight: 400;
  letter-spacing: 0;
}
.heatmap-detail p {
  grid-column: 1 / -1;
  margin: 0;
  color: var(--muted);
  font-size: 11px;
}
.writing-heatmap .heatmap-hint {
  margin: 12px 0 0;
  color: var(--muted);
  font-size: 11px;
  line-height: 1.7;
}
@media (max-width: 600px) {
  .writing-heatmap {
    --heat-cell: 14px;
    --heat-gap: 4px;
    padding: 12px;
  }
  .heatmap-detail {
    padding: 10px;
  }
  .heatmap-detail strong {
    font-size: 19px;
  }
  .writing-heatmap header strong {
    font-size: 12px;
  }
  .heatmap-legend {
    justify-content: flex-start;
  }
  .legend-negative,
  .legend-today {
    margin-left: 5px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .writing-heatmap .heatmap-cell {
    transition: none;
  }
}
</style>
