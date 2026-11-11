package com.tikitaka.bidwinback.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    // Common
    INVALID_INPUT_VALUE(
            HttpStatus.BAD_REQUEST,
            "COMMON_400_1",
            "요청 값이 올바르지 않습니다."
    ),
    METHOD_NOT_ALLOWED(
            HttpStatus.METHOD_NOT_ALLOWED,
            "COMMON_405_1",
            "지원하지 않는 HTTP 메서드입니다."
    ),
    ENTITY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "COMMON_404_1",
            "요청한 리소스를 찾을 수 없습니다."
    ),
    INTERNAL_SERVER_ERROR(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "COMMON_500_1",
            "서버 내부 오류가 발생했습니다."
    ),
    SSE_CONNECTION_LIMIT_EXCEEDED(
            HttpStatus.SERVICE_UNAVAILABLE,
            "COMMON_503_1",
            "실시간 연결이 많아 잠시 후 다시 시도해주세요."
    ),
    ASYNC_REQUEST_TIMEOUT(
            HttpStatus.SERVICE_UNAVAILABLE,
            "COMMON_503_2",
            "요청 처리 시간이 초과되었습니다. 잠시 후 다시 시도해주세요."
    ),

    // Member / authentication
    INVALID_CREDENTIALS(
            HttpStatus.UNAUTHORIZED,
            "MEMBER_401_1",
            "이메일 또는 비밀번호가 일치하지 않습니다."
    ),
    UNAUTHENTICATED(
            HttpStatus.UNAUTHORIZED,
            "MEMBER_401_2",
            "로그인 세션이 없거나 만료되었습니다."
    ),
    AUTHENTICATION_UNAVAILABLE(
            HttpStatus.SERVICE_UNAVAILABLE,
            "MEMBER_503_1",
            "인증 처리를 일시적으로 완료할 수 없습니다. 잠시 후 다시 시도해주세요."
    ),
    INVALID_PASSWORD_FORMAT(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_1",
            "비밀번호는 특수문자를 1개 이상 포함해야 합니다."
    ),
    TERMS_NOT_AGREED(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_2",
            "이용약관 및 개인정보 처리방침에 동의해야 가입할 수 있습니다."
    ),
    PASSWORD_CONFIRMATION_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_3",
            "새 비밀번호와 확인 비밀번호가 일치하지 않습니다."
    ),
    INVALID_PASSWORD_RESET_TOKEN(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_4",
            "유효하지 않은 비밀번호 재설정 토큰입니다."
    ),
    EXPIRED_PASSWORD_RESET_TOKEN(
            HttpStatus.GONE,
            "MEMBER_410_1",
            "비밀번호 재설정 토큰이 만료되었습니다."
    ),
    INVALID_EMAIL_VERIFICATION_TOKEN(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_5",
            "유효하지 않은 이메일 인증 토큰입니다."
    ),
    CURRENT_PASSWORD_MISMATCH(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_6",
            "현재 비밀번호가 일치하지 않습니다."
    ),
    NEW_PASSWORD_SAME_AS_CURRENT(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_7",
            "새 비밀번호는 현재 비밀번호와 달라야 합니다."
    ),
    EXPIRED_EMAIL_VERIFICATION_TOKEN(
            HttpStatus.GONE,
            "MEMBER_410_2",
            "이메일 인증 토큰이 만료되었습니다."
    ),
    INVALID_POINT_CHARGE_AMOUNT(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_8",
            "충전 금액은 1,000원 이상 1,000원 단위로 입력해야 합니다."
    ),
    POINT_CHARGE_AMOUNT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "MEMBER_400_9",
            "충전 금액은 1회 최대 1억 원까지 가능합니다."
    ),
    DUPLICATE_EMAIL(
            HttpStatus.CONFLICT,
            "MEMBER_409_1",
            "이미 가입된 이메일입니다."
    ),
    DUPLICATE_NICKNAME(
            HttpStatus.CONFLICT,
            "MEMBER_409_2",
            "이미 사용 중인 닉네임입니다."
    ),
    // 회원가입과 로그인 양쪽에서 쓰이므로 두 상황에 모두 맞는 문구를 유지한다.
    EMAIL_VERIFICATION_PENDING(
            HttpStatus.CONFLICT,
            "MEMBER_409_3",
            "이메일 인증이 완료되지 않은 계정입니다. 이메일 인증을 완료해주세요."
    ),
    MEMBER_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "MEMBER_404_1",
            "존재하지 않는 회원입니다."
    ),
    FORBIDDEN_ACCESS(
            HttpStatus.FORBIDDEN,
            "MEMBER_403_1",
            "해당 리소스에 접근할 권한이 없습니다."
    ),
    MEMBER_NOT_ACTIVE(
            HttpStatus.FORBIDDEN,
            "MEMBER_403_2",
            "활성 상태의 회원만 구매할 수 있습니다."
    ),

    // Auction
    AUCTION_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "AUCTION_404_1",
            "존재하지 않는 경매입니다."
    ),
    CATEGORY_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "AUCTION_404_2",
            "존재하지 않는 카테고리입니다."
    ),
    IMAGE_MIN_COUNT_VIOLATION(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_1",
            "상품 이미지는 최소 1장 이상 등록해야 합니다."
    ),
    INVALID_START_PRICE_UNIT(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_2",
            "시작가는 최소 1,000원 단위로 설정해야 합니다."
    ),
    INVALID_BID_UNIT(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_3",
            "입찰 호가 단위가 올바르지 않습니다."
    ),
    INVALID_BUY_NOW_PRICE(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_4",
            "즉시구매가는 시작가보다 높아야 합니다."
    ),
    INVALID_MINIMUM_PRICE(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_5",
            "최저가는 시작가보다 낮아야 합니다."
    ),
    INVALID_DURATION(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_6",
            "경매 마감 시간은 6분/30분/1시간/3시간/6시간 중에서 선택해야 합니다."
    ),
    LOCATION_REQUIRED_FOR_DIRECT_TRADE(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_7",
            "직거래를 선택한 경우 거래 희망 위치를 입력해야 합니다."
    ),
    INVALID_PRICE_UNIT(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_8",
            "가격은 1,000원 단위로 입력해야 합니다."
    ),
    AUCTION_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "AUCTION_403_1",
            "본인의 경매가 아닙니다."
    ),
    AUCTION_NOT_ONGOING(
            HttpStatus.CONFLICT,
            "AUCTION_409_1",
            "진행 중인 경매가 아닙니다."
    ),
    AUCTION_ALREADY_ENDED(
            HttpStatus.CONFLICT,
            "AUCTION_409_2",
            "이미 종료된 경매입니다."
    ),
    AUCTION_ALREADY_TRADED(
            HttpStatus.CONFLICT,
            "AUCTION_409_3",
            "이미 낙찰 처리된 경매입니다."
    ),
    AUCTION_MODIFICATION_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "AUCTION_409_4",
            "등록된 경매는 수정하거나 삭제할 수 없습니다."
    ),
    UP_BUY_NOW_CLOSED_NEAR_DEADLINE(
            HttpStatus.CONFLICT,
            "AUCTION_409_5",
            "상향 경매는 마감 5분 전까지만 즉시구매할 수 있습니다."
    ),
    INVALID_SORT(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_9",
            "지원하지 않는 정렬 기준입니다."
    ),
    PRICE_LIMIT_EXCEEDED(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_10",
            "가격은 1,000억 원 미만이어야 합니다."
    ),
    INVALID_PRICE_DROP_INTERVAL(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_11",
            "인하 주기는 1분/3분/5분/10분 중에서 선택해야 합니다."
    ),
    PRICE_DROP_INTERVAL_EXCEEDS_DURATION(
            HttpStatus.BAD_REQUEST,
            "AUCTION_400_12",
            "인하 주기는 경매 진행 시간보다 길 수 없습니다."
    ),

    // Upload
    UNSUPPORTED_IMAGE_TYPE(
            HttpStatus.BAD_REQUEST,
            "UPLOAD_400_1",
            "지원하지 않는 이미지 형식입니다."
    ),
    INVALID_IMAGE_REFERENCE(
            HttpStatus.BAD_REQUEST,
            "UPLOAD_400_2",
            "존재하지 않거나 본인이 업로드하지 않은 이미지입니다."
    ),

    // Bid
    BID_PRICE_TOO_LOW(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "BID_422_1",
            "입찰가는 현재가보다 호가 단위 이상 높아야 합니다."
    ),
    SELF_BID_NOT_ALLOWED(
            HttpStatus.FORBIDDEN,
            "BID_403_1",
            "본인이 등록한 경매에는 입찰할 수 없습니다."
    ),
    INSUFFICIENT_DEPOSIT(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "BID_422_2",
            "보증금이 부족합니다."
    ),
    BUY_NOW_PRICE_NOT_SET(
            HttpStatus.BAD_REQUEST,
            "BID_400_1",
            "즉시구매가가 설정되지 않은 경매입니다."
    ),
    NOT_UP_AUCTION(
            HttpStatus.BAD_REQUEST,
            "BID_400_2",
            "상향 경매만 입찰할 수 있습니다."
    ),
    BUY_NOW_PRICE_CHANGED(
            HttpStatus.CONFLICT,
            "BID_409_1",
            "즉시구매가가 변경되어 처리할 수 없습니다. 최신 가격을 다시 확인해주세요."
    ),
    CONCURRENT_TRADE_CONFLICT(
            HttpStatus.CONFLICT,
            "BID_409_2",
            "다른 사용자가 먼저 구매를 확정했습니다."
    ),
    SELF_PURCHASE_NOT_ALLOWED(
            HttpStatus.FORBIDDEN,
            "BID_403_2",
            "본인이 등록한 경매는 구매할 수 없습니다."
    ),
    IDEMPOTENCY_KEY_REUSED(
            HttpStatus.CONFLICT,
            "BID_409_3",
            "멱등 키를 다른 구매 요청에 재사용할 수 없습니다."
    ),
    CONCURRENT_BID_CONFLICT(
            HttpStatus.CONFLICT,
            "BID_409_4",
            "동시 입찰 처리에 실패했습니다. 최신 가격을 확인한 뒤 다시 시도해주세요."
    ),
    SEALED_BID_ALREADY_SUBMITTED(
            HttpStatus.CONFLICT,
            "BID_409_5",
            "해당 경매에는 밀봉입찰을 한 번만 제출할 수 있습니다."
    ),
    BID_PHASE_CHANGED(
            HttpStatus.CONFLICT,
            "BID_409_6",
            "입찰 단계가 변경되었습니다. 최신 경매 상태를 확인한 뒤 다시 입찰해주세요."
    ),

    // Deposit
    DEPOSIT_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "DEPOSIT_404_1",
            "해당 경매의 보증금 내역을 찾을 수 없습니다."
    ),
    DEPOSIT_ALREADY_SETTLED(
            HttpStatus.CONFLICT,
            "DEPOSIT_409_1",
            "이미 반환되었거나 몰수된 보증금입니다."
    ),
    DEPOSIT_AMOUNT_MISMATCH(
            HttpStatus.CONFLICT,
            "DEPOSIT_409_2",
            "보증금 예약 금액이 기대한 금액과 일치하지 않습니다."
    ),
    DEPOSIT_CONCURRENT_CONFLICT(
            HttpStatus.CONFLICT,
            "DEPOSIT_409_3",
            "보증금 처리 중 다른 요청과 충돌했습니다. 잠시 후 다시 시도해주세요."
    ),

    // Trade / settlement
    TRADE_NOT_FOUND(
            HttpStatus.NOT_FOUND,
            "TRADE_404_1",
            "존재하지 않는 거래입니다."
    ),
    TRADE_ACCESS_DENIED(
            HttpStatus.FORBIDDEN,
            "TRADE_403_1",
            "본인의 거래가 아닙니다."
    ),
    TRADE_ALREADY_CONFIRMED(
            HttpStatus.CONFLICT,
            "TRADE_409_1",
            "이미 확인 처리된 거래입니다."
    ),
    INVALID_TRADE_STATUS_TRANSITION(
            HttpStatus.CONFLICT,
            "TRADE_409_2",
            "현재 거래 상태에서는 요청한 상태로 변경할 수 없습니다."
    ),
    REFUND_NOT_ALLOWED(
            HttpStatus.CONFLICT,
            "TRADE_409_3",
            "환불/취소가 불가능한 거래 상태입니다."
    ),
    DELIVERY_ADDRESS_REQUIRED(
            HttpStatus.BAD_REQUEST,
            "TRADE_400_1",
            "택배 거래는 배송지 입력이 필요합니다."
    ),
    SETTLEMENT_NOT_AVAILABLE(
            HttpStatus.CONFLICT,
            "TRADE_409_4",
            "정산 가능한 상태의 거래가 아닙니다."
    );

    private final HttpStatus status;
    private final String code;
    private final String message;

    ErrorCode(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
