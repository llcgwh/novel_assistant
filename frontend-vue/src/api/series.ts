import { request } from './request'
import type {
  SeriesAdoptBody, SeriesArchiveCopyBody, SeriesArchiveTemplateBody,
  SeriesComparison, SeriesCreateCopyBody, SeriesCreateTemplateBody,
  SeriesCreateWorldBody, SeriesDuplicateCopyBody, SeriesImportBody,
  SeriesImportPreview, SeriesImportResult, SeriesMutationBody,
  SeriesMutationResult, SeriesPack, SeriesPublishedTemplateResult,
  SeriesPublishTemplateBody, SeriesRestoreCopyBody, SeriesReviewBody,
  SeriesSelectHeadBody, SeriesState, SeriesTemplate, SeriesTemplateDetails,
  SeriesTemplateResult, SeriesUpdateCopyBody, SeriesUpdateWorldBody,
} from '@/types/series'

const templateUrl = (uid: string, path = '') => `/series/templates/${encodeURIComponent(uid)}${path}`
const novelUrl = (novelId: number, path = '') => `/novels/${novelId}/series${path}`
const copyPath = (uid: string, path = '') => `/copies/${encodeURIComponent(uid)}${path}`

export const seriesApi = {
  listTemplates(includeArchived = true): Promise<{ items: SeriesTemplate[] }> {
    return request.get('/series/templates', { params: { includeArchived } })
  },
  template(uid: string): Promise<SeriesTemplateDetails> {
    return request.get(templateUrl(uid))
  },
  createTemplate(body: SeriesCreateTemplateBody): Promise<SeriesPublishedTemplateResult> {
    return request.post('/series/templates', body)
  },
  publishTemplate(uid: string, body: SeriesPublishTemplateBody): Promise<SeriesPublishedTemplateResult> {
    return request.post(templateUrl(uid, '/revisions'), body)
  },
  selectHead(uid: string, body: SeriesSelectHeadBody): Promise<SeriesTemplateResult> {
    return request.post(templateUrl(uid, '/head'), body)
  },
  archiveTemplate(uid: string, body: SeriesArchiveTemplateBody): Promise<SeriesTemplateResult> {
    return request.post(templateUrl(uid, '/archive'), body)
  },
  state(novelId: number): Promise<SeriesState> {
    return request.get(novelUrl(novelId))
  },
  createWorld(novelId: number, body: SeriesCreateWorldBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, '/worlds'), body)
  },
  updateWorld(novelId: number, uid: string, body: SeriesUpdateWorldBody): Promise<SeriesMutationResult> {
    return request.put(novelUrl(novelId, `/worlds/${encodeURIComponent(uid)}`), body)
  },
  removeWorld(novelId: number, uid: string, body: SeriesMutationBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, `/worlds/${encodeURIComponent(uid)}/remove`), body)
  },
  copy(novelId: number, body: SeriesCreateCopyBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, '/copies'), body)
  },
  duplicateCopy(novelId: number, uid: string, body: SeriesDuplicateCopyBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, copyPath(uid, '/duplicate')), body)
  },
  updateCopy(novelId: number, uid: string, body: SeriesUpdateCopyBody): Promise<SeriesMutationResult> {
    return request.put(novelUrl(novelId, copyPath(uid)), body)
  },
  archiveCopy(novelId: number, uid: string, body: SeriesArchiveCopyBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, copyPath(uid, '/archive')), body)
  },
  restoreCopy(novelId: number, uid: string, body: SeriesRestoreCopyBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, copyPath(uid, '/restore')), body)
  },
  compare(novelId: number, uid: string, revisionUid: string): Promise<SeriesComparison> {
    return request.post(novelUrl(novelId, copyPath(uid, '/compare')), { revisionUid })
  },
  adopt(novelId: number, uid: string, body: SeriesAdoptBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, copyPath(uid, '/adopt')), body)
  },
  review(novelId: number, uid: string, body: SeriesReviewBody): Promise<SeriesMutationResult> {
    return request.post(novelUrl(novelId, copyPath(uid, '/review')), body)
  },
  exportLibrary(templateUids?: string[]): Promise<SeriesPack> {
    return request.post('/series/export', templateUids === undefined ? {} : { templateUids }, { timeout: 60000 })
  },
  previewImport(pack: SeriesPack): Promise<SeriesImportPreview> {
    return request.post('/series/import/preview', { package: pack }, { timeout: 60000 })
  },
  importLibrary(body: SeriesImportBody): Promise<SeriesImportResult> {
    return request.post('/series/import', body, { timeout: 60000 })
  },
}
