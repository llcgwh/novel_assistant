import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
const load = compileSources()
const { useBackupMaintenance } = await load('composables/useBackupMaintenance')
const retention = { keepLast: 20, keepDays: 30, version: 0, automatic: false }
const file = { file: '2020_snapshot.json', revision: 'revision', size: 1024, time: '2020-01-01T00:00:00Z', etag: '"tag"', reason: '超出保留策略' }
const plan = () => ({ token: 'test-token', expiresAt: new Date(Date.now() + 600000).toISOString(), bookUid: 'book-a', policy: { ...retention }, candidates: [{ ...file }], retained: [{ ...file, file: 'baseline.json', reason: '恢复基线' }], protectedCount: 1, candidateBytes: 1024, retainedBytes: 1024, legacyProtectedCount: 1, legacyProtectedBytes: 4096, bridgeMarkers: ['bridge.resolved'], message: '已核对' })
const done = submission => ({ ...submission, status: 'SUCCEEDED', deleted: [file.file], deletedBytes: 1024, operationId: 8, message: '清理完成' })
function deferred() { let resolve, reject; const promise = new Promise((a,b) => { resolve=a; reject=b }); return { promise, resolve, reject } }
const settle = () => new Promise(resolve => setImmediate(resolve))
function fixture(overrides = {}, novelId = 11, receipts = new Map()) {
  const calls = []
  const implementations = {
    operations: async () => ({ items: [], nextBefore: null }), storage: async () => ({ totalBytes: 4096, unknownSizeCount: 0 }), retention: async () => ({ ...retention }),
    saveRetention: async (_id, body) => ({ ...body, version: body.version + 1, automatic: false }), preview: async () => plan(), execute: async (_id, body) => done(body), cancel: async () => ({ status: 'CANCELLED' }), ...overrides,
  }
  const api = Object.fromEntries(Object.entries(implementations).map(([name, fn]) => [name, (...args) => { calls.push([name, ...structuredClone(args)]); return fn(...args) }]))
  const storage = { get length() { return receipts.size }, key: index => [...receipts.keys()][index] ?? null, getItem: key => receipts.get(key) ?? null, setItem: (key, value) => receipts.set(key, value), removeItem: key => receipts.delete(key) }
  const state = useBackupMaintenance(novelId, api, storage)
  return { state, calls, receipts, storage, api, named: name => calls.filter(call => call[0] === name) }
}
async function ready(h) { await h.state.loadPolicy(); await h.state.createPreview() }

