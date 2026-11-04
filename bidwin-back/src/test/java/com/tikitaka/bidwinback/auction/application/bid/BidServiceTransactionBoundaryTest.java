package com.tikitaka.bidwinback.auction.application.bid;

import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionDepositRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.SealedBidRepository;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 입찰 진입점이 DB 연결을 쥔 채로 가격 캐시를 재동기화하지 않는지 실제 트랜잭션 프록시로 검증한다.
 *
 * <p>BidService에 @Transactional을 붙이거나 재동기화를 트랜잭션 콜백으로 되돌리면, 아래 순서
 * (begin → rollback → 재동기화용 DB 조회)가 깨지거나 조회 시점에 트랜잭션이 활성이라 이 테스트가 실패한다.
 * 이는 동시 입찰 수가 커넥션 풀 크기에 닿을 때 풀 전체가 교착하던 문제의 회귀 방지다.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = BidServiceTransactionBoundaryTest.TestConfig.class)
class BidServiceTransactionBoundaryTest {

    private static final long AUCTION_ID = 42L;
    private static final long MEMBER_ID = 1L;
    private static final long PRICE = 232_000L;

    @Autowired
    private BidService bidService;
    @Autowired
    private List<String> events;
    @Autowired
    private List<Boolean> transactionActiveAtResync;
    @Autowired
    private BidPriceCache bidPriceCache;

    @BeforeEach
    void setUp() {
        events.clear();
        transactionActiveAtResync.clear();
    }

    @Test
    void 입찰_진입점은_트랜잭션을_열지_않고_DB_트랜잭션은_BidTransactionService가_연다() throws Exception {
        assertThat(AnnotatedElementUtils.hasAnnotation(BidService.class, Transactional.class)).isFalse();
        assertThat(AnnotatedElementUtils.hasAnnotation(
                BidService.class.getMethod("place", Long.class, Long.class, long.class, BidType.class),
                Transactional.class
        )).isFalse();

        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                BidTransactionService.class.getMethod(
                        "place", Long.class, Long.class, long.class, BidType.class
                ),
                Transactional.class
        );
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRED);
    }

    @Test
    void 입찰이_실패하면_롤백으로_연결이_반납된_뒤에_가격_캐시를_재동기화한다() {
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(1L);

        assertThrows(
                AuctionException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(events).containsExactly("begin", "rollback", "resync-read");
        assertThat(transactionActiveAtResync).containsExactly(false);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        List<String> events() {
            return new ArrayList<>();
        }

        @Bean
        List<Boolean> transactionActiveAtResync() {
            return new ArrayList<>();
        }

        @Bean
        PlatformTransactionManager transactionManager(List<String> events) {
            return new RecordingTransactionManager(events);
        }

        @Bean
        BidPriceCache bidPriceCache() {
            return mock(BidPriceCache.class);
        }

        @Bean
        AuctionRepository auctionRepository(
                List<String> events,
                List<Boolean> transactionActiveAtResync
        ) {
            AuctionRepository repository = mock(AuctionRepository.class);
            // 입찰 UPDATE가 0건 → 실패 원인 판별에서 경매를 못 찾아 AuctionException(롤백)
            when(repository.updateCurrentPriceForBid(anyLong(), anyLong(), anyLong(), anyLong()))
                    .thenReturn(0);
            when(repository.findById(AUCTION_ID)).thenReturn(Optional.empty());
            when(repository.findCurrentPriceById(AUCTION_ID)).thenAnswer(invocation -> {
                events.add("resync-read");
                transactionActiveAtResync.add(
                        TransactionSynchronizationManager.isActualTransactionActive()
                );
                return Optional.of(231_000L);
            });
            return repository;
        }

        @Bean
        BidTransactionService bidTransactionService(AuctionRepository auctionRepository) {
            return new BidTransactionService(
                    mock(MemberRepository.class),
                    auctionRepository,
                    mock(AuctionDepositRepository.class),
                    mock(BidRepository.class),
                    mock(ApplicationEventPublisher.class),
                    mock(SealedBidRepository.class),
                    mock(AuctionLiveStateService.class)
            );
        }

        @Bean
        BidService bidService(
                BidTransactionService bidTransactionService,
                BidPriceCache bidPriceCache,
                AuctionRepository auctionRepository
        ) {
            return new BidService(bidTransactionService, bidPriceCache, auctionRepository);
        }
    }

    private static final class RecordingTransactionManager
            extends AbstractPlatformTransactionManager {

        private final List<String> events;

        private RecordingTransactionManager(List<String> events) {
            this.events = events;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            events.add("begin");
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            events.add("commit");
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            events.add("rollback");
        }
    }
}
