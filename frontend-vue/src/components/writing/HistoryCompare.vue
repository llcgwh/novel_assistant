<template>
  <section class="history-comparison" aria-label="历史正文差异">
    <div class="history-summary">
      <div>
        <span class="history-eyebrow">REVISION / 稿件对照</span>
        <p>从历史稿到当前稿，看看文字走过的路。</p>
      </div>
      <div class="history-counts">
        <span
          ><b>{{ counts.changed }}</b> 处修改</span
        ><span class="added"
          ><b>{{ counts.added }}</b> 处新增</span
        ><span class="removed"
          ><b>{{ counts.removed }}</b> 处移除</span
        >
      </div>
    </div>
    <p class="history-help">
      绿色是当前稿新增的文字，橙色是历史稿中已移除的文字。采用前会保存当前稿；仅修改正文，保留当前标题和资料关联。
    </p>
    <p v-if="rows.some((row) => row.moved)" class="history-help">
      存在顺序变化；逐段采用保留当前位置，整章恢复才会恢复历史顺序。
    </p>
    <div class="history-controls">
      <label
        ><input
          v-model="onlyChanges"
          type="checkbox"
          :disabled="busy"
        />只看变化</label
      ><span>列表与引用作为完整结构块处理</span>
    </div>
    <p v-if="!visible.length" class="history-empty">这两个版本的正文一致。</p>
    <article
      v-for="row in pageRows"
      :key="row.key"
      class="history-row"
      :class="row.kind"
    >
      <header>
        <span
          >{{ labels[row.kind]
          }}<small v-if="row.moved"> · 位置变化</small></span
        ><small
          >{{
            row.currentIndex >= 0
              ? `当前第 ${row.currentIndex + 1} 块`
              : `历史第 ${row.historicalIndex + 1} 块`
          }}
          · {{ nodeLabel(row.current || row.historical) }}</small
        >
      </header>
      <div class="history-columns">
        <section>
          <h4>历史稿</h4>
          <p v-if="row.historical">
            <template v-for="(part, i) in parts(row)" :key="i"
              ><del v-if="part.kind === 'remove'">{{ part.text }}</del
              ><span v-else-if="part.kind === 'equal'">{{
                part.text
              }}</span></template
            ><span v-if="!blockText(row.historical)" class="history-empty-text"
              >（空段或分隔线）</span
            >
          </p>
          <p v-else class="history-empty-text">这个版本尚无此段</p>
        </section>
        <section>
          <h4>当前稿</h4>
          <p v-if="row.current">
            <template v-for="(part, i) in parts(row)" :key="i"
              ><ins v-if="part.kind === 'add'">{{ part.text }}</ins
              ><span v-else-if="part.kind === 'equal'">{{
                part.text
              }}</span></template
            ><span v-if="!blockText(row.current)" class="history-empty-text"
              >（空段或分隔线）</span
            >
          </p>
          <p v-else class="history-empty-text">当前稿已移除此段</p>
        </section>
      </div>
      <p
        v-if="
          row.kind === 'changed' &&
          blockText(row.current) === blockText(row.historical)
        "
        class="history-format-note"
      >
        文字相同，排版或段落结构有变化。
      </p>
      <footer v-if="row.kind !== 'same'">
        <template v-if="confirmKey === row.key"
          ><span>确认{{ action(row) }}？当前稿会先留档。</span
          ><button
            class="btn-secondary"
            :disabled="busy"
            @click="confirmKey = ''"
          >
            取消</button
          ><button class="btn-primary" :disabled="busy" @click="confirm(row)">
            {{ busy ? '保存中…' : '确认采用' }}
          </button></template
        >
        <button
          v-else
          class="btn-secondary"
          :disabled="busy"
          @click="confirmKey = row.key"
        >
          {{ action(row) }}
        </button>
      </footer>
    </article>
    <nav v-if="pageCount > 1" class="history-pagination" aria-label="差异分页">
      <button
        class="btn-secondary"
        :disabled="page <= 1 || busy"
        @click="page--"
      >
        上一组</button
      ><span>{{ page }} / {{ pageCount }} · 共 {{ visible.length }} 块</span
      ><button
        class="btn-secondary"
        :disabled="page >= pageCount || busy"
        @click="page++"
      >
        下一组
      </button>
    </nav>
  </section>
