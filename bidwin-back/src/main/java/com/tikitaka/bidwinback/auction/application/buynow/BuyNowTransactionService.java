package com.tikitaka.bidwinback.auction.application.buynow;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidCreated;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.AuctionPricePolicy;
import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionDeposit;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionTrade;
import com.tikitaka.bidwinback.auction.domain.entity.Bid;
import com.tikitaka.bidwinback.auction.domain.entity.InstantPurchaseRequest;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
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
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_ALREADY_ENDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_ALREADY_TRADED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_ONGOING;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BUY_NOW_PRICE_NOT_SET;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.CONCURRENT_TRADE_CONFLICT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.IDEMPOTENCY_KEY_REUSED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INSUFFICIENT_DEPOSIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.MEMBER_NOT_ACTIVE;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.MEMBER_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.PRICE_LIMIT_EXCEEDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SELF_PURCHASE_NOT_ALLOWED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.UP_BUY_NOW_CLOSED_NEAR_DEADLINE;

@Service
@RequiredArgsConstructor
public class BuyNowTransactionService {

    private final MemberRepository memberRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionDepositRepository auctionDepositRepository;
    private final AuctionTradeRepository auctionTradeRepository;
    private final BidRepository bidRepository;
    private final InstantPurchaseRequestRepository requestRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final AuctionLiveStateService liveStateService;

    @Transactional
    public BuyNowResult buy(BuyNowCommand command) {
        if (!AuctionPricePolicy.isAllowed(command.finalPrice())) {
            throw new BidException(PRICE_LIMIT_EXCEEDED);
        }

        // 멱등 키 저장
        requestRepository.insertOrKeep(
                command.idempotencyKey(),
                command.memberId(),
                command.auctionId()
        );
        // 멱등 키 비관락을 조회, 동시요청은 여기서 앞썬 트랜잭션이 락을 풀어야 진입가능
        InstantPurchaseRequest request = findRequestForUpdate(
                command.idempotencyKey()
        );
        validateIdempotencyKey(request, command.memberId(), command.auctionId());

        // 앞선 트랜잭션이 커밋되면 후에 들어온 트랜잭션은 대기했다가 앞 트랜잭션이 커밋된것이므로
        // 변경된 값을 읽어옴 따라서 여기서 바로 isComplted()가 true가 된다.
        if (request.isCompleted()) {
            return BuyNowResult.from(request.getTrade());
        }

        Member buyer = memberRepository.findById(command.memberId())
                .orElseThrow(() -> new MemberException(MEMBER_NOT_FOUND));
        Auction auction = auctionRepository.findWithSellerById(command.auctionId())
                .orElseThrow(() -> new AuctionException(AUCTION_NOT_FOUND));

        validateBuyer(buyer, auction);
        validateAuction(auction, command.purchasedAt());

        if (command.finalPrice() <= 0) {
            throw new IllegalStateException("즉시구매 가격은 0보다 커야 합니다.");
        }

        // 잔액 확인과 전액 잠금을 한 UPDATE로 처리해 동시 구매의 초과 사용을 막는다.
        int lockedPoints = lockDepositPoint(command.memberId(), command.finalPrice());
        if (lockedPoints != 1) {
            throw new BidException(INSUFFICIENT_DEPOSIT);
        }

        // 요구사항: 동시 구매 시 DB 조건부 갱신에 성공한 한 요청만 낙찰된다.
        int completed = completeForBuyNow(command);
        if (completed != 1) {
            // 검증 후 조건부 UPDATE 전에 마감 경계를 넘었는지 최신 DB 시각으로 다시 확인한다.
            validateAuction(auction, auctionRepository.currentDatabaseTime());
            throw new BidException(CONCURRENT_TRADE_CONFLICT);
        }

        auctionDepositRepository.save(AuctionDeposit.builder()
                .member(buyer)
                .auction(auction)
                .reservedAmount(command.finalPrice())
                .build());

        Bid purchaseBid = bidRepository.save(Bid.builder()
                .auction(auction)
                .bidder(buyer)
                .price(command.finalPrice())
                .status(command.bidStatus())
                .build());

        // 거래와 멱등 요청을 함께 완료해 재요청 결과를 동일하게 보장한다.
        AuctionTrade trade = auctionTradeRepository.save(
                AuctionTrade.builder()
                        .auction(auction)
                        .buyer(buyer)
                        .status(TradeStatus.CONFIRMED)
                        .finalPrice(command.finalPrice())
                        .purchasedAt(command.purchasedAt())
                        .build()
        );
        request.complete(trade, command.finalPrice());
        // 커밋 뒤 리스너가 DB를 읽지 않도록 snapshot을 이 트랜잭션에서 만든다. 낙찰 UPDATE가 네이티브라서
        // 위에서 적재한 auction 엔티티는 낡았다. 그래서 엔티티가 아니라 DB를 직접 읽는 snapshot을 쓴다.
        eventPublisher.publishEvent(new AuctionStateChanged(
                liveStateService.snapshot(command.auctionId())
        ));
        if (auction instanceof UpAuction) {
            eventPublisher.publishEvent(AuctionBidCreated.from(purchaseBid));
        }

        return BuyNowResult.from(trade);
    }

