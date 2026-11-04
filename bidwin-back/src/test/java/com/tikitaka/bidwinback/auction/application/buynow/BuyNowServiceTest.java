package com.tikitaka.bidwinback.auction.application.buynow;

import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionDeposit;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionTrade;
import com.tikitaka.bidwinback.auction.domain.entity.Bid;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.InstantPurchaseRequest;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.enums.BidStatus;
import com.tikitaka.bidwinback.auction.domain.enums.DepositStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.exception.BidException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionDepositRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.InstantPurchaseRequestRepository;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import com.tikitaka.bidwinback.member.domain.exception.MemberException;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_ALREADY_TRADED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_ONGOING;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.CONCURRENT_TRADE_CONFLICT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INSUFFICIENT_DEPOSIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.MEMBER_NOT_ACTIVE;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.PRICE_LIMIT_EXCEEDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SELF_PURCHASE_NOT_ALLOWED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.UP_BUY_NOW_CLOSED_NEAR_DEADLINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BuyNowServiceTest {

    private static final Long MEMBER_ID = 1L;
    private static final Long SELLER_ID = 2L;
    private static final Long AUCTION_ID = 42L;
    private static final Long OTHER_AUCTION_ID = 43L;
    private static final String IDEMPOTENCY_KEY = "buy-now-42-request-1";
    private static final LocalDateTime DATABASE_TIME =
            LocalDateTime.of(2026, 7, 30, 12, 0);
    private static final LocalDateTime ENDED_AT = DATABASE_TIME.plusHours(1);
    private static final LocalDateTime PURCHASED_AT =
            DATABASE_TIME.plusSeconds(1);
    private static final long FINAL_PRICE = 232_000L;
    private static final AuctionLiveState LIVE_STATE = new AuctionLiveState(
            AUCTION_ID, 5L, AuctionType.DOWN, AuctionStatus.COMPLETED, FINAL_PRICE, 1L
    );

    @Mock
    private MemberRepository memberRepository;

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private AuctionDepositRepository auctionDepositRepository;

    @Mock
    private AuctionTradeRepository auctionTradeRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private InstantPurchaseRequestRepository requestRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private BuyNowPriceCalculator priceCalculator;

    @Mock
    private AuctionLiveStateService liveStateService;

    @Mock
    private BuyNowTransactionService transactionBoundary;

    @Mock
    private Member buyer;

    @Mock
    private Member seller;

    @Mock
    private DownAuction auction;

    @Mock
    private UpAuction upAuction;

    @Mock
    private InstantPurchaseRequest request;

    @Mock
    private AuctionTrade persistedTrade;

    private BuyNowTransactionService transactionService;
    private BuyNowService buyNowService;

    @BeforeEach
    void setUp() {
        transactionService = new BuyNowTransactionService(
                memberRepository,
                auctionRepository,
                auctionDepositRepository,
                auctionTradeRepository,
                bidRepository,
                requestRepository,
                eventPublisher,
                liveStateService
        );
        lenient().when(liveStateService.snapshot(AUCTION_ID)).thenReturn(LIVE_STATE);
        buyNowService = new BuyNowService(
                auctionRepository,
                priceCalculator,
                transactionBoundary
        );
    }

    @ParameterizedTest
    @EnumSource(value = BidStatus.class, names = {"BUY_NOW", "DOWN"})
    void 서비스가_정한_입찰_상태로_입찰_기록을_생성한다(
            BidStatus bidStatus
    ) {
        // given
        stubReadyToPurchase();
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE))
                .thenReturn(1);
        when(auctionRepository.completeForBuyNow(
                AUCTION_ID,
                MEMBER_ID,
                FINAL_PRICE,
                PURCHASED_AT
        )).thenReturn(1);
        stubPersistedTrade();
        when(auctionTradeRepository.save(any(AuctionTrade.class)))
                .thenReturn(persistedTrade);

        // when
        transactionService.buy(command(
                AUCTION_ID,
                FINAL_PRICE,
                bidStatus
        ));

        // then
        ArgumentCaptor<Bid> bidCaptor = ArgumentCaptor.forClass(Bid.class);
        verify(bidRepository).save(bidCaptor.capture());
        Bid bid = bidCaptor.getValue();
        assertAll(
                () -> assertThat(bid.getAuction()).isSameAs(auction),
                () -> assertThat(bid.getBidder()).isSameAs(buyer),
                () -> assertThat(bid.getPrice()).isEqualTo(FINAL_PRICE),
                () -> assertThat(bid.getStatus()).isEqualTo(bidStatus)
        );
    }

    @Test
    void 즉시구매가는_1000억_원부터_거절한다() {
        // given
        BuyNowCommand command = command(
                AUCTION_ID,
                100_000_000_000L,
                BidStatus.BUY_NOW
        );

        // when
        BidException exception = assertThrows(
                BidException.class,
                () -> transactionService.buy(command)
        );

        // then
        assertThat(exception.getErrorCode()).isEqualTo(PRICE_LIMIT_EXCEEDED);
        verifyNoInteractions(
                memberRepository,
                auctionRepository,
                auctionDepositRepository,
                auctionTradeRepository,
                bidRepository,
                requestRepository,
                eventPublisher
        );
    }

    @Test
    void OPEN_경매를_ACTIVE_회원이_낙찰가_전액을_잠그고_즉시구매한다() {
        stubReadyToPurchase();
        when(auctionRepository.completeForBuyNow(
                AUCTION_ID,
                MEMBER_ID,
                FINAL_PRICE,
                PURCHASED_AT
        ))
                .thenReturn(1);
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE))
                .thenReturn(1);
        stubPersistedTrade();
        when(auctionTradeRepository.save(any(AuctionTrade.class)))
                .thenReturn(persistedTrade);

        BuyNowResult result = buyInTransaction();

        ArgumentCaptor<AuctionTrade> tradeCaptor =
                ArgumentCaptor.forClass(AuctionTrade.class);
        verify(auctionTradeRepository).save(tradeCaptor.capture());
        AuctionTrade trade = tradeCaptor.getValue();
        ArgumentCaptor<AuctionDeposit> depositCaptor =
                ArgumentCaptor.forClass(AuctionDeposit.class);
        verify(auctionDepositRepository).save(depositCaptor.capture());
        AuctionDeposit deposit = depositCaptor.getValue();
        assertAll(
                () -> assertThat(trade.getAuction()).isSameAs(auction),
                () -> assertThat(trade.getBuyer()).isSameAs(buyer),
                () -> assertThat(trade.getStatus())
                        .isEqualTo(TradeStatus.CONFIRMED),
                () -> assertThat(trade.getFinalPrice()).isEqualTo(FINAL_PRICE),
                () -> assertThat(trade.getPurchasedAt()).isEqualTo(PURCHASED_AT),
                () -> assertThat(deposit.getMember()).isSameAs(buyer),
                () -> assertThat(deposit.getAuction()).isSameAs(auction),
                () -> assertThat(deposit.getReservedAmount()).isEqualTo(FINAL_PRICE),
                () -> assertThat(deposit.getStatus()).isEqualTo(DepositStatus.HELD),
                () -> assertThat(result.tradeId()).isEqualTo(7L),
                () -> assertThat(result.auctionId()).isEqualTo(AUCTION_ID),
                () -> assertThat(result.finalPrice()).isEqualTo(FINAL_PRICE),
                () -> assertThat(result.purchasedAt()).isEqualTo(PURCHASED_AT)
        );
        verify(auctionRepository).completeForBuyNow(
                AUCTION_ID,
                MEMBER_ID,
                FINAL_PRICE,
                PURCHASED_AT
        );
        verify(memberRepository).movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE);
        verify(request).complete(persistedTrade, FINAL_PRICE);
        verify(eventPublisher).publishEvent(new AuctionStateChanged(LIVE_STATE));
    }

    @Test
    void COMPLETED_경매는_즉시구매할_수_없다() {
        stubNewRequestWithLoadedEntities(AUCTION_ID);
        stubActiveBuyerWithDifferentSeller();
        when(auction.getStatus()).thenReturn(AuctionStatus.COMPLETED);

        AuctionException exception = assertThrows(
                AuctionException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(AUCTION_ALREADY_TRADED);
        verifyNoPurchaseMutation();
        verifyNoInteractions(priceCalculator);
    }

    @Test
    void BID_ONGOING_경매는_즉시구매할_수_없다() {
        stubNewRequestWithLoadedEntities(AUCTION_ID);
        stubActiveBuyerWithDifferentSeller();
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);

        AuctionException exception = assertThrows(
                AuctionException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(AUCTION_NOT_ONGOING);
        verifyNoPurchaseMutation();
        verifyNoInteractions(priceCalculator);
    }

    @Test
    void 판매자는_자신의_경매를_즉시구매할_수_없다() {
        stubNewRequestWithLoadedEntities(AUCTION_ID);
        when(buyer.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(buyer.getId()).thenReturn(MEMBER_ID);
        when(auction.getSeller()).thenReturn(buyer);

        BidException exception = assertThrows(
                BidException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(SELF_PURCHASE_NOT_ALLOWED);
        verifyNoPurchaseMutation();
        verifyNoInteractions(priceCalculator);
    }

    @Test
    void ACTIVE가_아닌_회원은_즉시구매할_수_없다() {
        stubNewRequestWithLoadedEntities(AUCTION_ID);
        when(buyer.getStatus()).thenReturn(MemberStatus.BANNED);

        MemberException exception = assertThrows(
                MemberException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(MEMBER_NOT_ACTIVE);
        verifyNoPurchaseMutation();
        verifyNoInteractions(priceCalculator);
    }

    @Test
    void 낙찰가_전액을_잠글_포인트가_없으면_구매할_수_없다() {
        stubReadyToPurchase();
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE))
                .thenReturn(0);

        BidException exception = assertThrows(
                BidException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(INSUFFICIENT_DEPOSIT);
        verify(auctionRepository, never())
                .completeForBuyNow(anyLong(), anyLong(), anyLong(), any());
        verify(auctionDepositRepository, never()).save(any());
        verify(auctionTradeRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(request, never()).complete(any(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 낙찰가가_0_이하면_포인트와_보증금을_변경하지_않는다() {
        stubReadyToPurchase();
        assertThrows(
                IllegalStateException.class,
                () -> transactionService.buy(command(
                        AUCTION_ID,
                        0L,
                        BidStatus.BUY_NOW
                ))
        );

        verify(memberRepository, never()).movePointToLockedIfEnough(anyLong(), anyLong());
        verify(auctionRepository, never())
                .completeForBuyNow(anyLong(), anyLong(), anyLong(), any());
        verify(auctionDepositRepository, never()).save(any());
        verify(auctionTradeRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(request, never()).complete(any(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 경매_CAS가_실패하면_동시구매_충돌로_처리한다() {
        stubReadyToPurchase();
        when(auctionRepository.currentDatabaseTime()).thenReturn(PURCHASED_AT);
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE))
                .thenReturn(1);
        when(auctionRepository.completeForBuyNow(
                AUCTION_ID,
                MEMBER_ID,
                FINAL_PRICE,
                PURCHASED_AT
        ))
                .thenReturn(0);

        BidException exception = assertThrows(
                BidException.class,
                this::buyInTransaction
        );

        assertThat(exception.getErrorCode()).isEqualTo(CONCURRENT_TRADE_CONFLICT);
        verify(memberRepository).movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE);
        verify(auctionDepositRepository, never()).save(any());
        verify(auctionTradeRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(request, never()).complete(any(), anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void 상향_경매는_마감_5분_전부터_즉시구매할_수_없다() {
        // given
        stubReadyToPurchase(upAuction);
        when(upAuction.getBuyNowPrice()).thenReturn(FINAL_PRICE);
        when(upAuction.getEndedAt()).thenReturn(PURCHASED_AT.plusMinutes(5));

        // when
        AuctionException exception = assertThrows(
                AuctionException.class,
                this::buyInTransaction
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(UP_BUY_NOW_CLOSED_NEAR_DEADLINE);
        verifyNoPurchaseMutation();
    }

    @Test
    void 조건부_갱신_전에_마감_5분_경계를_넘으면_전용_오류를_반환한다() {
        // given
        LocalDateTime cutoffReachedAt = PURCHASED_AT.plusSeconds(1);
        stubReadyToPurchase(upAuction);
        when(upAuction.getBuyNowPrice()).thenReturn(FINAL_PRICE);
        when(upAuction.getEndedAt())
                .thenReturn(PURCHASED_AT.plusMinutes(5).plusSeconds(1));
        when(memberRepository.movePointToLockedIfEnough(MEMBER_ID, FINAL_PRICE))
                .thenReturn(1);
        when(auctionRepository.completeForBuyNow(
                AUCTION_ID,
                MEMBER_ID,
                FINAL_PRICE,
                PURCHASED_AT
        )).thenReturn(0);
        when(auctionRepository.currentDatabaseTime()).thenReturn(cutoffReachedAt);

        // when
        AuctionException exception = assertThrows(
                AuctionException.class,
                this::buyInTransaction
        );

        // then
        assertThat(exception.getErrorCode())
                .isEqualTo(UP_BUY_NOW_CLOSED_NEAR_DEADLINE);
        verify(auctionDepositRepository, never()).save(any());
        verify(auctionTradeRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(request, never()).complete(any(), anyLong());
    }

    @Test
    void 완료된_동일_멱등_키를_재요청하면_기존_거래를_응답한다() {
        stubPersistedTrade();
        when(requestRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(request));
        when(request.belongsTo(MEMBER_ID, AUCTION_ID)).thenReturn(true);
        when(request.isCompleted()).thenReturn(true);
        when(request.getTrade()).thenReturn(persistedTrade);

        BuyNowResult result = buyInTransaction();

        assertAll(
                () -> assertThat(result.tradeId()).isEqualTo(7L),
                () -> assertThat(result.auctionId()).isEqualTo(AUCTION_ID),
                () -> assertThat(result.finalPrice()).isEqualTo(FINAL_PRICE),
                () -> assertThat(result.purchasedAt()).isEqualTo(PURCHASED_AT)
        );
        verifyNoInteractions(memberRepository);
        verify(auctionRepository, never())
                .completeForBuyNow(anyLong(), anyLong(), anyLong(), any());
        verify(memberRepository, never()).movePointToLockedIfEnough(anyLong(), anyLong());
        verifyNoInteractions(
                auctionDepositRepository,
                auctionTradeRepository,
                bidRepository,
                priceCalculator,
                eventPublisher
        );
    }

    @Test
    void 동일_멱등_키를_다른_요청에_재사용하면_거부한다() {
        when(requestRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(request));
        when(request.belongsTo(MEMBER_ID, OTHER_AUCTION_ID)).thenReturn(false);

        BidException exception = assertThrows(
                BidException.class,
                () -> transactionService.buy(command(
                        OTHER_AUCTION_ID,
                        FINAL_PRICE,
                        BidStatus.BUY_NOW
                ))
        );

        assertThat(exception.getErrorCode()).isEqualTo(IDEMPOTENCY_KEY_REUSED);
        verifyNoInteractions(memberRepository, auctionRepository);
        verify(auctionRepository, never())
                .completeForBuyNow(anyLong(), anyLong(), anyLong(), any());
        verify(memberRepository, never()).movePointToLockedIfEnough(anyLong(), anyLong());
        verifyNoInteractions(
                auctionDepositRepository,
                auctionTradeRepository,
                bidRepository,
                priceCalculator
        );
    }

    @Test
    void 상향_서비스는_즉시구매가와_BUY_NOW_상태를_트랜잭션에_전달한다() {
        // given
        BuyNowResult expected = completedResult();
        when(auctionRepository.findById(AUCTION_ID))
                .thenReturn(Optional.of(upAuction));
        when(auctionRepository.currentDatabaseTime())
                .thenReturn(PURCHASED_AT);
        when(priceCalculator.calculate(upAuction, PURCHASED_AT))
                .thenReturn(FINAL_PRICE);
        when(transactionBoundary.buy(any(BuyNowCommand.class)))
                .thenReturn(expected);

        // when
        BuyNowResult result = buyNowService.buyUpAuction(
                MEMBER_ID,
                AUCTION_ID,
                IDEMPOTENCY_KEY
        );

        // then
        ArgumentCaptor<BuyNowCommand> commandCaptor =
                ArgumentCaptor.forClass(BuyNowCommand.class);
        verify(transactionBoundary).buy(commandCaptor.capture());
        assertAll(
                () -> assertThat(result).isSameAs(expected),
                () -> assertThat(commandCaptor.getValue().finalPrice())
                        .isEqualTo(FINAL_PRICE),
                () -> assertThat(commandCaptor.getValue().purchasedAt())
                        .isEqualTo(PURCHASED_AT),
                () -> assertThat(commandCaptor.getValue().bidStatus())
                        .isEqualTo(BidStatus.BUY_NOW)
        );
    }

    @Test
    void 하향_서비스는_계산가와_DOWN_상태를_트랜잭션에_전달한다() {
        // given
        BuyNowResult expected = completedResult();
        when(auctionRepository.findById(AUCTION_ID))
                .thenReturn(Optional.of(auction));
        when(auctionRepository.currentDatabaseTime())
                .thenReturn(PURCHASED_AT);
        when(priceCalculator.calculate(auction, PURCHASED_AT))
                .thenReturn(FINAL_PRICE);
        when(transactionBoundary.buy(any(BuyNowCommand.class)))
                .thenReturn(expected);

        // when
        BuyNowResult result = buyNowService.buyDownAuction(
                MEMBER_ID,
                AUCTION_ID,
                IDEMPOTENCY_KEY
        );

        // then
        ArgumentCaptor<BuyNowCommand> commandCaptor =
                ArgumentCaptor.forClass(BuyNowCommand.class);
        verify(transactionBoundary).buy(commandCaptor.capture());
        assertAll(
                () -> assertThat(result).isSameAs(expected),
                () -> assertThat(commandCaptor.getValue().finalPrice())
                        .isEqualTo(FINAL_PRICE),
                () -> assertThat(commandCaptor.getValue().purchasedAt())
                        .isEqualTo(PURCHASED_AT),
                () -> assertThat(commandCaptor.getValue().bidStatus())
                        .isEqualTo(BidStatus.DOWN)
        );
    }

    private BuyNowCommand command(
            Long auctionId,
            long finalPrice,
            BidStatus bidStatus
    ) {
        return new BuyNowCommand(
                MEMBER_ID,
                auctionId,
                IDEMPOTENCY_KEY,
                finalPrice,
                PURCHASED_AT,
                bidStatus
        );
    }

    private BuyNowResult buyInTransaction() {
        return transactionService.buy(command(
                AUCTION_ID,
                FINAL_PRICE,
                BidStatus.BUY_NOW
        ));
    }

    private BuyNowResult completedResult() {
        return new BuyNowResult(7L, AUCTION_ID, FINAL_PRICE, PURCHASED_AT);
    }

    private void stubNewRequestWithLoadedEntities(Long auctionId) {
        stubNewRequestWithLoadedEntities(auctionId, auction);
    }

    private void stubNewRequestWithLoadedEntities(
            Long auctionId,
            Auction loadedAuction
    ) {
        stubLoadedEntities(auctionId, loadedAuction);
        when(requestRepository.findByIdempotencyKeyForUpdate(IDEMPOTENCY_KEY))
                .thenReturn(Optional.of(request));
        when(request.belongsTo(MEMBER_ID, auctionId)).thenReturn(true);
        when(request.isCompleted()).thenReturn(false);
    }

    private void stubLoadedEntities(Long auctionId, Auction loadedAuction) {
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(buyer));
        when(auctionRepository.findWithSellerById(auctionId))
                .thenReturn(Optional.of(loadedAuction));
    }

    private void stubActiveBuyerWithDifferentSeller() {
        when(buyer.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(buyer.getId()).thenReturn(MEMBER_ID);
        when(auction.getSeller()).thenReturn(seller);
        when(seller.getId()).thenReturn(SELLER_ID);
    }

    private void stubOpenAuctionBeforeEnd() {
        when(auction.getStatus()).thenReturn(AuctionStatus.OPEN);
        when(auction.getEndedAt()).thenReturn(ENDED_AT);
    }

    private void stubReadyToPurchase() {
        stubNewRequestWithLoadedEntities(AUCTION_ID);
        stubActiveBuyerWithDifferentSeller();
        stubOpenAuctionBeforeEnd();
    }

    private void stubReadyToPurchase(Auction loadedAuction) {
        stubNewRequestWithLoadedEntities(AUCTION_ID, loadedAuction);
        when(buyer.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(buyer.getId()).thenReturn(MEMBER_ID);
        when(loadedAuction.getSeller()).thenReturn(seller);
        when(seller.getId()).thenReturn(SELLER_ID);
        when(loadedAuction.getStatus()).thenReturn(AuctionStatus.OPEN);
    }

    private void stubPersistedTrade() {
        when(persistedTrade.getId()).thenReturn(7L);
        when(persistedTrade.getAuction()).thenReturn(auction);
        when(auction.getId()).thenReturn(AUCTION_ID);
        when(persistedTrade.getFinalPrice()).thenReturn(FINAL_PRICE);
        when(persistedTrade.getPurchasedAt()).thenReturn(PURCHASED_AT);
    }

    private void verifyNoPurchaseMutation() {
        verify(auctionRepository, never())
                .completeForBuyNow(anyLong(), anyLong(), anyLong(), any());
        verify(memberRepository, never()).movePointToLockedIfEnough(anyLong(), anyLong());
        verify(auctionDepositRepository, never()).save(any());
        verify(auctionTradeRepository, never()).save(any());
        verify(bidRepository, never()).save(any());
        verify(request, never()).complete(any(), anyLong());
        verifyNoInteractions(eventPublisher);
    }
}
