import test from 'node:test'
import assert from 'node:assert/strict'
import { compileSources } from './helpers/load-source.mjs'

const load = compileSources()
const policy = await load('utils/imageUpload')
const { imagesApi } = await load('api/images')
const { request } = await load('api/request')

test('image boundaries match backup capacity and global background stays smaller', () => {
  const file = { name: 'portrait.png', type: 'image/png', size: 8 * 1024 * 1024 }
  assert.doesNotThrow(() => policy.validateImageFile(file))
  assert.throws(() => policy.validateImageFile({ ...file, size: file.size + 1 }), /8 MiB/)
  assert.throws(() => policy.validateImageFile(file, policy.MAX_BACKGROUND_BYTES), /2 MiB/)
  assert.throws(() => policy.validateImageFile({ ...file, size: 0 }), /为空/)
  assert.throws(() => policy.validateImageFile({ ...file, size: NaN }), /为空/)
  assert.doesNotThrow(() => policy.validateImageFile({ ...file, name: '图像.JPEG', type: '' }))
  assert.equal(policy.getImageMimeType({ ...file, name: '图像.JPEG', type: '' }), 'image/jpeg')
  assert.equal(policy.getImageMimeType({ ...file, name: '图像.bmp', type: 'image/x-ms-bmp' }), 'image/bmp')
  for (const invalid of [
    { ...file, type: 'text/html' }, { ...file, name: 'image.svg', type: 'image/svg+xml' },
    { ...file, name: 'image.exe' }, { ...file, name: 'portrait.heic', type: 'image/heic' },
    { ...file, name: 'png' }, { ...file, name: 'portrait.png', type: 'image/jpeg' },
  ]) assert.throws(() => policy.validateImageFile(invalid), /PNG/)
})

test('image errors translate transport statuses and retain structured server limits', () => {
  const failure = status => ({ response: { status }, message: `Request failed with status code ${status}` })
  assert.match(policy.getImageUploadError(failure(413)), /8 MiB/)
  assert.match(policy.getImageUploadError(failure(415)), /PNG/)
  assert.match(policy.getImageUploadError(failure(500)), /原图片已保留/)
  assert.match(policy.getImageUploadError({ code: 'ERR_NETWORK' }), /无法连接/)
  assert.match(policy.getImageUploadError({ code: 'ECONNABORTED' }), /超时/)
  assert.match(policy.getImageUploadError({ name: 'QuotaExceededError' }), /存储空间不足/)
  assert.equal(policy.getImageUploadError({ response: { status: 413, data: { message: '服务器本次仅允许 4 MiB' } } }), '服务器本次仅允许 4 MiB')
})

test('every API upload validates before transport and normalizes a missing MIME type', async () => {
  const requests = []
  request.defaults.adapter = async config => {
    requests.push(config)
    return { data: { id: 8 }, status: 200, statusText: 'OK', headers: {}, config }
  }
  const valid = new File(['raster fixture'], 'portrait.png', { type: '' })
  assert.deepEqual(await imagesApi.upload(valid, 'character_portrait', 7), { id: 8 })
  assert.equal(requests[0].url, '/novels/7/images')
  assert.equal(requests[0].timeout, 60000)
  assert.equal(requests[0].data.get('file').type, 'image/png')
  assert.equal(await requests[0].data.get('file').text(), 'raster fixture')
  assert.equal(requests[0].data.get('imageType'), 'character_portrait')
  await assert.rejects(imagesApi.upload(new File(['<svg/>'], 'image.svg', { type: 'image/svg+xml' }), 'map_background', 7), /PNG/)
  assert.equal(requests.length, 1)
})
