<template>
  <section
    ref="panel"
    class="morph-sheet"
    :class="{ 'is-flying': moving, 'is-placed': placed }"
    :data-sheet="displayed"
    :data-destination="sheetId"
    :data-motion="moving ? 'flying' : 'settled'"
    :style="{ '--sheet-tint': sheetFor(displayed).tint }"
    :inert="moving || !placed"
    :aria-label="`${sheetFor(displayed).caption}折页`"
    @pointermove="shine"
    @pointerleave="clearShine"
  >
    <div class="sheet-glass" aria-hidden="true"></div>
    <div class="sheet-face" :class="{ 'is-reversed': reversed }">
      <SheetContents :sheet-id="displayed" />
    </div>
  </section>
</template>
<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useStudioStore } from '@/stores/studio'
import {
  createSheetFlight,
  sheetFor,
  sheets,
  type SheetId,
} from '@/utils/sheets'
import SheetContents from './SheetContents.vue'

const props = defineProps<{ anchor: HTMLElement | null; sheetId: SheetId }>()
const studio = useStudioStore()
const panel = ref<HTMLElement | null>(null)
const displayed = ref<SheetId>(props.sheetId),
  reversed = ref(false),
  moving = ref(false),
  placed = ref(false)
const flight = createSheetFlight({
  set: (fn, delay) => window.setTimeout(fn, delay),
  clear: (timer) => window.clearTimeout(timer as number),
})
let frame = 0,
  observer: ResizeObserver | undefined,
  media: MediaQueryList | undefined,
  disposed = false
let lastTarget = '',
  requested: SheetId = props.sheetId

function targetBox() {
  const box = props.anchor?.getBoundingClientRect()
  return box && box.width > 0 && box.height > 0
    ? {
        left: `${box.left}px`,
        top: `${box.top}px`,
        width: `${box.width}px`,
        height: `${box.height}px`,
      }
    : null
}
function settle() {
  flight.cancel()
  const box = targetBox()
  if (box && panel.value) {
    Object.assign(panel.value.style, box)
    placed.value = true
    lastTarget = JSON.stringify(box)
  }
  displayed.value = props.sheetId
  requested = props.sheetId
  reversed.value = false
  moving.value = false
}
async function place() {
  await nextTick()
  if (disposed || !panel.value) return
  const box = targetBox()
  if (!box) return
  const changed = requested !== props.sheetId
  if (!changed && lastTarget === JSON.stringify(box)) return
  if (
    !placed.value ||
    !changed ||
    media?.matches ||
    !studio.spatialMotion ||
    !panel.value.animate
  ) {
    settle()
    return
  }

  const element = panel.value,
    style = getComputedStyle(element)
  const from = {
    left: style.left,
    top: style.top,
    width: style.width,
    height: style.height,
  }
  const destination = props.sheetId
  const direction =
    sheets.findIndex((item) => item.id === destination) >=
    sheets.findIndex((item) => item.id === requested)
      ? 1
      : -1
  // Flatten an interrupted flip before starting its replacement, preserving its current position.
  flight.cancel()
  reversed.value = false
  moving.value = true
  requested = destination
  lastTarget = JSON.stringify(box)
  Object.assign(element.style, box)
  clearShine()
  const duration = window.innerWidth < 680 ? 600 : 820
  const geometry = element.animate([from, box], {
    duration,
    easing: 'cubic-bezier(.22,.72,.16,1)',
  })
  const turn = element.animate(
    [
      {
        transform: 'perspective(1300px) rotateY(0deg) rotateZ(0deg)',
        offset: 0,
      },
      {
        transform: `perspective(1300px) translateY(-28px) rotateY(${direction * 90}deg) rotateZ(${direction * -5}deg)`,
        borderRadius: '30px',
        offset: 0.5,
      },
      {
        transform: `perspective(1300px) rotateY(${direction * 180}deg) rotateZ(0deg)`,
        borderRadius: '18px',
        offset: 1,
      },
    ],
    { duration, easing: 'linear' },
  )
  // Geometry eases while the turn itself must swap faces precisely at its edge.
  flight.run(
    {
      cancel: () => {
        geometry.cancel()
        turn.cancel()
      },
      finished: Promise.all([geometry.finished, turn.finished]),
    },
    duration,
    () => {
      displayed.value = destination
      reversed.value = true
    },
    () => {
      displayed.value = destination
      reversed.value = false
      moving.value = false
    },
  )
}
function schedule() {
  cancelAnimationFrame(frame)
  frame = requestAnimationFrame(() => {
    void place()
  })
}
function viewportChanged() {
  if (moving.value) settle()
  else schedule()
}
function preferenceChanged() {
  settle()
}
function shine(event: PointerEvent) {
  if (
    moving.value ||
    event.pointerType !== 'mouse' ||
    media?.matches ||
    !studio.spatialMotion
  )
    return
  const box = panel.value!.getBoundingClientRect()
  panel.value!.style.setProperty(
    '--shine-x',
    `${((event.clientX - box.left) / box.width) * 100}%`,
  )
  panel.value!.style.setProperty(
    '--shine-y',
    `${((event.clientY - box.top) / box.height) * 100}%`,
  )
}
function clearShine() {
  panel.value?.style.removeProperty('--shine-x')
  panel.value?.style.removeProperty('--shine-y')
}
function observeDock() {
  observer?.disconnect()
  if (!props.anchor || !observer) return
  observer.observe(props.anchor)
  // The dock can move without resizing when the sidebar or scrollbar changes.
  const stage = props.anchor.closest('.sheet-stage')
  if (stage) observer.observe(stage)
}
watch(
  () => [props.anchor, props.sheetId],
  () => {
    observeDock()
    schedule()
  },
  { flush: 'post' },
)
watch(() => studio.spatialMotion, preferenceChanged)
watch(() => studio.focused, schedule, { flush: 'post' })
onMounted(() => {
  media = window.matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', preferenceChanged)
  observer = new ResizeObserver(schedule)
  observeDock()
  window.addEventListener('scroll', viewportChanged, { passive: true })
  window.addEventListener('resize', viewportChanged, { passive: true })
  schedule()
})
onBeforeUnmount(() => {
  disposed = true
  flight.cancel()
  cancelAnimationFrame(frame)
  observer?.disconnect()
  media?.removeEventListener('change', preferenceChanged)
  window.removeEventListener('scroll', viewportChanged)
  window.removeEventListener('resize', viewportChanged)
})
</script>
