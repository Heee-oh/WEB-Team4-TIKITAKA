package com.tikitaka.bidwinback.auction.domain.repository;

import com.tikitaka.bidwinback.auction.domain.entity.AuctionTrade;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionFinalPrice;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuctionTradeRepository extends JpaRepository<AuctionTrade, Long> {

    // HQL이 아닌 native로 두는 이유는 buyer가 Member 연관이라 HQL이면 member join이 강제되기 때문이다.
    // 그 join은 낙찰자 조회를 행마다 더 낼 뿐 아니라, REPEATABLE READ에서 INSERT ... SELECT가 읽은
    // member 행에 공유 잠금을 남긴다. 같은 회원의 입찰과 보증금이 그 행을 배타 잠금하므로
    // 마감과 입찰이 겹칠 때 경합이 된다. 거래는 낙찰자 식별자만 필요하므로 join을 걷어낸다.
    // 낙찰자가 없는 경매는 buyer_id가 NULL이라 빠지므로 선점한 배치를 통째로 넘겨도 된다.
    // 거래가 이미 있는 경매도 제외한다. 앞선 배치가 거래만 남기고 끊겨도 유니크 제약으로 죽지 않고,
    // 뒤따르는 closeAll이 그 거래를 그대로 써서 마감한다.
    // 아래 두 CASE는 같은 조건으로 각각 낙찰자와 낙찰가를 고르므로 함께 고쳐야 한다.
    @Modifying
    @Query(value = """
            INSERT INTO auction_trade
                (auction_id, buyer_id, status, final_price,
                 purchased_at, created_at, last_modified_at)
            SELECT winner.auction_id,
                   winner.buyer_id,
                   :status,
                   winner.final_price,
                   :settledAt,
                   :settledAt,
                   :settledAt
            FROM (
                SELECT auction.id AS auction_id,
                       CASE WHEN auction.current_bidder_id IS NOT NULL
                                 AND (auction.sealed_top_bidder_id IS NULL
                                      OR auction.current_price
                                         >= auction.sealed_top_price)
                            THEN auction.current_bidder_id
                            ELSE auction.sealed_top_bidder_id
                       END AS buyer_id,
                       CASE WHEN auction.current_bidder_id IS NOT NULL
                                 AND (auction.sealed_top_bidder_id IS NULL
                                      OR auction.current_price
                                         >= auction.sealed_top_price)
                            THEN auction.current_price
                            ELSE auction.sealed_top_price
                       END AS final_price
                FROM auction
                WHERE auction.id IN (:auctionIds)
            ) AS winner
            LEFT JOIN auction_trade settled ON settled.auction_id = winner.auction_id
            WHERE winner.buyer_id IS NOT NULL
              AND settled.auction_id IS NULL
            """, nativeQuery = true)
    int insertWinnerTradesAll(
            @Param("auctionIds") List<Long> auctionIds,
            @Param("status") String status,
            @Param("settledAt") LocalDateTime settledAt
    );

    // 마이페이지 낙찰/구매 내역용. 두 탭이 같은 AuctionTrade를 상태 필터만 다르게 조회한다.
    @EntityGraph(attributePaths = "auction")
    Page<AuctionTrade> findByBuyerIdAndStatusIn(
            Long buyerId,
            List<TradeStatus> statuses,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select trade
            from AuctionTrade trade
            join fetch trade.buyer
            join fetch trade.auction auction
            join fetch auction.seller
            where trade.id = :tradeId
            """)
    Optional<AuctionTrade> findByIdForUpdate(@Param("tradeId") Long tradeId);

    // 거래 상세·SSE 조회용. 잠금 없이 역할 판별과 연락처 게이팅에 필요한 연관을 함께 가져온다.
    @Query("""
            select trade
            from AuctionTrade trade
            join fetch trade.buyer
            join fetch trade.auction auction
            join fetch auction.seller
            where trade.id = :tradeId
            """)
    Optional<AuctionTrade> findDetailById(@Param("tradeId") Long tradeId);

    @Query("""
            select trade
            from AuctionTrade trade
            join fetch trade.buyer
            where trade.auction.id = :auctionId
            """)
    Optional<AuctionTrade> findByAuctionId(@Param("auctionId") long auctionId);

    @Query("""
            select trade.finalPrice
            from AuctionTrade trade
            where trade.auction.id = :auctionId
            """)
    Optional<Long> findFinalPriceByAuctionId(@Param("auctionId") long auctionId);

    // 실시간 상태 스냅샷용 일괄 조회. 완료된 경매의 확정 거래가를 목록 SSE에서 한 번에 모은다.
    @Query("""
            select trade.auction.id, trade.finalPrice
            from AuctionTrade trade
            where trade.auction.id in :auctionIds
            """)
    List<AuctionFinalPrice> findFinalPricesByAuctionIds(
            @Param("auctionIds") Collection<Long> auctionIds
    );

    long countByAuctionSellerIdAndStatus(long sellerId, TradeStatus status);

    // 마이페이지 구매 물품: 내가 산 거래. 경매를 함께 가져와 제목·시작가·유형을 매핑한다.
    @Query("""
            select trade
            from AuctionTrade trade
            join fetch trade.auction
            where trade.buyer.id = :memberId
              and trade.status in :statuses
            order by trade.id desc
            limit 3
            """)
    List<AuctionTrade> findBuyingItems(
            @Param("memberId") long memberId,
            @Param("statuses") Collection<TradeStatus> statuses
    );

    // 마이페이지 진행 중 거래 배너: 구매자·판매자 모두 거래 완료 전 두 단계를 조회한다.
    // 역할 판별을 위해 경매·판매자·구매자를 함께 가져온다.
    @Query("""
            select trade
            from AuctionTrade trade
            join fetch trade.auction auction
            join fetch auction.seller
            join fetch trade.buyer
            where (trade.buyer.id = :memberId or auction.seller.id = :memberId)
              and trade.status in (:buyerStatus, :sellerStatus)
            order by trade.purchasedAt asc, trade.id asc
            """)
    List<AuctionTrade> findActiveTrades(
            @Param("memberId") long memberId,
            @Param("buyerStatus") TradeStatus buyerStatus,
            @Param("sellerStatus") TradeStatus sellerStatus
    );

}
