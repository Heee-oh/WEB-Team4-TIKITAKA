import { SearchX } from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import Button from '../../components/ui/Button'
import { useAuctionEvents } from '../../hooks/useAuctionEvents'
import { useServerClock } from '../../hooks/useServerClock'
import { useToast } from '../../hooks/useToast'
import { requestAuctionCategories, requestAuctionList } from '../../lib/api/auctions'
import type { AuctionCategoryOption, AuctionListResponse } from '../../lib/api/auctions'
import AuctionCard, { AuctionCardSkeleton } from './components/AuctionCard'
import AuctionToolbar from './components/AuctionToolbar'
import FilterModal from './components/FilterModal'
import FilterPanel from './components/FilterPanel'
import FilterSheet from './components/FilterSheet'
import Pagination from './components/Pagination'
import {
  CATEGORY_QUERY_PARAM,
  FIRST_PAGE,
  LIST_TEXT,
  MAX_PAGES,
  PAGE_SIZE,
  SEARCH_QUERY_PARAM,
} from './constants'
import {
  DEFAULT_FILTER_SELECTION,
  FILTER_GROUP_ID,
  countSelectedOptions,
  createFilterGroups,
  toAuctionListFilters,
} from './filters'
import type { FilterSelection } from './filters'
import { DEFAULT_SORT } from './query'
import type { SortKey } from './query'
import type { AuctionTypeFilter } from './types'

const CONTENT_HEIGHT_CLASS = 'h-[calc(100dvh-4rem)]'
const FILTER_PANEL_WIDTH_CLASS = 'w-[190px]'
const SKELETON_KEYS = Array.from({ length: PAGE_SIZE }, (_, index) => index)

interface AuctionPagination {
  queryKey: string
  page: number
  maxVisitedPage: number
  lastPage: number | null
}

