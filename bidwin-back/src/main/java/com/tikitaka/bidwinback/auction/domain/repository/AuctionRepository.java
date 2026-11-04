package com.tikitaka.bidwinback.auction.domain.repository;

import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionClosingCandidate;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionLiveStateRow;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AuctionRepository extends JpaRepository<Auction, Long> {

    @Query(value = """
            SELECT auction.id AS auctionId,
                   auction.revision AS revision
            FROM auction FORCE INDEX (idx_auction_status_ended_at)
            WHERE auction.status = :status
              AND auction.ended_at <= NOW(6)
            ORDER BY auction.ended_at, auction.id
            LIMIT :batchSize
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AuctionClosingCandidate> findClosingCandidatesForUpdateSkipLocked(
            @Param("status") String status,
            @Param("batchSize") int batchSize
    );

    // 벌크 롤백 뒤 개별 재처리할 후보 스냅샷이다. 실제 선점과 상태 재검사는 아래 단건 쿼리에서 한다.
    @Query(value = """
            SELECT auction.id
            FROM auction FORCE INDEX (idx_auction_status_ended_at)
            WHERE auction.status = :status
              AND auction.ended_at <= NOW(6)
            ORDER BY auction.ended_at, auction.id
            LIMIT :limit
            """, nativeQuery = true)
    List<Long> findClosingCandidateIds(
            @Param("status") String status,
            @Param("limit") int limit
    );

    // 여러 서버가 같은 스냅샷을 읽어도 단건 트랜잭션에서 다시 선점하므로 중복 마감을 막는다.
    @Query(value = """
            SELECT auction.id AS auctionId,
                   auction.revision AS revision
            FROM auction
            WHERE auction.id = :auctionId
              AND auction.status = :status
              AND auction.ended_at <= NOW(6)
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<AuctionClosingCandidate> findClosingCandidateForUpdateSkipLocked(
            @Param("auctionId") Long auctionId,
            @Param("status") String status
    );

    // 선점한 배치를 낙찰·유찰로 한 번에 마감한다. 거래가 앞서 적재되므로 그 유무가 곧 낙찰 여부다.
    // 낙찰과 유찰을 따로 돌리면 뒤 문장이 이미 마감된 행까지 id 목록만큼 다시 훑는다.
    // 유찰분의 current_price는 COALESCE로 원래 값을 그대로 대입한다. 값이 같으면 MySQL이 변경으로
    // 치지 않아 가격 인덱스를 건드리지 않는다.
    @Modifying(flushAutomatically = true)
    @Query(value = """
            UPDATE auction FORCE INDEX (PRIMARY)
            LEFT JOIN auction_trade ON auction_trade.auction_id = auction.id
            SET auction.status = CASE WHEN auction_trade.auction_id IS NULL
                                      THEN 'UNSOLD'
                                      ELSE 'COMPLETED'
                                 END,
                auction.current_price =
                        COALESCE(auction_trade.final_price, auction.current_price),
                auction.completed_at = :settledAt,
                auction.revision = auction.revision + 1,
                auction.last_modified_at = :settledAt
            WHERE auction.id IN (:auctionIds)
              AND auction.status IN ('OPEN', 'BID_ONGOING')
            """, nativeQuery = true)
    int closeAll(
            @Param("auctionIds") List<Long> auctionIds,
            @Param("settledAt") LocalDateTime settledAt
    );

    // 입찰가 캐시(Redis)가 실패한 선점을 되돌릴 때, 커밋된 DB 현재가로 재동기화하기 위해 쓴다.
    // current_price가 없는 기존 경매는 조건부 UPDATE와 동일한 기준(Bid 최고가, 없으면 시작가)으로 보정한다.
    @Query(value = """
            SELECT COALESCE(
                    current_price,
                    (SELECT MAX(bid.price) FROM bid WHERE bid.auction_id = auction.id),
                    start_price
                )
            FROM auction
            WHERE id = :auctionId
            """, nativeQuery = true)
    Optional<Long> findCurrentPriceById(@Param("auctionId") Long auctionId);

    // 단일 조건부 UPDATE로 입찰을 직렬화하고 최소 호가 검증과 현재가 변경을 원자적으로 처리한다.
    // current_price가 없는 기존 경매만 Bid 최고가, 입찰도 없으면 시작가를 기준으로 한다.
    // 락 대기 중 흐른 시간까지 반영하도록 statement 시작 시각이 아닌 SYSDATE(6)를 사용한다.
    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query(value = """
            UPDATE auction
            SET current_price = :price,
                current_bidder_id = :bidderId,
                bid_count = bid_count + 1,
                status = 'BID_ONGOING',
                revision = revision + 1,
                last_modified_at = SYSDATE(6) -- Native UPDATE는 @LastModifiedDate가 적용되지 않아 직접 갱신한다.
            WHERE id = :auctionId
              AND auction_type = 'UP'
              AND status IN ('OPEN', 'BID_ONGOING')
              AND completed_at IS NULL
              AND ended_at > DATE_ADD(SYSDATE(6), INTERVAL 5 MINUTE)
              AND seller_id <> :bidderId
              AND COALESCE(
                    current_price,
                    (
                        SELECT MAX(bid.price)
                        FROM bid
                        WHERE bid.auction_id = auction.id
                    ),
                    start_price
              ) <= :price - :bidUnit
            """, nativeQuery = true)
    int updateCurrentPriceForBid(
            @Param("auctionId") Long auctionId,
            @Param("bidderId") Long bidderId,
            @Param("price") long price,
            @Param("bidUnit") long bidUnit
    );

    // OPEN과 BID_ONGOING을 나눠 호출해 첫 밀봉입찰에서만 revision을 올렸는지 호출자가 안다.
    // sealed_bid_count는 별도 컬럼에 누적해 공개 전 추천순 bid_count로 입찰 수가 새지 않게 한다.
    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query(value = """
            UPDATE auction
            SET sealed_bid_count = sealed_bid_count + 1,
                revision = revision + :revisionIncrement,
                status = 'BID_ONGOING',
                sealed_top_bidder_id = CASE
                        WHEN sealed_top_price IS NULL OR :price > sealed_top_price
                        THEN :bidderId
                        ELSE sealed_top_bidder_id
                    END,
                sealed_top_price = CASE
                        WHEN sealed_top_price IS NULL OR :price > sealed_top_price
                        THEN :price
                        ELSE sealed_top_price
                    END,
                last_modified_at = SYSDATE(6)
            WHERE id = :auctionId
              AND auction_type = 'UP'
              AND status = :expectedStatus
              AND completed_at IS NULL
              AND ended_at > SYSDATE(6)
              AND ended_at <= DATE_ADD(SYSDATE(6), INTERVAL 5 MINUTE)
              AND seller_id <> :bidderId
              AND current_price <= :price - :bidUnit
            """, nativeQuery = true)
    int tryUpdateAuctionForSealedBid(
            @Param("auctionId") Long auctionId,
            @Param("bidderId") Long bidderId,
            @Param("price") long price,
            @Param("bidUnit") long bidUnit,
            @Param("expectedStatus") String expectedStatus,
            @Param("revisionIncrement") int revisionIncrement
    );

    // 실시간 상태(SSE) snapshot의 재료. 엔티티를 거치지 않는 스칼라 조회라, 같은 트랜잭션에서 네이티브
    // UPDATE로 바꾼 status·revision을 낡은 1차 캐시 없이 읽는다. 쓰기 트랜잭션 안에서 부르면 자기가 쓴 값을,
    // 별도 읽기 트랜잭션에서 부르면 커밋된 값을 돌려준다.
    @Query(value = """
            SELECT auction.id AS auctionId,
                   auction.auction_type AS auctionType,
                   auction.status AS status,
                   auction.revision AS revision,
                   COALESCE(auction.current_price, auction.start_price) AS currentPrice,
                   auction.bid_count AS bidCount,
                   auction.sealed_bid_count AS sealedBidCount,
                   auction.start_price AS startPrice,
                   auction.started_at AS startedAt,
                   auction.ended_at AS endedAt,
                   down_auction.minimum_price AS minimumPrice,
                   down_auction.drop_price AS dropPrice,
                   down_auction.price_drop_interval AS priceDropInterval,
                   auction_trade.final_price AS finalPrice
            FROM auction
            LEFT JOIN down_auction ON down_auction.auction_id = auction.id
            LEFT JOIN auction_trade ON auction_trade.auction_id = auction.id
            WHERE auction.id IN (:auctionIds)
            """, nativeQuery = true)
    List<AuctionLiveStateRow> findLiveStateRows(
            @Param("auctionIds") Collection<Long> auctionIds
    );

    @Query("""
            select auction
            from Auction auction
            join fetch auction.seller
            where auction.id = :auctionId
            """)
    Optional<Auction> findWithSellerById(@Param("auctionId") Long auctionId);

    // 경매 상태·마감·판매자를 DB에서 다시 검사하고 한 요청만 완료 처리한다.
    // 즉시구매 흐름은 회원 행을 먼저 잠근 뒤 이 경매 행을 잠그는데, 입찰 흐름은 반대 순서이므로
    // 순환 대기 상황에서 오래 매달리지 않고 빠르게 실패하도록 타임아웃을 짧게 둔다.
    @Modifying
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "3000"))
    @Query(value = """
            UPDATE auction
            SET status = 'COMPLETED',
                current_price = :finalPrice,
                completed_at = :completedAt,
                bid_count = bid_count + 1,
                revision = revision + 1,
                last_modified_at = :completedAt
            WHERE id = :auctionId
              AND status = 'OPEN'
              AND completed_at IS NULL
              AND ended_at > SYSDATE(6)
              AND (
                    auction_type = 'DOWN'
                    OR ended_at > DATE_ADD(SYSDATE(6), INTERVAL 5 MINUTE)
              )
              AND seller_id <> :buyerId
            """, nativeQuery = true)
    int completeForBuyNow(
            @Param("auctionId") Long auctionId,
            @Param("buyerId") Long buyerId,
            @Param("finalPrice") long finalPrice,
            @Param("completedAt") LocalDateTime completedAt
    );

    // 요구사항: 낙찰 시각과 하향 경매 가격 계산은 DB가 기록한 동일 시각을 사용한다.
    @Query(value = """
            SELECT completed_at
            FROM auction
            WHERE id = :auctionId
            """, nativeQuery = true)
    Optional<LocalDateTime> findCompletedAt(@Param("auctionId") Long auctionId);

    @EntityGraph(attributePaths = "seller")
    @Query("select auction from Auction auction where auction.id = :auctionId")
    Optional<Auction> findDetailById(@Param("auctionId") long auctionId);

    // 하락 경매의 계산 기준이 애플리케이션 서버마다 달라지지 않도록 DB 시각을 사용한다.
    @Query(value = "select current_timestamp(6)", nativeQuery = true)
    LocalDateTime currentDatabaseTime();

    // 마이페이지 판매 내역용. 정렬 기준(등록 시각)이 실제 컬럼이라 Pageable의 정렬·페이징을 그대로 쓴다.
    Page<Auction> findBySellerIdAndStatusIn(
            Long sellerId,
            List<AuctionStatus> statuses,
            Pageable pageable
    );

    // 마이페이지 판매 물품: 내가 올린 경매를 최신순으로. 유형(UP/DOWN)은 서비스에서 구체 타입으로 매핑한다.
    List<Auction> findTop3BySellerIdOrderByIdDesc(long sellerId);
}
