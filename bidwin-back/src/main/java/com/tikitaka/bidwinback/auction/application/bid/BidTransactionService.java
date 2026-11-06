package com.tikitaka.bidwinback.auction.application.bid;

import com.tikitaka.bidwinback.auction.application.live.AuctionBidCreated;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.AuctionPricePolicy;
import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionDeposit;
import com.tikitaka.bidwinback.auction.domain.entity.Bid;
import com.tikitaka.bidwinback.auction.domain.entity.SealedBid;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.BidStatus;
import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.exception.BidException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionDepositRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.SealedBidRepository;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus.BID_ONGOING;
import static com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus.OPEN;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_ALREADY_ENDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_FOUND;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.AUCTION_NOT_ONGOING;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PHASE_CHANGED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.BID_PRICE_TOO_LOW;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.CONCURRENT_BID_CONFLICT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INSUFFICIENT_DEPOSIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.INVALID_BID_UNIT;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.NOT_UP_AUCTION;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.PRICE_LIMIT_EXCEEDED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SEALED_BID_ALREADY_SUBMITTED;
import static com.tikitaka.bidwinback.global.exception.ErrorCode.SELF_BID_NOT_ALLOWED;

/**
 * 입찰의 DB 트랜잭션 구간. 트랜잭션 안에서 끝나는 일만 한다.
 *
 * <p>Redis 선점과 실패 뒤 가격 캐시 재동기화는 DB 연결을 쥐지 않도록 트랜잭션 밖({@link BidService})에서 한다.
 * SSE 상태는 여기서 snapshot을 만들어 이벤트에 싣는다. 커밋 뒤 리스너가 DB를 다시 읽으면 원 트랜잭션의
 * 연결을 쥔 채 두 번째 연결을 기다려, 동시 입찰 수가 풀 크기에 닿는 순간 풀 전체가 교착한다.
 */
@Service
@RequiredArgsConstructor
public class BidTransactionService {

    private static final long BID_UNIT = 1_000L;
    private static final long SEALED_BID_WINDOW_MINUTES = 5L;
    private static final long DEPOSIT_RATE_NUMERATOR = 3L;
    private static final long DEPOSIT_RATE_DENOMINATOR = 10L;

    private final MemberRepository memberRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionDepositRepository auctionDepositRepository;
    private final BidRepository bidRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final SealedBidRepository sealedBidRepository;
    private final AuctionLiveStateService liveStateService;

    @Transactional
    public BidResult place(
            Long memberId,
            Long auctionId,
            long price,
            BidType bidType
    ) {
        // bidType은 클라이언트가 인지한 입찰 단계일 뿐이며, 실제 단계는 DB 시각을 사용하는
        // 조건부 UPDATE가 판정한다. 단계가 바뀌어도 다른 입찰 유형으로 자동 전환하지 않는다.
        int updatedRows;
        boolean stateChanged;
        if (bidType == BidType.OPEN) {
            updatedRows = updateCurrentPrice(memberId, auctionId, price);
            stateChanged = true;
        } else {
            SealedBidAuctionUpdate update = tryUpdateAuctionForSealedBid(
                    memberId,
                    auctionId,
                    price
            );
            updatedRows = update.updatedRows();
            stateChanged = update.stateChanged();
        }
        if (updatedRows != 1) {
            // 실패 원인을 최신 상태로 다시 판별해 구체적인 도메인 오류로 변환한다. 이 메서드는
            // 항상 예외를 던져 트랜잭션을 롤백시키며, Redis 선점의 재동기화는 호출자(BidService)가 한다.
            return rejectBid(memberId, auctionId, price, bidType);
        }

        Auction auction = auctionRepository.getReferenceById(auctionId);
        Member bidder = memberRepository.getReferenceById(memberId);
        reserveDepositForFirstBid(memberId, auctionId, auction, bidder);

        return switch (bidType) {
            case OPEN -> saveOpenBid(auction, bidder, price);
            case SEALED -> saveSealedBid(auction, bidder, price, stateChanged);
        };
    }

    private void reserveDepositForFirstBid(
            Long memberId,
            Long auctionId,
            Auction auction,
            Member bidder
    ) {
        if (auctionDepositRepository.existsByMemberIdAndAuctionId(memberId, auctionId)) {
            return;
        }

        // 시작가는 천원 단위이므로 나눗셈 후 곱해도 30%가 정확하며 곱셈 오버플로도 피한다.
        long depositAmount = Math.multiplyExact(
                auction.getStartPrice() / DEPOSIT_RATE_DENOMINATOR,
                DEPOSIT_RATE_NUMERATOR
        );
        int lockedPoints = lockDepositPoint(memberId, depositAmount);
        if (lockedPoints != 1) {
            throw new BidException(INSUFFICIENT_DEPOSIT);
        }

        auctionDepositRepository.save(AuctionDeposit.builder()
                .member(bidder)
                .auction(auction)
                .reservedAmount(depositAmount)
                .build());
    }

    private BidResult saveOpenBid(Auction auction, Member bidder, long price) {
        Bid bid = bidRepository.save(Bid.builder()
                .auction(auction)
                .bidder(bidder)
                .price(price)
                .status(BidStatus.UP)
                .build());

        publishStateChanged(auction.getId());
        eventPublisher.publishEvent(AuctionBidCreated.from(bid));
        return BidResult.from(bid);
    }

