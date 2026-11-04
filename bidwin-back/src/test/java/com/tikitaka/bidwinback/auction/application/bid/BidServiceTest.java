package com.tikitaka.bidwinback.auction.application.bid;

import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.exception.BidException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PRICE_TOO_LOW;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INVALID_BID_UNIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.PRICE_LIMIT_EXCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 입찰 진입점(트랜잭션 밖)의 책임만 검증한다: 가격 검증, Redis 선점, 실패 뒤 가격 캐시 재동기화.
 * DB 트랜잭션 로직은 BidTransactionServiceTest가 다룬다.
 */
@ExtendWith(MockitoExtension.class)
class BidServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long AUCTION_ID = 42L;
    private static final long PRICE = 232_000L;
    private static final long ACTUAL_PRICE = 231_000L;
    private static final long PRICE_LIMIT = 100_000_000_000L;
    private static final BidResult RESULT = new BidResult(
            7L, AUCTION_ID, MEMBER_ID, PRICE, null, LocalDateTime.of(2026, 7, 30, 12, 0)
    );

    @Mock
    private BidTransactionService transactionService;

    @Mock
    private BidPriceCache bidPriceCache;

    @Mock
    private AuctionRepository auctionRepository;

    @InjectMocks
    private BidService bidService;

    @ParameterizedTest
    @EnumSource(BidType.class)
    void 공개입찰과_밀봉입찰은_1000억_원부터_거절한다(BidType bidType) {
        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE_LIMIT, bidType)
        );

        assertThat(exception.getErrorCode()).isEqualTo(PRICE_LIMIT_EXCEEDED);
        verifyNoInteractions(transactionService, bidPriceCache, auctionRepository);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1_000L, 232_500L})
    void 양수가_아니거나_천원_단위가_아닌_가격은_Redis와_DB를_거치지_않고_거절한다(long invalidPrice) {
        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, invalidPrice, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(INVALID_BID_UNIT);
        verifyNoInteractions(transactionService, bidPriceCache, auctionRepository);
    }

    @Test
    void Redis_선점에_지면_DB_트랜잭션을_열지_않고_거절한다() {
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(-1L);
        when(bidPriceCache.isLost(-1L)).thenReturn(true);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(BID_PRICE_TOO_LOW);
        verifyNoInteractions(transactionService, auctionRepository);
        verify(bidPriceCache, never()).resyncToActualPrice(any(), anyLong(), anyLong());
    }

    @Test
    void 선점에_이기고_입찰이_성공하면_결과를_그대로_돌려주고_재동기화하지_않는다() {
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(1L);
        when(transactionService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)).thenReturn(RESULT);

        BidResult result = bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN);

        assertSame(RESULT, result);
        verifyNoInteractions(auctionRepository);
        verify(bidPriceCache, never()).resyncToActualPrice(any(), anyLong(), anyLong());
    }

    @Test
    void 트랜잭션이_실패하면_트랜잭션이_끝난_뒤_DB_현재가로_재동기화하고_원래_예외를_던진다() {
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(1L);
        BidException failure = new BidException(BID_PRICE_TOO_LOW);
        when(transactionService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)).thenThrow(failure);
        when(auctionRepository.findCurrentPriceById(AUCTION_ID)).thenReturn(Optional.of(ACTUAL_PRICE));

        BidException thrown = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertSame(failure, thrown);
        InOrder order = inOrder(transactionService, auctionRepository, bidPriceCache);
        order.verify(transactionService).place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN);
        order.verify(auctionRepository).findCurrentPriceById(AUCTION_ID);
        order.verify(bidPriceCache).resyncToActualPrice(AUCTION_ID, PRICE, ACTUAL_PRICE);
    }

    @Test
    void Redis_응답이_없어_선점_여부를_몰라도_실패하면_재동기화를_시도한다() {
        // tryWinRace가 null(키 없음/Redis 예외)이어도 Redis가 SET을 실행했을 수 있어 안전하게 되돌린다.
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(null);
        when(transactionService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN))
                .thenThrow(new AuctionException(AUCTION_NOT_FOUND));
        when(auctionRepository.findCurrentPriceById(AUCTION_ID)).thenReturn(Optional.of(ACTUAL_PRICE));

        assertThrows(
                AuctionException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        verify(bidPriceCache).resyncToActualPrice(AUCTION_ID, PRICE, ACTUAL_PRICE);
    }

    @Test
    void 재동기화가_실패해도_원래_입찰_예외를_가리지_않는다() {
        when(bidPriceCache.tryWinRace(AUCTION_ID, PRICE)).thenReturn(1L);
        BidException failure = new BidException(BID_PRICE_TOO_LOW);
        when(transactionService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)).thenThrow(failure);
        when(auctionRepository.findCurrentPriceById(AUCTION_ID))
                .thenThrow(new IllegalStateException("db down"));

        BidException thrown = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertSame(failure, thrown);
    }

    @Test
    void 밀봉입찰은_Redis_선점도_재동기화도_하지_않는다() {
        BidException failure = new BidException(BID_PRICE_TOO_LOW);
        when(transactionService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED)).thenThrow(failure);

        assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED)
        );

        verify(bidPriceCache, never()).tryWinRace(any(), anyLong());
        verifyNoInteractions(auctionRepository);
    }
}
