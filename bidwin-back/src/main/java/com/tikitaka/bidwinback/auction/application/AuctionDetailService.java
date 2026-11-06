package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.Image;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.ImageRepository;
import com.tikitaka.bidwinback.auction.domain.repository.SealedBidRepository;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionDetailResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionSellerResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.DownAuctionDetailResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.UpAuctionDetailResponse;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.tikitaka.bidwinback.auction.domain.enums.AuctionType.DOWN;
import static com.tikitaka.bidwinback.auction.domain.enums.AuctionType.UP;

@Service
@RequiredArgsConstructor
public class AuctionDetailService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final long MILLIS_PER_MINUTE = Duration.ofMinutes(1).toMillis();
    private static final long SEALED_BID_WINDOW_MINUTES = 5L;

    private final AuctionRepository auctionRepository;
    private final BidRepository bidRepository;
    private final SealedBidRepository sealedBidRepository;
    private final ImageRepository imageRepository;
    private final AuctionTradeRepository auctionTradeRepository;
    private final ImageUrlResolver imageUrlResolver;

    @Transactional(readOnly = true)
    public AuctionDetailResponse getDetail(long auctionId) {
        Auction auction = auctionRepository.findDetailById(auctionId)
                .orElseThrow(() -> new AuctionException(ErrorCode.AUCTION_NOT_FOUND));

        List<String> imageUrls = imageRepository.findByAuctionIdOrderByIdAsc(auctionId)
                .stream()
                .map(Image::getObjectKey)
                .map(imageUrlResolver::resolve)
                .toList();

        AuctionSellerResponse seller = toSellerResponse(auction.getSeller());

        if (auction instanceof UpAuction upAuction) {
            Optional<Long> finalPrice = upAuction.getStatus() == AuctionStatus.COMPLETED
                    ? auctionTradeRepository.findFinalPriceByAuctionId(auctionId)
                    : Optional.empty();

            return toUpAuctionResponse(upAuction, imageUrls, seller, finalPrice);
        }

        if (auction instanceof DownAuction downAuction) {
            // 경매가 종료되었고, 유찰이 아닌경우 최종 가격을 가져온다. 그외의 경우 빈 값을 반환하여 시간 대비 계산된 가격을 표시
            Optional<Long> finalPrice = downAuction.getStatus() == AuctionStatus.COMPLETED
                    ? auctionTradeRepository.findFinalPriceByAuctionId(auctionId)
                    : Optional.empty();

            return toDownAuctionResponse(downAuction, imageUrls, seller, finalPrice);
        }

        throw new IllegalStateException("Unsupported auction type: " + auction.getClass().getName());
    }

    private UpAuctionDetailResponse toUpAuctionResponse(
            UpAuction auction,
            List<String> imageUrls,
            AuctionSellerResponse seller,
            Optional<Long> finalPrice
    ) {
        long currentPrice = finalPrice.orElseGet(() -> auction.getCurrentPrice());
        long bidCount = bidRepository.countByAuctionId(auction.getId());
        LocalDateTime databaseTime = auctionRepository.currentDatabaseTime();
        if (auction.isSealedBidRevealed()) {
            bidCount += sealedBidRepository.countByAuctionId(auction.getId());
        }

        return new UpAuctionDetailResponse(
                auction.getId(),
                UP,
                auction.getTitle(),
                auction.getDescription(),
                auction.getCategory(),
                auction.getStatus(),
                auction.getRevision(),
                imageUrls,
                auction.getStartPrice(),
                toEpochMilli(auction.getEndedAt()),
                toEpochMilli(databaseTime),
                toEpochMilli(auction.getEndedAt().minusMinutes(SEALED_BID_WINDOW_MINUTES)),
                auction.getTradeType(),
                seller,
                // 요구사항: 입찰이 한 번이라도 들어와 status가 OPEN을 벗어나면 즉시구매가
                // 더 이상 불가능해지므로(BuyNowTransactionService.validateAuction 참고),
                // 응답에서도 즉시구매가를 더 이상 노출하지 않는다.
                auction.getStatus() == AuctionStatus.OPEN ? auction.getBuyNowPrice() : null,
                currentPrice,
                bidCount
        );
    }

    private DownAuctionDetailResponse toDownAuctionResponse(
            DownAuction auction,
            List<String> imageUrls,
            AuctionSellerResponse seller,
            Optional<Long> finalPrice
    ) {
        long intervalMs = toIntervalMillis(auction.getPriceDropInterval());
        LocalDateTime databaseTime = auctionRepository.currentDatabaseTime();

        return new DownAuctionDetailResponse(
                auction.getId(),
                DOWN,
                auction.getTitle(),
                auction.getDescription(),
                auction.getCategory(),
                auction.getStatus(),
                auction.getRevision(),
                imageUrls,
                auction.getStartPrice(),
                toEpochMilli(auction.getStartedAt()),
                toEpochMilli(databaseTime),
                toEpochMilli(auction.getEndedAt()),
                auction.getTradeType(),
                seller,
                finalPrice.orElse(null),
                auction.getMinimumPrice(),
                auction.getDropPrice(),
                intervalMs
        );
    }

    private AuctionSellerResponse toSellerResponse(Member seller) {
        long dealCount = auctionTradeRepository.countByAuctionSellerIdAndStatus(
                seller.getId(),
                TradeStatus.COMPLETED
        );

        return new AuctionSellerResponse(
                seller.getId(),
                seller.getNickname(),
                imageUrlResolver.resolve(seller.getProfileObjectKey()),
                seller.getStatus() == MemberStatus.ACTIVE,
                dealCount
        );
    }

    private long toIntervalMillis(long intervalMinutes) {
        if (intervalMinutes <= 0) {
            throw new IllegalStateException("Price drop interval must be positive");
        }
        return Math.multiplyExact(intervalMinutes, MILLIS_PER_MINUTE);
    }

    private long toEpochMilli(LocalDateTime dateTime) {
        return dateTime.atZone(SERVICE_ZONE).toInstant().toEpochMilli();
    }
}
