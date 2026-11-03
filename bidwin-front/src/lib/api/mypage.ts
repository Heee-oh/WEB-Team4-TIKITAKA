import { deleteJson, getJson, patchJson, postJson } from './client'
import type { ApiResult } from './client'
import type { AuctionStatus, AuctionType } from './auctions'

const MYPAGE_API_PATH = '/api/v1/mypage'

export type TradeRole = 'BUYER' | 'SELLER'
export type MainTradeStatus = 'PAYMENT_PENDING' | 'IN_PROGRESS' | 'DONE'
export type SellingStatus = 'ON_SALE' | 'SOLD' | 'FAILED'
export type DepositStatus = 'HELD' | 'REFUNDED' | 'FORFEITED' | 'USED'
export type TradeStatus =
  | 'WAITING_CONFIRM'
  | 'CONFIRMED'
  | 'COMPLETED'
  | 'BUYER_FAILED'
  | 'SELLER_FAILED'
export type TradeRoute = 'WON' | 'BUY_NOW'
export type HistorySort = 'latest' | 'oldest'

export interface MyProfile {
  nickname: string
  profileImageUrl: string | null
  joinedAt: number
  sellCount: number
  auctionJoinCount: number
}

export interface DepositAccount {
  balance: number
  inUse: number
}

export interface ActiveTrade {
  tradeId: number
  auctionId: number
  title: string
  thumbnailUrl: string | null
  role: TradeRole
  status: Exclude<MainTradeStatus, 'DONE'>
  price: number
}

export interface DownPricing {
  startPrice: number
  minimumPrice: number
  dropPrice: number
  priceDropIntervalMs: number
  startedAt: number
  serverTime: number
}

interface MyItemBase {
  auctionId: number
  title: string
  thumbnailUrl: string | null
  auctionType: AuctionType
  startPrice: number
  price: number
}

export interface SellingItem extends MyItemBase {
  status: SellingStatus
  downPricing: DownPricing | null
}

export interface BuyingItem extends MyItemBase {
  status: MainTradeStatus
}

export interface MyPageResponse {
  profile: MyProfile
  deposit: DepositAccount
  activeTrades: ActiveTrade[]
  sellingItems: SellingItem[]
  buyingItems: BuyingItem[]
}

export interface PageResponse<T> {
  items: T[]
  page: number
  totalPages: number
  totalCount: number
}

export interface HistoryQuery {
  status?: string
  page: number
  size: number
  sort: HistorySort
}

export interface MyBidRecord {
  auctionId: number
  title: string
  thumbnailUrl: string | null
  myBidAmount: number
  deadline: number
  isWinning: boolean
  isSealedPhase: boolean
  biddedAt: number
}

export interface MyTradeRecord {
  auctionId: number
  title: string
  thumbnailUrl: string | null
  finalPrice: number
  purchasedAt: number
  status: TradeStatus
  route: TradeRoute
}

export interface MySaleRecord {
  auctionId: number
  title: string
  thumbnailUrl: string | null
  auctionType: AuctionType
  startPrice: number
  price: number
  status: AuctionStatus
  listedAt: number
}

export interface MyDepositRecord {
  depositId: number
  auctionId: number
  auctionTitle: string
  amount: number
  status: DepositStatus
  changedAt: number
}

export interface NicknameUpdateResponse {
  nickname: string
}

export interface ProfileImageUpdateResponse {
  profileImageUrl: string | null
}

function historyPath(path: string, query: HistoryQuery) {
  const params = new URLSearchParams({
    page: String(query.page),
    size: String(query.size),
    sort: query.sort,
  })
  if (query.status) params.set('status', query.status)
  return `${MYPAGE_API_PATH}/${path}?${params.toString()}`
}

export function requestMyPage(signal?: AbortSignal): Promise<ApiResult<MyPageResponse>> {
  return getJson<MyPageResponse>(MYPAGE_API_PATH, signal)
}

export function requestMyBidRecords(
  query: HistoryQuery,
  signal?: AbortSignal,
): Promise<ApiResult<PageResponse<MyBidRecord>>> {
  return getJson<PageResponse<MyBidRecord>>(historyPath('bids', query), signal)
}

export function requestMyTradeRecords(
  query: HistoryQuery,
  signal?: AbortSignal,
): Promise<ApiResult<PageResponse<MyTradeRecord>>> {
  return getJson<PageResponse<MyTradeRecord>>(historyPath('trades', query), signal)
}

export function requestMySaleRecords(
  query: HistoryQuery,
  signal?: AbortSignal,
): Promise<ApiResult<PageResponse<MySaleRecord>>> {
  return getJson<PageResponse<MySaleRecord>>(historyPath('sales', query), signal)
}

export function requestMyDepositRecords(
  query: HistoryQuery,
  signal?: AbortSignal,
): Promise<ApiResult<PageResponse<MyDepositRecord>>> {
  return getJson<PageResponse<MyDepositRecord>>(historyPath('deposits', query), signal)
}

export function requestNicknameUpdate(
  nickname: string,
): Promise<ApiResult<NicknameUpdateResponse>> {
  return patchJson<NicknameUpdateResponse, { nickname: string }>(
    `${MYPAGE_API_PATH}/nickname`,
    { nickname },
  )
}

export function requestPasswordUpdate(request: {
  currentPassword: string
  newPassword: string
  newPasswordConfirm: string
}): Promise<ApiResult<void>> {
  return patchJson<void, typeof request>(`${MYPAGE_API_PATH}/password`, request)
}

export function requestProfileImageUpdate(
  objectKey: string,
): Promise<ApiResult<ProfileImageUpdateResponse>> {
  return patchJson<ProfileImageUpdateResponse, { objectKey: string }>(
    `${MYPAGE_API_PATH}/profile-image`,
    { objectKey },
  )
}

export function requestProfileImageReset(): Promise<ApiResult<ProfileImageUpdateResponse>> {
  return deleteJson<ProfileImageUpdateResponse>(`${MYPAGE_API_PATH}/profile-image`)
}

/*
 * 서버가 응답 대기 시간을 넘겨 503을 줘도 충전 명령은 나중에 적용될 수 있다.
 * 같은 시도의 재전송을 서버가 알아보도록 시도마다 만든 키를 Idempotency-Key로 보낸다.
 */
export function requestDepositCharge(
  amount: number,
  idempotencyKey: string,
): Promise<ApiResult<DepositAccount>> {
  return postJson<DepositAccount, { amount: number }>(
    `${MYPAGE_API_PATH}/points/charge`,
    { amount },
    { 'Idempotency-Key': idempotencyKey },
  )
}