</template>
<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { DocNode } from '@/types/writing'
import {
  compareHistory,
  diffText,
  blockText,
  type HistoryChange,
} from '@/utils/historyDiff'
const props = defineProps<{
  current: DocNode
  historical: DocNode
  busy: boolean
}>()
const emit = defineEmits<{ adopt: [row: HistoryChange] }>()
const onlyChanges = ref(true),
  confirmKey = ref(''),
  page = ref(1)
const rows = computed(() => compareHistory(props.current, props.historical))
const counts = computed(() => ({
  changed: rows.value.filter((r) => r.kind === 'changed' || r.moved).length,
  added: rows.value.filter((r) => r.kind === 'added').length,
  removed: rows.value.filter((r) => r.kind === 'removed').length,
}))
const visible = computed(() =>
  rows.value.filter(
    (row) => !onlyChanges.value || row.kind !== 'same' || row.moved,
  ),
)
const pageCount = computed(() =>
  Math.max(1, Math.ceil(visible.value.length / 20)),
)
const pageRows = computed(() =>
  visible.value.slice((page.value - 1) * 20, page.value * 20),
)
watch([() => props.current, () => props.historical, onlyChanges], () => {
  confirmKey.value = ''
  page.value = Math.min(page.value, pageCount.value)
})
const labels = {
  same: '正文相同',
  changed: '修改',
  added: '当前新增',
  removed: '当前移除',
}
const parts = (row: HistoryChange) =>
  diffText(blockText(row.historical), blockText(row.current))
const nodeLabel = (node?: DocNode) =>
  ({
    paragraph: '段落',
    heading: '标题',
    blockquote: '引用',
    bulletList: '列表',
    orderedList: '列表',
    horizontalRule: '分隔线',
  })[node?.type || ''] || '结构块'
const action = (row: HistoryChange) =>
  row.kind === 'added'
    ? '移除此新增段落'
    : row.kind === 'removed'
      ? '恢复这个历史段落'
      : '采用这个历史段落'
function confirm(row: HistoryChange) {
  emit('adopt', row)
  confirmKey.value = ''
}
</script>
<style scoped>
.history-comparison {
  margin-top: 22px;
  color: var(--text);
}
.history-summary,
.history-counts,
.history-controls,
.history-row header,
.history-row footer,
.history-pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.history-eyebrow {
  color: var(--accent);
  font-size: 10px;
  letter-spacing: 0.16em;
}
.history-summary p {
  margin: 8px 0;
}
.history-counts {
  gap: 12px;
  font-size: 12px;
  color: var(--muted);
}
.history-counts b {
  font-size: 22px;
  font-weight: 400;
  color: var(--text);
}
.history-help,
.history-controls,
.history-format-note {
  color: var(--muted);
  font-size: 12px;
  line-height: 1.8;
}
.history-controls {
  margin: 16px 0;
}
.history-controls label {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}
.history-controls input {
  accent-color: var(--accent);
  width: auto;
}
.history-row {
  border: 1px solid var(--line);
  border-radius: 14px;
  margin-top: 14px;
  overflow: hidden;
}
.history-row header {
  padding: 12px 16px;
  background: var(--panel);
  font-size: 12px;
}
.history-row header small {
  color: var(--muted);
}
.history-columns {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
}
.history-columns section {
  min-width: 0;
  padding: 16px;
}
.history-columns section + section {
  border-left: 1px solid var(--line);
}
.history-columns h4 {
  font-size: 10px;
  color: var(--muted);
  margin: 0 0 12px;
  letter-spacing: 0.08em;
}
.history-columns p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  line-height: 1.9;
  font-size: 14px;
  margin: 0;
}
.history-columns ins {
  color: var(--accent);
  background: var(--accent-soft);
  text-decoration: none;
  border-bottom: 1px solid var(--accent);
}
.history-columns del {
  color: var(--warning, #c79159);
  background: color-mix(in srgb, #c79159 12%, transparent);
  text-decoration-thickness: 1px;
}
.history-empty-text,
.history-empty {
  color: var(--muted);
  font-style: italic;
}
.history-format-note {
  padding: 0 16px;
}
.history-row footer {
  padding: 12px 16px;
  border-top: 1px solid var(--line);
  justify-content: flex-end;
  font-size: 12px;
}
.history-row footer > span {
  margin-right: auto;
}
.history-pagination {
  justify-content: center;
  margin-top: 20px;
  font-size: 12px;
}
@media (max-width: 600px) {
  .history-columns {
    grid-template-columns: minmax(0, 1fr);
  }
  .history-columns section + section {
    border-left: 0;
    border-top: 1px solid var(--line);
  }
  .history-row footer button {
    flex: 1;
  }
}
</style>
