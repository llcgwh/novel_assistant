<template>
  <div v-if="journey" class="book-passage-shade" aria-hidden="true"></div>
  <section
    v-if="journey"
    ref="panel"
    class="book-passage"
    :class="{ 'is-turned': turned }"
    :data-book-passage="journey.direction"
    :data-book-id="journey.novelId"
    aria-hidden="true"
    inert
  >
    <div ref="replica" v-show="hasReplica" class="book-passage-replica"></div>
    <div v-show="!hasReplica" class="book-passage-face">
      <img v-if="journey.cover && !turned" :src="journey.cover" alt="" />
      <div class="passage-orbit"><i></i><i></i><i></i></div>
      <small>{{
        turned
          ? journey.direction === 'open'
            ? 'INK / ENTER YOUR WORLD'
            : 'INK / BACK TO THE SHELF'
          : 'INK STUDIO'
      }}</small>
      <strong>{{ journey.title }}</strong>
      <span>{{
        turned
          ? journey.direction === 'open'
            ? '故事，由此展开。'
            : '合上这一页，灵感仍在。'
          : '每一个世界，始于一笔。'
      }}</span>
      <b>✦</b>
    </div>
  </section>
</template>
<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useNovelStore } from '@/stores/novel'
import { useStudioStore } from '@/stores/studio'
import { bookPassage, visibleBookBox } from '@/utils/bookPassage'
import { createSheetFlight } from '@/utils/sheets'

type Box = { left: number; top: number; width: number; height: number }
type Journey = NonNullable<ReturnType<typeof bookPassage>> & {
  title: string
  cover: string
  from: Box
  source: Element | null
  path: string
}
const router = useRouter(),
  novel = useNovelStore(),
  studio = useStudioStore()
const journey = ref<Journey | null>(null),
  panel = ref<HTMLElement | null>(null),
  replica = ref<HTMLElement | null>(null),
  hasReplica = ref(false),
  turned = ref(false)
let pending: Journey | null = null,
  ticket = 0,
  media: MediaQueryList | undefined
