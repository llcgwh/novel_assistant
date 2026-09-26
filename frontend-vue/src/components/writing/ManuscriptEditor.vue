<template>
  <section
    class="manuscript-editor"
    :class="{ 'paragraph-focus': focusLine, 'typewriter-mode': typewriter }"
  >
    <div class="writer-formatbar" aria-label="正文格式">
      <button
        title="撤销 ⌘Z"
        aria-label="撤销"
        @click="editor?.chain().focus().undo().run()"
      >
        ↶</button
      ><button
        title="重做"
        aria-label="重做"
        @click="editor?.chain().focus().redo().run()"
      >
        ↷</button
      ><i></i>
      <button
        :class="{ active: editor?.isActive('bold') }"
        aria-label="加粗"
        @click="editor?.chain().focus().toggleBold().run()"
      >
        <b>B</b></button
      ><button
        :class="{ active: editor?.isActive('italic') }"
        aria-label="斜体"
        @click="editor?.chain().focus().toggleItalic().run()"
      >
        <em>I</em></button
      ><button
        aria-label="下划线"
        @click="editor?.chain().focus().toggleUnderline().run()"
      >
        <u>U</u></button
      ><button
        aria-label="引用段落"
        @click="editor?.chain().focus().toggleBlockquote().run()"
      >
        ❞</button
      ><button
        aria-label="插入分隔线"
        @click="editor?.chain().focus().setHorizontalRule().run()"
      >
        —
      </button>
      <select aria-label="段落样式" @change="styleParagraph($event)">
        <option value="paragraph">正文</option>
        <option value="heading">小标题</option>
        <option value="list">项目列表</option></select
      ><i></i
      ><button :aria-expanded="findOpen" @click="findOpen = !findOpen">
        查找</button
      ><button :aria-expanded="appearance" @click="appearance = !appearance">
        排版
      </button>
    </div>
    <div v-if="findOpen" class="writer-find">
      <input
        v-model="find"
        placeholder="查找本章"
        aria-label="查找本章"
        @input="findIndex = -1"
        @keydown.enter="findNext"
      /><input
        v-model="replacement"
        placeholder="替换为"
        aria-label="替换为"
      /><button @click="findNext">下一处</button
      ><button @click="replaceOne">替换</button
      ><button @click="replaceAll">全部替换</button
      ><small>{{ matches.length }} 处</small>
    </div>
    <div v-if="appearance" class="writer-appearance">
      <label
        >字号<input
          v-model.number="fontSize"
          type="range"
          min="16"
          max="28" /></label
      ><label
        >行距<input
          v-model.number="lineHeight"
          type="range"
          min="1.5"
          max="2.8"
          step="0.1" /></label
      ><label
        >字体<select v-model="font">
          <option value="serif">书卷宋体</option>
          <option value="sans-serif">清晰黑体</option>
        </select></label
      ><label><input v-model="indent" type="checkbox" />首行缩进</label
      ><label><input v-model="focusLine" type="checkbox" />段落聚焦</label
      ><label><input v-model="typewriter" type="checkbox" />打字机滚动</label>
    </div>
    <EditorContent
      :editor="editor"
      class="manuscript-paper"
      :style="{
        '--manuscript-size': fontSize + 'px',
        '--manuscript-leading': lineHeight,
        '--manuscript-font':
          font === 'serif'
            ? 'Noto Serif SC, Songti SC, STSong, serif'
            : 'system-ui, sans-serif',
        '--manuscript-indent': indent ? '2em' : '0',
      }"
    />
    <div class="writer-livebar" aria-label="本次写作统计">
      <span
        ><b>{{ count }}</b> 本章字数</span
      ><span
        ><b>{{ rate }}</b> 字/分</span
      ><span
        ><b>{{ meter.record.typed }}</b> 手输</span
      ><span
        ><b>{{ meter.record.net > 0 ? '+' : '' }}{{ meter.record.net }}</b>
        净增</span
      ><span
        >峰值 <b>{{ meter.record.peak }}</b></span
      ><button :aria-pressed="manualPause" @click="manualPause = !manualPause">
        {{ manualPause ? '继续计时' : '暂停计时' }} ·
        {{ elapsedLabel(meter.record.activeSeconds) }}
      </button>
    </div>
    <p v-if="statsError" class="writer-warning" role="status">
      {{ statsError }}
    </p>
  </section>
</template>
<script setup lang="ts">
import {
  computed,
  ref,
  reactive,
  watch,
  onMounted,
  onBeforeUnmount,
  nextTick,
} from 'vue'
import { useEditor, EditorContent, Extension } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import UniqueID from '@tiptap/extension-unique-id'
import Placeholder from '@tiptap/extension-placeholder'
import { isHistoryTransaction } from '@tiptap/pm/history'
import { Plugin } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import { useWritingStore } from '@/stores/writing'
import {
  documentText,
  wordCount,
  WritingMeter,
  elapsedLabel,
} from '@/utils/writing'
import type { DocNode, SessionRecord } from '@/types/writing'
const emit = defineEmits<{ ready: [] }>()
const writer = useWritingStore(),
  chapterUid = writer.current!.uid,
  novelId = writer.novelId
