<template>
  <Teleport to="body">
    <div v-if="frame" ref="surface" class="theme-transition" aria-hidden="true" @pointerdown.prevent @click.stop @wheel.prevent
      :data-phase="phase" :data-source="frame.from" :data-target="frame.to"
      :style="{ backgroundColor: frame.background, clipPath: `circle(0px at ${frame.origin.x}px ${frame.origin.y}px)` }">
      <svg ref="icon" class="theme-transition-icon" viewBox="0 0 64 64" focusable="false"
        :data-current-icon="frame.from === 'dark' ? 'moon' : 'sun'" :style="{ color: frame.ink }">
        <defs>
          <mask :id="maskId" maskUnits="userSpaceOnUse" x="0" y="0" width="64" height="64">
            <rect width="64" height="64" fill="white" />
            <circle ref="cutout" cx="41" cy="23" r="20" fill="black"
              :style="{ transform: frame.from === 'dark' ? moonCutout : sunCutout }" />
          </mask>
        </defs>
        <circle cx="32" cy="32" r="19" fill="currentColor" :mask="`url(#${maskId})`" />
        <g ref="rays" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"
          :style="{ opacity: frame.from === 'light' ? 1 : 0, transform: frame.from === 'light' ? sunRays : moonRays }">
          <path d="M32 3v5 M32 56v5 M3 32h5 M56 32h5 M11.5 11.5l3.6 3.6 M48.9 48.9l3.6 3.6 M11.5 52.5l3.6-3.6 M48.9 15.1l3.6-3.6" />
        </g>
      </svg>
    </div>
  </Teleport>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useStudioStore } from '@/stores/studio'
import { createThemeTransition, resolveThemeOrigin, type StudioTheme, type ThemePhase, type ThemeSource } from '@/utils/themeTransition'

type Frame = { from: StudioTheme; to: StudioTheme; origin: ReturnType<typeof resolveThemeOrigin>; background: string; targetBackground: string; ink: string; targetInk: string }
const studio = useStudioStore(), route = useRoute()
const frame = ref<Frame | null>(null), phase = ref<ThemePhase | 'prepare'>('prepare')
const surface = ref<HTMLElement | null>(null), icon = ref<SVGSVGElement | null>(null), cutout = ref<SVGCircleElement | null>(null), rays = ref<SVGGElement | null>(null)
const maskId = `theme-disc-${useId().replace(/[^a-zA-Z0-9_-]/g, '')}`
const moonCutout = 'translate(0px, 0px)', sunCutout = 'translate(32px, -24px)'
const sunRays = 'rotate(0deg) scale(1)', moonRays = 'rotate(-12deg) scale(0.68)'
const animations = new Set<Animation>(), removeListeners: Array<() => void> = []
let media: MediaQueryList | undefined, observer: MutationObserver | undefined, watchdog: ReturnType<typeof setTimeout> | undefined
let unregister: (() => void) | undefined