    // 입찰 흐름은 경매 행을 먼저 잠근 뒤 회원 행을 잠그는 반대 순서라 드물게 순환 대기가
    // 날 수 있다. 그때도 500이 아니라 기존 동시성 충돌 응답으로 처리되도록 변환한다.
    private int lockDepositPoint(Long memberId, long amount) {
        try {
            return memberRepository.movePointToLockedIfEnough(memberId, amount);
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new BidException(CONCURRENT_TRADE_CONFLICT);
        }
    }

    // 이 UPDATE에도 짧은 쿼리 타임아웃이 걸려 있어, 락 대기가 길어지면 completed != 1과
    // 같은 취지의 응답을 주도록 여기서도 동일하게 변환한다.
    private int completeForBuyNow(BuyNowCommand command) {
        try {
            return auctionRepository.completeForBuyNow(
                    command.auctionId(),
                    command.memberId(),
                    command.finalPrice(),
                    command.purchasedAt()
            );
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new BidException(CONCURRENT_TRADE_CONFLICT);
        }
    }

    private InstantPurchaseRequest findRequestForUpdate(
            String idempotencyKey
    ) {
        return requestRepository.findByIdempotencyKeyForUpdate(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "선점한 즉시구매 멱등 요청을 찾을 수 없습니다."
                ));
    }

    private void validateIdempotencyKey(
            InstantPurchaseRequest request,
            Long memberId,
            Long auctionId
    ) {
        // 요구사항: 하나의 멱등 키를 다른 회원이나 경매 요청에 재사용할 수 없다.
        if (!request.belongsTo(memberId, auctionId)) {
            throw new BidException(IDEMPOTENCY_KEY_REUSED);
        }
    }

    private void validateBuyer(Member buyer, Auction auction) {
        // 요구사항: ACTIVE 회원만 즉시구매할 수 있다.
        if (buyer.getStatus() != MemberStatus.ACTIVE) {
            throw new MemberException(MEMBER_NOT_ACTIVE);
        }
        // 요구사항: 판매자는 자신의 경매를 구매할 수 없다.
        if (auction.getSeller().getId().equals(buyer.getId())) {
            throw new BidException(SELF_PURCHASE_NOT_ALLOWED);
        }
    }

    private void validateAuction(Auction auction, LocalDateTime databaseTime) {
        // 요구사항: 이미 거래가 확정된 경매는 다시 구매할 수 없다.
        if (auction.getStatus() == AuctionStatus.COMPLETED) {
            throw new AuctionException(AUCTION_ALREADY_TRADED);
        }
        // 요구사항: 첫 입찰 전 OPEN 상태의 경매만 즉시구매할 수 있다.
        if (auction.getStatus() != AuctionStatus.OPEN) {
            throw new AuctionException(AUCTION_NOT_ONGOING);
        }
        // 요구사항: 상향 경매는 판매자가 즉시구매가를 설정한 경우에만 구매할 수 있다.
        if (auction instanceof UpAuction upAuction
                && upAuction.getBuyNowPrice() == null) {
            throw new BidException(BUY_NOW_PRICE_NOT_SET);
        }

        // 요구사항: DB 현재 시각이 종료 시각과 같거나 지난 경매는 구매할 수 없다.
        if (!auction.getEndedAt().isAfter(databaseTime)) {
            throw new AuctionException(AUCTION_ALREADY_ENDED);
        }
        // 상향 경매는 마감 5분 전 밀봉입찰 구간부터 즉시구매할 수 없다.
        if (auction instanceof UpAuction
                && !databaseTime.isBefore(auction.getEndedAt().minusMinutes(5))) {
            throw new AuctionException(UP_BUY_NOW_CLOSED_NEAR_DEADLINE);
        }
    }

}
