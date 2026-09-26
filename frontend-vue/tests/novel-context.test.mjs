import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import axios from 'axios'

const load = compileSources()
const api = await load('api/request')
const { useNovelStore } = await load('stores/novel')
const { useCharactersStore } = await load('stores/characters')
const { useMapStore } = await load('stores/map')
const { useRelationshipGroupsStore } = await load('stores/relationshipGroups')
const { installNovelContext } = await load('router/novelContext')

function setup() {
  const storage = new Map()
  globalThis.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
    removeItem: key => storage.delete(key)
  }
  api.setCurrentNovelId(null)
  setActivePinia(createPinia())
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'NovelSelector', component: {} },
      { path: '/novel/:novelId/:page', component: {}, meta: { requiresNovel: true } }
    ]
  })
  installNovelContext(router)
  const pending = []
  api.request.defaults.adapter = config => new Promise((resolve, reject) => {
    pending.push({ config, reject, finish: data => resolve({ data, status: 200, statusText: 'OK', headers: {}, config }) })
  })
  return { router, pending, novel: useNovelStore(), characters: useCharactersStore() }
}

test('direct links override stored novel before page data requests', async () => {
  const { router, pending, novel, characters } = setup()
  localStorage.setItem('currentNovelId', '99')
  await router.push('/novel/2/characters')
  assert.equal(novel.currentNovelId, 2)
  const fetch = characters.fetchCharacters()
  assert.equal(pending[0].config.url, '/novels/2/characters')
  pending[0].finish([{ id: 21 }])
  await fetch
  assert.equal(characters.characters[0].id, 21)
})

test('invalid and unsafe route IDs return to the novel list', async () => {
  const { router } = setup()
  for (const id of ['0', '-1', '1.5', '2junk', 'NaN', '9007199254740992']) {
    await router.push(`/novel/${id}/characters`)
    assert.equal(router.currentRoute.value.name, 'NovelSelector')
    assert.throws(() => api.getCurrentNovelId(), /No novel selected/)
  }
})

test('switch clears records, groups, background and filters; same novel navigation retains them', async () => {
  const { router, characters } = setup()
  await router.push('/novel/1/characters')
  characters.characters = [{ id: 11 }]
  characters.searchKeyword = 'old'
  const map = useMapStore()
  map.backgroundImageId = 8
  map.backgroundImageUrl = '/old.png'
  const groups = useRelationshipGroupsStore()
  groups.groups = [{ id: 12 }]
  await router.push('/novel/1/scenes')
  assert.equal(characters.characters.length, 1)
  await router.push('/novel/2/characters')
  assert.deepEqual(characters.characters, [])
  assert.equal(characters.searchKeyword, '')
  assert.equal(map.backgroundImageId, null)
  assert.equal(map.backgroundImageUrl, null)
  assert.deepEqual(groups.groups, [])
})

test('late response cannot overwrite data or turn off the new novel loading indicator', async () => {
  const { router, characters, pending } = setup()
  await router.push('/novel/1/characters')
  const oldFetch = assert.rejects(characters.fetchCharacters(), error => axios.isCancel(error))
  await router.push('/novel/2/characters')
  const currentFetch = characters.fetchCharacters()
  pending[0].finish([{ id: 11 }])
  await oldFetch
  assert.deepEqual(characters.characters, [])
  assert.equal(characters.loading, true)
  pending[1].finish([{ id: 21 }])
  await currentFetch
  assert.equal(characters.characters[0].id, 21)
  assert.equal(characters.loading, false)
})

test('A to B to A still rejects the first A response', async () => {
  const { router, characters, pending } = setup()
  await router.push('/novel/1/characters')
  const oldFetch = assert.rejects(characters.fetchCharacters(), error => axios.isCancel(error))
  await router.push('/novel/2/characters')
  await router.push('/novel/1/characters')
  pending[0].finish([{ id: 11 }])
  await oldFetch
  assert.deepEqual(characters.characters, [])
})

test('late tag mutation does not start its follow-up fetch in the new novel', async () => {
  const { router, characters, pending } = setup()
  await router.push('/novel/1/characters')
  const mutation = assert.rejects(characters.setCharacterTags(11, [12]), error => axios.isCancel(error))
  await router.push('/novel/2/characters')
  pending[0].finish({})
  await mutation
  assert.equal(pending.length, 1)
})

test('cancelled navigation preserves active novel and data', async () => {
  const { router, characters, novel } = setup()
  await router.push('/novel/1/characters')
  characters.characters = [{ id: 11 }]
  router.beforeEach(to => to.params.novelId === '2' ? false : undefined)
  await router.push('/novel/2/characters')
  assert.equal(novel.currentNovelId, 1)
  assert.equal(api.getCurrentNovelId(), 1)
  assert.equal(characters.characters.length, 1)
})

test('leaving a novel clears its context while global list requests remain valid', async () => {
  const { router, novel, characters, pending } = setup()
  await router.push('/novel/1/characters')
  characters.characters = [{ id: 11 }]
  const list = novel.fetchNovels()
  await router.push('/')
  assert.equal(novel.currentNovelId, null)
  assert.deepEqual(characters.characters, [])
  assert.throws(() => api.withNovelId('/characters'), /No novel selected/)
  pending[0].finish([{ id: 1, title: 'Novel' }])
  await list
  assert.equal(novel.novels.length, 1)
})
