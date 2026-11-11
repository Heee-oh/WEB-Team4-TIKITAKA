package com.tikitaka.bidwinback.auction.application.live;

import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionLiveStateRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionLiveStateServiceTest {

    private static final LocalDateTime DATABASE_TIME = LocalDateTime.of(2026, 8, 4, 12, 0);

    @Mock
    private AuctionRepository auctionRepository;

    private AuctionLiveStateService stateService;

    @BeforeEach
    void setUp() {
        stateService = new AuctionLiveStateService(auctionRepository);
    }

    @Test
    void 진행중_경매는_revision과_현재가와_입찰수를_절대_상태로_만든다() {
        // given
        stubRows(Row.up(1L).status(AuctionStatus.BID_ONGOING).revision(7L)
                .currentPrice(145_000L).bidCount(4L));

        // when
        AuctionLiveState state = stateService.getStates(List.of(1L)).getFirst();

        // then
        assertThat(state).isEqualTo(new AuctionLiveState(
                1L,
                7L,
                AuctionType.UP,
                AuctionStatus.BID_ONGOING,
                145_000L,
                4L
        ));
    }

    @Test
    void 공개된_밀봉_경매는_상세와_같이_밀봉_입찰수까지_합산한다() {
        stubRows(Row.up(1L).status(AuctionStatus.UNSOLD).bidCount(3L).sealedBidCount(2L));

        assertThat(stateService.getStates(List.of(1L)).getFirst().bidCount()).isEqualTo(5L);
    }

    @Test
    void 공개되지_않은_밀봉_경매는_밀봉_입찰수를_노출하지_않는다() {
        stubRows(Row.up(1L).status(AuctionStatus.BID_ONGOING).bidCount(3L).sealedBidCount(2L));

        assertThat(stateService.getStates(List.of(1L)).getFirst().bidCount()).isEqualTo(3L);
    }

    @Test
    void 하향_경매는_DB_시각_기준으로_계산한_가격을_DOWN_타입으로_알린다() {
        // given
        // 시작가 100,000에서 10분마다 5,000 하락, 최저가 70,000. 시작 25분 후면 2번 하락한다.
        stubRows(Row.down(2L).status(AuctionStatus.OPEN)
                .startedAt(DATABASE_TIME.minusMinutes(25))
                .endedAt(DATABASE_TIME.plusHours(1)));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);

        // when
        AuctionLiveState state = stateService.getStates(List.of(2L)).getFirst();

        // then
        assertThat(state.auctionType()).isEqualTo(AuctionType.DOWN);
        assertThat(state.currentPrice()).isEqualTo(90_000L);
    }

    @Test
    void 마감이_지난_하향_경매는_마감_시각의_가격에서_멈춘다() {
        // given
        // 시작 후 120분이 지났지만 마감은 시작 30분 시점이었다. 3번 하락한 85,000에서 멈춘다.
        stubRows(Row.down(2L).status(AuctionStatus.OPEN)
                .startedAt(DATABASE_TIME.minusMinutes(120))
                .endedAt(DATABASE_TIME.minusMinutes(90)));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);

        // when
        AuctionLiveState state = stateService.getStates(List.of(2L)).getFirst();

        // then
        assertThat(state.currentPrice()).isEqualTo(85_000L);
    }

    @Test
    void 완료된_경매는_경매_행이_아니라_확정_거래가를_현재가로_보낸다() {
        stubRows(Row.up(1L).status(AuctionStatus.COMPLETED).revision(8L)
                .currentPrice(1L).finalPrice(180_000L));

        AuctionLiveState state = stateService.getStates(List.of(1L)).getFirst();

        assertThat(state.currentPrice()).isEqualTo(180_000L);
        assertThat(state.revision()).isEqualTo(8L);
    }

    @Test
    void 완료된_경매에_확정_거래가가_없으면_추정값을_SSE로_보내지_않는다() {
        stubRows(Row.up(1L).status(AuctionStatus.COMPLETED));

        assertThatThrownBy(() -> stateService.getStates(List.of(1L)).getFirst())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 상향_경매만_조회하면_하향_가격용_DB_시각을_조회하지_않는다() {
        stubRows(Row.up(1L).status(AuctionStatus.BID_ONGOING));

        stateService.getStates(List.of(1L)).getFirst();

        verify(auctionRepository, never()).currentDatabaseTime();
    }

    @Test
    void 목록_조회는_요청한_순서를_유지한다() {
        // 조회 결과의 순서가 달라도 호출자가 넘긴 순서대로 돌려준다.
        when(auctionRepository.findLiveStateRows(List.of(3L, 1L, 2L))).thenReturn(List.of(
                Row.up(1L).status(AuctionStatus.OPEN),
                Row.up(2L).status(AuctionStatus.OPEN),
                Row.up(3L).status(AuctionStatus.OPEN)
        ));

        List<AuctionLiveState> states = stateService.getStates(List.of(3L, 1L, 2L));

        assertThat(states).extracting(AuctionLiveState::auctionId).containsExactly(3L, 1L, 2L);
    }

    @Test
    void 목록_조회에_존재하지_않는_경매가_섞이면_구독을_거부한다() {
        when(auctionRepository.findLiveStateRows(List.of(1L, 2L)))
                .thenReturn(List.of(Row.up(1L).status(AuctionStatus.OPEN)));

        assertThatThrownBy(() -> stateService.getStates(List.of(1L, 2L)))
                .isInstanceOf(AuctionException.class);
    }

    @Test
    void 빈_목록은_조회하지_않는다() {
        assertThat(stateService.getStates(List.of())).isEmpty();

        verify(auctionRepository, never()).findLiveStateRows(List.of());
    }

    @Test
    void 쓰기_트랜잭션_안_snapshot은_트랜잭션을_필수로_하고_트랜잭션_밖_조회는_새_읽기_트랜잭션을_연다() throws Exception {
        // snapshot을 트랜잭션 밖(예: AFTER_COMMIT 콜백)에서 부르면 즉시 실패해야 오용이 드러난다.
        assertThat(propagationOf("snapshot", long.class)).isEqualTo(Propagation.MANDATORY);
        assertThat(propagationOf("snapshots", java.util.Collection.class)).isEqualTo(Propagation.MANDATORY);
        assertThat(propagationOf("getStates", java.util.Collection.class)).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void heartbeat_기준_시각은_데이터베이스_시각을_epoch_밀리초로_제공한다() {
        // given
        LocalDateTime databaseTime = LocalDateTime.of(2026, 8, 5, 12, 30);
        when(auctionRepository.currentDatabaseTime()).thenReturn(databaseTime);

        // when
        long serverTime = stateService.getDatabaseTimeMillis();

        // then
        assertThat(serverTime).isEqualTo(databaseTime
                .atZone(ZoneId.of("Asia/Seoul"))
                .toInstant()
                .toEpochMilli());
    }

    private Propagation propagationOf(String method, Class<?>... parameterTypes) throws Exception {
        Transactional transactional = AnnotatedElementUtils.findMergedAnnotation(
                AuctionLiveStateService.class.getMethod(method, parameterTypes),
                Transactional.class
        );
        assertThat(transactional).isNotNull();
        return transactional.propagation();
    }

    private void stubRows(Row row) {
        when(auctionRepository.findLiveStateRows(List.of(row.auctionId)))
                .thenReturn(List.of(row));
    }

    /** 시작가 100,000 / 하락 5,000 / 최저가 70,000 / 하락 주기 10분인 경매 행. */
    private static final class Row implements AuctionLiveStateRow {

        private final long auctionId;
        private final String type;
        private String status = "OPEN";
        private long revision;
        private long currentPrice = 100_000L;
        private long bidCount;
        private long sealedBidCount;
        private LocalDateTime startedAt = DATABASE_TIME.minusHours(1);
        private LocalDateTime endedAt = DATABASE_TIME.plusHours(1);
        private Long finalPrice;

        private Row(long auctionId, String type) {
            this.auctionId = auctionId;
            this.type = type;
        }

        static Row up(long auctionId) {
            return new Row(auctionId, "UP");
        }

        static Row down(long auctionId) {
            return new Row(auctionId, "DOWN");
        }

        Row status(AuctionStatus status) {
            this.status = status.name();
            return this;
        }

        Row revision(long revision) {
            this.revision = revision;
            return this;
        }

        Row currentPrice(long currentPrice) {
            this.currentPrice = currentPrice;
            return this;
        }

        Row bidCount(long bidCount) {
            this.bidCount = bidCount;
            return this;
        }

        Row sealedBidCount(long sealedBidCount) {
            this.sealedBidCount = sealedBidCount;
            return this;
        }

        Row startedAt(LocalDateTime startedAt) {
            this.startedAt = startedAt;
            return this;
        }

        Row endedAt(LocalDateTime endedAt) {
            this.endedAt = endedAt;
            return this;
        }

        Row finalPrice(long finalPrice) {
            this.finalPrice = finalPrice;
            return this;
        }

        @Override
        public long getAuctionId() {
            return auctionId;
        }

        @Override
        public String getAuctionType() {
            return type;
        }

        @Override
        public String getStatus() {
            return status;
        }

        @Override
        public long getRevision() {
            return revision;
        }

        @Override
        public long getCurrentPrice() {
            return currentPrice;
        }

        @Override
        public long getBidCount() {
            return bidCount;
        }

        @Override
        public long getSealedBidCount() {
            return sealedBidCount;
        }

        @Override
        public long getStartPrice() {
            return 100_000L;
        }

        @Override
        public LocalDateTime getStartedAt() {
            return startedAt;
        }

        @Override
        public LocalDateTime getEndedAt() {
            return endedAt;
        }

        @Override
        public Long getMinimumPrice() {
            return "DOWN".equals(type) ? 70_000L : null;
        }

        @Override
        public Long getDropPrice() {
            return "DOWN".equals(type) ? 5_000L : null;
        }

        @Override
        public Long getPriceDropInterval() {
            return "DOWN".equals(type) ? 10L : null;
        }

        @Override
        public Long getFinalPrice() {
            return finalPrice;
        }
    }
}
