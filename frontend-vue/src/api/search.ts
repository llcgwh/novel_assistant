import { request, withNovelId } from './request'
import type { SearchResult } from '@/types'
import type { ID } from '@/types'

export const searchApi = {
  global(keyword: string): Promise<SearchResult> {
    return request.get(withNovelId('/search'), { params: { keyword } })
  },

  byTag(tagId: ID): Promise<SearchResult> {
    return request.get(withNovelId(`/search/tag/${tagId}`))
  },

  byTagName(tagName: string): Promise<SearchResult> {
    return request.get(withNovelId('/search/tag-name'), { params: { tagName } })
  }
}