function AuctionListPage() {
  const { showToast } = useToast()
  const [searchParams, setSearchParams] = useSearchParams()
  const keyword = searchParams.get(SEARCH_QUERY_PARAM) ?? ''
  const initialCategory = searchParams.get(CATEGORY_QUERY_PARAM)
  const [isPanelOpen, setIsPanelOpen] = useState(true)
  const [isFilterEnabled, setIsFilterEnabled] = useState(true)
  const [selection, setSelection] = useState<FilterSelection>(() => ({
    ...DEFAULT_FILTER_SELECTION,
    ...(initialCategory ? { [FILTER_GROUP_ID.category]: [initialCategory] } : {}),
  }))
  const [openGroupId, setOpenGroupId] = useState<string | null>(null)
  const [isFilterSheetOpen, setIsFilterSheetOpen] = useState(false)
  const [categories, setCategories] = useState<AuctionCategoryOption[] | null>(null)
  const [auctionType, setAuctionType] = useState<AuctionTypeFilter>('ALL')
  const [sort, setSort] = useState<SortKey>(DEFAULT_SORT)
  const filterGroups = createFilterGroups(categories)
  const appliedFilters = useMemo(
    () => toAuctionListFilters(selection, isFilterEnabled),
    [isFilterEnabled, selection],
  )
  const queryKey = `${keyword}\u0000${auctionType}\u0000${appliedFilters.status ?? ''}\u0000${appliedFilters.category ?? ''}\u0000${sort}`
  const [pagination, setPagination] = useState<AuctionPagination>({
    queryKey,
    page: FIRST_PAGE,
    maxVisitedPage: FIRST_PAGE,
    lastPage: null,
  })
  const activePagination = pagination.queryKey === queryKey
    ? pagination
    : { queryKey, page: FIRST_PAGE, maxVisitedPage: FIRST_PAGE, lastPage: null }
  const { page, maxVisitedPage, lastPage } = activePagination
  const [response, setResponse] = useState<AuctionListResponse | null>(null)
  const [isLoading, setIsLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [retryToken, setRetryToken] = useState(0)
  const snapshotGenerationRef = useRef<{ queryKey: string; asOf: number } | null>(null)
  const fulfilledResetPageRef = useRef<{
    queryKey: string
    page: number
    retryToken: number
  } | null>(null)
  const listRef = useRef<HTMLDivElement>(null)
  const { serverOffsetMs } = useServerClock(response?.serverTime)

  useEffect(() => {
    const controller = new AbortController()
    let active = true

    requestAuctionCategories(controller.signal).then((result) => {
      if (!active) return
      if (!result.ok) {
        setCategories([])
        showToast('카테고리를 불러오지 못했어요.', 'info')
        return
      }
      setCategories(result.data)
    })

    return () => {
      active = false
      controller.abort()
    }
  }, [showToast])

  useEffect(() => {
    const fulfilledResetPage = fulfilledResetPageRef.current
    fulfilledResetPageRef.current = null
    if (
      fulfilledResetPage?.queryKey === queryKey
      && fulfilledResetPage.page === page
      && fulfilledResetPage.retryToken === retryToken
    ) {
      return
    }

    const controller = new AbortController()
    let active = true
    const snapshotAsOf = snapshotGenerationRef.current?.queryKey === queryKey
      ? snapshotGenerationRef.current.asOf
      : undefined

    setIsLoading(true)
    setError(null)
    setResponse(null)
    requestAuctionList({
      keyword,
      auctionType,
      status: appliedFilters.status,
      category: appliedFilters.category,
      sort,
      page,
      asOf: snapshotAsOf,
    }, controller.signal).then((result) => {
      if (!active) return
      if (!result.ok) {
        setIsLoading(false)
        setError(result.message)
        return
      }
      snapshotGenerationRef.current = { queryKey, asOf: result.data.asOf }
      if (result.data.snapshotReset) {
        if (page !== result.data.page) {
          fulfilledResetPageRef.current = {
            queryKey,
            page: result.data.page,
            retryToken,
          }
        }
        setPagination({
          queryKey,
          page: result.data.page,
          maxVisitedPage: result.data.page,
          lastPage: result.data.items.length < PAGE_SIZE ? result.data.page : null,
        })
      } else if (result.data.items.length === 0 && result.data.page > FIRST_PAGE) {
        const previousPage = result.data.page - 1
        setPagination((current) => ({
          queryKey,
          page: previousPage,
          maxVisitedPage: current.queryKey === queryKey
            ? Math.min(current.maxVisitedPage, previousPage)
            : previousPage,
          lastPage: previousPage,
        }))
        return
      } else {
        setPagination((current) => {
          const isSameQuery = current.queryKey === queryKey
          return {
            queryKey,
            page: result.data.page,
            maxVisitedPage: Math.max(
              isSameQuery ? current.maxVisitedPage : FIRST_PAGE,
              result.data.page,
            ),
            lastPage: result.data.items.length < PAGE_SIZE
              ? result.data.page
              : isSameQuery ? current.lastPage : null,
          }
        })
      }
      setIsLoading(false)
      setResponse(result.data)
    })

    return () => {
      active = false
      controller.abort()
    }
  }, [appliedFilters.category, appliedFilters.status, auctionType, keyword, page, queryKey, retryToken, sort])

  const auctionIds = response?.items.map((auction) => auction.auctionId) ?? []
  useAuctionEvents('list', auctionIds, {
    onState: (state) => {
      setResponse((current) => {
        if (!current) return current
        let changed = false
        const items = current.items.map((auction) => {
          if (auction.auctionId !== state.auctionId || state.revision < auction.revision) {
            return auction
          }
          changed = true
          return {
            ...auction,
            revision: state.revision,
            status: state.status,
            currentPrice: state.currentPrice,
            bidCount: state.bidCount,
          }
        })
        return changed ? { ...current, items } : current
      })
    },
  })

  function changePage(nextPage: number) {
    fulfilledResetPageRef.current = null
    setPagination((current) => ({
      ...(current.queryKey === queryKey ? current : activePagination),
      queryKey,
      page: nextPage,
    }))
    listRef.current?.scrollTo({ top: 0 })
  }

  function toggleFilters(next: boolean) {
    setIsFilterEnabled(next)
    listRef.current?.scrollTo({ top: 0 })
  }

  function applyFilters(next: FilterSelection) {
    setSelection(next)
    setOpenGroupId(null)
    listRef.current?.scrollTo({ top: 0 })
  }

  function resetFilters() {
    setSelection(DEFAULT_FILTER_SELECTION)
    setIsFilterEnabled(true)
    setOpenGroupId(null)
    setSearchParams((current) => {
      const next = new URLSearchParams(current)
      next.delete(CATEGORY_QUERY_PARAM)
      return next
    }, { replace: true })
    listRef.current?.scrollTo({ top: 0 })
  }

  function resetSearch() {
    resetFilters()
    setAuctionType('ALL')
    setSort(DEFAULT_SORT)
    setSearchParams({})
  }

  const items = response?.items ?? []
  const filterPanel = (
    <FilterPanel
      groups={filterGroups}
      selection={selection}
      isEnabled={isFilterEnabled}
      onToggleEnabled={toggleFilters}
      onOpenGroup={setOpenGroupId}
      onReset={resetFilters}
    />
  )

  return (
    <main className={`mx-auto flex ${CONTENT_HEIGHT_CLASS} max-w-[1200px] flex-col px-lg py-base`}>
      <div className="flex min-h-0 flex-1">
        <aside
          aria-hidden={!isPanelOpen}
          inert={!isPanelOpen}
          className={`hidden shrink-0 overflow-hidden transition-[width,margin-right,opacity] duration-300 ease-out lg:block ${
            isPanelOpen ? 'mr-lg w-[190px] opacity-100' : 'pointer-events-none mr-0 w-0 opacity-0'
          }`}
        >
          <div className={`h-full ${FILTER_PANEL_WIDTH_CLASS}`}>
            {filterPanel}
          </div>
        </aside>

        <section className="flex min-h-0 min-w-0 flex-1 flex-col">
          <div className="shrink-0">
            <AuctionToolbar
              isPanelOpen={isPanelOpen}
              onTogglePanel={() => setIsPanelOpen((open) => !open)}
              onOpenFilterSheet={() => setIsFilterSheetOpen(true)}
              selectedFilterCount={isFilterEnabled ? countSelectedOptions(selection) : 0}
              auctionType={auctionType}
              onChangeAuctionType={setAuctionType}
              sort={sort}
              onChangeSort={setSort}
            />
            {keyword && (
              <h1 className="py-sm text-lg font-bold text-ink">
                {LIST_TEXT.searchResultTitle(keyword)}
              </h1>
            )}
          </div>

          <div
            ref={listRef}
            className={`min-h-0 flex-1 overflow-y-auto pb-base ${keyword ? '' : 'pt-sm'}`}
          >
            {isLoading ? (
              <div role="status" aria-label="경매를 불러오는 중">
                <span className="sr-only">경매를 불러오는 중…</span>
                <div className="grid grid-cols-1 gap-sm md:grid-cols-4">
                  {SKELETON_KEYS.map((key) => <AuctionCardSkeleton key={key} />)}
                </div>
              </div>
            ) : error ? (
              <div className="flex h-full flex-col items-center justify-center gap-sm text-center">
                <p className="text-base font-semibold text-ink">경매를 불러오지 못했어요.</p>
                <p className="text-sm text-muted">{error}</p>
                <Button variant="secondary" onClick={() => setRetryToken((value) => value + 1)}>
                  다시 시도
                </Button>
              </div>
            ) : items.length > 0 && response ? (
              <div className="grid grid-cols-1 gap-sm md:grid-cols-4">
                {items.map((auction) => (
                  <AuctionCard
                    key={auction.auctionId}
                    auction={auction}
                    serverOffsetMs={serverOffsetMs}
                  />
                ))}
              </div>
            ) : (
              <div className="flex h-full flex-col items-center justify-center gap-sm">
                <span className="flex h-14 w-14 items-center justify-center rounded-full bg-surface-soft text-muted">
                  <SearchX size={24} />
                </span>
                <p className="text-base font-semibold text-ink">{LIST_TEXT.emptyTitle}</p>
                <p className="text-sm text-muted">{LIST_TEXT.emptyDescription}</p>
                <Button variant="secondary" onClick={resetSearch} className="mt-xs">
                  검색 초기화
                </Button>
              </div>
            )}
          </div>

          {response && (
            <div className="shrink-0 border-t border-hairline-soft pt-base">
              <Pagination
                currentPage={response.page}
                totalPages={MAX_PAGES}
                onChange={changePage}
                maxVisiblePage={maxVisitedPage}
                canGoNext={response.hasNext && (lastPage === null || response.page < lastPage)}
              />
            </div>
          )}
        </section>
      </div>

      {isFilterSheetOpen && (
        <FilterSheet onClose={() => setIsFilterSheetOpen(false)}>{filterPanel}</FilterSheet>
      )}

      {openGroupId && (
        <FilterModal
          groups={filterGroups}
          initialGroupId={openGroupId}
          selection={selection}
          onApply={applyFilters}
          onReset={resetFilters}
          onClose={() => setOpenGroupId(null)}
        />
      )}
    </main>
  )
}

export default AuctionListPage
