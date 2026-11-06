package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionListResponse;
import com.tikitaka.bidwinback.global.config.AsyncConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionListServiceTest {

    @Mock
    private DownPriceSnapshotResolver snapshotResolver;

    @Mock
    private AuctionListDbQuery dbQuery;

    @Mock
    private DownPriceSnapshotPageAssembler pageAssembler;

    private final Executor directExecutor = Runnable::run;

    @Test
    void 캐시_대상이_아니면_기존_DB_조회를_그대로_사용한다() {
        AuctionListQuery query = query(AuctionType.UP, AuctionSort.PRICE_LOW);
        AuctionListResponse response = response();
        when(snapshotResolver.supports(query)).thenReturn(false);
        when(dbQuery.findPage(query)).thenReturn(response);

        CompletableFuture<AuctionListResponse> result = service().getList(query);

        assertThat(result).isCompletedWithValue(response);
        verify(snapshotResolver, never()).resolve(query);
    }

    @Test
    void 캐시_대상이면_Resolver_완료후_별도_Executor에서_페이지를_조립한다() {
        AuctionListQuery query = query(AuctionType.DOWN, AuctionSort.PRICE_LOW);
        ResolvedDownPriceSnapshotPage resolved = new ResolvedDownPriceSnapshotPage(
                new DownPriceSnapshotPage(
                        java.time.LocalDateTime.of(2026, 8, 18, 12, 0),
                        List.of(),
                        0
                ),
                java.time.LocalDateTime.of(2026, 8, 18, 12, 0, 1),
                1,
                null
        );
        AuctionListResponse response = response();
        when(snapshotResolver.supports(query)).thenReturn(true);
        when(snapshotResolver.resolve(query)).thenReturn(CompletableFuture.completedFuture(resolved));
        when(pageAssembler.assemble(resolved)).thenReturn(response);

        CompletableFuture<AuctionListResponse> result = service().getList(query);

        assertThat(result).isCompletedWithValue(response);
        verify(dbQuery, never()).findPage(query);
    }

    @Test
    void cold_miss_동시_대기_1000건도_executor_포화로_실패하지_않는다() throws Exception {
        AuctionListQuery query = query(AuctionType.DOWN, AuctionSort.PRICE_LOW);
        ResolvedDownPriceSnapshotPage resolved = new ResolvedDownPriceSnapshotPage(
                new DownPriceSnapshotPage(
                        java.time.LocalDateTime.of(2026, 8, 18, 12, 0),
                        List.of(),
                        0
                ),
                java.time.LocalDateTime.of(2026, 8, 18, 12, 0, 1),
                1,
                null
        );
        AuctionListResponse response = response();
        CompletableFuture<ResolvedDownPriceSnapshotPage> sharedSnapshot =
                new CompletableFuture<>();
        CountDownLatch saturated = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeAssemblies = new AtomicInteger();
        AtomicInteger maximumActiveAssemblies = new AtomicInteger();
        when(snapshotResolver.supports(query)).thenReturn(true);
        when(snapshotResolver.resolve(query)).thenReturn(sharedSnapshot);
        when(pageAssembler.assemble(resolved)).thenAnswer(invocation -> {
            int active = activeAssemblies.incrementAndGet();
            maximumActiveAssemblies.accumulateAndGet(active, Math::max);
            saturated.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("페이지 조립 대기 해제에 실패했습니다.");
                }
                return response;
            } finally {
                activeAssemblies.decrementAndGet();
            }
        });

        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor)
                new AsyncConfig().pageAssemblyExecutor();
        executor.initialize();
        try {
            AuctionListService service = new AuctionListService(
                    snapshotResolver,
                    dbQuery,
                    pageAssembler,
                    executor
            );
            List<CompletableFuture<AuctionListResponse>> results = IntStream.range(0, 1_000)
                    .mapToObj(ignored -> service.getList(query))
                    .toList();

            CompletableFuture<Void> snapshotCompletion = CompletableFuture.runAsync(
                    () -> sharedSnapshot.complete(resolved)
            );
            assertThat(saturated.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();

            CompletableFuture.allOf(results.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
            snapshotCompletion.get(10, TimeUnit.SECONDS);
            assertThat(results).allSatisfy(result ->
                    assertThat(result).isCompletedWithValue(response)
            );
            assertThat(maximumActiveAssemblies).hasValue(2);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private AuctionListService service() {
        return new AuctionListService(
                snapshotResolver,
                dbQuery,
                pageAssembler,
                directExecutor
        );
    }

    private AuctionListQuery query(AuctionType type, AuctionSort sort) {
        return new AuctionListQuery(type, sort, null, null, null, 1, null);
    }

    private AuctionListResponse response() {
        return new AuctionListResponse(List.of(), 1L, 1L, 1, false);
    }
}
