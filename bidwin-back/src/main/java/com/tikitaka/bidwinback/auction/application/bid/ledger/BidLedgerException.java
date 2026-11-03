package com.tikitaka.bidwinback.auction.application.bid.ledger;

import com.tikitaka.bidwinback.global.exception.BusinessException;
import com.tikitaka.bidwinback.global.exception.ErrorCode;

public final class BidLedgerException extends BusinessException {
    public BidLedgerException(ErrorCode code) { super(code); }
}
