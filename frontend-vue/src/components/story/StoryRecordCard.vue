<template>
  <article class="story-record" :class="{ retracted: record.retracted }">
    <div class="story-record-top"><strong><slot /></strong><span v-if="record.retracted">已撤回</span></div>
    <p class="story-record-value">{{ detail }}</p>
    <small>故事顺序 {{ record.storyOrder ?? '未注明' }} · 叙述顺序 {{ record.narrativeOrder ?? '未注明' }}</small>
    <div class="story-sources">
      <RouterLink v-if="record.source.chapterUid && chapterExists" :to="chapterTarget" custom v-slot="{ href }"><a :href="href" :aria-disabled="busy || undefined" :tabindex="busy ? -1 : undefined" @click="followSource($event, chapterTarget)">来源章节 / 段落 ↗</a></RouterLink>
      <span v-else-if="record.source.chapterUid">来源章节已删除或不可用，摘录保留</span>
      <RouterLink v-if="record.source.eventId && eventExists" :to="eventTarget" custom v-slot="{ href }"><a :href="href" :aria-disabled="busy || undefined" :tabindex="busy ? -1 : undefined" @click="followSource($event, eventTarget)">{{ record.source.eventTitle || '来源事件' }} ↗</a></RouterLink>
      <span v-else-if="record.source.eventTitle">{{ record.source.eventTitle }}（来源事件已移除）</span>
    </div>
    <blockquote v-if="record.source.excerpt">{{ record.source.excerpt }}</blockquote>
    <small v-if="record.source.blockId && chapterExists">若原段落已移除，将回到来源章节；原摘录仍保留。</small>
    <p v-if="record.source.note" class="story-record-value">作者说明：{{ record.source.note }}</p>
    <div class="story-record-foot"><small>作者记录于 {{ new Date(record.recordedAt).toLocaleString() }}</small><button type="button" class="btn-small" :disabled="busy" @click="$emit('retract')">{{ record.retracted ? '恢复记录' : '撤回记录' }}</button></div>
  </article>
</template>
<script setup lang="ts">
import { computed } from 'vue'
import type { RouteLocationRaw } from 'vue-router'
import type { StoryRecord } from '@/types/storyState'
const props = defineProps<{ record: StoryRecord; detail: string; novelId: number; chapterExists: boolean; eventExists: boolean; busy: boolean }>()
const emit = defineEmits<{ retract: []; navigate: [target: RouteLocationRaw] }>()
const chapterTarget = computed(() => ({ path: `/novel/${props.novelId}/writing`, query: { chapter: props.record.source.chapterUid, ...(props.record.source.blockId ? { block: props.record.source.blockId } : {}) } }))
const eventTarget = computed(() => ({ path: `/novel/${props.novelId}/timeline`, query: { edit: String(props.record.source.eventId) } }))
function followSource(event: MouseEvent, target: RouteLocationRaw) {
  if (props.busy) { event.preventDefault(); return }
  // Preserve normal new-tab/modifier navigation; the current panel stays open.
  if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
  event.preventDefault()
  emit('navigate', target)
}
</script>
<style scoped>
.story-record { border: 1px solid var(--border-color, #ddd); border-radius: 10px; padding: 14px; margin: 10px 0; }
.story-record.retracted { opacity: .65; }
.story-record-top, .story-record-foot, .story-sources { display: flex; flex-wrap: wrap; gap: 10px; justify-content: space-between; align-items: center; }
.story-sources { justify-content: flex-start; margin-top: 8px; font-size: 13px; }
.story-sources a[aria-disabled='true'] { opacity: .5; cursor: wait; }
.story-record-value, blockquote { white-space: pre-wrap; overflow-wrap: anywhere; }
small { color: var(--text-secondary, #777); }
blockquote { border-left: 3px solid var(--accent, #977847); margin: 8px 0; padding-left: 10px; }
.story-record-foot { margin-top: 10px; }
</style>
