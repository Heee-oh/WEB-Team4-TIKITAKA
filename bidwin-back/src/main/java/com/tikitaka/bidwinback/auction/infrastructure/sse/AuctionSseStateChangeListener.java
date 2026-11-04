package com.tikitaka.bidwinback.auction.infrastructure.sse;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidHistoryCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.global.sse.RedisSseEventBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 커밋된 경매 변경의 snapshot을 Redis에 발행한다.
 * 이 리스너는 DB를 읽지 않는다. AFTER_COMMIT 시점엔 원 트랜잭션의 연결이 아직 반납되지 않아,
 * 여기서 DB를 읽으면 연결을 쥔 채 두 번째 연결을 기다리게 된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuctionSseStateChangeListener {

    private final AuctionLiveStateCache stateCache;
    private final AuctionBidHistoryCache bidHistoryCache;
    private final RedisSseEventBus eventBus;

    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = false
    )
    public void publishCommittedState(AuctionStateChanged event) {
        publish(event.state());
    }

    /** 커밋이 끝난 뒤(트랜잭션 밖 포함) 이미 만든 snapshot을 발행한다. */
    public void publish(AuctionLiveState state) {
        long auctionId = state.auctionId();
        // 구독자가 없더라도 다음 연결이 커밋 전 snapshot을 받지 않게 먼저 무효화한다.
        stateCache.invalidate(auctionId);
        bidHistoryCache.invalidate(auctionId);
        try {
            // 모든 인스턴스가 같은 절대 상태를 받도록 커밋된 snapshot을 Redis에 싣는다.
            eventBus.publish(AuctionSseMessages.state(state));
        } catch (RuntimeException exception) {
            // 다음 변경이나 재연결 snapshot으로 수렴할 수 있으므로 비즈니스 결과와 격리한다.
            log.atWarn()
                    .setCause(exception)
                    .addKeyValue("event", "auction_sse_publish_failed")
                    .addKeyValue("auctionId", auctionId)
                    .log("커밋된 경매 상태를 Redis로 발행하지 못했습니다.");
        }
    }
}