test('loading maintenance reads history, storage and policy without previewing or deleting', async () => {
  const h = fixture(); await h.state.refresh()
  assert.deepEqual(h.calls.map(c => c[0]).sort(), ['operations','retention','storage'])
  assert.equal(h.state.confirmed.value, false); assert.equal(h.state.canExecute.value, false)
})
test('cleanup requires a nonempty current preview and explicit confirmation', async () => {
  const h = fixture(); await ready(h); await h.state.execute(); assert.equal(h.named('execute').length, 0)
  h.state.confirmed.value = true; await h.state.execute(); await settle()
  assert.equal(h.named('execute').length, 1); assert.equal(h.named('execute')[0][1], 11)
  assert.equal(h.named('execute')[0][2].token, 'test-token'); assert.equal(h.named('execute')[0][2].confirmed, true)
  assert.equal(h.state.preview.value, null); assert.equal(h.state.result.value.deletedBytes, 1024); assert.equal(h.receipts.size, 0)
})
test('empty plans and expired plans never execute, even when the checkbox is forced', async () => {
  for (const changed of [{ candidates: [] }, { expiresAt: '2000-01-01T00:00:00Z' }, { expiresAt: 'invalid' }]) {
    const h = fixture({ preview: async () => ({ ...plan(), ...changed }) }); await ready(h); h.state.confirmed.value = true
    await h.state.execute(); assert.equal(h.named('execute').length, 0)
  }
})
test('policy edits revoke confirmation, and unsaved or invalid policies prevent cleanup', async () => {
  const h = fixture(); await ready(h); h.state.confirmed.value = true; h.state.keepLast.value = 2; h.state.policyEdited()
  assert.equal(h.state.confirmed.value, false); h.state.confirmed.value = true
  await h.state.execute(); assert.equal(h.named('execute').length, 0)
  h.state.keepLast.value = 1; await h.state.savePolicy(); assert.equal(h.named('saveRetention').length, 0)
})
test('policy save uses a version and locks before cancelling an old preview', async () => {
  const cancelling = deferred(), saving = deferred()
  const h = fixture({ cancel: () => cancelling.promise, saveRetention: () => saving.promise }); await ready(h)
  h.state.keepLast.value = 3
  const first = h.state.savePolicy(); const duplicate = h.state.savePolicy()
  assert.equal(h.state.policyBusy.value, true)
  await h.state.refresh(); assert.equal(h.named('retention').length, 1)
  cancelling.resolve({}); await settle()
  assert.equal(h.named('saveRetention').length, 1); assert.deepEqual(h.named('saveRetention')[0][2], { keepLast: 3, keepDays: 30, version: 0 })
  saving.resolve({ ...retention, keepLast: 3, version: 1 }); await Promise.all([first, duplicate])
  assert.equal(h.state.policy.value.version, 1); assert.equal(h.state.policyBusy.value, false)
})
test('policy version conflicts reload the latest values and demand renewed review', async () => {
  let count = 0
  const h = fixture({ retention: async () => count++ ? { ...retention, keepLast: 9, version: 4 } : { ...retention }, saveRetention: async () => { throw { response: { status: 409 } } } })
  await ready(h); h.state.keepLast.value = 4; await h.state.savePolicy()
  assert.equal(h.state.keepLast.value, 9); assert.equal(h.state.policy.value.version, 4)
  assert.match(h.state.policyError.value, /其他设备/); assert.equal(h.state.preview.value, null); assert.equal(h.state.policyBusy.value, false)
})
test('lost cleanup responses retry the same token and UUID, even after preview expiration', async () => {
  let count = 0
  const h = fixture({ execute: async (_id, body) => { if (!count++) throw Error('response lost'); return done(body) } }); await ready(h)
  h.state.confirmed.value = true; await h.state.execute()
  assert.equal(h.state.uncertain.value, true); assert.equal(h.receipts.size, 1)
  h.state.preview.value.expiresAt = '2000-01-01T00:00:00Z'; h.state.tick()
  await h.state.createPreview(); await h.state.cancelPreview(); assert.equal(h.named('preview').length, 1); assert.equal(h.named('cancel').length, 0)
  await h.state.execute()
  assert.deepEqual(h.named('execute')[0][2], h.named('execute')[1][2]); assert.equal(h.state.uncertain.value, false)
})
test('a browser refresh restores only the receipt and never starts deletion automatically', async () => {
  const receipts = new Map()
  const h = fixture({ execute: async () => { throw Error('offline') } }, 11, receipts); await ready(h); h.state.confirmed.value = true; await h.state.execute(); h.state.dispose()
  const restored = fixture({}, 11, receipts); await restored.state.refresh()
  assert.equal(restored.state.uncertain.value, true); assert.equal(restored.named('execute').length, 0)
  await restored.state.execute()
  assert.deepEqual(restored.named('execute')[0][2], h.named('execute')[0][2]); assert.equal(receipts.size, 0)
  const other = fixture({}, 12, new Map([[`backup-cleanup-pending:11:${h.named('execute')[0][2].requestId}`, JSON.stringify(h.named('execute')[0][2])]]))
  assert.equal(other.state.uncertain.value, false)
})
test('storage failure prevents a delete whose retry identity could not be kept', async () => {
  const h = fixture(); h.storage.setItem = () => { throw Error('quota') }; await ready(h); h.state.confirmed.value = true
  await h.state.execute(); assert.equal(h.named('execute').length, 0); assert.match(h.state.cleanupError.value, /未提交删除/)
})
test('repeated clicks during execution submit once and cannot cancel a mutation in flight', async () => {
  const pending = deferred(), h = fixture({ execute: () => pending.promise }); await ready(h); h.state.confirmed.value = true
  const first = h.state.execute(); await h.state.execute(); await h.state.cancelPreview(); await h.state.createPreview()
  assert.equal(h.named('execute').length, 1); assert.equal(h.named('cancel').length, 0)
  pending.resolve(done(h.named('execute')[0][2])); await first
})
test('RUNNING responses preserve receipt and never rotate the operation UUID', async () => {
  const h = fixture({ execute: async (_id, body) => ({ ...done(body), status: 'RUNNING', deleted: [] }) }); await ready(h); h.state.confirmed.value = true
  await h.state.execute(); await h.state.execute(); assert.equal(h.state.uncertain.value, true)
  assert.deepEqual(h.named('execute')[0][2], h.named('execute')[1][2])
})
test('409 invalidates the preview and clears a pending receipt without silent re-preview', async () => {
  const h = fixture({ execute: async () => { throw { response: { status: 409, data: { message: '远端目录已变化' } } } } }); await ready(h); h.state.confirmed.value = true
  await h.state.execute(); assert.equal(h.state.preview.value, null); assert.equal(h.state.uncertain.value, false); assert.equal(h.receipts.size, 0)
  assert.match(h.state.cleanupError.value, /远端目录已变化/); assert.equal(h.named('preview').length, 1)
})
test('PARTIAL and FAILED are terminal; confirmed and uncertain files stay visible for review', async () => {
  for (const status of ['PARTIAL', 'FAILED']) {
    const h = fixture({ execute: async (_id, body) => ({ ...done(body), status, uncertainFiles: ['unknown.json'] }) }); await ready(h); h.state.confirmed.value = true
    await h.state.execute(); await h.state.execute()
    assert.equal(h.state.result.value.status, status); assert.deepEqual(h.state.result.value.uncertainFiles, ['unknown.json'])
    assert.equal(h.named('execute').length, 1); assert.equal(h.state.uncertain.value, false); assert.equal(h.receipts.size, 0)
  }
})
test('cancelling a pending preview drops and cancels its late token for its original novel', async () => {
  const pending = deferred(), h = fixture({ preview: () => pending.promise }, 37); await h.state.loadPolicy()
  const loading = h.state.createPreview(); await h.state.cancelPreview(); pending.resolve(plan()); await loading; await settle()
  assert.equal(h.state.preview.value, null); assert.equal(h.state.previewBusy.value, false); assert.deepEqual(h.named('cancel')[0], ['cancel',37,'test-token'])
  assert.equal(h.named('execute').length, 0)
})
test('a late failed cancellation cannot overwrite a newer preview', async () => {
  const cancelling = deferred(), h = fixture({ cancel: () => cancelling.promise }); await ready(h)
  const cancel = h.state.cancelPreview(); await h.state.createPreview()
  cancelling.reject(Error('old cancel failed')); await cancel
  assert.equal(h.state.preview.value.token, 'test-token'); assert.equal(h.state.cleanupError.value, '')
})
test('failed previews remain retryable without ever issuing execute', async () => {
  let attempts = 0; const h = fixture({ preview: async () => { if (!attempts++) throw Error('network'); return plan() } }); await h.state.loadPolicy()
  await h.state.createPreview(); assert.match(h.state.cleanupError.value, /network/); assert.equal(h.state.previewBusy.value, false)
  await h.state.createPreview(); assert.equal(h.state.preview.value.token, 'test-token'); assert.equal(h.named('execute').length, 0)
})
test('disposal ignores all late reads and cancels late preview tokens', async () => {
  const reading = deferred(), previewing = deferred(); const h = fixture({ storage: () => reading.promise, preview: () => previewing.promise }, 7)
  await h.state.loadPolicy(); const a = h.state.loadStorage(), b = h.state.createPreview(); h.state.dispose()
  reading.resolve({ totalBytes: 123 }); previewing.resolve(plan()); await Promise.all([a,b]); await settle()
  assert.equal(h.state.storage.value, null); assert.equal(h.state.preview.value, null); assert.equal(h.named('cancel')[0][1], 7)
})
test('history pagination retains failures and de-duplicates ids; refresh discards late older pages', async () => {
  const pending = deferred(); let calls = 0
  const h = fixture({ operations: async (_id, before) => {
    if (before) return pending.promise
    return { items: [{ id: ++calls === 1 ? 10 : 20, status: 'FAILED', message: 'connection failed' }], nextBefore: 10 }
  } })
  await h.state.loadHistory(); const older = h.state.loadHistory(true); await h.state.loadHistory()
  pending.resolve({ items: [{ id: 9 }], nextBefore: null }); await older
  assert.deepEqual(h.state.operations.value.map(o => o.id), [20]); assert.equal(h.state.operations.value[0].status, 'FAILED')
})
test('read failures expose errors while preserving the last known history and storage', async () => {
  const h = fixture(); await h.state.refresh(); h.state.operations.value = [{ id: 5 }]
  h.api.operations = async () => { throw Error('history offline') }; h.api.storage = async () => { throw Error('storage offline') }
  await Promise.all([h.state.loadHistory(), h.state.loadStorage()])
  assert.equal(h.state.operations.value[0].id, 5); assert.match(h.state.historyError.value, /offline/)
  assert.equal(h.state.storage.value.totalBytes, 4096); assert.match(h.state.storageError.value, /offline/)
})

