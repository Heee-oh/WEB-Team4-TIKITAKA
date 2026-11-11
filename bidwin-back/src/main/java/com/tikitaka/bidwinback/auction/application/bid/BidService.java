package com.tikitaka.bidwinback.auction.application.bid;

import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.domain.exception.BidException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PRICE_TOO_LOW;

/**
 * 입찰 진입점. 트랜잭션을 열지 않는다(DB 트랜잭션 구간은 {@link BidTransactionService}).
 *
 * <p>Redis 선점과 실패 뒤 가격 캐시 재동기화는 DB 연결을 쥔 채로 하면 안 된다. 롤백 뒤 재동기화는 DB 현재가를
 * 다시 읽어야 하는데, 트랜잭션 콜백(AFTER_ROLLBACK)에서 REQUIRES_NEW로 읽으면 원 트랜잭션의 연결을 쥔 채 두 번째
 * 연결을 기다린다. 동시 입찰 수가 풀 크기에 닿으면 모든 연결 보유자가 서로의 두 번째 연결을 기다리며 풀 전체가
 * 교착한다. 그래서 첫 연결을 반납한 뒤 여기서 처리한다.
 *
 * <p>이 클래스에 @Transactional을 붙이면 위 보장이 깨진다. BidServiceTransactionBoundaryTest가 막는다.
 * 호출자는 트랜잭션 밖에서 불러야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BidService {

    private final BidTransactionService transactionService;
    private final BidPriceCache bidPriceCache;
    private final AuctionRepository auctionRepository;

    public BidResult place(
            Long memberId,
            Long auctionId,
            long price,
            BidType bidType
    ) {
        BidTransactionService.validateBidPrice(price);

        // Redis에서 즉시 원자적으로 승패를 가른다(비교+갱신을 한 번에). SEALED는 이 캐시 대상이 아니다.
        // 트랜잭션 밖이라 선점에 진 요청은 DB 연결을 잡지 않고 바로 거절된다.
        boolean preemptable = bidType == BidType.OPEN;
        Long previousPrice = preemptable
                ? bidPriceCache.tryWinRace(auctionId, price)
                : null;
        if (bidPriceCache.isLost(previousPrice)) {
            throw new BidException(BID_PRICE_TOO_LOW);
        }

        try {
            return transactionService.place(memberId, auctionId, price, bidType);
        } catch (RuntimeException exception) {
            // previousPrice가 null이어도(키가 없었거나, Redis 예외로 결과를 모르는 경우) 되돌린다.
            // 특히 예외 케이스는 Redis가 SET을 실제로 실행한 뒤 응답만 못 받았을 수도 있어
            // "안 건드렸다"고 확신할 수 없다. 되돌리기는 "내가 세팅한 값이 아직 그대로일 때만"
            // 작동하므로, 실제로는 안 건드렸던 경우엔 그냥 안전하게 아무 일도 안 일어난다.
            // 이 시점엔 트랜잭션이 롤백되어 연결이 반납된 상태다.
            if (preemptable) {
                resyncPriceCache(auctionId, price);
            }
            throw exception;
        }
    }

    private void resyncPriceCache(Long auctionId, long price) {
        try {
            auctionRepository.findCurrentPriceById(auctionId)
                    .ifPresent(actualPrice -> bidPriceCache.resyncToActualPrice(
                            auctionId,
                            price,
                            actualPrice
                    ));
        } catch (RuntimeException exception) {
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("event", "bid_price_cache_resync_failed")
                    .addKeyValue("auctionId", auctionId)
                    .log("롤백된 입찰의 가격 캐시 재동기화에 실패했습니다.");
        }
    }
}
