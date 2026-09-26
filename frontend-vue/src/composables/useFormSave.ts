import { request, withNovelId } from '@/api/request'

interface Links { tagIds?: number[]; characterIds?: number[]; sceneIds?: number[]; mapLocationIds?: number[] }

export function useFormSave<T extends { id: number }>(resource: string, items: () => T[]) {
  let token: string | undefined
  return {
    reset() { token = undefined },
    async save(id: number | undefined, data: object, links: Links = {}): Promise<T> {
      token ??= crypto.randomUUID()
      const url = withNovelId(`/forms/${resource}`)
      const body = { data, ...links }
      const saved = (id
        ? await request.put(`${url}/${id}`, body)
        : await request.post(url, body, { headers: { 'Idempotency-Key': token } })) as unknown as T
      const list = items()
      const index = list.findIndex(item => item.id === saved.id)
      if (index === -1) list.push(saved)
      else list[index] = saved
      return saved
    }
  }
}
