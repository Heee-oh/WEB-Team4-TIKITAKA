package com.tikitaka.bidwinback.auction.presentation.dto.response;

import com.tikitaka.bidwinback.auction.application.SnapshotResetReason;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record AuctionListResponse(
        List<AuctionSummaryResponse> items,
        // 응답 생성 시각. 클라이언트 시계 보정과 실시간 하락가 계산에 사용한다.
        @Schema(description = "서버 응답 생성 시각(epoch milliseconds)", example = "1786860000000")
        long serverTime,
        // 목록 계산 기준 시각. 추천순은 요청마다 DB 현재 시각을 새로 사용한다.
        @Schema(description = "다음 페이지 요청에 재사용할 목록 기준 시각(epoch milliseconds)", example = "1786860000000")
        long asOf,

        @Schema(description = "현재 페이지. 1부터 시작", example = "1")
        int page,

        @Schema(description = "다음 페이지 존재 가능 여부. 스냅샷 없는 조회는 마지막 페이지가 정확히 16건이면 다음 페이지가 비어 있을 수 있음", example = "true")
        boolean hasNext,

        @Schema(description = "요청 세대 만료로 최신 1페이지로 초기화했는지 여부")
        boolean snapshotReset,

        @Schema(description = "스냅샷 초기화 사유", nullable = true)
        SnapshotResetReason snapshotResetReason
) {

    public AuctionListResponse(
            List<AuctionSummaryResponse> items,
            long serverTime,
            long asOf,
            int page,
            boolean hasNext
    ) {
        this(items, serverTime, asOf, page, hasNext, false, null);
    }
}
