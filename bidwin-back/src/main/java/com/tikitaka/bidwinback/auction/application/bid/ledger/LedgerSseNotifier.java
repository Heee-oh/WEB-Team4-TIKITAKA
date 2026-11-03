package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidCreated;
import com.tikitaka.bidwinback.auction.application.live.AuctionBidHistoryCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.infrastructure.sse.AuctionBidSseListener;
import com.tikitaka.bidwinback.auction.infrastructure.sse.AuctionSseStateChangeListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 커밋된 입찰의 SSE 알림을 소비자 스레드 밖에서 발행한다.
 * 알림은 경매 상태 재조회(DB)와 Redis 발행을 포함해 소비자 시간의 큰 부분을 차지했지만, 입찰 저장의 성공 조건이 아니다.
 * 같은 경매의 상태 알림은 커밋된 최신 DB snapshot을 싣기 때문에 한 번으로 합쳐도 같은 상태에 수렴한다.
 * 개별 입찰 알림은 합치지 않는다. 기존 리스너를 그대로 호출하므로 발행 형식은 바뀌지 않는다.
 */
@Slf4j
final class LedgerSseNotifier implements AutoCloseable {
    private final ApplicationContext context;
    private final Object lock = new Object();
    private final Set<Long> changedAuctions = new LinkedHashSet<>();
    private final List<AuctionBidCreated> createdBids = new ArrayList<>();
    private Thread thread;
    private boolean closed;
    private AuctionSseStateChangeListener stateListener;
    private AuctionBidSseListener bidListener;

    LedgerSseNotifier(ApplicationContext context) {
        this.context = context;
    }

    void start() {
        thread = new Thread(this::run, "bid-ledger-sse");
        thread.setDaemon(true);
        thread.start();
    }

    /** 커밋 직후 소비자 스레드에서 호출한다. */
    void committed(long auctionId, AuctionBidCreated bid) {
        // 캐시 무효화는 로컬 메모리 연산이다. 발행을 기다리는 동안 새 연결이 커밋 전 snapshot을 받지 않도록 동기로 한다.
        context.getBean(AuctionLiveStateCache.class).invalidate(auctionId);
        context.getBean(AuctionBidHistoryCache.class).invalidate(auctionId);
        synchronized (lock) {
            changedAuctions.add(auctionId);
            createdBids.add(bid);
            lock.notifyAll();
        }
    }

    private void run() {
        while (true) {
            List<Long> auctions;
            List<AuctionBidCreated> bids;
            synchronized (lock) {
                try {
                    while (!closed && changedAuctions.isEmpty() && createdBids.isEmpty()) lock.wait();
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (closed) return;
                auctions = new ArrayList<>(changedAuctions);
                bids = new ArrayList<>(createdBids);
                changedAuctions.clear();
                createdBids.clear();
            }
            try {
                if (stateListener == null) {
                    stateListener = context.getBean(AuctionSseStateChangeListener.class);
                    bidListener = context.getBean(AuctionBidSseListener.class);
                }
                // 기존 처리 순서(상태 → 입찰)를 유지한다. 두 리스너는 발행 실패를 스스로 격리한다.
                for (long auction : auctions) stateListener.publishCommittedState(new AuctionStateChanged(auction));
                for (AuctionBidCreated bid : bids) bidListener.publishBid(bid);
            } catch (RuntimeException failure) {
                log.atWarn().setCause(failure).addKeyValue("event", "bid_ledger_sse_failed")
                        .log("입찰 SSE 알림을 발행하지 못했습니다. 다음 변경이나 재연결 snapshot으로 수렴합니다.");
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
            lock.notifyAll();
        }
        if (thread != null) thread.interrupt();
    }
}
