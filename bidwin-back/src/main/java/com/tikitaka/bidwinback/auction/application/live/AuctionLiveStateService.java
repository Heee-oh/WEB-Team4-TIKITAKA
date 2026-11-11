package com.tikitaka.bidwinback.auction.application.live;

import com.tikitaka.bidwinback.auction.domain.DownAuctionCurrentPriceCalculator;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionLiveStateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;

/**
 * 경매의 실시간 상태(AuctionLiveState)를 만든다. 경매와 확정 거래가를 한 번의 조회로 읽어 섞인 시점의
 * 이벤트를 막고, 엔티티를 거치지 않아 영속성 컨텍스트의 낡은 값이 섞이지 않는다.
 *
 * <p>읽는 방법이 두 가지다. 어느 쪽이든 같은 규칙으로 상태를 조립한다.
 * <ul>
 *   <li>{@link #snapshot}, {@link #snapshots}: 쓰기 트랜잭션 안에서 부른다. 방금 쓴 값을 읽어 이벤트에 싣는다.
 *   AFTER_COMMIT 리스너가 DB를 다시 읽지 않게 하려는 용도다. 리스너에서 REQUIRES_NEW로 읽으면 원 트랜잭션의
 *   연결을 쥔 채 두 번째 연결을 기다려, 동시 트랜잭션이 풀 크기에 닿는 순간 풀 전체가 교착한다.</li>
 *   <li>{@link #getStates}: 트랜잭션 밖(SSE 연결)에서 커밋된 값을 읽는다.
 *   트랜잭션 안에서 부르지 말 것: 연결을 하나 더 쥔다.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class AuctionLiveStateService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final AuctionRepository auctionRepository;

    /** 호출자의 트랜잭션이 쓴 값까지 포함한다. 트랜잭션이 없으면 거부해 AFTER_COMMIT 같은 곳에서의 오용을 막는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuctionLiveState snapshot(long auctionId) {
        return load(List.of(auctionId)).getFirst();
    }

    /** 마감 배치처럼 여러 경매를 한 번에 조회해 락 보유 시간을 줄인다. 입력 순서를 유지한다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<AuctionLiveState> snapshots(Collection<Long> auctionIds) {
        return load(auctionIds);
    }

    /** 커밋된 최신 상태를 별도 읽기 트랜잭션에서 조회한다. 트랜잭션 밖에서만 부른다. */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public List<AuctionLiveState> getStates(Collection<Long> auctionIds) {
        return load(auctionIds);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public long getDatabaseTimeMillis() {
        return auctionRepository.currentDatabaseTime()
                .atZone(SERVICE_ZONE)
                .toInstant()
                .toEpochMilli();
    }

    private List<AuctionLiveState> load(Collection<Long> auctionIds) {
        if (auctionIds.isEmpty()) {
            return List.of();
        }

        Map<Long, AuctionLiveStateRow> rows = auctionRepository.findLiveStateRows(auctionIds)
                .stream()
                .collect(Collectors.toMap(
                        AuctionLiveStateRow::getAuctionId,
                        Function.identity()
                ));
        // IN 조회는 없는 ID를 조용히 누락하므로, 존재하지 않는 경매를 구독하면 초기 snapshot 없이
        // 채널만 붙는다. 명시적으로 거부한다.
        if (auctionIds.stream().anyMatch(id -> !rows.containsKey(id))) {
            throw new AuctionException(AUCTION_NOT_FOUND);
        }

        // 하향 경매 가격은 앱 서버마다 달라지지 않게 DB 시각 기준으로 계산한다.
        LocalDateTime databaseTime = rows.values().stream()
                .anyMatch(row -> AuctionType.valueOf(row.getAuctionType()) == AuctionType.DOWN)
                ? auctionRepository.currentDatabaseTime()
                : null;

        return auctionIds.stream()
                .map(rows::get)
                .map(row -> toState(row, databaseTime))
                .toList();
    }

    /**
     * 상세 응답과 같은 값을 내야 한다. 어긋나면 상세를 연 직후 첫 SSE 이벤트에서 숫자가 튄다.
     */
    private AuctionLiveState toState(AuctionLiveStateRow row, LocalDateTime databaseTime) {
        AuctionType auctionType = AuctionType.valueOf(row.getAuctionType());
        AuctionStatus status = AuctionStatus.valueOf(row.getStatus());

        return new AuctionLiveState(
                row.getAuctionId(),
                row.getRevision(),
                auctionType,
                status,
                currentPrice(row, auctionType, status, databaseTime),
                bidCount(row, auctionType, status)
        );
    }

    private long bidCount(
            AuctionLiveStateRow row,
            AuctionType auctionType,
            AuctionStatus status
    ) {
        long count = row.getBidCount();
        // 상세 조회와 동일하게, 밀봉이 공개된 상향 경매만 밀봉 입찰 수를 합산한다.
        if (auctionType == AuctionType.UP && isSealedBidRevealed(status)) {
            count += row.getSealedBidCount();
        }
        return count;
    }

    private long currentPrice(
            AuctionLiveStateRow row,
            AuctionType auctionType,
            AuctionStatus status,
            LocalDateTime databaseTime
    ) {
        if (status == AuctionStatus.COMPLETED) {
            if (row.getFinalPrice() == null) {
                throw new IllegalStateException(
                        "완료된 경매의 확정 거래가가 없습니다. auctionId=" + row.getAuctionId()
                );
            }
            return row.getFinalPrice();
        }

        return switch (auctionType) {
            case UP -> row.getCurrentPrice();
            case DOWN -> DownAuctionCurrentPriceCalculator.calculate(
                    row.getStartPrice(),
                    row.getMinimumPrice(),
                    row.getDropPrice(),
                    row.getPriceDropInterval(),
                    row.getStartedAt(),
                    databaseTime.isAfter(row.getEndedAt()) ? row.getEndedAt() : databaseTime
            );
        };
    }

    // Auction.isSealedBidRevealed()와 같은 기준이다.
    private boolean isSealedBidRevealed(AuctionStatus status) {
        return status == AuctionStatus.WINNER_DETERMINING
                || status == AuctionStatus.COMPLETED
                || status == AuctionStatus.UNSOLD;
    }
}
