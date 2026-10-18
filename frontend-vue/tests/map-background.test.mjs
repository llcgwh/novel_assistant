import test from 'node:test'
import assert from 'node:assert/strict'
import { createPinia, setActivePinia } from 'pinia'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources(), api = await load('api/request'), { imagesApi } = await load('api/images')
const { useMapStore } = await load('stores/map'), { MAX_IMAGE_BYTES } = await load('utils/imageUpload')
const image = () => new File(['png-bytes'], 'world.png', { type: 'image/png' })
const until = async predicate => { while (!predicate()) await new Promise(resolve => setImmediate(resolve)) }
function harness(t) {
  const storage = new Map([['mapBackground_2', '7']]), previous = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
  Object.defineProperty(globalThis, 'localStorage', { configurable: true, value: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) } })
  api.setCurrentNovelId(2); setActivePinia(createPinia())
  const original = { ...imagesApi }, calls = []
  imagesApi.upload = async (...args) => { calls.push(args); return { id: 99 } }
  imagesApi.getByType = async () => [{ id: 7 }]
  imagesApi.getFileUrl = (id, novelId) => `/api/novels/${novelId}/images/${id}/file`
  const store = useMapStore(); store.backgroundImageId = 7; store.backgroundImageUrl = '/old-map.png'
  t.after(() => { Object.assign(imagesApi, original); if (previous) Object.defineProperty(globalThis, 'localStorage', previous); else delete globalThis.localStorage })
  return { store, storage, calls }
}

test('map backgrounds validate 8 MiB and raster format before sending, preserving the original selection', async t => {
  const h = harness(t)
  for (const invalid of [{ name: 'world.png', type: 'image/png', size: MAX_IMAGE_BYTES + 1 }, { name: 'world.svg', type: 'image/svg+xml', size: 20 }]) {
    await assert.rejects(h.store.setBackground(invalid, 2))
    assert.equal(h.calls.length, 0); assert.equal(h.store.backgroundImageId, 7); assert.equal(h.storage.get('mapBackground_2'), '7')
    assert.equal(h.store.backgroundUploading, false)
  }
  await h.store.setBackground({ name: 'world.png', type: 'image/png', size: MAX_IMAGE_BYTES }, 2)
  assert.equal(h.calls.length, 1); assert.equal(h.calls[0][2], 2)
  assert.equal(h.store.backgroundImageUrl, '/api/novels/2/images/99/file')
})

test('a failed map upload preserves the image and storage and releases the lock for a same-file retry', async t => {
  const h = harness(t), file = image(), upload = imagesApi.upload
  imagesApi.upload = async () => { throw { response: { status: 413 } } }
  await assert.rejects(h.store.setBackground(file, 2))
  assert.equal(h.store.backgroundImageId, 7); assert.equal(h.store.backgroundImageUrl, '/old-map.png')
  assert.equal(h.storage.get('mapBackground_2'), '7'); assert.equal(h.store.backgroundUploading, false)
  imagesApi.upload = upload; await h.store.setBackground(file, 2)
  assert.equal(h.store.backgroundImageId, 99); assert.equal(h.store.backgroundUploading, false)
})

test('map uploads serialize and a subsequent clear cannot be undone by the old response', async t => {
  const h = harness(t)
  let release, sends = 0, reads = 0
  imagesApi.upload = () => { sends++; return new Promise(resolve => { release = resolve }) }
  imagesApi.getByType = async () => { reads++; return [] }
  const pending = h.store.setBackground(image(), 2); await until(() => release)
  await assert.rejects(h.store.setBackground(image(), 2), /正在上传/)
  await h.store.loadBackgroundFromStorage(2)
  assert.equal(sends, 1); assert.equal(reads, 0); assert.equal(h.store.backgroundImageId, 7)
  h.store.clearBackground(2); release({ id: 99 })
  await assert.rejects(pending, error => error.code === 'ERR_CANCELED')
  assert.equal(h.store.backgroundImageId, null); assert.equal(h.storage.get('mapBackground_2'), 'none')
})

test('old map background loading cannot overwrite a newly uploaded selection', async t => {
  const h = harness(t)
  let release
  imagesApi.getByType = () => new Promise(resolve => { release = resolve })
  const loading = h.store.loadBackgroundFromStorage(2); await until(() => release)
  await h.store.setBackground(image(), 2); release([{ id: 7 }]); await loading
  assert.equal(h.store.backgroundImageId, 99); assert.equal(h.storage.get('mapBackground_2'), '99')
})

test('map upload responses from an earlier A to B to A context never replace the new image or clear its upload lock', async t => {
  const h = harness(t), releases = []
  imagesApi.upload = () => new Promise(resolve => { releases.push(resolve) })
  const oldUpload = h.store.setBackground(image(), 2); await until(() => releases.length === 1)
  api.setCurrentNovelId(3); h.store.$reset(); api.setCurrentNovelId(2); h.store.$reset()
  const newUpload = h.store.setBackground(image(), 2); await until(() => releases.length === 2)
  releases[0]({ id: 50 }); await assert.rejects(oldUpload, error => error.code === 'ERR_CANCELED')
  assert.equal(h.store.backgroundImageId, null); assert.equal(h.store.backgroundUploading, true)
  assert.equal(h.storage.get('mapBackground_2'), '7')
  releases[1]({ id: 100 }); await newUpload
  assert.equal(h.store.backgroundImageId, 100); assert.equal(h.store.backgroundUploading, false)
})

test('background reads and writes refuse mismatched novel IDs and do not silently use the current novel', async t => {
  const h = harness(t)
  await assert.rejects(h.store.setBackground(image(), 3), error => error.code === 'ERR_CANCELED')
  await assert.rejects(h.store.loadBackgroundFromStorage(3), error => error.code === 'ERR_CANCELED')
  assert.throws(() => h.store.clearBackground(3), error => error.code === 'ERR_CANCELED')
  assert.equal(h.calls.length, 0); assert.equal(h.store.backgroundImageId, 7); assert.equal(h.storage.has('mapBackground_3'), false)
})
