package com.tikitaka.bidwinback.auction.presentation;

import com.tikitaka.bidwinback.auction.application.bid.ledger.BidLedgerException;
import com.tikitaka.bidwinback.auction.application.bid.ledger.RedisBidRuntime;
import com.tikitaka.bidwinback.auction.domain.enums.BidType;
import com.tikitaka.bidwinback.auction.presentation.dto.request.BidRequest;
import com.tikitaka.bidwinback.global.auth.AuthMember;
import com.tikitaka.bidwinback.global.auth.Login;
import com.tikitaka.bidwinback.global.common.ApiResponse;
import com.tikitaka.bidwinback.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auctions")
@ConditionalOnProperty(name = "app.bid-ledger.enabled", havingValue = "true")
public class RedisBidRequestController {
    private final RedisBidRuntime runtime;

    @PostMapping("/up/{auctionId}/bid-requests")
    public ResponseEntity<ApiResponse<RedisBidRuntime.Receipt>> accept(
            @Login AuthMember member, @PathVariable long auctionId,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId, @Valid @RequestBody BidRequest request) {
        if (request.bidType() != BidType.OPEN) throw new BidLedgerException(ErrorCode.BID_PHASE_CHANGED);
        var receipt = runtime.submitOpen(member.memberId(), auctionId, request.price(), requestId);
        return ResponseEntity.accepted().location(URI.create(receipt.resultUrl())).body(ApiResponse.success(receipt));
    }

    @GetMapping("/bid-requests/{requestId}")
    public ResponseEntity<ApiResponse<Object>> lookup(@Login AuthMember member, @PathVariable String requestId) {
        return ResponseEntity.ok(ApiResponse.success(runtime.lookup(member.memberId(), requestId)));
    }
}
