package com.tikitaka.bidwinback.mypage.application;

import com.tikitaka.bidwinback.auction.application.buynow.BuyNowPriceCalculator;
import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.ImageRepository;
import com.tikitaka.bidwinback.global.common.PageResponse;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import com.tikitaka.bidwinback.mypage.domain.RecordPageRequest;
import com.tikitaka.bidwinback.mypage.domain.enums.RecordSort;
import com.tikitaka.bidwinback.mypage.domain.enums.SellingStatusFilter;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.MySaleRecordResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MySaleRecordService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final AuctionRepository auctionRepository;
    private final AuctionTradeRepository auctionTradeRepository;
    private final ImageRepository imageRepository;
    private final ImageUrlResolver imageUrlResolver;
    private final BuyNowPriceCalculator buyNowPriceCalculator;

    @Transactional(readOnly = true)
    public PageResponse<MySaleRecordResponse> getSales(
            long memberId,
            String statusFilter,
            int pageNumber,
            int size,
            String sort
    ) {
        List<AuctionStatus> statuses = SellingStatusFilter.statusesOf(statusFilter);
        Pageable pageable = RecordPageRequest.of(pageNumber, size, "createdAt", RecordSort.from(sort));

        Page<Auction> page = auctionRepository.findBySellerIdAndStatusIn(memberId, statuses, pageable);
        List<Auction> auctions = page.getContent();

        LocalDateTime asOf = auctionRepository.currentDatabaseTime();
        Map<Long, String> thumbnails = fetchThumbnails(auctions);

        List<MySaleRecordResponse> items = auctions.stream()
                .map(auction -> toResponse(auction, asOf, thumbnails))
                .toList();

        return PageResponse.from(page, items);
    }

    private Map<Long, String> fetchThumbnails(List<Auction> auctions) {
        List<Long> auctionIds = auctions.stream().map(Auction::getId).toList();
        if (auctionIds.isEmpty()) {
            return Map.of();
        }
        return imageRepository.findFirstImageByAuctionIds(auctionIds).stream()
                .collect(Collectors.toMap(
                        image -> image.getAuction().getId(),
                        image -> imageUrlResolver.resolve(image.getObjectKey())));
    }

    private MySaleRecordResponse toResponse(Auction auction, LocalDateTime asOf, Map<Long, String> thumbnails) {
        return new MySaleRecordResponse(
                auction.getId(),
                auction.getTitle(),
                thumbnails.get(auction.getId()),
                AuctionType.from(auction),
                auction.getStartPrice(),
                resolvePrice(auction, asOf),
                auction.getStatus(),
                toEpochMilli(auction.getCreatedAt())
        );
    }

    private long resolvePrice(Auction auction, LocalDateTime asOf) {
        if (auction.getStatus() == AuctionStatus.COMPLETED) {
            return auctionTradeRepository.findFinalPriceByAuctionId(auction.getId())
                    .orElseGet(() -> resolveLivePrice(auction, asOf));
        }
        return resolveLivePrice(auction, asOf);
    }

    private long resolveLivePrice(Auction auction, LocalDateTime asOf) {
        if (auction instanceof UpAuction upAuction) {
            return upAuction.getCurrentPrice();
        }
        if (auction instanceof DownAuction downAuction) {
            return buyNowPriceCalculator.calculate(downAuction, asOf);
        }
        throw new IllegalStateException("지원하지 않는 경매 유형입니다: " + auction.getClass());
    }

    private long toEpochMilli(LocalDateTime dateTime) {
        return dateTime.atZone(SERVICE_ZONE).toInstant().toEpochMilli();
    }
}
