package com.tikitaka.bidwinback.auction.application.live;

import com.github.benmanes.caffeine.cache.Ticker;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionLiveStateCacheTest {

    @Mock
    private AuctionLiveStateService stateService;

    private MutableTicker ticker;
    private AuctionLiveStateCache stateCache;
    private ExecutorService loaderExecutor;

    @BeforeEach
    void setUp() {
        ticker = new MutableTicker();
        loaderExecutor = Executors.newFixedThreadPool(4);
        stateCache = new AuctionLiveStateCache(
                stateService,
                new SimpleMeterRegistry(),
                loaderExecutor,
                ticker
        );
    }

    @AfterEach
    void tearDown() {
        loaderExecutor.shutdownNow();
    }

    @Test
    void 동시에_같은_경매_목록을_조회하면_DB_snapshot을_한번만_만든다() throws Exception {
        // given
        int connectionCount = 20;
        List<Long> auctionIds = List.of(1L, 2L);
        List<AuctionLiveState> states = List.of(state(1L, 1L), state(2L, 1L));
        CountDownLatch callersReady = new CountDownLatch(connectionCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        when(stateService.getStates(anyCollection())).thenAnswer(ignored -> {
            loadStarted.countDown();
            releaseLoad.await(5, TimeUnit.SECONDS);
            return states;
        });

        try (ExecutorService executor = Executors.newFixedThreadPool(connectionCount)) {
            List<Future<List<AuctionLiveState>>> futures = java.util.stream.IntStream
                    .range(0, connectionCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        callersReady.countDown();
                        start.await();
                        return stateCache.getStates(auctionIds);
                    }))
                    .toList();
            callersReady.await(5, TimeUnit.SECONDS);

            // when
            start.countDown();
            loadStarted.await(5, TimeUnit.SECONDS);
            releaseLoad.countDown();

            // then
            for (Future<List<AuctionLiveState>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo(states);
            }
        }
        verify(stateService).getStates(anyCollection());
    }

    @Test
    void 목록에서_읽은_snapshot은_같은_경매의_단건_구독에서도_공유한다() {
        // given
        AuctionLiveState state = state(1L, 1L);
        when(stateService.getStates(anyCollection())).thenReturn(List.of(state));

        // when
        stateCache.getStates(List.of(1L));
        AuctionLiveState cached = stateCache.getState(1L);

        // then
        assertThat(cached).isEqualTo(state);
        verify(stateService).getStates(anyCollection());
    }

    @Test
    void 목록_snapshot은_DB_반환순서와_무관하게_요청한_경매순서를_유지한다() {
        // given
        AuctionLiveState first = state(1L, 1L);
        AuctionLiveState second = state(2L, 1L);
        when(stateService.getStates(anyCollection())).thenReturn(List.of(first, second));

        // when
        List<AuctionLiveState> result = stateCache.getStates(List.of(2L, 1L));

        // then
        assertThat(result).containsExactly(second, first);
    }

    @Test
    void 무효화와_겹친_이전_revision_조회가_완료돼도_최신_cache를_덮지_않는다() throws Exception {
        // given
        AuctionLiveState oldState = state(1L, 1L);
        AuctionLiveState newState = state(1L, 2L);
        AtomicInteger loadCount = new AtomicInteger();
        CountDownLatch oldLoadStarted = new CountDownLatch(1);
        CountDownLatch releaseOldLoad = new CountDownLatch(1);
        when(stateService.getStates(anyCollection())).thenAnswer(ignored -> {
            if (loadCount.incrementAndGet() == 1) {
                oldLoadStarted.countDown();
                releaseOldLoad.await(5, TimeUnit.SECONDS);
                return List.of(oldState);
            }
            return List.of(newState);
        });

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<AuctionLiveState> oldResult = executor.submit(() -> stateCache.getState(1L));
            oldLoadStarted.await(5, TimeUnit.SECONDS);

            // when
            stateCache.invalidate(1L);
            Future<AuctionLiveState> newResult = executor.submit(() -> stateCache.getState(1L));
            assertThat(newResult.get(5, TimeUnit.SECONDS)).isEqualTo(newState);
            releaseOldLoad.countDown();
            assertThat(oldResult.get(5, TimeUnit.SECONDS)).isEqualTo(oldState);

            // then
            assertThat(stateCache.getState(1L)).isEqualTo(newState);
        } finally {
            releaseOldLoad.countDown();
        }
    }

    @Test
    void snapshot은_500밀리초가_지나면_DB에서_다시_읽는다() {
        // given
        AuctionLiveStateCache expiringCache = new AuctionLiveStateCache(
                stateService,
                new SimpleMeterRegistry(),
                Runnable::run,
                ticker
        );
        AuctionLiveState oldState = state(1L, 1L);
        AuctionLiveState newState = state(1L, 2L);
        AtomicInteger loadCount = new AtomicInteger();
        when(stateService.getStates(anyCollection())).thenAnswer(ignored ->
                loadCount.getAndIncrement() == 0 ? List.of(oldState) : List.of(newState)
        );
        expiringCache.getState(1L);

        // when
        ticker.advance(Duration.ofMillis(501));
        AuctionLiveState refreshed = expiringCache.getState(1L);

        // then
        assertThat(refreshed).isEqualTo(newState);
    }

    @Test
    void snapshot_조회의_도메인_예외는_구독_응답이_처리할_수_있게_그대로_전파한다() {
        // given
        AuctionException failure = new AuctionException(AUCTION_NOT_FOUND);
        when(stateService.getStates(anyCollection())).thenThrow(failure);

        // when & then
        assertThatThrownBy(() -> stateCache.getState(1L)).isSameAs(failure);
    }

    private AuctionLiveState state(long auctionId, long revision) {
        return new AuctionLiveState(
                auctionId,
                revision,
                AuctionType.UP,
                AuctionStatus.BID_ONGOING,
                120_000L + revision,
                revision
        );
    }

    private static final class MutableTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong(1L);

        @Override
        public long read() {
            return nanos.get();
        }

        void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
