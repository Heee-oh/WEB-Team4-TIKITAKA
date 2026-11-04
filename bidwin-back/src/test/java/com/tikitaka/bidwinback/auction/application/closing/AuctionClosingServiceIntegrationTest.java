package com.tikitaka.bidwinback.auction.application.closing;

import com.tikitaka.bidwinback.auction.domain.entity.AuctionDeposit;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.DepositStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;

@SpringBootTest(properties = {
        "app.storage.s3.bucket=test-bucket",
        "app.auction.closing-interval=24h"
})
@RecordApplicationEvents
class AuctionClosingServiceIntegrationTest {

    private static final long BID_UNIT = 1_000L;
    private static final long START_PRICE = 100_000L;
    private static final long DEPOSIT_AMOUNT = 30_000L;
    private static final long INITIAL_TOTAL_POINT = 1_970_000L;
    private static final int BATCH_SIZE = 200;

    @Autowired
    private AuctionClosingService auctionClosingService;

    @Autowired
    private ApplicationEvents applicationEvents;

    @Autowired
    private AuctionClosingBatchProcessor batchProcessor;

    @Autowired
    private AuctionRepository auctionRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private final List<Long> auctionIds = new ArrayList<>();
    private final List<Long> memberIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        executeInTransaction(entityManager -> {
            for (Long auctionId : auctionIds) {
                delete(entityManager, "DELETE FROM auction_trade WHERE auction_id = :id", auctionId);
                delete(entityManager, "DELETE FROM auction_deposit WHERE auction_id = :id", auctionId);
                delete(entityManager, "DELETE FROM up_auction WHERE auction_id = :id", auctionId);
                delete(entityManager, "DELETE FROM auction WHERE id = :id", auctionId);
            }
            for (Long memberId : memberIds) {
                delete(entityManager, "DELETE FROM member WHERE id = :id", memberId);
            }
            return null;
        });
    }

    @Test
    void 다른_트랜잭션이_경매를_변경중이면_기다리지_않고_다음_시도에서_마감한다()
            throws Exception {
        // given
        Long auctionId = createEndedOpenAuction();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> lockHolder = executor.submit(() -> holdAuctionLock(
                auctionId,
                locked,
                release
        ));

        try {
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();

            // when
            long startedAt = System.nanoTime();
            auctionClosingService.closeBatch(AuctionStatus.OPEN, BATCH_SIZE);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startedAt
            );


            assertAll(
                    () -> assertThat(elapsedMillis).isLessThan(1_000L),
                    () -> assertThat(findStatus(auctionId)).isEqualTo(AuctionStatus.OPEN)
            );

            // when
            release.countDown();
            lockHolder.get(5, TimeUnit.SECONDS);
            auctionClosingService.closeBatch(AuctionStatus.OPEN, BATCH_SIZE);

            // then
            assertThat(findStatus(auctionId)).isEqualTo(AuctionStatus.UNSOLD);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 마감이_발행하는_상태_이벤트는_마감_직후의_상태와_확정가를_담는다() {
        // 마감 UPDATE와 거래 INSERT가 네이티브라, 엔티티가 아니라 DB를 직접 읽어야 마감 후 값이 나간다.
        Long auctionId = createEndedAuctionWithBid();

        auctionClosingService.closeBatch(AuctionStatus.BID_ONGOING, BATCH_SIZE);

        List<AuctionStateChanged> events = applicationEvents.stream(AuctionStateChanged.class)
                .filter(event -> event.auctionId() == auctionId)
                .toList();
        assertThat(events).hasSize(1);
        AuctionLiveState state = events.getFirst().state();
        assertThat(state.status()).isEqualTo(AuctionStatus.COMPLETED);
        assertThat(state.currentPrice()).isEqualTo(START_PRICE + BID_UNIT);
        assertThat(state.revision()).isEqualTo(2L);
    }

    @Test
    void 두_배치가_동시에_돌아도_거래는_한_건만_생성된다() throws Exception {
        // given
        Long auctionId = createEndedAuctionWithBid();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // when
            Future<Integer> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeBatch(AuctionStatus.BID_ONGOING, BATCH_SIZE);
            });
            Future<Integer> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeBatch(AuctionStatus.BID_ONGOING, BATCH_SIZE);
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            // then
            assertAll(
                    () -> assertThat(findStatus(auctionId)).isEqualTo(AuctionStatus.COMPLETED),
                    () -> assertThat(findTradeCount(auctionId)).isEqualTo(1L)
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 두_배치가_동시에_돌아도_비낙찰_보증금은_한_번만_반환된다() throws Exception {
        // given
        ClosingFixture fixture = createEndedAuctionWithWinnerAndLoser(DEPOSIT_AMOUNT);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // when
            Future<Integer> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeBatch(AuctionStatus.BID_ONGOING, BATCH_SIZE);
            });
            Future<Integer> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeBatch(AuctionStatus.BID_ONGOING, BATCH_SIZE);
            });
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);

            // then
            assertAll(
                    () -> assertThat(findStatus(fixture.auctionId()))
                            .isEqualTo(AuctionStatus.COMPLETED),
                    () -> assertThat(findTradeCount(fixture.auctionId())).isEqualTo(1L),
                    () -> assertThat(findDepositStatus(
                            fixture.auctionId(), fixture.loserId()))
                            .isEqualTo(DepositStatus.REFUNDED.name()),
                    () -> assertThat(findMemberPoints(fixture.loserId()))
                            .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT + DEPOSIT_AMOUNT, 0L))
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 두_개별_재처리가_겹쳐도_비낙찰_보증금은_한_번만_반환된다() throws Exception {
        // given
        ClosingFixture fixture = createEndedAuctionWithWinnerAndLoser(DEPOSIT_AMOUNT);
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            // when
            Future<Integer> first = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeOne(
                        AuctionStatus.BID_ONGOING,
                        fixture.auctionId()
                );
            });
            Future<Integer> second = executor.submit(() -> {
                barrier.await(5, TimeUnit.SECONDS);
                return auctionClosingService.closeOne(
                        AuctionStatus.BID_ONGOING,
                        fixture.auctionId()
                );
            });
            int closed = first.get(10, TimeUnit.SECONDS)
                    + second.get(10, TimeUnit.SECONDS);

            // then
            assertAll(
                    () -> assertThat(closed).isEqualTo(1),
                    () -> assertThat(findStatus(fixture.auctionId()))
                            .isEqualTo(AuctionStatus.COMPLETED),
                    () -> assertThat(findTradeCount(fixture.auctionId())).isEqualTo(1L),
                    () -> assertThat(findDepositStatus(
                            fixture.auctionId(), fixture.loserId()))
                            .isEqualTo(DepositStatus.REFUNDED.name()),
                    () -> assertThat(findMemberPoints(fixture.loserId()))
                            .isEqualTo(new PointSnapshot(
                                    INITIAL_TOTAL_POINT + DEPOSIT_AMOUNT,
                                    0L
                            ))
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void 비낙찰_잠금포인트가_부족하면_경매마감과_보증금반환이_함께_롤백된다() {
        // given
        long insufficientLockedPoint = DEPOSIT_AMOUNT - 1;
        ClosingFixture fixture = createEndedAuctionWithWinnerAndLoser(insufficientLockedPoint);

        // when
        Throwable exception = catchThrowable(() -> auctionClosingService.closeBatch(
                AuctionStatus.BID_ONGOING,
                BATCH_SIZE
        ));

        // then
        assertAll(
                () -> assertThat(exception)
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessage("보증금 반환 중 잠금 포인트를 되돌리지 못했습니다."),
                () -> assertThat(findStatus(fixture.auctionId()))
                        .isEqualTo(AuctionStatus.BID_ONGOING),
                () -> assertThat(findTradeCount(fixture.auctionId())).isZero(),
                () -> assertThat(findDepositStatus(fixture.auctionId(), fixture.loserId()))
                        .isEqualTo(DepositStatus.HELD.name()),
                () -> assertThat(findMemberPoints(fixture.loserId()))
                        .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT, insufficientLockedPoint))
        );
    }

    @Test
    void 한_경매의_정산이_실패해도_다른_경매는_마감하고_실패한_경매만_재시도한다() {
        // given
        ClosingFixture firstHealthy = createEndedAuctionWithWinnerAndLoser(DEPOSIT_AMOUNT);
        long insufficientLockedPoint = DEPOSIT_AMOUNT - 1;
        ClosingFixture failed = createEndedAuctionWithWinnerAndLoser(insufficientLockedPoint);
        ClosingFixture secondHealthy = createEndedAuctionWithWinnerAndLoser(DEPOSIT_AMOUNT);

        // when
        batchProcessor.closeEndedAuctions();

        // then
        assertAll(
                () -> assertThat(findStatus(firstHealthy.auctionId()))
                        .isEqualTo(AuctionStatus.COMPLETED),
                () -> assertThat(findTradeCount(firstHealthy.auctionId())).isEqualTo(1L),
                () -> assertThat(findDepositStatus(
                        firstHealthy.auctionId(), firstHealthy.loserId()))
                        .isEqualTo(DepositStatus.REFUNDED.name()),
                () -> assertThat(findMemberPoints(firstHealthy.loserId()))
                        .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT + DEPOSIT_AMOUNT, 0L)),
                () -> assertThat(findStatus(secondHealthy.auctionId()))
                        .isEqualTo(AuctionStatus.COMPLETED),
                () -> assertThat(findTradeCount(secondHealthy.auctionId())).isEqualTo(1L),
                () -> assertThat(findDepositStatus(
                        secondHealthy.auctionId(), secondHealthy.loserId()))
                        .isEqualTo(DepositStatus.REFUNDED.name()),
                () -> assertThat(findMemberPoints(secondHealthy.loserId()))
                        .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT + DEPOSIT_AMOUNT, 0L)),
                () -> assertThat(findStatus(failed.auctionId()))
                        .isEqualTo(AuctionStatus.BID_ONGOING),
                () -> assertThat(findTradeCount(failed.auctionId())).isZero(),
                () -> assertThat(findDepositStatus(failed.auctionId(), failed.winnerId()))
                        .isEqualTo(DepositStatus.HELD.name()),
                () -> assertThat(findMemberPoints(failed.winnerId()))
                        .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT, DEPOSIT_AMOUNT)),
                () -> assertThat(findDepositStatus(failed.auctionId(), failed.loserId()))
                        .isEqualTo(DepositStatus.HELD.name()),
                () -> assertThat(findMemberPoints(failed.loserId()))
                        .isEqualTo(new PointSnapshot(
                                INITIAL_TOTAL_POINT,
                                insufficientLockedPoint
                        ))
        );

        // when
        updateLockedPoint(failed.loserId(), DEPOSIT_AMOUNT);
        batchProcessor.closeEndedAuctions();

        // then
        assertAll(
                () -> assertThat(findStatus(failed.auctionId()))
                        .isEqualTo(AuctionStatus.COMPLETED),
                () -> assertThat(findTradeCount(failed.auctionId())).isEqualTo(1L),
                () -> assertThat(findDepositStatus(failed.auctionId(), failed.loserId()))
                        .isEqualTo(DepositStatus.REFUNDED.name()),
                () -> assertThat(findMemberPoints(failed.loserId()))
                        .isEqualTo(new PointSnapshot(INITIAL_TOTAL_POINT + DEPOSIT_AMOUNT, 0L))
        );
    }

    private Long createEndedOpenAuction() {
        Long auctionId = executeInTransaction(entityManager -> {
            Member seller = persistMember(entityManager, "seller");
            Long persistedId = persistAuction(entityManager, seller);
            memberIds.add(seller.getId());
            return persistedId;
        });
        endNow(auctionId);
        auctionIds.add(auctionId);
        return auctionId;
    }

    private Long createEndedAuctionWithBid() {
        long[] ids = executeInTransaction(entityManager -> {
            Member seller = persistMember(entityManager, "seller");
            Member bidder = persistMember(entityManager, "bidder");
            Long persistedId = persistAuction(entityManager, seller);
            memberIds.add(seller.getId());
            memberIds.add(bidder.getId());
            return new long[]{persistedId, bidder.getId()};
        });
        auctionIds.add(ids[0]);
        transactionTemplate.executeWithoutResult(status ->
                auctionRepository.updateCurrentPriceForBid(
                        ids[0],
                        ids[1],
                        START_PRICE + BID_UNIT,
                        BID_UNIT
                )
        );
        endNow(ids[0]);
        return ids[0];
    }

    private ClosingFixture createEndedAuctionWithWinnerAndLoser(long loserLockedPoint) {
        long[] ids = executeInTransaction(entityManager -> {
            Member seller = persistMember(entityManager, "seller");
            Member winner = persistMember(
                    entityManager,
                    "winner",
                    INITIAL_TOTAL_POINT,
                    DEPOSIT_AMOUNT
            );
            Member loser = persistMember(
                    entityManager,
                    "loser",
                    INITIAL_TOTAL_POINT,
                    loserLockedPoint
            );
            Long persistedAuctionId = persistAuction(entityManager, seller);
            UpAuction auction = entityManager.getReference(UpAuction.class, persistedAuctionId);
            entityManager.persist(AuctionDeposit.builder()
                    .member(winner)
                    .auction(auction)
                    .reservedAmount(DEPOSIT_AMOUNT)
                    .status(DepositStatus.HELD)
                    .build());
            entityManager.persist(AuctionDeposit.builder()
                    .member(loser)
                    .auction(auction)
                    .reservedAmount(DEPOSIT_AMOUNT)
                    .status(DepositStatus.HELD)
                    .build());
            entityManager.flush();
            memberIds.add(seller.getId());
            memberIds.add(winner.getId());
            memberIds.add(loser.getId());
            return new long[]{persistedAuctionId, winner.getId(), loser.getId()};
        });
        auctionIds.add(ids[0]);
        transactionTemplate.executeWithoutResult(status ->
                auctionRepository.updateCurrentPriceForBid(
                        ids[0],
                        ids[1],
                        START_PRICE + BID_UNIT,
                        BID_UNIT
                )
        );
        endNow(ids[0]);
        return new ClosingFixture(ids[0], ids[1], ids[2]);
    }

    private Long persistAuction(EntityManager entityManager, Member seller) {
        UpAuction auction = UpAuction.builder()
                .seller(seller)
                .title("락 경합 마감 테스트")
                .description("잠긴 경매를 건너뛰고 재시도하는지 검증")
                .status(AuctionStatus.OPEN)
                .category(AuctionCategory.HOUSEHOLD)
                .startPrice(START_PRICE)
                .endedAt(LocalDateTime.now().plusDays(1))
                .tradeType(TradeType.DELIVERY)
                .contact("01012345678")
                .buyNowPrice(300_000L)
                .build();
        entityManager.persist(auction);
        entityManager.flush();
        return auction.getId();
    }

    private void endNow(Long auctionId) {
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE auction
                            SET ended_at = SYSDATE(6) - INTERVAL 1 SECOND
                            WHERE id = :auctionId
                            """)
                    .setParameter("auctionId", auctionId)
                    .executeUpdate();
            return null;
        });
    }

    private void updateLockedPoint(Long memberId, long lockedPoint) {
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            UPDATE member
                            SET locked_point = :lockedPoint
                            WHERE id = :memberId
                            """)
                    .setParameter("lockedPoint", lockedPoint)
                    .setParameter("memberId", memberId)
                    .executeUpdate();
            return null;
        });
    }

    private Member persistMember(EntityManager entityManager, String role) {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8);
        Member member = Member.builder()
                .email(role + "-" + suffix + "@example.com")
                .password("encoded-password")
                .name("통합테스트")
                .phoneNumber("01012345678")
                .nickname("n" + suffix)
                .status(MemberStatus.ACTIVE)
                .build();
        entityManager.persist(member);
        return member;
    }

    private Member persistMember(
            EntityManager entityManager,
            String role,
            long totalPoint,
            long lockedPoint
    ) {
        String suffix = UUID.randomUUID().toString()
                .replace("-", "")
                .substring(0, 8);
        Member member = Member.builder()
                .email(role + "-" + suffix + "@example.com")
                .password("encoded-password")
                .name("통합테스트")
                .phoneNumber("01012345678")
                .nickname("n" + suffix)
                .status(MemberStatus.ACTIVE)
                .totalPoint(totalPoint)
                .lockedPoint(lockedPoint)
                .build();
        entityManager.persist(member);
        return member;
    }

    private void holdAuctionLock(
            Long auctionId,
            CountDownLatch locked,
            CountDownLatch release
    ) {
        executeInTransaction(entityManager -> {
            entityManager.createNativeQuery("""
                            SELECT id
                            FROM auction
                            WHERE id = :auctionId
                            FOR UPDATE
                            """)
                    .setParameter("auctionId", auctionId)
                    .getSingleResult();
            locked.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("락 유지 스레드가 중단되었습니다.", exception);
            }
            return null;
        });
    }

    private AuctionStatus findStatus(Long auctionId) {
        return executeInTransaction(entityManager -> AuctionStatus.valueOf(
                (String) entityManager.createNativeQuery("""
                                SELECT status
                                FROM auction
                                WHERE id = :auctionId
                                """)
                        .setParameter("auctionId", auctionId)
                        .getSingleResult()
        ));
    }

    private long findTradeCount(Long auctionId) {
        return executeInTransaction(entityManager -> ((Number) entityManager.createNativeQuery("""
                                SELECT COUNT(*)
                                FROM auction_trade
                                WHERE auction_id = :auctionId
                                """)
                        .setParameter("auctionId", auctionId)
                        .getSingleResult()
        ).longValue());
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

    private PointSnapshot findMemberPoints(Long memberId) {
        return executeInTransaction(entityManager -> {
            Object[] points = (Object[]) entityManager.createNativeQuery("""
                            SELECT total_point, locked_point
                            FROM member
                            WHERE id = :memberId
                            """)
                    .setParameter("memberId", memberId)
                    .getSingleResult();
            return new PointSnapshot(
                    ((Number) points[0]).longValue(),
                    ((Number) points[1]).longValue()
            );
        });
    }

    private void delete(EntityManager entityManager, String sql, Long id) {
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

    private record ClosingFixture(Long auctionId, Long winnerId, Long loserId) {
    }

    private record PointSnapshot(long totalPoint, long lockedPoint) {
    }
}
