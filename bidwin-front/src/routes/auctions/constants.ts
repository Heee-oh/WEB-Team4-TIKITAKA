/*
 * 경매 목록(검색 결과) 화면에서 반복해서 쓰이는 숫자·문구.
 * 화면 문구나 페이지 크기를 바꿀 일이 생기면 컴포넌트가 아니라 이 파일만 고친다.
 */

/** 한 페이지에 노출되는 경매 카드 수. 무한 스크롤이 아니라 번호 페이지네이션이다. */
export const PAGE_SIZE = 16
/** 페이지네이션에 한 번에 노출할 번호 버튼 개수 (넘치면 앞뒤를 … 로 접는다) */
export const PAGE_WINDOW_SIZE = 5
export const FIRST_PAGE = 1
/** 서버의 목록 조회 상한(AuctionListPagePolicy.MAX_PAGES)과 같은 값 */
export const MAX_PAGES = 100
/** 카드 하단에 노출할 해시태그 최대 개수 */
export const CARD_HASHTAG_LIMIT = 3
/*
 * 추천순 점수 = 조회수 + 입찰수 × 가중치.
 * 하향 경매는 낙찰 전까지 입찰수가 0이라, 가중치를 크게 잡으면 상향 경매만 앞으로 몰린다.
 * 입찰을 조회보다 조금 더 쳐주되 순위를 뒤집지는 않을 정도로 낮게 둔다.
 */
export const BID_SCORE_WEIGHT = 3

/** 검색어를 담는 쿼리 스트링 키. TopNav 검색과 이 페이지가 공유한다. */
export const SEARCH_QUERY_PARAM = 'q'
/** 홈 카테고리 버튼이 목록 화면에 전달하는 카테고리 코드. */
export const CATEGORY_QUERY_PARAM = 'category'

export const LIST_TEXT = {
  searchResultTitle: (keyword: string) => `'${keyword}' 검색 결과`,
  emptyTitle: '조건에 맞는 경매가 없어요.',
  emptyDescription: '필터를 줄이거나 다른 검색어로 다시 찾아보세요.',
  emptyAction: '필터 초기화',
} as const

export const FILTER_TEXT = {
  panelTitle: '필터',
  switchOn: 'ON',
  switchOff: 'OFF',
  switchLabel: '필터 사용',
  reset: '필터 초기화',
  addAriaLabel: (groupLabel: string) => `${groupLabel} 필터 선택`,
  emptyPanel: '아직 사용할 수 있는 필터 항목이 없어요.',
  emptyPanelHint: '카테고리·지역·가격 등 필터 항목이 추가되면 여기에서 바로 고를 수 있어요.',
  collapse: '필터 접기',
  expand: '필터 펼치기',
  /* 모바일에서는 사이드바 대신 시트로 띄운다 */
  openSheet: '필터 보기',
  closeSheet: '필터 닫기',
  applySheet: '적용하기',
} as const

export const FILTER_MODAL_TEXT = {
  title: '필터 선택',
  close: '닫기',
  reset: '초기화',
  submit: '필터 적용',
  removeAriaLabel: (label: string) => `${label} 선택 해제`,
} as const

export const CARD_TEXT = {
  bidCountSuffix: '회 입찰',
  noBid: '입찰 없음',
  viewCountLabel: '조회수',
  remainingSuffix: '남음',
  ended: '마감',
} as const

export const PAGINATION_TEXT = {
  navLabel: '페이지 이동',
  prev: '이전 페이지',
  next: '다음 페이지',
  pageAriaLabel: (page: number) => `${page}페이지`,
} as const
