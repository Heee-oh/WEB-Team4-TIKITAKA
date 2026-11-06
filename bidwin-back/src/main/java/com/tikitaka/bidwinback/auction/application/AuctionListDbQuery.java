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

        List<AuctionListRow> rows = findPage(
                condition,
                currentPage,
                size,
                candidateCountLimit,
                offset
        );
        // COUNT 없이 판단하므로 마지막 페이지가 정확히 16건이면 다음 페이지가 비어 있을 수 있다.
        boolean hasNext = rows.size() == size && currentPage < AuctionListPagePolicy.MAX_PAGES;
        return new AuctionListResponse(
                rows.stream().map(responseMapper::toSummary).toList(),
                responseMapper.toEpochMilli(serverTime),
                responseMapper.toEpochMilli(asOf),
                currentPage,
                hasNext
        );
    }

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
                    auctionListQueryRepository.findPage(condition, offset, size);
        };
    }

    private int normalizedPage(int requestedPage) {
        return Math.min(
                Math.max(FIRST_PAGE, requestedPage),
                AuctionListPagePolicy.MAX_PAGES
        );
    }
}
