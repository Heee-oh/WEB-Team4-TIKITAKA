package com.tikitaka.bidwinback.auction.application.bid;

import com.tikitaka.bidwinback.auction.domain.entity.Auction;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidCreated;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionDeposit;
import com.tikitaka.bidwinback.auction.domain.entity.Bid;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.SealedBid;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.enums.BidStatus;
import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.domain.enums.DepositStatus;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.exception.BidException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionDepositRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.SealedBidRepository;
import com.tikitaka.bidwinback.auction.presentation.dto.response.BidHistoryItemResponse;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_ALREADY_ENDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_ONGOING;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PHASE_CHANGED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PRICE_TOO_LOW;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.CONCURRENT_BID_CONFLICT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INSUFFICIENT_DEPOSIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.NOT_UP_AUCTION;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SEALED_BID_ALREADY_SUBMITTED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SELF_BID_NOT_ALLOWED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BidTransactionServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long AUCTION_ID = 42L;
    private static final Long BID_ID = 7L;
    private static final long BID_UNIT = 1_000L;
    private static final long CURRENT_PRICE = 231_000L;
    private static final long PRICE = 232_000L;
    private static final long START_PRICE = 100_000L;
    private static final long DEPOSIT_AMOUNT = 30_000L;
    private static final LocalDateTime DATABASE_TIME =
            LocalDateTime.of(2026, 7, 30, 12, 34, 55);
    private static final LocalDateTime BID_AT = DATABASE_TIME.plusSeconds(1);
    private static final AuctionLiveState LIVE_STATE = new AuctionLiveState(
            AUCTION_ID, 3L, AuctionType.UP, AuctionStatus.BID_ONGOING, PRICE, 2L
    );

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionDepositRepository auctionDepositRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private SealedBidRepository sealedBidRepository;

    @Mock
    private AuctionLiveStateService liveStateService;

    @Mock
    private Member bidder;

    @Mock
    private Member seller;

    @Mock
    private UpAuction auction;

    @Mock
    private DownAuction downAuction;

    @Mock
    private Bid persistedBid;

    @Mock
    private SealedBid persistedSealedBid;

    @InjectMocks
    private BidTransactionService bidService;

    @BeforeEach
    void setUpLiveState() {
        lenient().when(liveStateService.snapshot(AUCTION_ID)).thenReturn(LIVE_STATE);
    }

    @Test
    void 조건부_현재가_갱신에_성공하면_UP_입찰을_저장한다() {
        stubSuccessfulUpdate();
        stubExistingDeposit();
        stubPersistedBid();
        when(bidRepository.save(any(Bid.class))).thenReturn(persistedBid);

        BidResult result = bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN);

        ArgumentCaptor<Bid> bidCaptor = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepository).save(bidCaptor.capture());
        verify(eventPublisher).publishEvent(new AuctionStateChanged(LIVE_STATE));
        verify(eventPublisher).publishEvent(new AuctionBidCreated(
                AUCTION_ID,
                BID_ID,
                new BidHistoryItemResponse(
                        "BID:" + BID_ID,
                        "입*자",
                        PRICE,
                        BID_AT.atZone(java.time.ZoneId.of("Asia/Seoul"))
                                .toInstant()
                                .toEpochMilli()
                )
        ));
        Bid saved = bidCaptor.getValue();
        assertAll(
                () -> assertThat(saved.getAuction()).isSameAs(auction),
                () -> assertThat(saved.getBidder()).isSameAs(bidder),
                () -> assertThat(saved.getPrice()).isEqualTo(PRICE),
                () -> assertThat(saved.getStatus()).isEqualTo(BidStatus.UP),
                () -> assertThat(result.bidId()).isEqualTo(BID_ID),
                () -> assertThat(result.auctionId()).isEqualTo(AUCTION_ID),
                () -> assertThat(result.bidderId()).isEqualTo(MEMBER_ID),
                () -> assertThat(result.price()).isEqualTo(PRICE),
                () -> assertThat(result.status()).isEqualTo(BidStatus.UP),
                () -> assertThat(result.bidAt()).isEqualTo(BID_AT)
        );
    }

    @Test
    void 첫_입찰이면_시작가의_30퍼센트를_보증금으로_예치한다() {
        stubSuccessfulUpdate();
        stubPersistedBid();
        when(auction.getStartPrice()).thenReturn(START_PRICE);
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, DEPOSIT_AMOUNT))
                .thenReturn(1);
        when(bidRepository.save(any(Bid.class))).thenReturn(persistedBid);

        bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN);

        ArgumentCaptor<AuctionDeposit> depositCaptor =
                ArgumentCaptor.forClass(AuctionDeposit.class);
        verify(auctionDepositRepository).save(depositCaptor.capture());
        AuctionDeposit deposit = depositCaptor.getValue();
        assertAll(
                () -> assertThat(deposit.getMember()).isSameAs(bidder),
                () -> assertThat(deposit.getAuction()).isSameAs(auction),
                () -> assertThat(deposit.getReservedAmount()).isEqualTo(DEPOSIT_AMOUNT),
                () -> assertThat(deposit.getStatus()).isEqualTo(DepositStatus.HELD)
        );
    }

    @Test
    void 이미_보증금이_있으면_추가로_포인트를_잠그지_않는다() {
        stubSuccessfulUpdate();
        stubExistingDeposit();
        stubPersistedBid();
        when(bidRepository.save(any(Bid.class))).thenReturn(persistedBid);

        bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN);

        verify(memberRepository, never()).movePointToLockedIfEnough(anyLong(), anyLong());
        verify(auctionDepositRepository, never()).save(any(AuctionDeposit.class));
    }

    @Test
    void 첫_입찰_보증금이_부족하면_입찰을_저장하지_않는다() {
        stubSuccessfulUpdate();
        when(auction.getStartPrice()).thenReturn(START_PRICE);
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, DEPOSIT_AMOUNT))
                .thenReturn(0);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(INSUFFICIENT_DEPOSIT);
        verify(auctionDepositRepository, never()).save(any(AuctionDeposit.class));
        verifyNoInteractions(bidRepository, sealedBidRepository);
    }

    @Test
    void 밀봉_구간이면_현재가를_갱신하지_않고_가격을_숨긴_밀봉입찰을_저장한다() {
        // given
        stubSuccessfulSealedBid();

        // when
        BidResult result = bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED);

        // then
        assertAll(
                () -> assertThat(result.bidId()).isEqualTo(BID_ID),
                () -> assertThat(result.price()).isNull(),
                () -> assertThat(result.status()).isEqualTo(BidStatus.SEALED),
                () -> assertThat(result.bidAt()).isEqualTo(BID_AT)
        );
        verify(auctionRepository, never()).updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        );
        verifyNoInteractions(bidRepository);
    }

    @Test
    void 첫_밀봉입찰은_공개_상태_변경_이벤트를_발행한다() {
        // given
        stubSuccessfulSealedBid();

        // when
        bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED);

        // then
        verify(eventPublisher).publishEvent(new AuctionStateChanged(LIVE_STATE));
    }

    @Test
    void 후속_밀봉입찰은_같은_revision의_상태_이벤트를_발행하지_않는다() {
        // given
        stubSuccessfulSubsequentSealedBid();

        // when
        BidResult result = bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED);

        // then
        assertThat(result.bidId()).isEqualTo(BID_ID);
        verifyNoInteractions(eventPublisher, liveStateService);
    }

    @Test
    void 같은_회원의_밀봉입찰이_이미_있으면_거절한다() {
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        )).thenReturn(1);
        when(auctionRepository.getReferenceById(AUCTION_ID)).thenReturn(auction);
        when(memberRepository.getReferenceById(MEMBER_ID)).thenReturn(bidder);
        stubExistingDeposit();
        when(sealedBidRepository.saveAndFlush(any(SealedBid.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate sealed bid"));

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED)
        );

        assertThat(exception.getErrorCode()).isEqualTo(SEALED_BID_ALREADY_SUBMITTED);
        verifyNoInteractions(bidRepository);
    }

    @Test
    void 일반입찰_요청_중_밀봉_구간으로_바뀌면_자동_전환하지_않고_거절한다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusMinutes(2));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(BID_PHASE_CHANGED);
        verify(auctionRepository, never()).tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        );
        verify(auctionRepository, never()).tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.BID_ONGOING.name(),
                0
        );
        verifyNoInteractions(memberRepository, bidRepository, sealedBidRepository);
    }

    @Test
    void 밀봉입찰을_요청했지만_아직_일반_구간이면_거절한다() {
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        )).thenReturn(0);
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusDays(1));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED)
        );

        assertThat(exception.getErrorCode()).isEqualTo(BID_PHASE_CHANGED);
        verify(auctionRepository, never()).updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        );
        verifyNoInteractions(memberRepository, bidRepository, sealedBidRepository);
    }

    @Test
    void 기존_밀봉_최고가보다_천원_이상_높지_않으면_거절한다() {
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        )).thenReturn(0);
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(auction));
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusMinutes(2));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        when(auction.hasCurrentPrice()).thenReturn(true);
        when(auction.getCurrentPrice()).thenReturn(CURRENT_PRICE);
        when(auction.getId()).thenReturn(AUCTION_ID);
        when(sealedBidRepository.findHighestPriceByAuctionId(AUCTION_ID))
                .thenReturn(PRICE);
        stubSeller(2L);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.SEALED)
        );

        assertThat(exception.getErrorCode()).isEqualTo(BID_PRICE_TOO_LOW);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 최신_현재가보다_천원_이상_높지_않으면_거절한다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusDays(1));
        when(auction.hasCurrentPrice()).thenReturn(true);
        when(auction.getCurrentPrice()).thenReturn(PRICE);
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        stubSeller(2L);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(BID_PRICE_TOO_LOW);
        verifyNoInteractions(memberRepository, bidRepository);
        // 실패한 입찰은 상태 이벤트를 만들지 않는다. Redis 가격 재동기화는 호출자(BidService)의 몫이다.
        verifyNoInteractions(eventPublisher, liveStateService);
    }

    @Test
    void 존재하지_않는_경매에는_입찰할_수_없다() {
        when(auctionRepository.updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        ))
                .thenReturn(0);
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.empty());

        AuctionException exception = assertThrows(
                AuctionException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(AUCTION_NOT_FOUND);
        verifyNoInteractions(memberRepository, bidRepository);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, liveStateService);
    }

    @Test
    void 하향_경매에는_입찰할_수_없다() {
        stubFailedUpdate(downAuction);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(NOT_UP_AUCTION);
        verifyNoInteractions(memberRepository, bidRepository);
        verify(bidRepository, never()).save(any());
        verifyNoInteractions(eventPublisher, liveStateService);
    }

    @Test
    void 진행_중이_아닌_경매에는_입찰할_수_없다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.COMPLETED);

        AuctionException exception = assertThrows(
                AuctionException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(AUCTION_NOT_ONGOING);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 종료된_경매에는_입찰할_수_없다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME);
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);

        AuctionException exception = assertThrows(
                AuctionException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(AUCTION_ALREADY_ENDED);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 판매자는_자신의_경매에_입찰할_수_없다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusDays(1));
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        stubSeller(MEMBER_ID);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(SELF_BID_NOT_ALLOWED);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 조건부_갱신은_실패했지만_재조회한_조건이_유효하면_동시_입찰_충돌이다() {
        stubFailedUpdate(auction);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getEndedAt()).thenReturn(DATABASE_TIME.plusDays(1));
        when(auction.hasCurrentPrice()).thenReturn(true);
        when(auction.getCurrentPrice()).thenReturn(CURRENT_PRICE);
        when(auctionRepository.currentDatabaseTime()).thenReturn(DATABASE_TIME);
        stubSeller(2L);

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(CONCURRENT_BID_CONFLICT);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 조건부_갱신의_락_획득에_실패하면_동시_입찰_충돌로_변환한다() {
        when(auctionRepository.updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        ))
                .thenThrow(new PessimisticLockingFailureException("lock timeout"));

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(CONCURRENT_BID_CONFLICT);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    @Test
    void 조건부_갱신_쿼리의_타임아웃은_동시_입찰_충돌로_변환한다() {
        when(auctionRepository.updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        ))
                .thenThrow(new QueryTimeoutException("update query timeout"));

        BidException exception = assertThrows(
                BidException.class,
                () -> bidService.place(MEMBER_ID, AUCTION_ID, PRICE, BidType.OPEN)
        );

        assertThat(exception.getErrorCode()).isEqualTo(CONCURRENT_BID_CONFLICT);
        verifyNoInteractions(memberRepository, bidRepository);
    }

    private void stubSuccessfulUpdate() {
        when(auctionRepository.updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        ))
                .thenReturn(1);
        when(auctionRepository.getReferenceById(AUCTION_ID)).thenReturn(auction);
        when(memberRepository.getReferenceById(MEMBER_ID)).thenReturn(bidder);
    }

    private void stubSuccessfulSealedBid() {
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        )).thenReturn(1);
        stubSuccessfulSealedBidPersistence();
    }

    private void stubSuccessfulSubsequentSealedBid() {
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.OPEN.name(),
                1
        )).thenReturn(0);
        when(auctionRepository.tryUpdateAuctionForSealedBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT,
                AuctionStatus.BID_ONGOING.name(),
                0
        )).thenReturn(1);
        stubSuccessfulSealedBidPersistence();
    }

    private void stubSuccessfulSealedBidPersistence() {
        when(auctionRepository.getReferenceById(AUCTION_ID)).thenReturn(auction);
        when(memberRepository.getReferenceById(MEMBER_ID)).thenReturn(bidder);
        stubExistingDeposit();
        when(sealedBidRepository.saveAndFlush(any(SealedBid.class)))
                .thenReturn(persistedSealedBid);
        when(persistedSealedBid.getId()).thenReturn(BID_ID);
        when(persistedSealedBid.getAuction()).thenReturn(auction);
        when(auction.getId()).thenReturn(AUCTION_ID);
        when(persistedSealedBid.getBidder()).thenReturn(bidder);
        when(bidder.getId()).thenReturn(MEMBER_ID);
        when(persistedSealedBid.getSubmittedAt()).thenReturn(BID_AT);
    }

    private void stubFailedUpdate(Auction failedAuction) {
        when(auctionRepository.updateCurrentPriceForBid(
                AUCTION_ID,
                MEMBER_ID,
                PRICE,
                BID_UNIT
        ))
                .thenReturn(0);
        when(auctionRepository.findById(AUCTION_ID)).thenReturn(Optional.of(failedAuction));
    }

    private void stubExistingDeposit() {
        when(auctionDepositRepository.existsByMemberIdAndAuctionId(
                MEMBER_ID,
                AUCTION_ID
        )).thenReturn(true);
    }

    private void stubSeller(long sellerId) {
        when(auction.getSeller()).thenReturn(seller);
        when(seller.getId()).thenReturn(sellerId);
    }

    private void stubPersistedBid() {
        when(persistedBid.getId()).thenReturn(BID_ID);
        when(persistedBid.getAuction()).thenReturn(auction);
        when(auction.getId()).thenReturn(AUCTION_ID);
        when(persistedBid.getBidder()).thenReturn(bidder);
        when(bidder.getId()).thenReturn(MEMBER_ID);
        when(bidder.getNickname()).thenReturn("입찰자");
        when(persistedBid.getPrice()).thenReturn(PRICE);
        when(persistedBid.getStatus()).thenReturn(BidStatus.UP);
        when(persistedBid.getCreatedAt()).thenReturn(BID_AT);
    }
}
