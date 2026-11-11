package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionListQueryRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListSearchCondition;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceCursor;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionPriceSnapshot;
import com.tikitaka.bidwinback.auction.domain.repository.dto.DownAuctionPriceCandidate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionPricePageQueryTest {

    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 10, 12, 0);

    @Mock
    private AuctionListQueryRepository auctionListQueryRepository;

    private AuctionPricePageQuery auctionPricePageQuery;

    @BeforeEach
    void setUp() {
        auctionPricePageQuery = new AuctionPricePageQuery(auctionListQueryRepository);
        lenient().when(auctionListQueryRepository.findRowsByPriceSnapshots(
                anyList(),
                any(LocalDateTime.class)
        ))
                .thenReturn(List.of());
    }

    @Test
    void 스냅샷_추출은_가격순_top_k를_상세_조회없이_반환한다() {
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        DownAuctionPriceCandidate higher = downCandidate(
                2L,
                20L,
                100L,
                10L,
                1L,
                2L
        );
        DownAuctionPriceCandidate lower = downCandidate(
                1L,
                50L,
                100L,
                10L,
                1L,
                5L
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(List.of(higher, lower));

        List<AuctionPriceSnapshot> snapshots = auctionPricePageQuery.findSnapshots(
                condition,
                2
        );

        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(1L, 2L);
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::sortPrice)
                .containsExactly(50L, 80L);
        verify(auctionListQueryRepository, never()).findRowsByPriceSnapshots(
                anyList(),
                any(LocalDateTime.class)
        );
    }

    @Test
    void 스냅샷_추출_범위가_0이하면_저장소를_호출하지_않고_빈_목록을_반환한다() {
        assertThat(auctionPricePageQuery.findSnapshots(
                downCondition(AuctionSort.PRICE_LOW),
                0
        )).isEmpty();

        verifyNoInteractions(auctionListQueryRepository);
    }

    @Test
    void 하향_낮은가격_top_k가_남은_최저가보다_낮으면_첫_배치에서_멈춘다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        List<DownAuctionPriceCandidate> firstBatch = IntStream.rangeClosed(1, 1_000)
                .mapToObj(price -> downCandidate(price, price, price))
                .toList();
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(firstBatch);

        // when
        auctionPricePageQuery.findPage(condition, 1, 16, 2_000);

        // then
        verify(auctionListQueryRepository, times(1)).findDownPriceCandidates(
                eq(condition),
                nullable(AuctionPriceCursor.class),
                eq(1_000)
        );
    }

    @Test
    void 하향_낮은가격_top_k를_확정하지_못하면_다음_커서_배치를_반영한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        List<DownAuctionPriceCandidate> firstBatch = IntStream.rangeClosed(1, 1_000)
                .mapToObj(price -> downCandidate(price, price, 10_000L))
                .toList();
        List<DownAuctionPriceCandidate> secondBatch = List.of(
                downCandidate(1_001L, 1_001L, 1_001L),
                downCandidate(1_002L, 1_002L, 1_002L)
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(firstBatch);
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                eq(new AuctionPriceCursor(1_000L, 1_000L)),
                eq(1_000)
        )).thenReturn(secondBatch);

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 1_002);

        // then: 페이지 2건과 다음 페이지 확인용 1건(1_000)까지 top-k가 3건이다.
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(1_001L, 1_002L, 1_000L);
    }

    @Test
    void 낮은가격_동률_그룹을_모두_반영하면_다음_가격대는_조회하지_않는다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        List<DownAuctionPriceCandidate> firstBatch = IntStream.range(0, 1_000)
                .mapToObj(index -> downCandidate(2_000L - index, 1_000L, 2_000L))
                .toList();
        AuctionPriceCursor cursor = new AuctionPriceCursor(1_000L, 1_001L);
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(firstBatch);
        when(auctionListQueryRepository.findRemainingDownPriceCandidatesAtBound(
                eq(condition),
                eq(cursor)
        )).thenReturn(List.of(
                downCandidate(1_000L, 1_000L, 1_000L),
                downCandidate(999L, 1_000L, 1_000L)
        ));

        // when: top-k는 페이지 1건과 다음 페이지 확인용 1건이라 2건이다. 경계 동률 그룹이 이를 채운다.
        auctionPricePageQuery.findPage(condition, 1, 1, 1_001);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(1_000L, 999L);
        verify(auctionListQueryRepository, times(1)).findDownPriceCandidates(
                eq(condition),
                nullable(AuctionPriceCursor.class),
                eq(1_000)
        );
    }

    @Test
    void 높은가격_동률_그룹을_모두_반영하면_다음_가격대는_조회하지_않는다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_HIGH);
        List<DownAuctionPriceCandidate> firstBatch = IntStream.range(0, 1_000)
                .mapToObj(index -> downCandidate(2_000L - index, 0L, 1_000L))
                .toList();
        AuctionPriceCursor cursor = new AuctionPriceCursor(1_000L, 1_001L);
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(firstBatch);
        when(auctionListQueryRepository.findRemainingDownPriceCandidatesAtBound(
                eq(condition),
                eq(cursor)
        )).thenReturn(List.of(downCandidate(1_000L, 0L, 1_000L)));

        // when
        auctionPricePageQuery.findPage(condition, 1, 1, 1_001);

        // then: 페이지 1건과 다음 페이지 확인용 1건이다.
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(2_000L, 1_999L);
        verify(auctionListQueryRepository, times(1)).findDownPriceCandidates(
                eq(condition),
                nullable(AuctionPriceCursor.class),
                eq(1_000)
        );
    }

    @Test
    void 하향_높은가격순은_시작가를_상한으로_현재가_top_k를_고른다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_HIGH);
        List<DownAuctionPriceCandidate> candidates = List.of(
                downCandidate(1L, 0L, 1_000L, 100L, 10L, 90L),
                downCandidate(2L, 0L, 900L, 100L, 10L, 10L),
                downCandidate(3L, 0L, 800L, 100L, 10L, 10L)
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(candidates);

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 3);

        // then: 페이지 2건과 다음 페이지 확인용 1건이다.
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(2L, 3L, 1L);
    }

    @Test
    void 하향_높은가격_top_k를_확정하지_못하면_시작가_커서로_이어_조회한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_HIGH);
        List<DownAuctionPriceCandidate> firstBatch = IntStream.range(0, 1_000)
                .mapToObj(index -> {
                    long startPrice = 2_000L - index;
                    return downCandidate(
                            startPrice,
                            0L,
                            startPrice,
                            startPrice - 100L,
                            1L,
                            1L
                    );
                })
                .toList();
        AuctionPriceCursor cursor = new AuctionPriceCursor(1_001L, 1_001L);
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(firstBatch);
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                eq(cursor),
                eq(1_000)
        )).thenReturn(List.of(downCandidate(1_000L, 0L, 1_000L)));

        // when
        auctionPricePageQuery.findPage(condition, 1, 1, 1_001);

        // then
        verify(auctionListQueryRepository).findDownPriceCandidates(
                eq(condition),
                eq(cursor),
                eq(1_000)
        );
    }

    @Test
    void 상향_높은가격순은_current_price_스냅샷을_추가계산없이_사용한다() {
        // given
        AuctionListSearchCondition condition = upCondition(AuctionSort.PRICE_HIGH);
        when(auctionListQueryRepository.findUpPriceSnapshots(condition, 3))
                .thenReturn(List.of(
                        new AuctionPriceSnapshot(2L, 800L, 800L),
                        new AuctionPriceSnapshot(3L, 700L, 700L)
                ));

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 3);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::displayPrice)
                .containsExactly(800L, 700L);
    }

    @Test
    void 상향_낮은가격순도_current_price로_판단하고_하향_top_k를_조회하지_않는다() {
        // given
        AuctionListSearchCondition condition = upCondition(AuctionSort.PRICE_LOW);
        when(auctionListQueryRepository.findUpPriceSnapshots(condition, 2))
                .thenReturn(List.of(
                        new AuctionPriceSnapshot(1L, 100L, 100L),
                        new AuctionPriceSnapshot(2L, 200L, 200L)
                ));

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 2);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::displayPrice)
                .containsExactly(100L, 200L);
        verify(auctionListQueryRepository, never()).findDownPriceCandidates(
                any(),
                nullable(AuctionPriceCursor.class),
                eq(1_000)
        );
    }

    @Test
    void 다음_페이지가_있으면_페이지_크기보다_한_건_더_반환한다() {
        // given
        AuctionListSearchCondition condition = upCondition(AuctionSort.PRICE_LOW);
        when(auctionListQueryRepository.findUpPriceSnapshots(condition, 3))
                .thenReturn(List.of(
                        new AuctionPriceSnapshot(1L, 100L, 100L),
                        new AuctionPriceSnapshot(2L, 200L, 200L),
                        new AuctionPriceSnapshot(3L, 300L, 300L)
                ));

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 1_000);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(1L, 2L, 3L);
    }

    @Test
    void 후보_상한까지만_읽으면_페이지_크기를_넘기지_않는다() {
        // given: 2페이지(size 2)의 후보 상한이 4건이라 다음 페이지 확인용 한 건을 읽을 수 없다.
        AuctionListSearchCondition condition = upCondition(AuctionSort.PRICE_LOW);
        when(auctionListQueryRepository.findUpPriceSnapshots(condition, 4))
                .thenReturn(List.of(
                        new AuctionPriceSnapshot(1L, 100L, 100L),
                        new AuctionPriceSnapshot(2L, 200L, 200L),
                        new AuctionPriceSnapshot(3L, 300L, 300L),
                        new AuctionPriceSnapshot(4L, 400L, 400L)
                ));

        // when
        auctionPricePageQuery.findPage(condition, 2, 2, 4);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(3L, 4L);
    }

    @Test
    void 상향_current_price와_하향_top_k를_합쳐_전체_가격순을_만든다() {
        // given
        AuctionListSearchCondition condition = condition(null, AuctionSort.PRICE_HIGH);
        when(auctionListQueryRepository.findUpPriceSnapshots(condition, 3))
                .thenReturn(List.of(
                        new AuctionPriceSnapshot(1L, 500L, 500L),
                        new AuctionPriceSnapshot(2L, 300L, 300L)
                ));
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(List.of(
                downCandidate(3L, 0L, 400L),
                downCandidate(4L, 0L, 200L)
        ));

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 4);

        // then: 페이지 2건과 다음 페이지 확인용 1건이다.
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(1L, 3L, 2L);
    }

    @Test
    void 상향_가격순_페이지_1_100은_최대_100페이지_top_k를_사용한다() {
        int size = 2;
        long candidateCountLimit = 100L * size;

        for (AuctionSort sort : List.of(AuctionSort.PRICE_LOW, AuctionSort.PRICE_HIGH)) {
            AuctionListSearchCondition condition = upCondition(sort);
            for (int page : List.of(1, 100)) {
                auctionPricePageQuery.findPage(
                        condition,
                        page,
                        size,
                        candidateCountLimit
                );
            }

            // 첫 페이지는 다음 페이지 확인용 한 건을 더 읽고, 마지막 페이지는 후보 상한까지만 읽는다.
            verify(auctionListQueryRepository).findUpPriceSnapshots(condition, size + 1);
            verify(auctionListQueryRepository)
                    .findUpPriceSnapshots(condition, (int) candidateCountLimit);
        }
    }

    @Test
    void 하향과_ALL_가격순_페이지_1_100은_두_경매_유형_분기를_유지한다() {
        int size = 2;
        long candidateCountLimit = 100L * size;

        for (AuctionType type : new AuctionType[]{AuctionType.DOWN, null}) {
            for (AuctionSort sort : List.of(AuctionSort.PRICE_LOW, AuctionSort.PRICE_HIGH)) {
                AuctionListSearchCondition condition = condition(type, sort);
                when(auctionListQueryRepository.findDownPriceCandidates(
                        eq(condition),
                        isNull(),
                        eq(1_000)
                )).thenReturn(List.of());

                for (int page : List.of(1, 100)) {
                    auctionPricePageQuery.findPage(
                            condition,
                            page,
                            size,
                            candidateCountLimit
                    );
                }

                verify(auctionListQueryRepository, times(2)).findDownPriceCandidates(
                        eq(condition),
                        isNull(),
                        eq(1_000)
                );
                if (type == null) {
                    verify(auctionListQueryRepository).findUpPriceSnapshots(condition, size + 1);
                    verify(auctionListQueryRepository)
                            .findUpPriceSnapshots(condition, (int) candidateCountLimit);
                } else {
                    verify(auctionListQueryRepository, never())
                            .findUpPriceSnapshots(eq(condition), anyInt());
                }
            }
        }
    }

    @Test
    void N페이지는_N곱하기_페이지크기_top_k에서_해당_페이지만_조회한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        List<DownAuctionPriceCandidate> candidates = IntStream.rangeClosed(1, 40)
                .mapToObj(price -> downCandidate(price, price, price))
                .toList();
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(candidates);

        // when
        auctionPricePageQuery.findPage(condition, 2, 16, 40);

        // then: 2페이지 16건(17~32)과 다음 페이지 확인용 1건(33)이다.
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactlyElementsOf(
                        IntStream.rangeClosed(17, 33).mapToObj(Long::valueOf).toList()
                );
    }

    @Test
    void 하향_경매_현재가는_조회_asOf를_기준으로_계산한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        DownAuctionPriceCandidate downCandidate = downCandidate(
                1L,
                40_000L,
                100_000L,
                10_000L,
                10L,
                20L
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(List.of(downCandidate));

        // when
        auctionPricePageQuery.findPage(condition, 1, 1, 1);

        // then
        assertThat(capturedSnapshots())
                .extracting(AuctionPriceSnapshot::displayPrice)
                .containsExactly(80_000L);
    }

    @Test
    void 하향_스냅샷_이후_완료된_경매는_정렬가는_asOf_계산가를_표시가는_낙찰가를_사용한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        DownAuctionPriceCandidate completedAfterAsOf = new DownAuctionPriceCandidate(
                1L,
                100_000L,
                40_000L,
                AS_OF.minusMinutes(10),
                AS_OF.plusHours(1),
                10_000L,
                5L,
                AuctionStatus.COMPLETED,
                AS_OF.plusMinutes(1),
                60_000L
        );
        DownAuctionPriceCandidate ongoing = new DownAuctionPriceCandidate(
                2L,
                100_000L,
                40_000L,
                AS_OF.minusMinutes(15),
                AS_OF.plusHours(1),
                10_000L,
                5L,
                AuctionStatus.OPEN,
                null,
                null
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(List.of(completedAfterAsOf, ongoing));

        // when
        auctionPricePageQuery.findPage(condition, 1, 2, 2);

        // then
        List<AuctionPriceSnapshot> snapshots = capturedSnapshots();
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::auctionId)
                .containsExactly(2L, 1L);
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::sortPrice)
                .containsExactly(70_000L, 80_000L);
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::displayPrice)
                .containsExactly(70_000L, 60_000L);
    }

    @Test
    void 하향_미완료_경매는_asOf가_endedAt을_지나도_endedAt_가격을_사용한다() {
        // given
        AuctionListSearchCondition condition = downCondition(AuctionSort.PRICE_LOW);
        DownAuctionPriceCandidate ended = new DownAuctionPriceCandidate(
                1L,
                100_000L,
                20_000L,
                AS_OF.minusMinutes(5),
                AS_OF.minusMinutes(1),
                10_000L,
                5L,
                AuctionStatus.OPEN,
                null,
                null
        );
        when(auctionListQueryRepository.findDownPriceCandidates(
                eq(condition),
                isNull(),
                eq(1_000)
        )).thenReturn(List.of(ended));

        // when
        auctionPricePageQuery.findPage(condition, 1, 1, 1);

        // then
        List<AuctionPriceSnapshot> snapshots = capturedSnapshots();
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::sortPrice)
                .containsExactly(100_000L);
        assertThat(snapshots)
                .extracting(AuctionPriceSnapshot::displayPrice)
                .containsExactly(100_000L);
    }

    private List<AuctionPriceSnapshot> capturedSnapshots() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuctionPriceSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(auctionListQueryRepository).findRowsByPriceSnapshots(
                captor.capture(),
                eq(AS_OF)
        );
        return captor.getValue();
    }

    private AuctionListSearchCondition downCondition(AuctionSort sort) {
        return condition(AuctionType.DOWN, sort);
    }

    private AuctionListSearchCondition upCondition(AuctionSort sort) {
        return condition(AuctionType.UP, sort);
    }

    private AuctionListSearchCondition condition(AuctionType type, AuctionSort sort) {
        return new AuctionListSearchCondition(type, sort, null, AS_OF);
    }

    private DownAuctionPriceCandidate downCandidate(
            long id,
            long minimumPrice,
            long startPrice
    ) {
        return downCandidate(id, minimumPrice, startPrice, 1L, 1L, 0L);
    }

    private DownAuctionPriceCandidate downCandidate(
            long id,
            long minimumPrice,
            long startPrice,
            long dropPrice,
            long priceDropInterval,
            long elapsedMinutes
    ) {
        return new DownAuctionPriceCandidate(
                id,
                startPrice,
                minimumPrice,
                AS_OF.minusMinutes(elapsedMinutes),
                AS_OF.plusDays(1),
                dropPrice,
                priceDropInterval,
                AuctionStatus.OPEN,
                null,
                null
        );
    }
}
