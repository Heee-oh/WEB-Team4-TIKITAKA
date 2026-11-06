package com.tikitaka.bidwinback.auction.application;

import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.Image;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeStatus;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.BidRepository;
import com.tikitaka.bidwinback.auction.domain.repository.ImageRepository;
import com.tikitaka.bidwinback.auction.domain.repository.SealedBidRepository;
import com.tikitaka.bidwinback.auction.presentation.dto.response.DownAuctionDetailResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.UpAuctionDetailResponse;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import com.tikitaka.bidwinback.member.domain.entity.Member;
import com.tikitaka.bidwinback.member.domain.enums.MemberStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuctionDetailServiceTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime SERVER_TIME = LocalDateTime.of(
            2026,
            8,
            1,
            12,
            35
    );

    @Mock
    private AuctionRepository auctionRepository;

    @Mock
    private BidRepository bidRepository;

    @Mock
    private SealedBidRepository sealedBidRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private AuctionTradeRepository auctionTradeRepository;

    @Mock
    private ImageUrlResolver imageUrlResolver;

    private AuctionDetailService auctionDetailService;

    @BeforeEach
    void setUp() {
        auctionDetailService = new AuctionDetailService(
                auctionRepository,
                bidRepository,
                sealedBidRepository,
                imageRepository,
                auctionTradeRepository,
                imageUrlResolver
        );
    }

    @Test
    void 상승_경매의_공통_정보와_현재가를_조회한다() {
        UpAuction auction = mock(UpAuction.class);
        Member seller = mockSeller();
        Image image = mock(Image.class);
        LocalDateTime deadline = LocalDateTime.of(2026, 8, 1, 13, 0);
        stubCommonAuction(auction, seller, deadline);
        when(auction.getStatus()).thenReturn(AuctionStatus.OPEN);
        when(auction.getBuyNowPrice()).thenReturn(300_000L);
        when(image.getObjectKey()).thenReturn("auction-images/product.jpg");
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of(image));
        when(imageUrlResolver.resolve("auction-images/product.jpg"))
                .thenReturn("https://cdn.example.com/auction-images/product.jpg");
        when(auction.getCurrentPrice()).thenReturn(240_000L);
        when(bidRepository.countByAuctionId(1L)).thenReturn(3L);

        UpAuctionDetailResponse response = (UpAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.auctionId()).isEqualTo(1L);
        assertThat(response.auctionType().name()).isEqualTo("UP");
        assertThat(response.images()).containsExactly(
                "https://cdn.example.com/auction-images/product.jpg"
        );
        assertThat(response.seller().name()).isEqualTo("판매자");
        assertThat(response.currentPrice()).isEqualTo(240_000L);
        assertThat(response.buyNowPrice()).isEqualTo(300_000L);
        assertThat(response.bidCount()).isEqualTo(3L);
        assertThat(response.deadline()).isEqualTo(toEpochMilli(deadline));
        assertThat(response.serverTime()).isEqualTo(toEpochMilli(SERVER_TIME));
        assertThat(response.sealedBidStartsAt())
                .isEqualTo(toEpochMilli(deadline.minusMinutes(5)));
        assertThat(response.seller().verified()).isTrue();
        assertThat(response.seller().dealCount()).isEqualTo(12L);
        verify(auctionTradeRepository, never()).findFinalPriceByAuctionId(1L);
    }

    @Test
    void 입찰이_들어와_진행중인_상승_경매는_즉시구매가를_응답하지_않는다() {
        UpAuction auction = mock(UpAuction.class);
        Member seller = mockSeller();
        stubCommonAuction(auction, seller, LocalDateTime.of(2026, 8, 1, 13, 0));
        // stubCommonAuction 기본값(BID_ONGOING)을 그대로 사용해, 입찰이 한 번이라도 들어와
        // 즉시구매가 더 이상 불가능해진 경매를 재현한다.
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(auction.getCurrentPrice()).thenReturn(240_000L);
        when(bidRepository.countByAuctionId(1L)).thenReturn(3L);

        UpAuctionDetailResponse response = (UpAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.buyNowPrice()).isNull();
        verify(auction, never()).getBuyNowPrice();
    }

    @Test
    void 입찰이_없는_상승_경매는_시작가를_현재가로_응답한다() {
        UpAuction auction = mock(UpAuction.class);
        stubCommonAuction(
                auction,
                mockSeller(),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(auction.getCurrentPrice()).thenReturn(200_000L);
        when(bidRepository.countByAuctionId(1L)).thenReturn(0L);

        UpAuctionDetailResponse response = (UpAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.currentPrice()).isEqualTo(200_000L);
        assertThat(response.bidCount()).isZero();
        verify(auctionTradeRepository, never()).findFinalPriceByAuctionId(1L);
    }

    @Test
    void 완료된_상승_경매는_최종_거래가와_밀봉입찰을_포함한_건수를_조회한다() {
        UpAuction auction = mock(UpAuction.class);
        stubCommonAuction(
                auction,
                mockSeller(),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
        when(auction.getStatus()).thenReturn(AuctionStatus.COMPLETED);
        when(auction.isSealedBidRevealed()).thenReturn(true);
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(auctionTradeRepository.findFinalPriceByAuctionId(1L))
                .thenReturn(Optional.of(300_000L));
        when(bidRepository.countByAuctionId(1L)).thenReturn(5L);
        when(sealedBidRepository.countByAuctionId(1L)).thenReturn(2L);

        UpAuctionDetailResponse response = (UpAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.currentPrice()).isEqualTo(300_000L);
        assertThat(response.bidCount()).isEqualTo(7L);
        verify(auctionTradeRepository).findFinalPriceByAuctionId(1L);
    }

    @Test
    void 하락_경매의_DB_기준_시각과_가격_하락_정보를_조회한다() {
        DownAuction auction = mock(DownAuction.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 1, 12, 0);
        stubCommonAuction(
                auction,
                mockSeller(),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
        when(auction.getStartedAt()).thenReturn(startedAt);
        when(auction.getMinimumPrice()).thenReturn(150_000L);
        when(auction.getDropPrice()).thenReturn(10_000L);
        when(auction.getPriceDropInterval()).thenReturn(10L);
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of());

        DownAuctionDetailResponse response = (DownAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.auctionType().name()).isEqualTo("DOWN");
        assertThat(response.priceDropIntervalMs()).isEqualTo(600_000L);
        assertThat(response.startedAt()).isEqualTo(toEpochMilli(startedAt));
        assertThat(response.serverTime()).isEqualTo(toEpochMilli(SERVER_TIME));
        assertThat(response.finalPrice()).isNull();
        verify(auctionTradeRepository, never()).findFinalPriceByAuctionId(1L);
    }

    @Test
    void 완료된_하락_경매는_최종_거래가를_응답한다() {
        DownAuction auction = mock(DownAuction.class);
        LocalDateTime startedAt = LocalDateTime.of(2026, 8, 1, 12, 0);
        stubCommonAuction(
                auction,
                mockSeller(),
                LocalDateTime.of(2026, 8, 1, 13, 0)
        );
        when(auction.getStatus()).thenReturn(AuctionStatus.COMPLETED);
        when(auction.getStartedAt()).thenReturn(startedAt);
        when(auction.getMinimumPrice()).thenReturn(150_000L);
        when(auction.getDropPrice()).thenReturn(10_000L);
        when(auction.getPriceDropInterval()).thenReturn(10L);
        when(auctionRepository.findDetailById(1L)).thenReturn(Optional.of(auction));
        when(imageRepository.findByAuctionIdOrderByIdAsc(1L)).thenReturn(List.of());
        when(auctionTradeRepository.findFinalPriceByAuctionId(1L))
                .thenReturn(Optional.of(170_000L));

        DownAuctionDetailResponse response = (DownAuctionDetailResponse)
                auctionDetailService.getDetail(1L);

        assertThat(response.finalPrice()).isEqualTo(170_000L);
        verify(auctionTradeRepository).findFinalPriceByAuctionId(1L);
    }

    @Test
    void 존재하지_않는_경매를_조회하면_예외가_발생한다() {
        when(auctionRepository.findDetailById(999L)).thenReturn(Optional.empty());

        assertThatExceptionOfType(AuctionException.class)
                .isThrownBy(() -> auctionDetailService.getDetail(999L))
                .extracting(AuctionException::getErrorCode)
                .isEqualTo(ErrorCode.AUCTION_NOT_FOUND);

        verify(auctionRepository).findDetailById(999L);
    }

    private Member mockSeller() {
        Member seller = mock(Member.class);
        when(seller.getId()).thenReturn(10L);
        when(seller.getNickname()).thenReturn("판매자");
        when(seller.getProfileObjectKey()).thenReturn("profiles/seller.png");
        when(seller.getStatus()).thenReturn(MemberStatus.ACTIVE);
        when(imageUrlResolver.resolve("profiles/seller.png"))
                .thenReturn("https://cdn.example.com/profiles/seller.png");
        when(auctionTradeRepository.countByAuctionSellerIdAndStatus(
                10L,
                TradeStatus.COMPLETED
        )).thenReturn(12L);
        return seller;
    }

    private void stubCommonAuction(
            Auction auction,
            Member seller,
            LocalDateTime deadline
    ) {
        when(auction.getId()).thenReturn(1L);
        when(auction.getTitle()).thenReturn("헤드폰");
        when(auction.getDescription()).thenReturn("미개봉 상품");
        when(auction.getCategory()).thenReturn(AuctionCategory.HOUSEHOLD);
        when(auction.getStatus()).thenReturn(AuctionStatus.BID_ONGOING);
        when(auction.getStartPrice()).thenReturn(200_000L);
        when(auction.getEndedAt()).thenReturn(deadline);
        when(auction.getTradeType()).thenReturn(TradeType.DELIVERY);
        when(auction.getSeller()).thenReturn(seller);
        when(auctionRepository.currentDatabaseTime()).thenReturn(SERVER_TIME);
    }

    private long toEpochMilli(LocalDateTime dateTime) {
        return dateTime.atZone(SEOUL).toInstant().toEpochMilli();
    }
}
