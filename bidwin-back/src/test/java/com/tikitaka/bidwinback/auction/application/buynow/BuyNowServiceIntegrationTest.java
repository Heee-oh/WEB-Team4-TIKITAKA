package com.tikitaka.bidwinback.auction.application.buynow;

import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionTrade;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.BidStatus;
import com.tikitaka.bidwinback.auction.domain.enums.DepositStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.global.exception.BusinessException;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest(properties = {
        "app.storage.s3.bucket=test-bucket",
        "app.auction.closing-interval=1d"
})
@RecordApplicationEvents
class BuyNowServiceIntegrationTest {

    private static final long BUY_NOW_PRICE = 150_000L;
    private static final long INITIAL_POINT = 2_000_000L;

    @Autowired
    private BuyNowService buyNowService;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> auctionIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();
    private final AtomicInteger memberSequence = new AtomicInteger();

    private String runId;

    @BeforeEach
    void setUp() {
        runId = UUID.randomUUID().toString().replace("-", "");
    }

    @AfterEach
    void tearDown() {
        executeInTransaction(entityManager -> {
            for (Long auctionId : auctionIds) {
                executeDelete(entityManager,
                        "DELETE FROM instant_purchase_request WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM auction_trade WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM auction_deposit WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM bid WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM up_auction WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM down_auction WHERE auction_id = :id", auctionId);
                executeDelete(entityManager,
                        "DELETE FROM auction WHERE id = :id", auctionId);
            }
            for (Long memberId : memberIds) {
                executeDelete(entityManager,
                        "DELETE FROM member WHERE id = :id", memberId);
            }
            return null;
        });
    }

