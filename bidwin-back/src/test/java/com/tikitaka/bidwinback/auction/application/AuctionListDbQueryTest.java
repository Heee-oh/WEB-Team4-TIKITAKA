package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionListStatusFilter;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionListQueryRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListRow;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionListSearchCondition;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionListResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionSummaryResponse;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionListDbQueryTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime AS_OF = LocalDateTime.of(2026, 8, 1, 12, 0);
    private static final LocalDateTime SERVER_TIME = AS_OF.plusMinutes(1);

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionListQueryRepository auctionListQueryRepository;

    @Mock
    private AuctionPricePageQuery auctionPricePageQuery;

    @Mock
    private ImageUrlResolver imageUrlResolver;

    private AuctionListDbQuery auctionListDbQuery;

    @BeforeEach
    void setUp() {
        auctionListDbQuery = new AuctionListDbQuery(
                auctionRepository,
                auctionListQueryRepository,
                auctionPricePageQuery,
                new AuctionSummaryResponseMapper(imageUrlResolver)
        );
        when(auctionRepository.currentDatabaseTime()).thenReturn(SERVER_TIME);
    }

    @Test
    void projection을_UP_DOWN_경매_응답으로_매핑하고_썸네일_URL을_변환한다() {
        AuctionListRow up = upRow(1L, "up-thumbnail", 260_000L, 4L);
        AuctionListRow down = downRow(2L, "down-thumbnail", 170_000L);
        when(auctionListQueryRepository.findPage(any(), eq(0L), eq(17)))
                .thenReturn(List.of(up, down));
        when(imageUrlResolver.resolve("up-thumbnail")).thenReturn("https://cdn/up.jpg");
        when(imageUrlResolver.resolve("down-thumbnail")).thenReturn("https://cdn/down.jpg");

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1)
        );

        AuctionSummaryResponse upSummary = response.items().get(0);
        assertThat(upSummary.auctionId()).isEqualTo(1L);
        assertThat(upSummary.auctionType()).isEqualTo(AuctionType.UP);
        assertThat(upSummary.thumbnailUrl()).isEqualTo("https://cdn/up.jpg");
        assertThat(upSummary.currentPrice()).isEqualTo(260_000L);
        assertThat(upSummary.bidCount()).isEqualTo(4L);
        assertThat(upSummary.downPricing()).isNull();

        AuctionSummaryResponse downSummary = response.items().get(1);
        assertThat(downSummary.auctionId()).isEqualTo(2L);
        assertThat(downSummary.auctionType()).isEqualTo(AuctionType.DOWN);
        assertThat(downSummary.thumbnailUrl()).isEqualTo("https://cdn/down.jpg");
        assertThat(downSummary.currentPrice()).isEqualTo(170_000L);
        assertThat(downSummary.downPricing()).isNotNull();
        assertThat(downSummary.downPricing().minimumPrice()).isEqualTo(150_000L);
        assertThat(downSummary.downPricing().dropPrice()).isEqualTo(10_000L);
        assertThat(downSummary.downPricing().priceDropIntervalMs()).isEqualTo(600_000L);
        assertThat(downSummary.downPricing().startedAt())
                .isEqualTo(toEpochMilli(LocalDateTime.of(2026, 8, 1, 9, 0)));
    }

    @Test
    void DB_서버시각과_명시한_asOf를_응답과_조회조건에_사용한다() {
        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1, AS_OF)
        );

        assertThat(response.serverTime()).isEqualTo(toEpochMilli(SERVER_TIME));
        assertThat(response.asOf()).isEqualTo(toEpochMilli(AS_OF));
        verify(auctionListQueryRepository).findPage(
                eq(new AuctionListSearchCondition(null, AuctionSort.LATEST, null, AS_OF)),
                eq(0L),
                eq(17)
        );
        verify(auctionListQueryRepository, never()).count(any());
    }

    @Test
    void asOf가_없으면_DB_서버시각을_목록_스냅샷으로_사용한다() {
        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1, null)
        );

        assertThat(response.serverTime()).isEqualTo(toEpochMilli(SERVER_TIME));
        assertThat(response.asOf()).isEqualTo(toEpochMilli(SERVER_TIME));
        verify(auctionListQueryRepository).findPage(
                eq(new AuctionListSearchCondition(null, AuctionSort.LATEST, null, SERVER_TIME)),
                eq(0L),
                eq(17)
        );
    }

    @Test
    void 추천순은_요청_asOf를_무시하고_DB_현재시각을_사용한다() {
        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.RECOMMENDED, null, 1, AS_OF)
        );

        assertThat(response.serverTime()).isEqualTo(toEpochMilli(SERVER_TIME));
        assertThat(response.asOf()).isEqualTo(toEpochMilli(SERVER_TIME));
        verify(auctionListQueryRepository).findPage(
                eq(new AuctionListSearchCondition(
                        null,
                        AuctionSort.RECOMMENDED,
                        null,
                        SERVER_TIME
                )),
                eq(0L),
                eq(17)
        );
    }

    @Test
    void 상태와_카테고리를_저장소_조회조건에_전달한다() {
        auctionListDbQuery.findPage(new AuctionListQuery(
                AuctionType.DOWN,
                AuctionSort.LATEST,
                "의자",
                AuctionListStatusFilter.ENDED,
                AuctionCategory.FURNITURE,
                1,
                AS_OF
        ));

        verify(auctionListQueryRepository).findPage(
                eq(new AuctionListSearchCondition(
                        AuctionType.DOWN,
                        AuctionSort.LATEST,
                        "의자",
                        AuctionListStatusFilter.ENDED,
                        AuctionCategory.FURNITURE,
                        AS_OF
                )),
                eq(0L),
                eq(17)
        );
    }

    @Test
    void 페이지_크기보다_적게_조회되면_다음_페이지가_없다() {
        when(auctionListQueryRepository.findPage(any(), eq(0L), eq(17)))
                .thenReturn(List.of(upRow(1L, null, 100_000L, 0L), upRow(2L, null, 200_000L, 1L)));

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1)
        );

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isFalse();
        assertThat(response.page()).isEqualTo(1);
        verify(auctionListQueryRepository, never()).count(any());
    }

    @Test
    void 페이지_크기보다_한_건_더_조회되면_다음_페이지가_있고_응답은_페이지_크기만_담는다() {
        when(auctionListQueryRepository.findPage(any(), eq(0L), eq(17)))
                .thenReturn(rows(17));

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1)
        );

        assertThat(response.hasNext()).isTrue();
        assertThat(response.items()).hasSize(16);
        assertThat(response.items().getLast().auctionId()).isEqualTo(16L);
    }

    @Test
    void 페이지_크기만큼만_조회되면_마지막_페이지라서_다음_페이지가_없다() {
        when(auctionListQueryRepository.findPage(any(), eq(0L), eq(17)))
                .thenReturn(rows(16));

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1)
        );

        assertThat(response.hasNext()).isFalse();
        assertThat(response.items()).hasSize(16);
    }

    @Test
    void 상한_페이지는_다음_건이_있어도_다음_페이지가_없다() {
        when(auctionListQueryRepository.findPage(any(), eq(1_584L), eq(17)))
                .thenReturn(rows(17));

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 100)
        );

        assertThat(response.items()).hasSize(16);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    void 가격순은_16개씩_최대_100페이지_범위만_top_k_조회에_전달한다() {
        // given
        AuctionListSearchCondition condition = new AuctionListSearchCondition(
                null,
                AuctionSort.PRICE_LOW,
                null,
                AS_OF
        );
        when(auctionPricePageQuery.findPage(condition, 100, 16, 1_600L))
                .thenReturn(List.of(upRow(1L, null, 100_000L, 0L)));

        // when
        auctionListDbQuery.findPage(query(null, AuctionSort.PRICE_LOW, null, 101));

        // then
        verify(auctionPricePageQuery).findPage(condition, 100, 16, 1_600L);
        verify(auctionListQueryRepository, never()).findPage(any(), anyLong(), anyInt());
        verify(auctionListQueryRepository, never()).count(any());
    }

    @Test
    void page가_1보다_작으면_첫_페이지와_offset_0으로_보정한다() {
        when(auctionListQueryRepository.findPage(any(), eq(0L), eq(17)))
                .thenReturn(List.of(upRow(1L, null, 100_000L, 0L), upRow(2L, null, 200_000L, 1L)));

        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 0)
        );

        assertThat(response.page()).isEqualTo(1);
        verify(auctionListQueryRepository).findPage(any(), eq(0L), eq(17));
    }

    @Test
    void page가_100과_초과_page면_100페이지와_최대_offset으로_보정한다() {
        AuctionListResponse page100 = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 100)
        );
        AuctionListResponse page101 = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 101)
        );

        assertThat(page100.page()).isEqualTo(100);
        assertThat(page101.page()).isEqualTo(100);
        assertThat(page100.hasNext()).isFalse();
        assertThat(page101.hasNext()).isFalse();
        verify(auctionListQueryRepository, times(2))
                .findPage(any(), eq(1_584L), eq(17));
    }

    @Test
    void 결과가_없어도_상한_페이지_목록을_조회한다() {
        AuctionListResponse response = auctionListDbQuery.findPage(
                query(null, AuctionSort.LATEST, null, 1)
        );

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        verify(auctionListQueryRepository).findPage(any(), eq(0L), eq(17));
        verify(auctionListQueryRepository, never()).count(any());
    }

    private AuctionListQuery query(AuctionType type, AuctionSort sort, String keyword, int page) {
        return query(type, sort, keyword, page, AS_OF);
    }

    private AuctionListQuery query(
            AuctionType type,
            AuctionSort sort,
            String keyword,
            int page,
            LocalDateTime asOf
    ) {
        return new AuctionListQuery(type, sort, keyword, null, null, page, asOf);
    }

    private List<AuctionListRow> rows(long count) {
        return LongStream.rangeClosed(1L, count)
                .mapToObj(id -> upRow(id, null, 100_000L, 0L))
                .toList();
    }

    private AuctionListRow upRow(long id, String thumbnailObjectKey, long currentPrice, long bidCount) {
        return new AuctionListRow(
                id,
                AuctionType.UP,
                "상품" + id,
                "판매자" + id,
                AuctionCategory.HOUSEHOLD,
                thumbnailObjectKey,
                currentPrice,
                200_000L,
                bidCount,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                LocalDateTime.of(2026, 8, 1, 9, 0),
                AuctionStatus.BID_ONGOING,
                id,
                null,
                null,
                null,
                null
        );
    }

    private AuctionListRow downRow(long id, String thumbnailObjectKey, long currentPrice) {
        return new AuctionListRow(
                id,
                AuctionType.DOWN,
                "상품" + id,
                "판매자" + id,
                AuctionCategory.FOOD,
                thumbnailObjectKey,
                currentPrice,
                200_000L,
                0L,
                LocalDateTime.of(2026, 8, 1, 18, 0),
                LocalDateTime.of(2026, 8, 1, 9, 0),
                AuctionStatus.OPEN,
                id,
                150_000L,
                10_000L,
                10L,
                LocalDateTime.of(2026, 8, 1, 9, 0)
        );
    }

    private long toEpochMilli(LocalDateTime dateTime) {
        return dateTime.atZone(SEOUL).toInstant().toEpochMilli();
    }
}
