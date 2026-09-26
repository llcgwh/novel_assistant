<template>
  <aside
    class="project-signature"
    :class="{
      'on-library': !route.params.novelId,
      'is-immersed': studio.focused,
    }"
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
import { useStudioStore } from '@/stores/studio'
const route = useRoute(),
  studio = useStudioStore()
</script>
<style>
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
.project-signature.on-library {
  left: auto;
  right: 18px;
  background: var(--panel);
  border: 1px solid var(--line);
  padding: 2px 12px;
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
  .project-signature,
  .project-signature.on-library,
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
  .studio-content,
  .library-main {
    padding-bottom: 70px !important;
  }
  .writer-livebar {
    bottom: 32px !important;
  }
}
</style>
