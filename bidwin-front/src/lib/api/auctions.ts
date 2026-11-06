import { getJson, postJson } from './client'
import type { ApiResult } from './client'
import type { AuctionCategory } from '../auctionCategory'

export type { AuctionCategory }

export type AuctionType = 'UP' | 'DOWN'
export type AuctionStatus = 'OPEN' | 'BID_ONGOING' | 'WINNER_DETERMINING' | 'COMPLETED' | 'UNSOLD'
export type AuctionListStatusFilter = 'ACTIVE' | 'ENDED'
export type TradeType = 'DELIVERY' | 'DIRECT'
export type BidType = 'OPEN' | 'SEALED'
export type AuctionSort = 'recommended' | 'deadline' | 'latest' | 'priceLow' | 'priceHigh'

export interface AuctionDownPricing {
  minimumPrice: number
  dropPrice: number
  priceDropIntervalMs: number
  startedAt: number
}

export interface AuctionSummary {
  auctionId: number
  auctionType: AuctionType
  title: string
  sellerName: string
  category: AuctionCategory
  thumbnailUrl: string | null
  currentPrice: number
  startPrice: number
  bidCount: number
  deadline: number
  listedAt: number
  status: AuctionStatus
  revision: number
  downPricing: AuctionDownPricing | null
}

export interface AuctionListResponse {
  items: AuctionSummary[]
  serverTime: number
  asOf: number
  page: number
  hasNext: boolean
  snapshotReset: boolean
  snapshotResetReason: 'GENERATION_EXPIRED' | null
}

export interface AuctionSeller {
  sellerId: number
  name: string
  profileImageUrl: string | null
  verified: boolean
  dealCount: number
}

interface AuctionDetailBase {
  auctionId: number
  auctionType: AuctionType
  title: string
  description: string
  category: AuctionCategory
  status: AuctionStatus
  revision: number
  images: string[]
  startPrice: number
  deadline: number
  tradeType: TradeType
  seller: AuctionSeller
}

export interface UpAuctionDetail extends AuctionDetailBase {
  auctionType: 'UP'
  serverTime: number
  sealedBidStartsAt: number
  buyNowPrice: number | null
  currentPrice: number
  bidCount: number
}

export interface DownAuctionDetail extends AuctionDetailBase {
  auctionType: 'DOWN'
  startedAt: number
  serverTime: number
  finalPrice: number | null
  minimumPrice: number
  dropPrice: number
  priceDropIntervalMs: number
}

export type AuctionDetail = UpAuctionDetail | DownAuctionDetail

export interface BidHistoryItem {
  entryId: string
  bidder: string
  amount: number
  biddedAt: number
}

export interface BidHistoryResponse {
  bidCount: number
  bidLog: BidHistoryItem[]
}

export interface BidRequest {
  price: number
  bidType: BidType
}

interface BidResponseBase {
  bidId: number
  auctionId: number
  bidderId: number
  status: 'UP' | 'SEALED'
  bidAt: string
}

export interface OpenBidResponse extends BidResponseBase {
  status: 'UP'
  price: number
}

export interface SealedBidResponse extends BidResponseBase {
  status: 'SEALED'
}

export type BidResponse = OpenBidResponse | SealedBidResponse

export interface BuyNowResponse {
  tradeId: number
  auctionId: number
  finalPrice: number
  purchasedAt: string
}

export interface AuctionListQuery {
  keyword: string
  auctionType: AuctionType | 'ALL'
  status?: AuctionListStatusFilter
  category?: AuctionCategory
  sort: AuctionSort
  page: number
  asOf?: number
}

export interface AuctionCategoryOption {
  code: AuctionCategory
  label: string
}

export interface AuctionCreateRequest {
  draftId: string
  title: string
  description: string
  category: AuctionCategory
  contact: string
  auctionType: AuctionType
  tradeType: TradeType
  durationMinutes: number
  startPrice: number
  buyNowPrice: number | null
  minimumPrice: number | null
  dropPrice: number | null
  priceDropInterval: number | null
  imageUploadIds: string[]
}

export interface AuctionCreateResponse {
  auctionId: number
}

const API_PATH = '/api/v1/auctions'
const CATEGORY_API_PATH = '/api/v1/categories'

export function requestAuctionCreate(
  request: AuctionCreateRequest,
): Promise<ApiResult<AuctionCreateResponse>> {
  return postJson<AuctionCreateResponse, AuctionCreateRequest>(API_PATH, request)
}

export function requestAuctionList(
  query: AuctionListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<AuctionListResponse>> {
  const params = new URLSearchParams({
    sort: query.sort,
    page: String(query.page),
  })
  const keyword = query.keyword.trim()
  if (keyword) params.set('keyword', keyword)
  if (query.auctionType !== 'ALL') params.set('auctionType', query.auctionType)
  if (query.status) params.set('status', query.status)
  if (query.category) params.set('category', query.category)
  if (query.asOf !== undefined) params.set('asOf', String(query.asOf))
  return getJson<AuctionListResponse>(`${API_PATH}?${params.toString()}`, signal)
}

export function requestAuctionCategories(
  signal?: AbortSignal,
): Promise<ApiResult<AuctionCategoryOption[]>> {
  return getJson<AuctionCategoryOption[]>(CATEGORY_API_PATH, signal)
}

export function requestAuctionDetail(
  auctionId: number,
  signal?: AbortSignal,
): Promise<ApiResult<AuctionDetail>> {
  return getJson<AuctionDetail>(`${API_PATH}/${auctionId}`, signal)
}

export function requestBidHistory(
  auctionId: number,
  signal?: AbortSignal,
): Promise<ApiResult<BidHistoryResponse>> {
  return getJson<BidHistoryResponse>(`${API_PATH}/${auctionId}/bids`, signal)
}

export function requestBid(
  auctionId: number,
  request: BidRequest,
): Promise<ApiResult<BidResponse>> {
  return postJson<BidResponse, BidRequest>(`${API_PATH}/up/${auctionId}/bids`, request)
}

export function requestBuyNow(
  auctionType: AuctionType,
  auctionId: number,
  idempotencyKey: string,
): Promise<ApiResult<BuyNowResponse>> {
  return postJson<BuyNowResponse, { idempotencyKey: string }>(
    `${API_PATH}/${auctionType.toLowerCase()}/${auctionId}/buy-now`,
    { idempotencyKey },
  )
}
