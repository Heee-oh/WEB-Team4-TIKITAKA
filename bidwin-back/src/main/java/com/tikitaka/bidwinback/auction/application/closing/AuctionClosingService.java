package com.tikitaka.bidwinback.auction.application.closing;

import com.tikitaka.bidwinback.auction.application.deposit.DepositSettlementService;
import com.tikitaka.bidwinback.auction.application.live.AuctionBidHistoryRevealed;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveState;
import com.tikitaka.bidwinback.auction.application.live.AuctionLiveStateService;
import com.tikitaka.bidwinback.auction.application.live.AuctionStateChanged;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionClosingCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuctionClosingService {

    private final AuctionRepository auctionRepository;
    private final AuctionTradeRepository auctionTradeRepository;
    private final DepositSettlementService depositSettlementService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuctionLiveStateService liveStateService;

    @Transactional
    public int closeBatch(AuctionStatus candidateStatus, int batchSize) {
        List<AuctionClosingCandidate> candidates =
                auctionRepository.findClosingCandidatesForUpdateSkipLocked(
                        candidateStatus.name(),
                        batchSize
                );
        return closeClaimed(candidates, candidateStatus);
    }

    @Transactional(readOnly = true)
    public List<Long> findClosingCandidateIds(AuctionStatus candidateStatus, int limit) {
        return auctionRepository.findClosingCandidateIds(candidateStatus.name(), limit);
    }

    @Transactional
    public int closeOne(AuctionStatus candidateStatus, Long auctionId) {
        List<AuctionClosingCandidate> candidates =
                auctionRepository.findClosingCandidateForUpdateSkipLocked(
                        auctionId,
                        candidateStatus.name()
                );
        return closeClaimed(candidates, candidateStatus);
    }

    private int closeClaimed(
            List<AuctionClosingCandidate> candidates,
            AuctionStatus candidateStatus
    ) {
        if (candidates.isEmpty()) {
            return 0;
        }

        LocalDateTime settledAt = auctionRepository.currentDatabaseTime();
        List<Long> claimedAuctionIds = candidates.stream()
                .map(AuctionClosingCandidate::getAuctionId)
                .toList();

        auctionTradeRepository.insertWinnerTradesAll(
                claimedAuctionIds,
                TradeStatus.WAITING_CONFIRM.name(),
                settledAt
        );
        int closed = auctionRepository.closeAll(claimedAuctionIds, settledAt);
        verifyAllClosed(closed, candidates.size());

        // BID_ONGOING은 공개 또는 밀봉 입찰이 있어 낙찰 거래가 생긴 상향 경매다.
        // 같은 트랜잭션에서 비낙찰자의 보증금을 반환해 마감과 포인트 복구를 원자적으로 처리한다.
        if (candidateStatus == AuctionStatus.BID_ONGOING) {
            depositSettlementService.refundLosingDeposits(claimedAuctionIds);
        }

        publishClosedEvent(candidates, candidateStatus);
        return candidates.size();
    }

    private void verifyAllClosed(int closed, int claimed) {
        if (closed != claimed) {
            throw new IllegalStateException(
                    "경매 마감 배치의 반영 수가 선점 수와 다릅니다. claimed=" + claimed
                            + ", closed=" + closed
            );
        }
    }

    private void publishClosedEvent(
            List<AuctionClosingCandidate> candidates,
            AuctionStatus candidateStatus
    ) {
        // 후보마다 조회하면 선점한 행 락을 쥔 시간이 후보 수만큼 늘어난다. 한 번에 읽는다.
        List<AuctionLiveState> states = liveStateService.snapshots(
                candidates.stream().map(AuctionClosingCandidate::getAuctionId).toList()
        );
        for (int i = 0; i < candidates.size(); i++) {
            AuctionClosingCandidate candidate = candidates.get(i);
            eventPublisher.publishEvent(new AuctionStateChanged(states.get(i)));
            // 공개입찰조차 없던 경매(OPEN)는 공개할 밀봉 입찰 내역이 없다.
            if (candidateStatus == AuctionStatus.BID_ONGOING) {
                eventPublisher.publishEvent(new AuctionBidHistoryRevealed(
                        candidate.getAuctionId(),
                        candidate.getRevision() + 1
                ));
            }
        }
    }
}
