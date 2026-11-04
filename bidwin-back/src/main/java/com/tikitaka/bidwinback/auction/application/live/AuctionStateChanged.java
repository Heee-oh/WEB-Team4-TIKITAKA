package com.tikitaka.bidwinback.auction.application.live;

/**
 * 커밋 뒤 전파할 경매 상태의 절대 snapshot.
 *
 * <p>snapshot은 변경한 트랜잭션 안에서 만들어 싣는다. AFTER_COMMIT 리스너는 DB를 읽지 않고 이 값을 그대로
 * 발행한다(읽으려면 원 트랜잭션의 연결을 쥔 채 두 번째 연결이 필요해 풀이 교착할 수 있다).
 */
public record AuctionStateChanged(AuctionLiveState state) {

    public long auctionId() {
        return state.auctionId();
    }
}
