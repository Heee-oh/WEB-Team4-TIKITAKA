package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionListQueryRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListRow;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListSearchCondition;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceCursor;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceSnapshot;
import com.tikitaka.bidwinback.auction.domain.repository.dto.DownAuctionPriceCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

@Component
@RequiredArgsConstructor
public class AuctionPricePageQuery {

    static final int CANDIDATE_BATCH_SIZE = 1_000;

    private final AuctionListQueryRepository auctionListQueryRepository;

    /**
     * 가격순 목록의 한 페이지를 조회한다. 다음 페이지 존재 확인을 위해 최대 size + 1건을 돌려준다.
     * size를 넘는 한 건이 있으면 다음 페이지가 있다는 뜻이다. 후보 상한(candidateCountLimit)에 막히면 size건까지만 온다.
     *
     * @param page 1 이상, {@link AuctionListPagePolicy#MAX_PAGES} 이하. 범위 보정은 호출자(AuctionListDbQuery)가 한다.
     */
    public List<AuctionListRow> findPage(
            AuctionListSearchCondition condition,
            int page,
            int size,
            long candidateCountLimit
    ) {
        int topKSize = topKSize(page, size, candidateCountLimit);
        if (topKSize == 0) {
            return List.of();
        }

        List<AuctionPriceSnapshot> orderedTopK = findSnapshots(condition, topKSize);
        int fromIndex = (int) Math.min(
                (long) (page - 1) * size,
                orderedTopK.size()
        );
        int toIndex = Math.min(fromIndex + size + 1, orderedTopK.size());
        return auctionListQueryRepository.findRowsByPriceSnapshots(
                orderedTopK.subList(fromIndex, toIndex),
                condition.asOf()
        );
    }

    List<AuctionPriceSnapshot> findSnapshots(
            AuctionListSearchCondition condition,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        Comparator<AuctionPriceSnapshot> resultOrder = resultOrder(condition.sort());
        PriorityQueue<AuctionPriceSnapshot> topK = new PriorityQueue<>(
                Math.min(limit, CANDIDATE_BATCH_SIZE),
                resultOrder.reversed()
        );

        if (condition.auctionType() != AuctionType.DOWN) {
            auctionListQueryRepository.findUpPriceSnapshots(condition, limit)
                    .forEach(snapshot -> offer(topK, snapshot, limit, resultOrder));
        }
        if (condition.auctionType() != AuctionType.UP) {
            findDownTopK(condition, limit, resultOrder, topK);
        }

        return topK.stream()
                .sorted(resultOrder)
                .toList();
    }

    private void findDownTopK(
            AuctionListSearchCondition condition,
            int topKSize,
            Comparator<AuctionPriceSnapshot> resultOrder,
            PriorityQueue<AuctionPriceSnapshot> topK
    ) {
        AuctionPriceCursor cursor = null;

        while (true) {
            List<DownAuctionPriceCandidate> candidates = auctionListQueryRepository
                    .findDownPriceCandidates(condition, cursor, CANDIDATE_BATCH_SIZE);
            if (candidates.isEmpty()) {
                break;
            }

            candidates.forEach(candidate -> offer(
                    topK,
                    snapshotAt(candidate, condition),
                    topKSize,
                    resultOrder
            ));

            DownAuctionPriceCandidate lastCandidate = candidates.getLast();
            long remainingPriceBound = priceBound(lastCandidate, condition.sort());
            if (candidates.size() < CANDIDATE_BATCH_SIZE) {
                break;
            }

            if (canDiscardRemaining(
                    topK,
                    topKSize,
                    remainingPriceBound,
                    condition.sort()
            )) {
                break;
            }

            AuctionPriceCursor priceBoundCursor = new AuctionPriceCursor(
                    remainingPriceBound,
                    lastCandidate.auctionId()
            );
            // 경계와 Top-K 가격이 겹치면 같은 경계의 뒤쪽 후보도 Top-K에 들어올 수 있다.
            // 경계를 한 번에 소진해야 동률 후보를 누락하지 않으면서 다음 가격대로 넘어가지 않는다.
            List<DownAuctionPriceCandidate> remainingAtBound = auctionListQueryRepository
                    .findRemainingDownPriceCandidatesAtBound(condition, priceBoundCursor);
            remainingAtBound.forEach(candidate -> offer(
                    topK,
                    snapshotAt(candidate, condition),
                    topKSize,
                    resultOrder
            ));

            if (canDiscardAfterExhaustedBound(
                    topK,
                    topKSize,
                    remainingPriceBound,
                    condition.sort()
            )) {
                break;
            }

            DownAuctionPriceCandidate lastAtBound = remainingAtBound.isEmpty()
                    ? lastCandidate
                    : remainingAtBound.getLast();
            cursor = new AuctionPriceCursor(
                    remainingPriceBound,
                    lastAtBound.auctionId()
            );
        }
    }

