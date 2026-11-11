package com.tikitaka.bidwinback.mypage.application;

import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.AuctionTrade;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.ImageRepository;
import com.tikitaka.bidwinback.auction.domain.repository.dto.AuctionThumbnailRow;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.exception.MemberException;
import com.tikitaka.bidwinback.member.domain.repository.MemberRepository;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.ActiveTradeResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.BuyingItemResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.DepositResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.DownPricingResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.MyPageResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.ProfileResponse;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.SellingItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MyPageService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    // 구매 물품으로 노출할 거래 상태(실패 거래는 제외한다).
    private static final List<TradeStatus> BUYING_STATUSES = List.of(
            TradeStatus.WAITING_CONFIRM,
            TradeStatus.CONFIRMED,
            TradeStatus.COMPLETED
    );

    private final MemberRepository memberRepository;
    private final AuctionRepository auctionRepository;
    private final AuctionTradeRepository auctionTradeRepository;
    private final BidRepository bidRepository;
    private final ImageRepository imageRepository;
    private final ImageUrlResolver imageUrlResolver;

    @Transactional(readOnly = true)
    public MyPageResponse getMyPage(long memberId) {
        Member member = memberRepository.findById(memberId)
                .orElseThrow(() -> new MemberException(ErrorCode.MEMBER_NOT_FOUND));

        List<Auction> sellingAuctions = auctionRepository.findTop3BySellerIdOrderByIdDesc(memberId);
        List<AuctionTrade> buyingTrades = auctionTradeRepository.findBuyingItems(memberId, BUYING_STATUSES);
        List<AuctionTrade> activeTrades = auctionTradeRepository.findActiveTrades(
                memberId,
                TradeStatus.WAITING_CONFIRM,
                TradeStatus.CONFIRMED
        );

        Map<Long, String> thumbnailUrls = resolveThumbnailUrls(sellingAuctions, buyingTrades, activeTrades);

        // 하락 경매 현재가와 마감 판정 기준을 애플리케이션 서버 시계가 아닌 DB 시각으로 통일한다.
        LocalDateTime databaseTime = auctionRepository.currentDatabaseTime();

        return new MyPageResponse(
                toProfile(member),
                toDeposit(member),
                activeTrades.stream().map(trade -> toActiveTrade(trade, memberId, thumbnailUrls)).toList(),
                sellingAuctions.stream().map(auction -> toSellingItem(auction, thumbnailUrls, databaseTime)).toList(),
                buyingTrades.stream().map(trade -> toBuyingItem(trade, thumbnailUrls)).toList()
        );
    }

    private ProfileResponse toProfile(Member member) {
        long sellCount = auctionTradeRepository
                .countByAuctionSellerIdAndStatus(member.getId(), TradeStatus.COMPLETED);
        long auctionJoinCount = bidRepository.countDistinctAuctionByBidderId(member.getId());

        return new ProfileResponse(
                member.getNickname(),
                imageUrlResolver.resolve(member.getProfileObjectKey()),
                toEpochMilli(member.getCreatedAt()),
                sellCount,
                auctionJoinCount
        );
    }

    private DepositResponse toDeposit(Member member) {
        return new DepositResponse(member.getTotalPoint(), member.getLockedPoint());
    }

    private ActiveTradeResponse toActiveTrade(
            AuctionTrade trade,
            long memberId,
            Map<Long, String> thumbnailUrls
    ) {
        Auction auction = trade.getAuction();
        boolean isBuyer = trade.getBuyer().getId().equals(memberId);

        return new ActiveTradeResponse(
                trade.getId(),
                auction.getId(),
                auction.getTitle(),
                thumbnailUrls.get(auction.getId()),
                isBuyer ? "BUYER" : "SELLER",
                buyingStatus(trade.getStatus()),
                trade.getFinalPrice()
        );
    }

    private SellingItemResponse toSellingItem(
            Auction auction,
            Map<Long, String> thumbnailUrls,
            LocalDateTime databaseTime
    ) {
        return new SellingItemResponse(
                auction.getId(),
                auction.getTitle(),
                thumbnailUrls.get(auction.getId()),
                AuctionType.from(auction).name(),
                auction.getStartPrice(),
                sellingPrice(auction),
                sellingStatus(auction.getStatus()),
                downPricing(auction, databaseTime)
        );
    }

    private long sellingPrice(Auction auction) {
        if (auction.getStatus() != AuctionStatus.COMPLETED) {
            return auction.getCurrentPrice();
        }

        // 메인 화면은 판매 물품을 최대 3건만 조회하므로 완료 거래별 단건 조회도 상한이 고정된다.
        return auctionTradeRepository.findFinalPriceByAuctionId(auction.getId())
                .orElseGet(auction::getCurrentPrice);
    }

    private DownPricingResponse downPricing(Auction auction, LocalDateTime databaseTime) {
        if (!(auction instanceof DownAuction downAuction)) {
            return null;
        }

        // 마감 시각이 지난 하락 경매는 더 이상 구매할 수 없으므로 현재가를 계속 떨어뜨리지 않는다.
        if (!databaseTime.isBefore(downAuction.getEndedAt())) {
            return null;
        }

        return new DownPricingResponse(
                downAuction.getStartPrice(),
                downAuction.getMinimumPrice(),
                downAuction.getDropPrice(),
                Duration.ofMinutes(downAuction.getPriceDropInterval()).toMillis(),
                toEpochMilli(downAuction.getStartedAt()),
                toEpochMilli(databaseTime)
        );
    }

    private BuyingItemResponse toBuyingItem(AuctionTrade trade, Map<Long, String> thumbnailUrls) {
        Auction auction = trade.getAuction();

        return new BuyingItemResponse(
                auction.getId(),
                auction.getTitle(),
                thumbnailUrls.get(auction.getId()),
                AuctionType.from(auction).name(),
                auction.getStartPrice(),
                trade.getFinalPrice(),
                buyingStatus(trade.getStatus())
        );
    }

    // 세 목록에 등장하는 경매의 대표 썸네일을 한 번에 조회해 URL로 변환한다.
    private Map<Long, String> resolveThumbnailUrls(
            List<Auction> sellingAuctions,
            List<AuctionTrade> buyingTrades,
            List<AuctionTrade> activeTrades
    ) {
        Set<Long> auctionIds = new HashSet<>();
        sellingAuctions.forEach(auction -> auctionIds.add(auction.getId()));
        buyingTrades.forEach(trade -> auctionIds.add(trade.getAuction().getId()));
        activeTrades.forEach(trade -> auctionIds.add(trade.getAuction().getId()));

        if (auctionIds.isEmpty()) {
            return Map.of();
        }

        return imageRepository.findRepresentativeThumbnails(auctionIds).stream()
                .collect(Collectors.toMap(
                        AuctionThumbnailRow::auctionId,
                        row -> imageUrlResolver.resolve(row.objectKey())
                ));
    }

    private String sellingStatus(AuctionStatus status) {
        return switch (status) {
            case OPEN, BID_ONGOING, WINNER_DETERMINING -> "ON_SALE";
            case COMPLETED -> "SOLD";
            case UNSOLD -> "FAILED";
        };
    }

    private String buyingStatus(TradeStatus status) {
        return switch (status) {
            case WAITING_CONFIRM -> "PAYMENT_PENDING";
            case CONFIRMED -> "IN_PROGRESS";
            case COMPLETED -> "DONE";
            // 실패 거래는 조회 쿼리에서 이미 걸러진다.
            case BUYER_FAILED, SELLER_FAILED ->
                    throw new IllegalStateException("구매 물품에 노출할 수 없는 거래 상태입니다: " + status);
        };
    }

    private long toEpochMilli(LocalDateTime dateTime) {
        return dateTime.atZone(SERVICE_ZONE).toInstant().toEpochMilli();
    }
}
