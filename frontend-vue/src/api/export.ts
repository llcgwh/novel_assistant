import { getApiBaseUrl, getCurrentNovelId } from './request'

export const exportApi = {
  getJsonUrl(): string {
    return `${getApiBaseUrl()}/novels/${getCurrentNovelId()}/export/json`
  },

  getMarkdownUrl(): string {
    return `${getApiBaseUrl()}/novels/${getCurrentNovelId()}/export/markdown`
  },

  getCharactersMarkdownUrl(): string {
    return `${getApiBaseUrl()}/novels/${getCurrentNovelId()}/export/characters/markdown`
  },

  getOutlinesMarkdownUrl(): string {
    return `${getApiBaseUrl()}/novels/${getCurrentNovelId()}/export/outlines/markdown`
  },

  downloadJson(): void {
    window.open(this.getJsonUrl(), '_blank')
  },

  downloadMarkdown(): void {
    window.open(this.getMarkdownUrl(), '_blank')
  },

  downloadCharactersMarkdown(): void {
    window.open(this.getCharactersMarkdownUrl(), '_blank')
  },

  downloadOutlinesMarkdown(): void {
    window.open(this.getOutlinesMarkdownUrl(), '_blank')
  }
}
