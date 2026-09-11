import test, { after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, readdirSync, mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join, relative } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import ts from 'typescript'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import axios from 'axios'

// Compile the actual router/store/API modules; only HTTP transport and browser storage are replaced.
const root = fileURLToPath(new URL('../src/', import.meta.url))
const output = mkdtempSync(fileURLToPath(new URL('.runtime-', import.meta.url)))
after(() => rmSync(output, { recursive: true, force: true }))
function compile(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const source = join(directory, entry.name)
    if (entry.isDirectory()) { compile(source); continue }
    if (!entry.name.endsWith('.ts') || entry.name.endsWith('.d.ts')) continue
    const target = join(output, relative(root, source)).replace(/\.ts$/, '.mjs')
    let code = ts.transpileModule(readFileSync(source, 'utf8'), {
      compilerOptions: { module: ts.ModuleKind.ESNext, target: ts.ScriptTarget.ES2022 }
    }).outputText
    code = code.replaceAll('import.meta.env.VITE_API_BASE_URL', "'/api'")
    code = code.replace(/from ['"](@\/[^'"]+|\.[^'"]+)['"]/g, (_, specifier) => {
      const path = specifier.startsWith('@/') ? join(output, specifier.slice(2)) : join(dirname(target), specifier)
      return `from '${pathToFileURL(path + '.mjs').href}'`
    })
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, code)
  }
}
compile(root)
const load = path => import(pathToFileURL(join(output, path + '.mjs')).href)
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
