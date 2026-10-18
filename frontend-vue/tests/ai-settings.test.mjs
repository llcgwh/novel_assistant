import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { computed, reactive, ref } from 'vue'

const source = readFileSync(new URL('../src/components/settings/AiSettings.vue', import.meta.url), 'utf8')
const script = source.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1]
const js = ts.transpileModule(script, { compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 } }).outputText.replace(/^import .* from .*\n/gm, '')
const baseConfig = () => ({ baseUrl: 'https://api.deepseek.com', model: 'author-selected-model', endpointMode: 'remote', proxyUrl: '', hasKey: true, configured: true, revision: 'revision-1', maxInputChars: 120000, maxOutputTokens: 2000, tokenParameter: 'max_tokens' })
function harness(initial = baseConfig()) {
  let saved = { ...initial }, mounted
  const writes = [], tests = []
  const api = {
    config: async () => ({ ...saved }),
    saveConfig: async input => {
      writes.push({ ...input })
      saved = { ...saved, ...input, hasKey: !input.clearKey, revision: `revision-${writes.length + 1}` }
      delete saved.apiKey
      return { ...saved }
    },
    test: async revision => { tests.push(revision); return { success: true } },
  }
  const runtime = { computed, reactive, ref, aiApi: api, onMounted: fn => { mounted = fn } }
  const component = new Function(...Object.keys(runtime), `${js}\nreturn { form, config, connectionMode, proxyDraft, proxyUrl, busy, changed, endpointChanged, error, message, load, save, clearKey, test };`)(...Object.values(runtime))
  return { ...component, api, writes, tests, mount: () => mounted() }
}

test('older configurations without a proxy load as direct without a dirty draft', async () => {
  const initial = baseConfig(); delete initial.proxyUrl
  const h = harness(initial); await h.mount()
  assert.equal(h.config.value.proxyUrl, '')
  assert.equal(h.connectionMode.value, 'direct')
  assert.equal(h.proxyDraft.value, '')
  assert.equal(h.changed.value, false)
})

test('selecting an empty proxy is unsaved and cannot silently test or save a direct connection', async () => {
  const h = harness(); await h.mount(); h.connectionMode.value = 'proxy'
  assert.equal(h.changed.value, true); assert.equal(h.endpointChanged.value, false)
  await h.test(); await h.save()
  assert.equal(h.tests.length, 0); assert.equal(h.writes.length, 0)
  assert.match(h.error.value, /填写本机代理地址.*切回直连/)
})

test('proxy changes preserve the endpoint key and revision; switching direct keeps only a component draft', async () => {
  const h = harness(); await h.mount()
  h.connectionMode.value = 'proxy'; h.proxyDraft.value = ' http://127.0.0.1:7897 '
  assert.equal(h.endpointChanged.value, false); assert.equal(h.changed.value, true)
  await h.save()
  assert.equal(h.writes[0].proxyUrl, 'http://127.0.0.1:7897')
  assert.equal(h.writes[0].apiKey, undefined); assert.equal(h.writes[0].expectedRevision, 'revision-1')
  assert.equal(h.config.value.hasKey, true); assert.equal(h.changed.value, false)
  h.connectionMode.value = 'direct'; await h.save()
  assert.equal(h.writes[1].proxyUrl, ''); assert.equal(h.writes[1].expectedRevision, 'revision-2')
  assert.equal(h.config.value.proxyUrl, ''); assert.equal(h.proxyDraft.value, 'http://127.0.0.1:7897')
  assert.equal(h.changed.value, false); assert.equal(h.tests.length, 0)
  h.connectionMode.value = 'proxy'
  assert.equal(h.proxyUrl.value, 'http://127.0.0.1:7897'); assert.equal(h.changed.value, true)
})

test('local endpoints never send the remote proxy setting', async () => {
  const h = harness({ ...baseConfig(), proxyUrl: 'http://127.0.0.1:7897' }); await h.mount()
  h.form.endpointMode = 'local'; h.form.baseUrl = 'http://127.0.0.1:11434/v1'
  await h.save()
  assert.equal(h.writes[0].proxyUrl, ''); assert.equal(h.writes[0].endpointMode, 'local')
  assert.equal(h.connectionMode.value, 'direct')
})

test('deleting the key preserves the saved transport and does not apply unsaved proxy changes', async () => {
  const h = harness({ ...baseConfig(), proxyUrl: 'http://127.0.0.1:7897' }); await h.mount()
  h.proxyDraft.value = 'http://127.0.0.1:8088'; h.connectionMode.value = 'direct'
  await h.clearKey()
  assert.equal(h.writes[0].clearKey, true)
  assert.equal(h.writes[0].proxyUrl, 'http://127.0.0.1:7897')
  assert.equal(h.writes[0].expectedRevision, 'revision-1')
  assert.equal(h.config.value.hasKey, false); assert.equal(h.changed.value, false)
})

test('save conflict retains the proxy and key drafts without replaying the request', async () => {
  const h = harness(); await h.mount(); let calls = 0
  h.connectionMode.value = 'proxy'; h.proxyDraft.value = 'http://127.0.0.1:7897'; h.form.apiKey = 'new-secret-draft'
  h.api.saveConfig = async () => { calls++; throw Error('配置已改变，请重新读取') }
  await h.save()
  assert.equal(calls, 1); assert.equal(h.form.apiKey, 'new-secret-draft')
  assert.equal(h.proxyDraft.value, 'http://127.0.0.1:7897')
  assert.equal(h.config.value.revision, 'revision-1'); assert.equal(h.changed.value, true)
  assert.match(h.error.value, /配置已改变/); assert.doesNotMatch(h.error.value, /new-secret/)
})

test('a failed model test reports the failure without fallback or an automatic paid retry', async () => {
  const h = harness({ ...baseConfig(), proxyUrl: 'http://127.0.0.1:7897' }); await h.mount(); let calls = 0
  h.api.test = async revision => { calls++; assert.equal(revision, 'revision-1'); throw Error('本机代理不可达，请启动代理或切换直连') }
  await h.test()
  assert.equal(calls, 1); assert.equal(h.writes.length, 0)
  assert.equal(h.connectionMode.value, 'proxy'); assert.equal(h.config.value.proxyUrl, 'http://127.0.0.1:7897')
  assert.match(h.error.value, /本机代理不可达/); assert.equal(h.busy.value, false)
})