const findOpen = ref(false),
  find = ref(''),
  replacement = ref(''),
  findIndex = ref(-1),
  appearance = ref(false),
  fontSize = ref(20),
  lineHeight = ref(2),
  font = ref('serif'),
  indent = ref(true),
  focusLine = ref(false),
  typewriter = ref(false)
try {
  const p = JSON.parse(localStorage.getItem('ink-writing-appearance') || '{}')
  fontSize.value = p.fontSize || 20
  lineHeight.value = p.lineHeight || 2
  font.value = p.font || 'serif'
  indent.value = p.indent !== false
} catch {}
watch([fontSize, lineHeight, font, indent], () => {
  try {
    localStorage.setItem(
      'ink-writing-appearance',
      JSON.stringify({
        fontSize: fontSize.value,
        lineHeight: lineHeight.value,
        font: font.value,
        indent: indent.value,
      }),
    )
  } catch {}
})
let previous = documentText(writer.current!.doc),
  composing = false,
  source: 'typed' | 'paste' = 'typed',
  disposed = false
const meter = reactive(new WritingMeter(chapterUid, previous)),
  tick = ref(Date.now()),
  manualPause = ref(false),
  statsError = ref('')
const count = computed(() =>
    wordCount(documentText(writer.current?.doc || { type: 'doc' })),
  ),
  rate = computed(() => meter.rate(tick.value))
const ActiveParagraph = Extension.create({
  name: 'activeParagraph',
  addProseMirrorPlugins() {
    return [
      new Plugin({
        props: {
          decorations(state) {
            const { $from } = state.selection
            for (let depth = $from.depth; depth > 0; depth--)
              if ($from.node(depth).attrs.id)
                return DecorationSet.create(state.doc, [
                  Decoration.node($from.before(depth), $from.after(depth), {
                    class: 'writer-active-block',
                  }),
                ])
            return DecorationSet.empty
          },
        },
      }),
    ]
  },
})
const editor = useEditor({
  extensions: [
    ActiveParagraph,
    StarterKit.configure({
      link: false,
      codeBlock: false,
      heading: { levels: [1, 2, 3] },
    }),
    UniqueID.configure({
      types: ['paragraph', 'heading', 'blockquote', 'horizontalRule'],
    }),
    Placeholder.configure({ placeholder: '把脑海里的世界，写到这里…' }),
  ],
  content: writer.current!.doc,
  editorProps: {
    attributes: {
      'aria-label': '章节正文',
      role: 'textbox',
      'aria-multiline': 'true',
      spellcheck: 'false',
    },
    handleDOMEvents: {
      compositionstart: () => {
        composing = true
        return false
      },
      compositionend: () => {
        composing = false
        queueMicrotask(() => {
          if (!disposed) capture('typed')
        })
        return false
      },
      paste: () => {
        source = 'paste'
        return false
      },
    },
  },
  onUpdate: ({ transaction }) => {
    if (composing) return
    capture(
      isHistoryTransaction(transaction) || transaction.getMeta('ink-replace')
        ? 'history'
        : transaction.getMeta('uiEvent') === 'paste'
          ? 'paste'
          : source,
    )
    source = 'typed'
  },
  onCreate: () => {
    updateContext()
    emit('ready')
  },
  onSelectionUpdate: () => updateContext(),
  onFocus: () => updateContext(),
})
function capture(kind: 'typed' | 'paste' | 'history' | 'restore') {
  if (!editor.value || !writer.current || writer.current.uid !== chapterUid)
    return
  const doc = editor.value.getJSON() as DocNode
  const text = documentText(doc)
  rotateDay()
  meter.paused = manualPause.value || document.hidden
  meter.update(previous, text, kind)
  stashStats(meter.record)
  previous = text
  writer.current.doc = doc
  writer.changed()
  updateContext()
}
function updateContext() {
  if (!editor.value || !writer.current) return
  const { from, to, $from } = editor.value.state.selection
  let id = ''
  for (let depth = $from.depth; depth > 0; depth--) {
    if ($from.node(depth).attrs.id) {
      id = $from.node(depth).attrs.id
      break
    }
  }
  writer.activeBlock = id
  writer.contextText = $from.parent.textContent
  writer.selectedText = editor.value.state.doc.textBetween(from, to, '\n')
  requestAnimationFrame(() => {
    const root = editor.value?.view.dom
    if (!root) return
    const block = [...root.querySelectorAll('[data-id]')].find(
      (n) => n.getAttribute('data-id') === id,
    )
    if (typewriter.value && block && document.activeElement === root) {
      const rect = block.getBoundingClientRect()
      if (rect.top < 180 || rect.top > window.innerHeight * 0.68)
        block.scrollIntoView({ block: 'center', behavior: 'auto' })
    }
  })
}
watch(
  () => writer.current?.doc,
  (doc) => {
    if (!doc || !editor.value || writer.current?.uid !== chapterUid) return
    if (JSON.stringify(doc) !== JSON.stringify(editor.value.getJSON())) {
      editor.value.commands.setContent(doc, { emitUpdate: false })
      previous = documentText(doc)
      meter.update(previous, previous, 'restore')
      updateContext()
    }
  },
)
function styleParagraph(e: Event) {
  const value = (e.target as HTMLSelectElement).value
  if (value === 'heading')
    editor.value?.chain().focus().toggleHeading({ level: 2 }).run()
  else if (value === 'list')
    editor.value?.chain().focus().toggleBulletList().run()
  else editor.value?.chain().focus().setParagraph().run()
}
const matches = computed(() => {
  const rows: { from: number; to: number }[] = []
  if (!find.value || !editor.value) return rows
  editor.value.state.doc.descendants((node, pos) => {
    if (!node.isTextblock) return
    const text = node.textBetween(0, node.content.size, '', '\n')
    let at = 0
    while ((at = text.indexOf(find.value, at)) !== -1) {
      rows.push({ from: pos + 1 + at, to: pos + 1 + at + find.value.length })
      at += find.value.length
    }
  })
  return rows
})
function findNext() {
  if (!matches.value.length) return
  findIndex.value = (findIndex.value + 1) % matches.value.length
  editor.value
    ?.chain()
    .focus()
    .setTextSelection(matches.value[findIndex.value])
    .scrollIntoView()
    .run()
}
function replaceOne() {
  if (findIndex.value < 0) {
    findNext()
    return
  }
  const match = matches.value[findIndex.value]
  if (!match) return
  if (editor.value)
    editor.value.view.dispatch(
      editor.value.state.tr
        .insertText(replacement.value, match.from, match.to)
        .setMeta('ink-replace', true),
    )
  findIndex.value = -1
}
function replaceAll() {
  if (!editor.value) return
  const tr = editor.value.state.tr.setMeta('ink-replace', true)
  for (const m of [...matches.value].reverse())
    tr.insertText(replacement.value, m.from, m.to)
  editor.value.view.dispatch(tr)
  findIndex.value = -1
}

