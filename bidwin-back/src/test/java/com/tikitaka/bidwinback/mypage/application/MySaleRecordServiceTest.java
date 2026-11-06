package com.tikitaka.bidwinback.mypage.application;

import com.tikitaka.bidwinback.auction.application.buynow.BuyNowPriceCalculator;
import com.tikitaka.bidwinback.auction.domain.entity.Auction;
import com.tikitaka.bidwinback.auction.domain.entity.DownAuction;
import com.tikitaka.bidwinback.auction.domain.entity.UpAuction;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionRepository;
import com.tikitaka.bidwinback.auction.domain.repository.AuctionTradeRepository;
import com.tikitaka.bidwinback.auction.domain.repository.ImageRepository;
import com.tikitaka.bidwinback.global.common.PageResponse;
import com.tikitaka.bidwinback.global.storage.ImageUrlResolver;
import com.tikitaka.bidwinback.mypage.presentation.dto.response.MySaleRecordResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MySaleRecordServiceTest {

    private static final long MEMBER_ID = 1L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 1, 12, 0);

    @Mock
    private AuctionRepository auctionRepository;
    @Mock
    private AuctionTradeRepository auctionTradeRepository;
    @Mock
    private ImageRepository imageRepository;
    @Mock
    private ImageUrlResolver imageUrlResolver;
    @Mock
    private BuyNowPriceCalculator buyNowPriceCalculator;

    private MySaleRecordService service;

    @BeforeEach
    void setUp() {
        service = new MySaleRecordService(
                auctionRepository,
                auctionTradeRepository,
                imageRepository,
                imageUrlResolver,
                buyNowPriceCalculator
        );
        lenient().when(auctionRepository.currentDatabaseTime()).thenReturn(NOW);
        lenient().when(imageRepository.findFirstImageByAuctionIds(anyList())).thenReturn(List.of());
    }

    @Test
    void 진행중인_상향_경매는_현재가를_그대로_보여준다() {
        UpAuction auction = mock(UpAuction.class);
        stubCommon(auction, 1L, AuctionStatus.BID_ONGOING);
        when(auction.getCurrentPrice()).thenReturn(150_000L);
        stubPage(List.of(auction));

        PageResponse<MySaleRecordResponse> response = service.getSales(MEMBER_ID, null, 1, 10, null);

        assertThat(response.items().getFirst().price()).isEqualTo(150_000L);
    }

    @Test
    void 진행중인_하향_경매는_BuyNowPriceCalculator로_현재가를_계산한다() {
        DownAuction auction = mock(DownAuction.class);
        stubCommon(auction, 2L, AuctionStatus.OPEN);
        when(buyNowPriceCalculator.calculate(auction, NOW)).thenReturn(80_000L);
        stubPage(List.of(auction));

        PageResponse<MySaleRecordResponse> response = service.getSales(MEMBER_ID, null, 1, 10, null);

        assertThat(response.items().getFirst().price()).isEqualTo(80_000L);
    }

    @Test
    void 완료된_경매는_거래의_최종가를_보여준다() {
        UpAuction auction = mock(UpAuction.class);
        stubCommon(auction, 3L, AuctionStatus.COMPLETED);
        when(auctionTradeRepository.findFinalPriceByAuctionId(3L)).thenReturn(java.util.Optional.of(300_000L));
        stubPage(List.of(auction));

        PageResponse<MySaleRecordResponse> response = service.getSales(MEMBER_ID, null, 1, 10, null);

        assertThat(response.items().getFirst().price()).isEqualTo(300_000L);
    }

    private void stubCommon(Auction auction, long id, AuctionStatus status) {
        when(auction.getId()).thenReturn(id);
        when(auction.getTitle()).thenReturn("상품" + id);
        when(auction.getStatus()).thenReturn(status);
        when(auction.getStartPrice()).thenReturn(100_000L);
        when(auction.getCreatedAt()).thenReturn(NOW.minusDays(1));
    }

    private void stubPage(List<Auction> auctions) {
        Pageable pageable = PageRequest.of(0, 10);
        Page<Auction> page = new PageImpl<>(auctions, pageable, auctions.size());
        when(auctionRepository.findBySellerIdAndStatusIn(eq(MEMBER_ID), anyList(), any())).thenReturn(page);
    }
}