    @Test
    void 동일한_멱등키의_동시요청은_같은_거래를_반환한다() throws Exception {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        String idempotencyKey = idempotencyKey("same-key");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<BuyNowResult> first = executor.submit(
                    buyAfterBarrier(barrier, buyerId, fixture.auctionId(), idempotencyKey));
            Future<BuyNowResult> second = executor.submit(
                    buyAfterBarrier(barrier, buyerId, fixture.auctionId(), idempotencyKey));

            BuyNowResult firstResult = first.get(15, TimeUnit.SECONDS);
            BuyNowResult secondResult = second.get(15, TimeUnit.SECONDS);

            assertThat(secondResult).isEqualTo(firstResult);
            assertThat(firstResult.finalPrice()).isEqualTo(BUY_NOW_PRICE);
            assertSuccessfulPurchase(fixture.auctionId(), buyerId, 1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 즉시구매가_발행하는_상태_이벤트는_낙찰_직후의_상태와_확정가와_증가한_revision을_담는다() {
        // 낙찰 UPDATE가 네이티브라 같은 트랜잭션이 적재한 Auction 엔티티는 낡은 값(OPEN, 이전 revision)을 갖는다.
        // 엔티티로 snapshot을 만들면 이 값이 그대로 SSE로 나가므로, 엔티티를 거치지 않는지 여기서 확인한다.
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();

        buyNowService.buyUpAuction(buyerId, fixture.auctionId(), idempotencyKey("snapshot"));

        List<AuctionStateChanged> events = applicationEvents.stream(AuctionStateChanged.class)
                .filter(event -> event.auctionId() == fixture.auctionId())
                .toList();
        assertThat(events).hasSize(1);
        AuctionLiveState state = events.getFirst().state();
        assertThat(state.status()).isEqualTo(AuctionStatus.COMPLETED);
        assertThat(state.revision()).isEqualTo(1L);
        assertThat(state.currentPrice()).isEqualTo(BUY_NOW_PRICE);
        assertThat(state.bidCount()).isEqualTo(1L);
    }

    @Test
    void 서로_다른_구매자가_동시에_구매하면_한명만_낙찰된다() throws Exception {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 2);
        Long firstBuyerId = fixture.buyerIds().get(0);
        Long secondBuyerId = fixture.buyerIds().get(1);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Attempt> first = executor.submit(attemptAfterBarrier(
                    barrier,
                    firstBuyerId,
                    fixture.auctionId(),
                    idempotencyKey("first-buyer")
            ));
            Future<Attempt> second = executor.submit(attemptAfterBarrier(
                    barrier,
                    secondBuyerId,
                    fixture.auctionId(),
                    idempotencyKey("second-buyer")
            ));

            List<Attempt> attempts = List.of(
                    first.get(15, TimeUnit.SECONDS),
                    second.get(15, TimeUnit.SECONDS)
            );
            List<Attempt> successes = attempts.stream()
                    .filter(Attempt::succeeded)
                    .toList();
            List<Attempt> failures = attempts.stream()
                    .filter(attempt -> !attempt.succeeded())
                    .toList();

            assertThat(successes).hasSize(1);
            assertThat(failures).hasSize(1);
            assertThat(failures.getFirst().errorCode()).isIn(
                    ErrorCode.CONCURRENT_TRADE_CONFLICT,
                    ErrorCode.AUCTION_ALREADY_TRADED
            );

            Long winnerId = successes.getFirst().memberId();
            Long loserId = failures.getFirst().memberId();
            assertSuccessfulPurchase(fixture.auctionId(), winnerId, 1L);
            assertThat(countDeposits(fixture.auctionId(), loserId)).isZero();
            assertMemberPoints(loserId, INITIAL_POINT, 0L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 하락_경계에서는_DB의_completedAt을_기준으로_내려간_가격에_낙찰된다() {
        Fixture fixture = createDownAuctionFixture();
        Long buyerId = fixture.buyerIds().getFirst();
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE auction
                            SET started_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 5 MINUTE)
                            WHERE id = :auctionId
                            """)
                    .setParameter("auctionId", fixture.auctionId())
                    .executeUpdate();
            return null;
        });

        BuyNowResult result = buyNowService.buyDownAuction(
                buyerId,
                fixture.auctionId(),
                idempotencyKey("drop-boundary")
        );

        assertThat(result.finalPrice()).isEqualTo(90_000L);
        executeInTransaction(entityManager -> {
            DownAuction auction = entityManager.find(
                    DownAuction.class,
                    fixture.auctionId()
            );
            AuctionTrade trade = entityManager.createQuery("""
                            select auctionTrade
                            from AuctionTrade auctionTrade
                            where auctionTrade.auction.id = :auctionId
                            """, AuctionTrade.class)
                    .setParameter("auctionId", fixture.auctionId())
                    .getSingleResult();

            assertThat(ChronoUnit.MINUTES.between(
                    auction.getStartedAt(),
                    auction.getCompletedAt()
            )).isEqualTo(5L);
            assertThat(trade.getPurchasedAt()).isEqualTo(auction.getCompletedAt());
            assertThat(result.purchasedAt()).isEqualTo(auction.getCompletedAt());
            assertThat(trade.getFinalPrice()).isEqualTo(90_000L);
            assertThat(auction.getCurrentPrice()).isEqualTo(90_000L);
            String bidStatus = (String) entityManager.createNativeQuery("""
                            SELECT status
                            FROM bid
                            WHERE auction_id = :auctionId
                            """)
                    .setParameter("auctionId", fixture.auctionId())
                    .getSingleResult();
            assertThat(bidStatus).isEqualTo(BidStatus.DOWN.name());
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM instant_purchase_request WHERE auction_id = :id",
                    fixture.auctionId())).isEqualTo(1L);
            return null;
        });
        assertThat(findDepositStatus(fixture.auctionId(), buyerId))
                .isEqualTo(DepositStatus.HELD.name());
        assertThat(findDepositAmount(fixture.auctionId(), buyerId)).isEqualTo(90_000L);
        assertMemberPoints(buyerId, INITIAL_POINT - 90_000L, 90_000L);
    }

    @ParameterizedTest
    @EnumSource(value = AuctionStatus.class, names = "OPEN", mode = EnumSource.Mode.EXCLUDE)
    void OPEN이_아닌_경매는_즉시구매할_수_없고_상태가_되돌아가지_않는다(
            AuctionStatus status
    ) {
        Fixture fixture = createFixture(status, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        ErrorCode expected = status == AuctionStatus.COMPLETED
                ? ErrorCode.AUCTION_ALREADY_TRADED
                : ErrorCode.AUCTION_NOT_ONGOING;

        assertFailure(
                buyerId,
                fixture.auctionId(),
                idempotencyKey("invalid-status"),
                expected
        );

        assertNoPurchaseEffects(fixture.auctionId(), status);
        assertMemberPoints(buyerId, INITIAL_POINT, 0L);
    }

    @Test
    void 종료시각과_같은_시각에는_즉시구매할_수_없다() {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE auction
                            SET ended_at = CURRENT_TIMESTAMP(6)
                            WHERE id = :auctionId
                            """)
                    .setParameter("auctionId", fixture.auctionId())
                    .executeUpdate();
            return null;
        });