const flight = createSheetFlight({
  set: (fn, delay) => window.setTimeout(fn, delay),
  clear: (id) => window.clearTimeout(id as number),
})
function box(element: Element | null): Box | null {
  if (!element) return null
  const r = element.getBoundingClientRect()
  return visibleBookBox(r, innerWidth, innerHeight)
    ? { left: r.left, top: r.top, width: r.width, height: r.height }
    : null
}
function cover(id: number) {
  return document.querySelector(`[data-book-portal="${id}"]`)
}
function enabled() {
  return studio.spatialMotion && !media?.matches
}
function cancel() {
  ticket++
  pending = null
  flight.cancel()
  journey.value = null
  turned.value = false
  studio.bookArrival = null
}
function copyFace(element: Element | null) {
  if (!element || !replica.value) return
  const clone = element.cloneNode(true) as HTMLElement
  clone.classList.remove('is-book-arriving', 'is-flying')
  clone.classList.add('passage-clone')
  clone.removeAttribute('aria-hidden')
  clone.removeAttribute('id')
  clone.querySelectorAll('[id]').forEach((node) => node.removeAttribute('id'))
  replica.value.replaceChildren(clone)
  hasReplica.value = true
}
const removeBefore = router.beforeResolve((to, from) => {
  cancel()
  const change = bookPassage(to, from)
  if (!change || !enabled()) return
  const book = novel.novels.find((n) => n.id === change.novelId)
  const source =
    change.direction === 'open'
      ? cover(change.novelId)
      : document.querySelector('.morph-sheet') ||
        document.querySelector('.current-book')
  const origin = box(source)
  pending = {
    ...change,
    title: book?.title || '故事的另一面',
    cover: book?.coverImage || '',
    path: to.fullPath,
    source: source?.cloneNode(true) as Element | null,
    from: origin || {
      left: innerWidth / 2 - 80,
      top: innerHeight / 2 - 110,
      width: 160,
      height: 220,
    },
  }
  studio.bookArrival = change
})
const removeAfter = router.afterEach((to, _from, failure) => {
  const next = pending
  pending = null
  if (failure || !next || next.path !== to.fullPath || !enabled()) {
    cancel()
    return
  }
  void travel(next)
})
async function travel(next: Journey) {
  const turn = ++ticket
  await nextTick()
  // Let the destination route mount and its dock register; this never delays navigation.
  let destination: Box | null = null
  const deadline = performance.now() + 1600
  while (!destination && performance.now() < deadline) {
    await new Promise<void>((resolve) => requestAnimationFrame(() => resolve()))
    if (turn !== ticket || !enabled()) return
    const element =
      next.direction === 'open'
        ? document.querySelector('.sheet-dock')
        : cover(next.novelId)
    if (next.direction === 'close' && element && !box(element))
      element.scrollIntoView({ block: 'center', behavior: 'instant' })
    destination = box(element)
  }
  if (turn !== ticket) return
  if (!destination) {
    cancel()
    return
  }
  journey.value = next
  turned.value = false
  hasReplica.value = false
  await nextTick()
  if (turn !== ticket || !panel.value) return
  const el = panel.value
  if (!el.animate) {
    cancel()
    return
  }
  copyFace(next.source)
  const cssBox = (r: Box) => ({
    left: `${r.left}px`,
    top: `${r.top}px`,
    width: `${r.width}px`,
    height: `${r.height}px`,
  })
  Object.assign(el.style, cssBox(destination))
  const duration = innerWidth < 680 ? 620 : 840
  const direction = next.direction === 'open' ? 1 : -1
  const geometry = el.animate([cssBox(next.from), cssBox(destination)], {
    duration,
    easing: 'cubic-bezier(.22,.72,.16,1)',
  })
  const flip = el.animate(
    [
      {
        transform: 'perspective(1400px) rotateY(0deg) rotateZ(-2deg)',
        opacity: 1,
        offset: 0,
      },
      {
        transform: `perspective(1400px) translateY(-22px) rotateY(${direction * 90}deg) rotateZ(${direction * 3}deg)`,
        opacity: 1,
        offset: 0.5,
      },
      {
        transform: `perspective(1400px) rotateY(${direction * 180}deg)`,
        opacity: 1,
        offset: 0.88,
      },
      {
        transform: `perspective(1400px) rotateY(${direction * 180}deg)`,
        opacity: 1,
        offset: 1,
      },
    ],
    { duration, easing: 'linear' },
  )
  flight.run(
    {
      cancel: () => {
        geometry.cancel()
        flip.cancel()
      },
      finished: Promise.all([geometry.finished, flip.finished]),
    },
    duration,
    () => {
      copyFace(
        next.direction === 'open'
          ? document.querySelector('.morph-sheet')
          : cover(next.novelId),
      )
      turned.value = true
    },
    () => {
      if (turn === ticket) {
        journey.value = null
        studio.bookArrival = null
      }
    },
  )
}
watch(
  () => studio.spatialMotion,
  (value) => {
    if (!value) cancel()
  },
)
onMounted(() => {
  media = matchMedia('(prefers-reduced-motion: reduce)')
  media.addEventListener('change', cancel)
  window.addEventListener('resize', cancel)
})
onBeforeUnmount(() => {
  cancel()
  removeBefore()
  removeAfter()
  media?.removeEventListener('change', cancel)
  window.removeEventListener('resize', cancel)
})
</script>
<style scoped>
:global(.morph-sheet.is-book-arriving),
:global(.book-cover.is-book-arriving) {
  visibility: hidden !important;
}
.book-passage-replica {
  width: 100%;
  height: 100%;
  overflow: hidden;
}
.book-passage-replica :deep(.passage-clone) {
  position: relative !important;
  inset: 0 !important;
  width: 100% !important;
  height: 100% !important;
  margin: 0 !important;
  visibility: visible !important;
  opacity: 1 !important;
  transform: none !important;
  box-shadow: none !important;
}
.is-turned .book-passage-replica {
  transform: rotateY(180deg);
}
.book-passage {
  position: fixed;
  z-index: 1200;
  pointer-events: none;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--accent) 50%, var(--line));
  border-radius: 12px 20px 20px 12px;
  background: var(--panel);
  box-shadow:
    inset 8px 0 0 color-mix(in srgb, var(--accent) 10%, transparent),
    0 30px 100px #0005;
  transform-origin: center;
}
.book-passage-face {
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: clamp(16px, 3vw, 42px);
  gap: 18px;
  position: relative;
  overflow: hidden;
  color: var(--text);
  background: linear-gradient(
    140deg,
    color-mix(in srgb, var(--accent) 14%, var(--panel)),
    var(--panel)
  );
}
.is-turned .book-passage-face {
  transform: rotateY(180deg);
}
.book-passage-face img {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0.2;
}
.book-passage-face small {
  font:
    9px ui-monospace,
    monospace;
  letter-spacing: 0.2em;
  position: relative;
  color: var(--muted);
}
.book-passage-face strong {
  font: clamp(22px, 3vw, 40px) var(--serif);
  position: relative;
  overflow: hidden;
  max-height: 3.5em;
}
.book-passage-face span {
  font:
    11px system-ui,
    sans-serif;
  position: relative;
  color: var(--muted);
}
.book-passage-face b {
  color: var(--accent);
  font-size: 20px;
  font-weight: 400;
}
.passage-orbit {
  position: absolute;
  inset: 15%;
  transform: rotate(-25deg);
  opacity: 0.3;
}
.passage-orbit i {
  position: absolute;
  inset: 0;
  border: 1px solid var(--accent);
  border-radius: 50%;
}
.passage-orbit i:nth-child(2) {
  transform: rotate(60deg) scaleX(0.5);
}
.passage-orbit i:nth-child(3) {
  transform: rotate(-60deg) scaleX(0.5);
}
.book-passage-shade {
  position: fixed;
  inset: 0;
  z-index: 1199;
  pointer-events: none;
  background: radial-gradient(ellipse at center, transparent, #0002);
  animation: passage-shade 0.84s ease both;
}
@keyframes passage-shade {
  0%,
  100% {
    opacity: 0;
  }
  40% {
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .book-passage,
  .book-passage-shade {
    display: none;
  }
}
</style>
