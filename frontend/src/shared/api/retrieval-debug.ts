import { apiRequest } from './client'
export interface RetrievalDebugHit {
  id: string
  name: string
  excerpt: string
  lexicalRank: number | null
  vectorRank: number | null
  lexicalScore: number | null
  vectorScore: number | null
  recallScore: number
  rerankScore: number
  recallRank: number
  rank: number
}
export interface RetrievalDebugResult {
  items: RetrievalDebugHit[]
  vectorAvailable: boolean
  embedding: string
  reranker: string
  notice: string
}
export const debugRetrieval = (
  query: string,
  limit: number,
  collection: 'occupations' | 'staff_docs' = 'occupations',
) =>
  apiRequest<RetrievalDebugResult>(
    `/staff/knowledge/retrieval-debug?${new URLSearchParams({ query, limit: String(limit), collection })}`,
  )