const submitted = new Map<string, number>(),
  inFlight = new Map<string, Promise<void>>()
function stashStats(record: SessionRecord) {
  if (!record.typed && !record.pasted && !record.net) return
  try {
    localStorage.setItem(
      `ink-session-${novelId}-${record.uid}`,
      JSON.stringify(record),
    )
  } catch {
    statsError.value = '统计本机缓存不可用；正文保存状态不受影响。'
  }
}
function persistRecord(value: SessionRecord): Promise<void> {
  const record = JSON.parse(JSON.stringify(value)) as SessionRecord
  if (
    (submitted.get(record.uid) ?? -1) >= record.sequence ||
    (!record.typed && !record.pasted && !record.net)
  )
    return Promise.resolve()
  const pending = inFlight.get(record.uid)
  if (pending) return pending.then(() => persistRecord(record))
  stashStats(record)
  const task = (async () => {
    try {
      await writer.saveSession(record, novelId)
      submitted.set(record.uid, record.sequence)
      statsError.value = ''
      const key = `ink-session-${novelId}-${record.uid}`,
        local = JSON.parse(localStorage.getItem(key) || 'null')
      if (!local || local.sequence <= record.sequence)
        localStorage.removeItem(key)
    } catch {
      statsError.value = '写作统计尚未上传，已保留本机记录。'
    } finally {
      inFlight.delete(record.uid)
    }
  })()
  inFlight.set(record.uid, task)
  return task
}
function rotateDay() {
  const previous = meter.rollover()
  if (previous) void persistRecord(previous)
}
function persistMeter() {
  return persistRecord(meter.record)
}
let interval: ReturnType<typeof setInterval> | undefined,
  lastPersist = 0
onMounted(() => {
  interval = setInterval(() => {
    tick.value = Date.now()
    rotateDay()
    meter.paused = manualPause.value || document.hidden
    meter.tick(tick.value)
    if (tick.value - lastPersist > 15000) {
      lastPersist = tick.value
      void persistMeter()
    }
  }, 1000)
  void nextTick(updateContext)
})
onBeforeUnmount(() => {
  disposed = true
  clearInterval(interval)
  void persistMeter()
})
defineExpose({ editor, findNext, find, flushStats: persistMeter })
</script>
