package com.tikitaka.bidwinback.auction.infrastructure.sse;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidHistoryCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateCache;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.global.sse.RedisSseEventBus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AuctionSseStateChangeListenerTransactionTest.TestConfig.class)
class AuctionSseStateChangeListenerTransactionTest {

    @Autowired
    private ApplicationEventPublisher eventPublisher;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AuctionLiveStateCache stateCache;
    @Autowired
    private AuctionBidHistoryCache bidHistoryCache;
    @Autowired
    private RedisSseEventBus eventBus;

    private final AuctionLiveState state = new AuctionLiveState(
            1L,
            3L,
            AuctionType.UP,
            AuctionStatus.BID_ONGOING,
            130_000L,
            2L
    );

    @BeforeEach
    void setUp() {
        reset(stateCache, bidHistoryCache, eventBus);
    }

    @Test
    void 트랜잭션이_커밋된_뒤에만_경매_상태를_Redis로_전송한다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // when
        transaction.executeWithoutResult(ignored -> {
            eventPublisher.publishEvent(new AuctionStateChanged(state));
            verifyNoInteractions(eventBus);
        });

        // then
        verify(stateCache).invalidate(1L);
        verify(bidHistoryCache).invalidate(1L);
        verify(eventBus).publish(AuctionSseMessages.state(state));
    }

    @Test
    void 트랜잭션이_롤백되면_경매_상태를_Redis로_전송하지_않는다() {
        // given
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // when
        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new AuctionStateChanged(state));
            status.setRollbackOnly();
        });

        // then
        verifyNoInteractions(eventBus);
        verifyNoInteractions(stateCache);
        verifyNoInteractions(bidHistoryCache);
    }

    @Test
    void 커밋후_Redis_발행이_실패해도_이미_완료된_비즈니스_결과는_실패하지_않는다() {
        // given
        doThrow(new IllegalStateException("redis down"))
                .when(eventBus).publish(AuctionSseMessages.state(state));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        // when & then
        assertThatCode(() -> transaction.executeWithoutResult(ignored ->
                eventPublisher.publishEvent(new AuctionStateChanged(state))
        )).doesNotThrowAnyException();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TestConfig {

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        AuctionLiveStateCache stateCache() {
            return mock(AuctionLiveStateCache.class);
        }

        @Bean
        AuctionBidHistoryCache bidHistoryCache() {
            return mock(AuctionBidHistoryCache.class);
        }

        @Bean
        RedisSseEventBus eventBus() {
            return mock(RedisSseEventBus.class);
        }

        @Bean
        AuctionSseStateChangeListener auctionSseStateChangeListener(
                AuctionLiveStateCache stateCache,
                AuctionBidHistoryCache bidHistoryCache,
                RedisSseEventBus eventBus
        ) {
            return new AuctionSseStateChangeListener(
                    stateCache,
                    bidHistoryCache,
                    eventBus
            );
        }
    }

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction,
                TransactionDefinition definition
        ) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