    private int topKSize(int page, int size, long candidateCountLimit) {
        // 해당 페이지까지의 건수에 다음 페이지 확인용 한 건을 더한다. 후보 상한은 넘지 않는다.
        long requested = Math.addExact(Math.multiplyExact((long) page, size), 1L);
        long required = Math.min(candidateCountLimit, requested);
        if (required > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("가격순으로 조회할 페이지 범위가 너무 큽니다.");
        }
        return (int) required;
    }

    private AuctionPriceSnapshot snapshotAt(
            DownAuctionPriceCandidate candidate,
            AuctionListSearchCondition condition
    ) {
        return new AuctionPriceSnapshot(
                candidate.auctionId(),
                candidate.sortPriceAt(condition.asOf()),
                candidate.displayPriceAt(condition.asOf())
        );
    }

    private void offer(
            PriorityQueue<AuctionPriceSnapshot> topK,
            AuctionPriceSnapshot candidate,
            int topKSize,
            Comparator<AuctionPriceSnapshot> resultOrder
    ) {
        if (topK.size() < topKSize) {
            topK.offer(candidate);
            return;
        }
        if (resultOrder.compare(candidate, topK.element()) < 0) {
            topK.remove();
            topK.offer(candidate);
        }
    }

    private boolean canDiscardRemaining(
            PriorityQueue<AuctionPriceSnapshot> topK,
            int topKSize,
            long remainingPriceBound,
            AuctionSort sort
    ) {
        if (topK.size() < topKSize) {
            return false;
        }

        long worstTopKPrice = topK.element().sortPrice();
        // 경계가 같은 뒤쪽 레코드는 id tie-break에 따라 Top-K에 들어올 수 있으므로
        // 등호에서는 동률 경계를 소진하고, 가격이 엄격히 앞설 때만 즉시 중단한다.
        return switch (sort) {
            case PRICE_LOW -> worstTopKPrice < remainingPriceBound;
            case PRICE_HIGH -> worstTopKPrice > remainingPriceBound;
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }

    private boolean canDiscardAfterExhaustedBound(
            PriorityQueue<AuctionPriceSnapshot> topK,
            int topKSize,
            long exhaustedPriceBound,
            AuctionSort sort
    ) {
        if (topK.size() < topKSize) {
            return false;
        }

        long worstTopKPrice = topK.element().sortPrice();
        // 같은 가격 경계를 모두 처리했으므로 남은 후보의 경계는 엄격히 뒤에 있다.
        // 이때는 Top-K 가격과 소진한 경계가 같아도 결과가 뒤집히지 않는다.
        return switch (sort) {
            case PRICE_LOW -> worstTopKPrice <= exhaustedPriceBound;
            case PRICE_HIGH -> worstTopKPrice >= exhaustedPriceBound;
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }

    private long priceBound(DownAuctionPriceCandidate candidate, AuctionSort sort) {
        return switch (sort) {
            case PRICE_LOW -> candidate.minimumPrice();
            case PRICE_HIGH -> candidate.startPrice();
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }

    private Comparator<AuctionPriceSnapshot> resultOrder(AuctionSort sort) {
        Comparator<AuctionPriceSnapshot> idDescending = Comparator
                .comparingLong(AuctionPriceSnapshot::auctionId)
                .reversed();
        return switch (sort) {
            case PRICE_LOW -> Comparator
                    .comparingLong(AuctionPriceSnapshot::sortPrice)
                    .thenComparing(idDescending);
            case PRICE_HIGH -> Comparator
                    .comparingLong(AuctionPriceSnapshot::sortPrice)
                    .reversed()
                    .thenComparing(idDescending);
            case RECOMMENDED, DEADLINE, LATEST ->
                    throw new IllegalArgumentException("가격 정렬이 아닙니다: " + sort);
        };
    }
}
