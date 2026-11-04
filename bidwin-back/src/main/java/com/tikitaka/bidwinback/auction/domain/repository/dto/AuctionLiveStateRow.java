package com.tikitaka.bidwinback.auction.domain.repository.dto;

import java.time.LocalDateTime;

/**
 * 실시간 상태를 만들 재료. 엔티티가 아니라 스칼라 projection이라 영속성 컨텍스트의 낡은 1차 캐시를 거치지 않고,
 * 같은 트랜잭션에서 방금 쓴 네이티브 UPDATE 결과(status, revision 등)를 그대로 읽는다.
 * 하향 경매 컬럼과 확정 거래가는 해당하지 않으면 null이다.
 */
public interface AuctionLiveStateRow {

    long getAuctionId();

    String getAuctionType();

    String getStatus();

    long getRevision();

    long getCurrentPrice();

    long getBidCount();

    long getSealedBidCount();

    long getStartPrice();

    LocalDateTime getStartedAt();

    LocalDateTime getEndedAt();

    Long getMinimumPrice();

    Long getDropPrice();

    Long getPriceDropInterval();

    Long getFinalPrice();
}