    private BidResult saveSealedBid(
            Auction auction,
            Member bidder,
            long price,
            boolean stateChanged
    ) {
        SealedBid sealedBid;
        try {
            sealedBid = sealedBidRepository.saveAndFlush(
                    SealedBid.builder()
                            .auction(auction)
                            .bidder(bidder)
                            .price(price)
                            .build()
            );
        } catch (DataIntegrityViolationException exception) {
            throw new BidException(SEALED_BID_ALREADY_SUBMITTED);
        }

        // 후속 밀봉입찰은 공개 상태가 그대로라 같은 revision의 snapshot을 다시 만들 필요가 없다.
        if (stateChanged) {
            publishStateChanged(auction.getId());
        }
        return BidResult.from(sealedBid);
    }

    private void publishStateChanged(long auctionId) {
        eventPublisher.publishEvent(new AuctionStateChanged(liveStateService.snapshot(auctionId)));
    }

    static void validateBidPrice(long price) {
        if (!AuctionPricePolicy.isAllowed(price)) {
            throw new BidException(PRICE_LIMIT_EXCEEDED);
        }
        if (price <= 0 || price % BID_UNIT != 0) {
            throw new BidException(INVALID_BID_UNIT);
        }
    }

    private int updateCurrentPrice(Long memberId, Long auctionId, long price) {
        try {
            return auctionRepository.updateCurrentPriceForBid(
                    auctionId,
                    memberId,
                    price,
                    BID_UNIT
            );
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new BidException(CONCURRENT_BID_CONFLICT);
        }
    }

    private SealedBidAuctionUpdate tryUpdateAuctionForSealedBid(
            Long memberId,
            Long auctionId,
            long price
    ) {
        try {
            int firstSealedBid = auctionRepository.tryUpdateAuctionForSealedBid(
                    auctionId,
                    memberId,
                    price,
                    BID_UNIT,
                    OPEN.name(),
                    1
            );
            if (firstSealedBid == 1) {
                return new SealedBidAuctionUpdate(1, true);
            }

            int subsequentSealedBid = auctionRepository.tryUpdateAuctionForSealedBid(
                    auctionId,
                    memberId,
                    price,
                    BID_UNIT,
                    BID_ONGOING.name(),
                    0
            );
            return new SealedBidAuctionUpdate(subsequentSealedBid, false);
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new BidException(CONCURRENT_BID_CONFLICT);
        }
    }

    private record SealedBidAuctionUpdate(int updatedRows, boolean stateChanged) {
    }

    // 즉시구매 흐름은 회원 행을 먼저 잠근 뒤 경매 행을 잠그는 반대 순서라 드물게 순환 대기가
    // 날 수 있다. 그때도 500이 아니라 기존 동시성 충돌 응답으로 처리되도록 변환한다.
    private int lockDepositPoint(Long memberId, long depositAmount) {
        try {
            return memberRepository.movePointToLockedIfEnough(memberId, depositAmount);
        } catch (PessimisticLockingFailureException | QueryTimeoutException exception) {
            throw new BidException(CONCURRENT_BID_CONFLICT);
        }
    }

    private BidResult rejectBid(
            Long memberId,
            Long auctionId,
            long price,
            BidType bidType
    ) {
        Auction auction = auctionRepository.findById(auctionId)
                .orElseThrow(() -> new AuctionException(AUCTION_NOT_FOUND));

        // 하향 경매는 즉시구매로만 거래되므로 입찰은 상향 경매에만 허용한다.
        if (!(auction instanceof UpAuction)) {
            throw new BidException(NOT_UP_AUCTION);
        }
        if (auction.getStatus() != OPEN && auction.getStatus() != BID_ONGOING) {
            throw new AuctionException(AUCTION_NOT_ONGOING);
        }

        LocalDateTime databaseTime = auctionRepository.currentDatabaseTime();
        if (!auction.getEndedAt().isAfter(databaseTime)) {
            throw new AuctionException(AUCTION_ALREADY_ENDED);
        }
        if (currentBidType(auction, databaseTime) != bidType) {
            throw new BidException(BID_PHASE_CHANGED);
        }
        if (auction.getSeller().getId().equals(memberId)) {
            throw new BidException(SELF_BID_NOT_ALLOWED);
        }
        if (price - BID_UNIT < highestAcceptedPriceOf(auction, bidType)) {
            throw new BidException(BID_PRICE_TOO_LOW);
        }

        throw new BidException(CONCURRENT_BID_CONFLICT);
    }

    private BidType currentBidType(Auction auction, LocalDateTime databaseTime) {
        LocalDateTime sealedBidStartedAt = auction.getEndedAt().minusMinutes(
                SEALED_BID_WINDOW_MINUTES
        );
        return databaseTime.isBefore(sealedBidStartedAt)
                ? BidType.OPEN
                : BidType.SEALED;
    }

    private long highestAcceptedPriceOf(Auction auction, BidType bidType) {
        long currentPrice = auction.getCurrentPrice();
        if (bidType != BidType.SEALED) {
            return currentPrice;
        }

        Long highestSealedPrice = sealedBidRepository.findHighestPriceByAuctionId(
                auction.getId()
        );
        return highestSealedPrice == null
                ? currentPrice
                : Math.max(currentPrice, highestSealedPrice);
    }
}