function modalOpen() { return !!document.querySelector('dialog[open], [role="dialog"]') }
function enabled() {
  try {
    if (!studio.spatialMotion || typeof matchMedia !== 'function' || typeof Element.prototype.animate !== 'function' || typeof CSS?.supports !== 'function' || typeof MutationObserver !== 'function' || modalOpen()) return false
    media = matchMedia('(prefers-reduced-motion: reduce)')
    return !media.matches && CSS.supports('clip-path', 'circle(1px at 1px 1px)')
  } catch { return false }
}
function captureColors(from: StudioTheme, target: StudioTheme) {
  const probe = document.createElement('span')
  probe.style.cssText = 'position:fixed;visibility:hidden;pointer-events:none;width:0;height:0;background-color:var(--bg);color:var(--text)'
  if (from === 'light') probe.style.color = 'var(--warm)'
  document.body.appendChild(probe)
  try {
    const initial = getComputedStyle(probe)
    const background = initial.backgroundColor, ink = initial.color
    probe.style.backgroundColor = `var(--studio-${target}-background)`
    probe.style.color = target === 'light' ? 'var(--studio-light-warm)' : 'var(--studio-dark-ink)'
    const targetStyle = getComputedStyle(probe)
    return { background, ink, targetBackground: targetStyle.backgroundColor, targetInk: targetStyle.color }
  } finally { probe.remove() }
}
function listen(target: EventTarget, name: string, callback: EventListener, capture = false) {
  target.addEventListener(name, callback, capture)
  removeListeners.push(() => target.removeEventListener(name, callback, capture))
}
function finish(target?: StudioTheme) { return transition.finish(target) }
function onEscape(event: Event) {
  const key = event as KeyboardEvent
  if (key.key !== 'Escape') return
  key.preventDefault(); key.stopPropagation(); void finish()
}
function onResize() { void finish() }
function onVisibility() { if (document.hidden) void finish() }
function onReducedMotion() { if (media?.matches) void finish() }
function show(from: StudioTheme, to: StudioTheme, source?: ThemeSource) {
  const colors = captureColors(from, to)
  frame.value = { from, to, ...colors, origin: resolveThemeOrigin(source, innerWidth, innerHeight) }
  phase.value = 'prepare'
  listen(window, 'resize', onResize)
  listen(window, 'orientationchange', onResize)
  listen(document, 'keydown', onEscape, true)
  listen(document, 'visibilitychange', onVisibility)
  if (window.visualViewport) listen(window.visualViewport, 'resize', onResize)
  if (media?.addEventListener) listen(media, 'change', onReducedMotion)
  else if (media?.addListener) { const current = media; current.addListener(onReducedMotion); removeListeners.push(() => current.removeListener(onReducedMotion)) }
  observer = new MutationObserver(() => { if (modalOpen()) void finish() })
  observer.observe(document.body, { subtree: true, childList: true, attributes: true, attributeFilter: ['open'] })
  watchdog = setTimeout(() => { void finish() }, 1600)
}
function animate(element: Element | null, keyframes: Keyframe[], duration: number, easing = 'cubic-bezier(.22,.61,.36,1)') {
  if (!element || typeof element.animate !== 'function') throw Error('Theme animation unavailable')
  const animation = element.animate(keyframes, { duration, easing, fill: 'forwards' })
  animations.add(animation)
  if (!animation.finished || typeof animation.finished.then !== 'function') throw Error('Theme animation completion unavailable')
  const completion = Promise.resolve(animation.finished)
  // Attach before constructing another animation, which itself may throw.
  void completion.catch(() => {})
  return completion
}
async function playPhase(name: ThemePhase, duration: number) {
  const value = frame.value
  if (!value) throw Error('Theme transition has ended')
  phase.value = name
  const toLight = value.to === 'light'
  if (name === 'cover') {
    await Promise.all([
      animate(surface.value, [{ clipPath: `circle(0px at ${value.origin.x}px ${value.origin.y}px)` }, { clipPath: `circle(${value.origin.radius}px at ${value.origin.x}px ${value.origin.y}px)` }], duration),
      animate(icon.value, [{ opacity: 0, transform: 'scale(.94)', offset: 0 }, { opacity: 0, transform: 'scale(.94)', offset: 0.5 }, { opacity: 1, transform: 'scale(1)', offset: 1 }], duration),
    ])
  } else if (name === 'hold') {
    await animate(surface.value, [{ opacity: 1 }, { opacity: 1 }], duration, 'linear')
  } else if (name === 'morph') {
    await Promise.all([
      animate(surface.value, [{ backgroundColor: value.background }, { backgroundColor: value.targetBackground }], duration, 'ease-in-out'),
      animate(icon.value, [{ color: value.ink }, { color: value.targetInk }], duration, 'ease-in-out'),
      animate(cutout.value, [{ transform: toLight ? moonCutout : sunCutout }, { transform: toLight ? sunCutout : moonCutout }], duration, 'ease-in-out'),
      animate(rays.value, [{ opacity: toLight ? 0 : 1, transform: toLight ? moonRays : sunRays }, { opacity: toLight ? 1 : 0, transform: toLight ? sunRays : moonRays }], duration, 'ease-in-out'),
    ])
  } else await animate(surface.value, [{ opacity: 1 }, { opacity: 0 }], duration, 'ease-out')
}
function cleanup() {
  if (watchdog !== undefined) clearTimeout(watchdog)
  watchdog = undefined
  try { observer?.disconnect() } catch { /* Continue releasing the remaining resources. */ }
  observer = undefined
  for (const remove of removeListeners.splice(0)) { try { remove() } catch { /* Already removed by the browser. */ } }
  for (const animation of animations) { try { animation.cancel() } catch { /* Detached effect. */ } }
  animations.clear(); frame.value = null; phase.value = 'prepare'
}
const transition = createThemeTransition({ enabled, show, phase: playPhase, commit: target => studio.commitTheme(target), nextTick, cleanup })
function runThemeTransition(from: StudioTheme, to: StudioTheme, source?: ThemeSource) { return transition.request(from, to, source) }
watch(() => studio.spatialMotion, value => { if (!value) void finish() }, { flush: 'sync' })
watch(() => route.fullPath, () => { void finish() })
onMounted(() => { unregister = studio.registerThemeTransition(runThemeTransition, finish) })
onBeforeUnmount(() => { unregister?.(); void transition.dispose() })
</script>

<style scoped>
.theme-transition { position:fixed; inset:0; z-index:2147483000; display:grid; place-items:center; pointer-events:auto; touch-action:none; isolation:isolate; contain:paint; }
.theme-transition-icon { display:block; width:84px; height:84px; opacity:0; overflow:visible; }
.theme-transition-icon g { transform-origin:32px 32px; }
</style>
