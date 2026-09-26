import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const { createSheetFlight, sheets } = await load('utils/sheets')
const { studioModules } = await load('utils/studio')

function harness() {
  let now = 0, serial = 0
  const timers = new Map(), events = []
  const flight = createSheetFlight({
    set(fn, delay) { const id = ++serial; timers.set(id, { fn, at: now + delay }); return id },
    clear(id) { timers.delete(id) },
  })
  return {
    flight, events, timers,
    advance(ms) { now += ms; for (const [id, timer] of timers) if (timer.at <= now) { timers.delete(id); timer.fn() } },
    start(name) {
      let finish, reject
      const animation = { finished: new Promise((resolve, fail) => { finish = resolve; reject = fail }), cancel() { events.push(name + ':cancel') } }
      flight.run(animation, 800, () => events.push(name + ':swap'), () => events.push(name + ':settle'))
      return { finish, reject }
    },
  }
}

test('the sheet swaps at the edge of its turn and settles when both animations finish', async () => {
  const h = harness(), a = h.start('characters')
  h.advance(399); assert.deepEqual(h.events, [])
  h.advance(1); assert.deepEqual(h.events, ['characters:swap'])
  a.finish(); await Promise.resolve()
  assert.deepEqual(h.events, ['characters:swap', 'characters:settle'])
  assert.equal(h.timers.size, 0)
})

test('rapid navigation invalidates the old content swap and a late completion', async () => {
  const h = harness(), a = h.start('characters')
  h.advance(170)
  const b = h.start('outlines')
  a.finish(); await Promise.resolve()
  h.advance(400)
  b.finish(); await Promise.resolve()
  assert.deepEqual(h.events, ['characters:cancel', 'outlines:swap', 'outlines:settle'])
})

test('interrupting a flight after the face changed cannot restore its obsolete destination', async () => {
  const h = harness(), a = h.start('characters')
  h.advance(500)
  const b = h.start('timeline')
  a.finish(); await Promise.resolve()
  h.advance(400); b.finish(); await Promise.resolve()
  assert.deepEqual(h.events, ['characters:swap', 'characters:cancel', 'timeline:swap', 'timeline:settle'])
})

test('leaving the novel or disabling motion cancels pending timers and rejected animations safely', async () => {
  const h = harness(), a = h.start('worldview')
  h.flight.cancel()
  a.reject(new Error('animation cancelled'))
  h.advance(900); await Promise.resolve()
  assert.deepEqual(h.events, ['worldview:cancel'])
  assert.equal(h.timers.size, 0)
})

test('every workspace route has a sheet destination with a usable layout', () => {
  assert.deepEqual(sheets.map(item => item.id).sort(), [...studioModules.map(item => item.path), 'settings', 'search'].sort())
  assert.ok(sheets.every(item => ['hero', 'left', 'right', 'wide'].includes(item.layout)))
})
