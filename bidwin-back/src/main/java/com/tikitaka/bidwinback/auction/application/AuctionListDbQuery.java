package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionListStatusFilter;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionListQueryRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListRow;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListSearchCondition;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionListResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class AuctionListDbQuery {

    private static final int FIRST_PAGE = 1;

    private final AuctionRepository auctionRepository;
    private final AuctionListQueryRepository auctionListQueryRepository;
    private final AuctionPricePageQuery auctionPricePageQuery;
    private final AuctionSummaryResponseMapper responseMapper;

    @Transactional(readOnly = true)
    public AuctionListResponse findPage(AuctionListQuery query) {
        LocalDateTime serverTime = auctionRepository.currentDatabaseTime();
        LocalDateTime asOf = query.sort() == AuctionSort.RECOMMENDED
                ? serverTime
                : query.asOf() != null ? query.asOf() : serverTime;
        int size = AuctionListPagePolicy.PAGE_SIZE;
        AuctionListSearchCondition condition = new AuctionListSearchCondition(
                query.auctionType(),
                query.sort(),
                query.keyword(),
                query.status() != null ? query.status() : AuctionListStatusFilter.ACTIVE,
                query.category(),
                asOf
        );
        int currentPage = normalizedPage(query.page());
        long offset = (long) (currentPage - FIRST_PAGE) * size;
        long candidateCountLimit = (long) AuctionListPagePolicy.MAX_PAGES * size;

        List<AuctionListRow> fetched = findPage(
                condition,
                currentPage,
                size,
                candidateCountLimit,
                offset
        );
        // 한 건 더 읽어 size를 넘으면 다음 페이지가 있다. COUNT 없이 정확히 판단하고, 응답에는 size건만 담는다.
        boolean exceedsPageSize = fetched.size() > size;
        boolean hasNext = exceedsPageSize && currentPage < AuctionListPagePolicy.MAX_PAGES;
        List<AuctionListRow> rows = exceedsPageSize ? fetched.subList(0, size) : fetched;
        return new AuctionListResponse(
                rows.stream().map(responseMapper::toSummary).toList(),
                responseMapper.toEpochMilli(serverTime),
                responseMapper.toEpochMilli(asOf),
                currentPage,
                hasNext
        );
    }

    /** 다음 페이지 존재 확인을 위해 최대 size + 1건을 돌려준다. */
    private List<AuctionListRow> findPage(
            AuctionListSearchCondition condition,
            int currentPage,
            int size,
            long candidateCountLimit,
            long offset
    ) {
        return switch (condition.sort()) {
            case PRICE_LOW, PRICE_HIGH -> auctionPricePageQuery.findPage(
                    condition,
                    currentPage,
                    size,
                    candidateCountLimit
            );
            case RECOMMENDED, DEADLINE, LATEST ->
                    auctionListQueryRepository.findPage(condition, offset, size + 1);
        };
    }

    private int normalizedPage(int requestedPage) {
        return Math.min(
                Math.max(FIRST_PAGE, requestedPage),
                AuctionListPagePolicy.MAX_PAGES
        );
    }
}
