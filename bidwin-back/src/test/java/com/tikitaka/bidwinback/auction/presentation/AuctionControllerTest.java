package com.tikitaka.bidwinback.auction.presentation;

import com.tikitaka.bidwinback.auction.application.AuctionCreateService;
import com.tikitaka.bidwinback.auction.application.AuctionDetailService;
import com.tikitaka.bidwinback.auction.application.AuctionListQuery;
import com.tikitaka.bidwinback.auction.application.AuctionListService;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionCategory;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionListStatusFilter;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionSort;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionStatus;
import com.tikitaka.bidwinback.auction.domain.enums.AuctionType;
import com.tikitaka.bidwinback.auction.domain.enums.TradeType;
import com.tikitaka.bidwinback.auction.domain.exception.AuctionException;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionSellerResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.AuctionListResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.DownAuctionDetailResponse;
import com.tikitaka.bidwinback.auction.presentation.dto.response.UpAuctionDetailResponse;
import com.tikitaka.bidwinback.global.auth.AuthConstant;
import com.tikitaka.bidwinback.global.auth.AuthMemberFixture;
import com.tikitaka.bidwinback.global.auth.LoginMemberArgumentResolver;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import com.tikitaka.bidwinback.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AuctionControllerTest {

    @Mock
    private AuctionDetailService auctionDetailService;

    @Mock
    private AuctionCreateService auctionCreateService;

    @Mock
    private AuctionListService auctionListService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuctionController(
                        auctionDetailService,
                        auctionCreateService,
                        auctionListService
                ))
                .setCustomArgumentResolvers(new LoginMemberArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void 상승_경매_상세를_조회하면_200과_유형별_정보를_응답한다() throws Exception {
        when(auctionDetailService.getDetail(1L)).thenReturn(upAuctionResponse());

        mockMvc.perform(get("/api/v1/auctions/{auctionId}", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionId").value(1L))
                .andExpect(jsonPath("$.data.auctionType").value("UP"))
                .andExpect(jsonPath("$.data.revision").value(7L))
                .andExpect(jsonPath("$.data.currentPrice").value(240_000L))
                .andExpect(jsonPath("$.data.buyNowPrice").value(300_000L))
                .andExpect(jsonPath("$.data.bidCount").value(3L))
                .andExpect(jsonPath("$.data.images[0]")
                        .value("https://cdn.example.com/product.jpg"))
                .andExpect(jsonPath("$.data.seller.name").value("판매자"))
                .andExpect(jsonPath("$.data.seller.verified").value(true))
                .andExpect(jsonPath("$.data.seller.dealCount").value(12L))
                .andExpect(jsonPath("$.data.contact").doesNotExist())
                .andExpect(jsonPath("$.data.startedAt").doesNotExist());

        verify(auctionDetailService).getDetail(1L);
    }

    @Test
    void 하락_경매_상세를_조회하면_200과_가격_하락_정보를_응답한다() throws Exception {
        when(auctionDetailService.getDetail(2L)).thenReturn(downAuctionResponse());

        mockMvc.perform(get("/api/v1/auctions/{auctionId}", 2L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.auctionType").value("DOWN"))
                .andExpect(jsonPath("$.data.revision").value(3L))
                .andExpect(jsonPath("$.data.minimumPrice").value(150_000L))
                .andExpect(jsonPath("$.data.dropPrice").value(10_000L))
                .andExpect(jsonPath("$.data.priceDropIntervalMs").value(600_000L))
                .andExpect(jsonPath("$.data.serverTime").value(1_754_020_500_000L))
                .andExpect(jsonPath("$.data.contact").doesNotExist())
                .andExpect(jsonPath("$.data.currentPrice").doesNotExist());

        verify(auctionDetailService).getDetail(2L);
    }

    @Test
    void 존재하지_않는_경매를_조회하면_404를_응답한다() throws Exception {
        when(auctionDetailService.getDetail(999L))
                .thenThrow(new AuctionException(ErrorCode.AUCTION_NOT_FOUND));

        mockMvc.perform(get("/api/v1/auctions/{auctionId}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUCTION_404_1"));
    }

    @Test
    void 목록_조회_중_예상치_못한_실패가_나면_내부_정보를_숨긴_500을_응답한다() throws Exception {
        // given
        when(auctionListService.getList(any(AuctionListQuery.class)))
                .thenThrow(new IllegalStateException("jdbc:mysql://internal-db/bidwin"));

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/auctions"));

        // then
        result
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_500_1"))
                .andExpect(jsonPath("$.error.message")
                        .value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    void 비동기_스냅샷_생성이_실패해도_내부_정보를_숨긴_500을_응답한다() throws Exception {
        when(auctionListService.getList(any(AuctionListQuery.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new IllegalStateException("redis://internal-cache")
                ));

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/auctions")
                        .param("auctionType", "DOWN")
                        .param("sort", "priceLow"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("COMMON_500_1"))
                .andExpect(jsonPath("$.error.message")
                        .value("서버 내부 오류가 발생했습니다."));
    }

    @Test
    void 비동기_목록_조회_시간이_초과되면_표준_503을_응답한다() throws Exception {
        when(auctionListService.getList(any(AuctionListQuery.class)))
                .thenReturn(CompletableFuture.failedFuture(
                        new AsyncRequestTimeoutException()
                ));

        MvcResult asyncResult = mockMvc.perform(get("/api/v1/auctions"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_503_2"))
                .andExpect(jsonPath("$.error.message")
                        .value("요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."));
    }

    @Test
    void 목록_필터를_요청하면_상태와_카테고리를_조회_조건으로_전달한다() throws Exception {
        // given
        when(auctionListService.getList(any(AuctionListQuery.class)))
                .thenReturn(CompletableFuture.completedFuture(
                        new AuctionListResponse(List.of(), 0L, 0L, 1, false)
                ));

        // when
        MvcResult asyncResult = mockMvc.perform(get("/api/v1/auctions")
                        .param("status", "ACTIVE")
                        .param("category", "ELECTRONICS"))
                .andExpect(request().asyncStarted())
                .andReturn();

        // then
        mockMvc.perform(asyncDispatch(asyncResult))
                .andExpect(status().isOk());
        verify(auctionListService).getList(new AuctionListQuery(
                null,
                AuctionSort.RECOMMENDED,
                null,
                AuctionListStatusFilter.ACTIVE,
                AuctionCategory.ELECTRONICS,
                1,
                null
        ));
    }

    @ParameterizedTest
    @ValueSource(ints = {15, 17, 100})
    void 페이지_크기가_16이_아니면_400이고_목록을_조회하지_않는다(int size) throws Exception {
        ResultActions result = mockMvc.perform(get("/api/v1/auctions")
                .param("size", Integer.toString(size)));

        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_400_1"))
                .andExpect(jsonPath("$.error.message").value("페이지 크기는 16이어야 합니다."));
        verifyNoInteractions(auctionListService);
    }

    @Test
    void 지원하지_않는_경매_상태_필터면_400이고_목록을_조회하지_않는다() throws Exception {
        // given
        String unsupportedStatus = "PAUSED";

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/auctions")
                .param("status", unsupportedStatus));

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_400_1"));
        verifyNoInteractions(auctionListService);
    }

    @Test
    void 지원하지_않는_카테고리_필터면_400이고_목록을_조회하지_않는다() throws Exception {
        // given
        String unsupportedCategory = "DIGITAL";

        // when
        ResultActions result = mockMvc.perform(get("/api/v1/auctions")
                .param("category", unsupportedCategory));

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_400_1"));
        verifyNoInteractions(auctionListService);
    }

    @Test
    void 등록_요청의_필수값이_비어_있으면_400이고_경매를_등록하지_않는다() throws Exception {
        // given
        String request = """
                {
                  "draftId": "8097514e-ae2a-4f1f-81da-d8fb25270188",
                  "title": " ",
                  "description": "미개봉 상품",
                  "category": "HOUSEHOLD",
                  "contact": "01012345678",
                  "auctionType": "UP",
                  "tradeType": "DELIVERY",
                  "durationMinutes": 60,
                  "startPrice": 200000,
                  "buyNowPrice": 300000,
                  "imageUploadIds": ["a2ddf707-cc3b-43d0-8c92-b86e8da74bc6"]
                }
                """;

        // when
        ResultActions result = mockMvc.perform(post("/api/v1/auctions")
                        .requestAttr(
                                AuthConstant.REQUEST_ATTRIBUTE_KEY,
                                AuthMemberFixture.of(1L)
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request));

        // then
        result
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("COMMON_400_1"))
                .andExpect(jsonPath("$.error.message").value("제목은 필수입니다."));

        verifyNoInteractions(auctionCreateService);
    }

    private UpAuctionDetailResponse upAuctionResponse() {
        return new UpAuctionDetailResponse(
                1L,
                AuctionType.UP,
                "헤드폰",
                "미개봉 상품",
                AuctionCategory.HOUSEHOLD,
                AuctionStatus.BID_ONGOING,
                7L,
                List.of("https://cdn.example.com/product.jpg"),
                200_000L,
                1_754_022_000_000L,
                1_754_020_500_000L,
                1_754_021_700_000L,
                TradeType.DELIVERY,
                new AuctionSellerResponse(
                        10L,
                        "판매자",
                        "https://cdn.example.com/profile.jpg",
                        true,
                        12L
                ),
                300_000L,
                240_000L,
                3L
        );
    }

    private DownAuctionDetailResponse downAuctionResponse() {
        return new DownAuctionDetailResponse(
                2L,
                AuctionType.DOWN,
                "냉장고",
                "이사 정리",
                AuctionCategory.HOUSEHOLD,
                AuctionStatus.BID_ONGOING,
                3L,
                List.of(),
                200_000L,
                1_754_018_400_000L,
                1_754_020_500_000L,
                1_754_022_000_000L,
                TradeType.DIRECT,
                new AuctionSellerResponse(
                        10L,
                        "판매자",
                        "https://cdn.example.com/profile.jpg",
                        true,
                        12L
                ),
                null,
                150_000L,
                10_000L,
                600_000L
        );
    }
}