test('RUNNING tracking can be explicitly ended and re-previewed without replaying deletion', async () => {
  const h = fixture({ execute: async (_id, body) => ({ ...done(body), status: 'RUNNING', deleted: [] }) }); await ready(h); h.state.confirmed.value = true
  await h.state.execute(); const original = h.named('execute')[0][2]
  await h.state.closeUnknownTracking()
  assert.equal(h.state.uncertain.value, false); assert.equal(h.named('execute').length, 1)
  assert.equal(h.named('preview').length, 2); assert.equal(h.state.confirmed.value, false)
  assert.equal(h.receipts.has(`backup-cleanup-closed:11:${original.requestId}`), true)
  assert.equal(h.receipts.has(`backup-cleanup-pending:11:${original.requestId}`), false)
})
test('one receipt completion never removes another tab’s pending identity', async () => {
  const receipts = new Map(), gate = deferred(), h = fixture({ execute: () => gate.promise }, 11, receipts)
  await ready(h); h.state.confirmed.value = true; const executing = h.state.execute()
  const other = { token: 'other-token', requestId: crypto.randomUUID(), confirmed: true }
  receipts.set(`backup-cleanup-pending:11:${other.requestId}`, JSON.stringify(other))
  gate.resolve(done(h.named('execute')[0][2])); await executing
  assert.equal(receipts.get(`backup-cleanup-pending:11:${other.requestId}`), JSON.stringify(other))
  await h.state.createPreview(); assert.equal(h.state.uncertain.value, true); assert.equal(h.named('preview').length, 1)
})
test('malformed receipts remain visible after refresh and prevent a new delete', async () => {
  const receipts = new Map([['backup-cleanup-pending:11:invalid', '{broken']]), h = fixture({}, 11, receipts)
  assert.match(h.state.receiptError.value, /无法读取/); await h.state.refresh()
  assert.match(h.state.receiptError.value, /无法读取/); await h.state.createPreview()
  assert.equal(h.named('preview').length, 0); assert.equal(receipts.get('backup-cleanup-pending:11:invalid'), '{broken')
})
test('a policy refresh arriving after a new preview revokes its old-policy confirmation', async () => {
  const cancelled = deferred(); let loads = 0
  const h = fixture({ cancel: () => cancelled.promise, retention: async () => ({ ...retention, version: loads++ }) }); await ready(h)
  const refreshing = h.state.refresh(); await h.state.createPreview(); h.state.confirmed.value = true
  cancelled.resolve({}); await refreshing
  assert.equal(h.state.preview.value.policy.version, 0); assert.equal(h.state.policy.value.version, 1)
  assert.equal(h.state.confirmed.value, false); assert.equal(h.state.canExecute.value, false)
})
test('a receipt appearing in another tab requires a fresh explicit result-read action', async () => {
  const h = fixture(); await ready(h); h.state.confirmed.value = true
  const pending = { token: 'another-tab', requestId: crypto.randomUUID(), confirmed: true }
  h.receipts.set(`backup-cleanup-pending:11:${pending.requestId}`, JSON.stringify(pending))
  await h.state.execute()
  assert.equal(h.named('execute').length, 0); assert.equal(h.state.uncertain.value, true)
  await h.state.execute(); assert.deepEqual(h.named('execute')[0][2], pending)
})
test('combined refresh reads audit history after storage has reached its final status', async () => {
  const gate = deferred(); let operationStatus = 'RUNNING'
  const h = fixture({ storage: async () => { await gate.promise; operationStatus = 'SUCCEEDED'; return { totalBytes: 5 } }, operations: async () => ({ items: [{ id: 30, type: 'STORAGE', status: operationStatus }], nextBefore: null }) })
  const refreshing = h.state.refresh(); await settle()
  assert.equal(h.named('operations').length, 0)
  gate.resolve(); await refreshing
  assert.equal(h.state.operations.value[0].status, 'SUCCEEDED'); assert.equal(h.state.storageBusy.value, false)
})
test('standalone storage retries also replace a temporary RUNNING audit row with its outcome', async () => {
  const h = fixture({ operations: async () => ({ items: [{ id: 31, type: 'STORAGE', status: 'FAILED' }], nextBefore: null }), storage: async () => { throw Error('connection failed') } })
  h.state.operations.value = [{ id: 31, type: 'STORAGE', status: 'RUNNING' }]
  await h.state.loadStorage()
  assert.equal(h.state.operations.value[0].status, 'FAILED'); assert.match(h.state.storageError.value, /connection failed/)
})
