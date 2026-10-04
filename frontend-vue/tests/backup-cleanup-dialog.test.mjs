import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
const source = readFileSync(new URL('../src/components/settings/BackupCleanupDialog.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
const code = ts.transpileModule(script, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.None } }).outputText
function harness() {
  const props = { title: '确认远端清理', busy: false }, mounted = [], unmount = [], emitted = [], events = []
  let sharedDialog
  const runtime = {
    ref: value => ({ value }), defineProps: () => props, withDefaults: p => p, defineEmits: () => event => emitted.push(event),
    onMounted: fn => mounted.push(fn), onBeforeUnmount: fn => unmount.push(fn),
    useDialog: (panel, busy, close) => { sharedDialog = { panel, busy, close }; unmount.push(() => events.push('restore-focus')) },
  }
  const component = new Function(...Object.keys(runtime), code + ';return {panel,close,backdropClick}')(...Object.values(runtime))
  const dialog = { open: false, showModal() { this.open = true; events.push('top-layer-open') }, close() { this.open = false; events.push('top-layer-close') }, getBoundingClientRect: () => ({ left: 30, right: 330, top: 20, bottom: 620 }) }
  component.panel.value = dialog
  return { ...component, props, dialog, events, emitted, sharedDialog, mount: () => mounted.forEach(fn => fn()), unmount: () => unmount.forEach(fn => fn()) }
}
test('cleanup uses the native modal top layer and closes it before focus restoration', () => {
  const h = harness(); h.mount()
  assert.equal(h.dialog.open, true); assert.deepEqual(h.events, ['top-layer-open'])
  assert.equal(h.sharedDialog.panel, h.panel)
  h.unmount(); assert.deepEqual(h.events, ['top-layer-open','top-layer-close','restore-focus'])
})
test('shared Escape handling and native cancellation both respect the busy guard', () => {
  const h = harness(); h.props.busy = true
  assert.equal(h.sharedDialog.busy(), true); h.sharedDialog.close(); h.close()
  assert.deepEqual(h.emitted, [])
  h.props.busy = false; h.sharedDialog.close(); assert.deepEqual(h.emitted, ['close'])
})
test('only an outside backdrop click closes the dialog, never panel padding or child content', () => {
  const h = harness()
  h.backdropClick({ target: h.dialog, clientX: 100, clientY: 100 })
  h.backdropClick({ target: {}, clientX: 5, clientY: 5 })
  assert.deepEqual(h.emitted, [])
  h.backdropClick({ target: h.dialog, clientX: 10, clientY: 100 })
  assert.deepEqual(h.emitted, ['close'])
  h.props.busy = true; h.backdropClick({ target: h.dialog, clientX: 100, clientY: 700 })
  assert.deepEqual(h.emitted, ['close'])
})
