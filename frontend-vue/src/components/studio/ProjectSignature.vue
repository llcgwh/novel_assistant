<template>
  <div
    v-if="library"
    ref="dock"
    class="project-signature-anchor"
    aria-hidden="true"
  ></div>
  <aside
    ref="signature"
    class="project-signature"
    :class="{
      'on-library': library,
      'on-global': !library && !route.params.novelId,
      'is-immersed': !library && studio.focused,
      'without-motion': !studio.spatialMotion,
    }"
    :style="library ? dockStyle : undefined"
    aria-label="项目与作者联系方式"
  >
    <a
      href="https://github.com/llcgwh"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="llcg 的 GitHub 主页"
      ><span>by</span> <strong>llcg</strong></a
    >
    <span aria-hidden="true">·</span>
    <a
      href="https://github.com/llcgwh/novel_assistant"
      target="_blank"
      rel="noopener noreferrer"
      aria-label="墨境项目 GitHub 仓库"
      >GitHub ↗</a
    >
    <a
      href="mailto:llcgwh0210@gmail.com"
      title="llcgwh0210@gmail.com"
      aria-label="邮件联系 llcg：llcgwh0210@gmail.com"
      >联系</a
    >
  </aside>
</template>
<script setup lang="ts">
import { useRoute } from 'vue-router'
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useStudioStore } from '@/stores/studio'
const props = defineProps<{ library?: boolean }>()
const route = useRoute(),
  studio = useStudioStore()
const dock = ref<HTMLElement | null>(null)
const signature = ref<HTMLElement | null>(null)
const dockStyle = ref({ '--signature-x': '0px', '--signature-y': '0px' })
let observer: ResizeObserver | undefined
let frame = 0

function updateDock() {
  frame = 0
  if (!dock.value || !signature.value) return
  const target = dock.value.getBoundingClientRect()
  const badge = signature.value
  // Start moving before the footer enters, then settle into its reserved row.
  const nearFooter = target.top <= window.innerHeight + 80
  dockStyle.value = {
    '--signature-x': nearFooter
      ? `${target.left + (target.width - badge.offsetWidth) / 2 - badge.offsetLeft}px`
      : '0px',
    '--signature-y': nearFooter
      ? `${Math.min(0, target.top + (target.height - badge.offsetHeight) / 2 - badge.offsetTop)}px`
      : '0px',
  }
}
function scheduleDock() {
  if (!frame) frame = requestAnimationFrame(updateDock)
}
onMounted(() => {
  if (!props.library) return
  window.addEventListener('scroll', scheduleDock, { passive: true })
  window.addEventListener('resize', scheduleDock)
  observer = new ResizeObserver(scheduleDock)
  observer.observe(document.body)
  if (dock.value) observer.observe(dock.value)
  if (signature.value) observer.observe(signature.value)
  scheduleDock()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', scheduleDock)
  window.removeEventListener('resize', scheduleDock)
  observer?.disconnect()
  cancelAnimationFrame(frame)
})
</script>
<style>
.studio-page-footer.library-footer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  align-items: center;
  gap: 20px;
}
.library-footer > span:nth-child(2) {
  text-align: right;
}
.library-footer > span {
  grid-row: 2;
}
.project-signature-anchor {
  grid-column: 1 / -1;
  grid-row: 1;
  min-height: 44px;
}
.project-signature {
  position: fixed;
  left: 19px;
  bottom: 8px;
  z-index: 35;
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 28px;
  padding: 3px 5px;
  color: var(--muted);
  background: var(--sidebar);
  font-size: 10px;
  letter-spacing: 0.025em;
  border-radius: 6px;
}
.project-signature a {
  color: inherit;
  text-decoration: none;
  white-space: nowrap;
  padding: 5px 0;
}
.project-signature strong {
  font:
    600 12px ui-monospace,
    monospace;
  color: var(--text);
  letter-spacing: 0.08em;
}
.project-signature a:hover {
  color: var(--accent);
}
.project-signature a:focus-visible {
  outline: 2px solid var(--accent);
  outline-offset: 3px;
  border-radius: 2px;
}
.project-signature a > span {
  opacity: 0.65;
  font-size: 9px;
}
.project-signature.on-library,
.project-signature.on-global {
  left: auto;
  right: 18px;
  background: var(--panel);
  border: 1px solid var(--line);
  padding: 2px 12px;
}
.project-signature.on-library {
  bottom: max(8px, env(safe-area-inset-bottom, 0px));
  width: max-content;
  max-width: calc(100vw - 36px);
  transform: translate(var(--signature-x, 0px), var(--signature-y, 0px));
  transition: transform 420ms cubic-bezier(0.22, 1, 0.36, 1);
}
.project-signature.without-motion {
  transition: none;
}
.project-signature.is-immersed {
  left: 14px;
  bottom: 40px;
  background: var(--panel);
  opacity: 0.7;
}
.project-signature.is-immersed:hover,
.project-signature.is-immersed:focus-within {
  opacity: 1;
}
.studio-sidebar .sidebar-bottom {
  padding-bottom: 28px;
}
@media (max-width: 960px) {
  .project-signature:not(.on-library),
  .project-signature.on-global,
  .project-signature.is-immersed {
    left: 0;
    right: 0;
    bottom: 0;
    min-height: 32px;
    justify-content: center;
    border-radius: 0;
    border: 0;
    border-top: 1px solid var(--line);
    background: var(--bg);
    opacity: 1;
    padding: 0 10px;
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
  .studio-content {
    padding-bottom: 70px !important;
  }
  .writer-livebar {
    bottom: 32px !important;
  }
  .studio-page-footer.library-footer {
    padding-bottom: env(safe-area-inset-bottom, 0px);
  }
}
</style>