        assertFailure(
                buyerId,
                fixture.auctionId(),
                idempotencyKey("ended"),
                ErrorCode.AUCTION_ALREADY_ENDED
        );

        assertNoPurchaseEffects(fixture.auctionId(), AuctionStatus.OPEN);
        assertMemberPoints(buyerId, INITIAL_POINT, 0L);
    }

    @Test
    void 상향_경매는_마감_5분_전_구간에서_조건부_완료되지_않는다() {
        // given
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        moveAuctionEndToThreeMinutesLater(fixture.auctionId());
        LocalDateTime purchasedAt = auctionRepository.currentDatabaseTime();

        // when
        int completed = completeForBuyNow(
                fixture.auctionId(),
                buyerId,
                BUY_NOW_PRICE,
                purchasedAt
        );

        // then
        assertThat(completed).isZero();
        assertThat(findAuctionStatus(fixture.auctionId()))
                .isEqualTo(AuctionStatus.OPEN.name());
    }

    @Test
    void 하향_경매는_마감_5분_전_구간에서도_조건부_완료된다() {
        // given
        Fixture fixture = createDownAuctionFixture();
        Long buyerId = fixture.buyerIds().getFirst();
        moveAuctionEndToThreeMinutesLater(fixture.auctionId());
        LocalDateTime purchasedAt = auctionRepository.currentDatabaseTime();

        // when
        int completed = completeForBuyNow(
                fixture.auctionId(),
                buyerId,
                100_000L,
                purchasedAt
        );

        // then
        assertThat(completed).isOne();
        assertThat(findAuctionStatus(fixture.auctionId()))
                .isEqualTo(AuctionStatus.COMPLETED.name());
    }

    @Test
    void 판매자는_자신의_경매를_즉시구매할_수_없다() {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 0);

        assertFailure(
                fixture.sellerId(),
                fixture.auctionId(),
                idempotencyKey("seller"),
                ErrorCode.SELF_PURCHASE_NOT_ALLOWED
        );

        assertNoPurchaseEffects(fixture.auctionId(), AuctionStatus.OPEN);
        assertMemberPoints(fixture.sellerId(), INITIAL_POINT, 0L);
    }

    @Test
    void 낙찰가_전액을_잠글_포인트가_없으면_즉시구매할_수_없다() {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE member
                            SET total_point = :point
                            WHERE id = :memberId
                            """)
                    .setParameter("point", BUY_NOW_PRICE - 1)
                    .setParameter("memberId", buyerId)
                    .executeUpdate();
            return null;
        });

        assertFailure(
                buyerId,
                fixture.auctionId(),
                idempotencyKey("no-deposit"),
                ErrorCode.INSUFFICIENT_DEPOSIT
        );

        assertNoPurchaseEffects(fixture.auctionId(), AuctionStatus.OPEN);
        assertMemberPoints(buyerId, BUY_NOW_PRICE - 1, 0L);
    }

    @Test
    void 비활성_회원은_포인트가_있어도_즉시구매할_수_없다() {
        Fixture fixture = createFixture(AuctionStatus.OPEN, 1);
        Long buyerId = fixture.buyerIds().getFirst();
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE member
                            SET status = 'PENDING'
                            WHERE id = :memberId
                            """)
                    .setParameter("memberId", buyerId)
                    .executeUpdate();
            return null;
        });

        assertFailure(
                buyerId,
                fixture.auctionId(),
                idempotencyKey("inactive"),
                ErrorCode.MEMBER_NOT_ACTIVE
        );

        assertNoPurchaseEffects(fixture.auctionId(), AuctionStatus.OPEN);
        assertMemberPoints(buyerId, INITIAL_POINT, 0L);
    }

    private Callable<BuyNowResult> buyAfterBarrier(
            CyclicBarrier barrier,
            Long memberId,
            Long auctionId,
            String idempotencyKey
    ) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            return buyNowService.buyUpAuction(memberId, auctionId, idempotencyKey);
        };
    }

    private Callable<Attempt> attemptAfterBarrier(
            CyclicBarrier barrier,
            Long memberId,
            Long auctionId,
            String idempotencyKey
    ) {
        return () -> {
            barrier.await(5, TimeUnit.SECONDS);
            try {
                return Attempt.success(
                        memberId,
                        buyNowService.buyUpAuction(memberId, auctionId, idempotencyKey)
                );
            } catch (BusinessException exception) {
                return Attempt.failure(memberId, exception.getErrorCode());
            }
        };
    }

    private void assertFailure(
            Long memberId,
            Long auctionId,
            String idempotencyKey,
            ErrorCode expected
    ) {
        Throwable thrown = catchThrowable(
                () -> buyNowService.buyUpAuction(
                        memberId,
                        auctionId,
                        idempotencyKey
                ));

        assertThat(thrown).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) thrown).getErrorCode()).isEqualTo(expected);
    }

    private void assertSuccessfulPurchase(
            Long auctionId,
            Long expectedBuyerId,
            long expectedRequestLogCount
    ) {
        executeInTransaction(entityManager -> {
            assertThat(findAuctionStatus(entityManager, auctionId))
                    .isEqualTo(AuctionStatus.COMPLETED.name());
            assertThat(findAuctionRevision(entityManager, auctionId)).isEqualTo(1L);
            assertThat(findAuctionCurrentPrice(entityManager, auctionId))
                    .isEqualTo(BUY_NOW_PRICE);
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM auction_trade WHERE auction_id = :id", auctionId))
                    .isEqualTo(1L);
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM auction_deposit WHERE auction_id = :id", auctionId))
                    .isEqualTo(1L);
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM bid WHERE auction_id = :id", auctionId))
                    .isEqualTo(1L);
            assertThat(findAuctionBidCount(entityManager, auctionId)).isEqualTo(1L);
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM instant_purchase_request WHERE auction_id = :id", auctionId))
                    .isEqualTo(expectedRequestLogCount);

            Number buyerId = (Number) entityManager.createNativeQuery("""
                            SELECT buyer_id
                            FROM auction_trade
                            WHERE auction_id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .getSingleResult();
            Number finalPrice = (Number) entityManager.createNativeQuery("""
                            SELECT final_price
                            FROM auction_trade
                            WHERE auction_id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .getSingleResult();
            String tradeStatus = (String) entityManager.createNativeQuery("""
                            SELECT status
                            FROM auction_trade
                            WHERE auction_id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .getSingleResult();

            assertThat(buyerId.longValue()).isEqualTo(expectedBuyerId);
            assertThat(finalPrice.longValue()).isEqualTo(BUY_NOW_PRICE);
            assertThat(tradeStatus).isEqualTo(TradeStatus.CONFIRMED.name());

            Object[] bid = (Object[]) entityManager.createNativeQuery("""
                            SELECT bidder_id, price, status
                            FROM bid
                            WHERE auction_id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .getSingleResult();
            assertThat(((Number) bid[0]).longValue()).isEqualTo(expectedBuyerId);
            assertThat(((Number) bid[1]).longValue()).isEqualTo(BUY_NOW_PRICE);
            assertThat(bid[2]).isEqualTo(BidStatus.BUY_NOW.name());
            return null;
        });
        assertThat(findDepositStatus(auctionId, expectedBuyerId))
                .isEqualTo(DepositStatus.HELD.name());
        assertThat(findDepositAmount(auctionId, expectedBuyerId))
                .isEqualTo(BUY_NOW_PRICE);
        assertMemberPoints(
                expectedBuyerId,
                INITIAL_POINT - BUY_NOW_PRICE,
                BUY_NOW_PRICE
        );
    }

    private void assertNoPurchaseEffects(Long auctionId, AuctionStatus expectedStatus) {
        executeInTransaction(entityManager -> {
            assertThat(findAuctionStatus(entityManager, auctionId))
                    .isEqualTo(expectedStatus.name());
            assertThat(findAuctionRevision(entityManager, auctionId)).isZero();
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM auction_trade WHERE auction_id = :id", auctionId))
                    .isZero();
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM auction_deposit WHERE auction_id = :id", auctionId))
                    .isZero();
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM bid WHERE auction_id = :id", auctionId))
                    .isZero();
            assertThat(findAuctionBidCount(entityManager, auctionId)).isZero();
            assertThat(countRows(entityManager,
                    "SELECT COUNT(*) FROM instant_purchase_request WHERE auction_id = :id", auctionId))
                    .isZero();
            return null;
        });
    }

    private String findDepositStatus(Long auctionId, Long memberId) {
        return executeInTransaction(entityManager -> (String) entityManager.createNativeQuery("""
                        SELECT status
                        FROM auction_deposit
                        WHERE auction_id = :auctionId
                          AND member_id = :memberId
                        """)
                .setParameter("auctionId", auctionId)
                .setParameter("memberId", memberId)
                .getSingleResult());
    }

    private long findDepositAmount(Long auctionId, Long memberId) {
        return executeInTransaction(entityManager -> {
            Number amount = (Number) entityManager.createNativeQuery("""
                            SELECT reserved_amount
                            FROM auction_deposit
                            WHERE auction_id = :auctionId
                              AND member_id = :memberId
                            """)
                    .setParameter("auctionId", auctionId)
                    .setParameter("memberId", memberId)
                    .getSingleResult();
            return amount.longValue();
        });
    }

    private long countDeposits(Long auctionId, Long memberId) {
        return executeInTransaction(entityManager -> {
            Number count = (Number) entityManager.createNativeQuery("""
                            SELECT COUNT(*)
                            FROM auction_deposit
                            WHERE auction_id = :auctionId
                              AND member_id = :memberId
                            """)
                    .setParameter("auctionId", auctionId)
                    .setParameter("memberId", memberId)
                    .getSingleResult();
            return count.longValue();
        });
    }

    private void assertMemberPoints(
            Long memberId,
            long expectedTotalPoint,
            long expectedLockedPoint
    ) {
        executeInTransaction(entityManager -> {
            Object[] points = (Object[]) entityManager.createNativeQuery("""
                            SELECT total_point, locked_point
                            FROM member
                            WHERE id = :memberId
                            """)
                    .setParameter("memberId", memberId)
                    .getSingleResult();
            assertThat(((Number) points[0]).longValue()).isEqualTo(expectedTotalPoint);
            assertThat(((Number) points[1]).longValue()).isEqualTo(expectedLockedPoint);
            return null;
        });
    }

    private Fixture createFixture(
            AuctionStatus status,
            int buyerCount
    ) {
        Fixture fixture = executeInTransaction(entityManager -> {
            Member seller = persistMember(entityManager, MemberStatus.ACTIVE);
            List<Member> buyers = new ArrayList<>();
            for (int index = 0; index < buyerCount; index++) {
                buyers.add(persistMember(entityManager, MemberStatus.ACTIVE));
            }

            UpAuction auction = UpAuction.builder()
                    .seller(seller)
                    .title("즉시구매 통합테스트 " + runId.substring(0, 6))
                    .description("즉시구매 통합테스트 상품")
                    .status(status)
                    .category(AuctionCategory.HOUSEHOLD)
                    .startPrice(100_000L)
                    .endedAt(LocalDateTime.now().plusDays(1))
                    .tradeType(TradeType.DELIVERY)
                    .contact("01012345678")
                    .buyNowPrice(BUY_NOW_PRICE)
                    .build();
            entityManager.persist(auction);
            entityManager.flush();

            return new Fixture(
                    seller.getId(),
                    buyers.stream().map(Member::getId).toList(),
                    auction.getId()
            );
        });

        auctionIds.add(fixture.auctionId());
        memberIds.add(fixture.sellerId());
        memberIds.addAll(fixture.buyerIds());
        return fixture;
    }

    private Fixture createDownAuctionFixture() {
        Fixture fixture = executeInTransaction(entityManager -> {
            Member seller = persistMember(entityManager, MemberStatus.ACTIVE);
            Member buyer = persistMember(entityManager, MemberStatus.ACTIVE);
            DownAuction auction = DownAuction.builder()
                    .seller(seller)
                    .title("하향경매 통합테스트 " + runId.substring(0, 6))
                    .description("하락 경계 가격 검증 상품")
                    .status(AuctionStatus.OPEN)
                    .category(AuctionCategory.HOUSEHOLD)
                    .startPrice(100_000L)
                    .endedAt(LocalDateTime.now().plusDays(1))
                    .tradeType(TradeType.DELIVERY)
                    .contact("01012345678")
                    .minimumPrice(50_000L)
                    .dropPrice(10_000L)
                    .priceDropInterval(5L)
                    .build();
            entityManager.persist(auction);
            entityManager.flush();

            return new Fixture(
                    seller.getId(),
                    List.of(buyer.getId()),
                    auction.getId()
            );
        });

        auctionIds.add(fixture.auctionId());
        memberIds.add(fixture.sellerId());
        memberIds.addAll(fixture.buyerIds());
        return fixture;
    }

    private Member persistMember(EntityManager entityManager, MemberStatus status) {
        int sequence = memberSequence.incrementAndGet();
        String identifier = runId.substring(0, 7) + sequence;
        Member member = Member.builder()
                .email(identifier + "@example.com")
                .password("encoded-password")
                .name("통합테스트")
                .phoneNumber("01012345678")
                .nickname(identifier)
                .status(status)
                .build();
        entityManager.persist(member);
        return member;
    }

    private String idempotencyKey(String suffix) {
        return runId + "-" + suffix;
    }

    private void moveAuctionEndToThreeMinutesLater(Long auctionId) {
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE auction
                            SET ended_at = CURRENT_TIMESTAMP(6) + INTERVAL 3 MINUTE
                            WHERE id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .executeUpdate();
            return null;
        });
    }

    private int completeForBuyNow(
            Long auctionId,
            Long buyerId,
            long finalPrice,
            LocalDateTime purchasedAt
    ) {
        Integer completed = new TransactionTemplate(transactionManager).execute(
                status -> auctionRepository.completeForBuyNow(
                        auctionId,
                        buyerId,
                        finalPrice,
                        purchasedAt
                )
        );
        return completed == null ? 0 : completed;
    }

    private String findAuctionStatus(EntityManager entityManager, Long auctionId) {
        return (String) entityManager.createNativeQuery("""
                        SELECT status
                        FROM auction
                        WHERE id = :auctionId
                        """)
                .setParameter("auctionId", auctionId)
                .getSingleResult();
    }

    private long findAuctionRevision(EntityManager entityManager, Long auctionId) {
        Number revision = (Number) entityManager.createNativeQuery("""
                        SELECT revision
                        FROM auction
                        WHERE id = :auctionId
                        """)
                .setParameter("auctionId", auctionId)
                .getSingleResult();
        return revision.longValue();
    }

    private long findAuctionBidCount(EntityManager entityManager, Long auctionId) {
        Number bidCount = (Number) entityManager.createNativeQuery("""
                        SELECT bid_count
                        FROM auction
                        WHERE id = :auctionId
                        """)
                .setParameter("auctionId", auctionId)
                .getSingleResult();
        return bidCount.longValue();
    }

    private long findAuctionCurrentPrice(EntityManager entityManager, Long auctionId) {
        Number currentPrice = (Number) entityManager.createNativeQuery("""
                        SELECT current_price
                        FROM auction
                        WHERE id = :auctionId
                        """)
                .setParameter("auctionId", auctionId)
                .getSingleResult();
        return currentPrice.longValue();
    }
                                                                   
    private String findAuctionStatus(Long auctionId) {
        return executeInTransaction(
                entityManager -> findAuctionStatus(entityManager, auctionId)
        );
    }

    private long countRows(EntityManager entityManager, String sql, Long id) {
        Number count = (Number) entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .getSingleResult();
        return count.longValue();
    }

    private void executeDelete(EntityManager entityManager, String sql, Long id) {
        entityManager.createNativeQuery(sql)
                .setParameter("id", id)
                .executeUpdate();
    }

    private <T> T executeInTransaction(Function<EntityManager, T> action) {
        EntityManager entityManager = entityManagerFactory.createEntityManager();
        EntityTransaction transaction = entityManager.getTransaction();

        try {
            transaction.begin();
            T result = action.apply(entityManager);
            transaction.commit();
            return result;
        } catch (RuntimeException | Error exception) {
            if (transaction.isActive()) {
                transaction.rollback();
            }
            throw exception;
        } finally {
            entityManager.close();
        }
    }

    private record Fixture(
            Long sellerId,
            List<Long> buyerIds,
            Long auctionId
    ) {
    }

    private record Attempt(
            Long memberId,
            BuyNowResult result,
            ErrorCode errorCode
    ) {

        private static Attempt success(Long memberId, BuyNowResult result) {
            return new Attempt(memberId, result, null);
        }

        private static Attempt failure(Long memberId, ErrorCode errorCode) {
            return new Attempt(memberId, null, errorCode);
        }

        private boolean succeeded() {
            return result != null;
        }
    }
}
