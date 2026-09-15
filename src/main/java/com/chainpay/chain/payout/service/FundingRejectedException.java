package com.chainpay.chain.payout.service;

import com.chainpay.common.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** 注资登记的拒绝（M6-②）：没有这条日志 404、还没 finalized 409、不是外部注资或指认不清 400。状态码与错误码由业务定。 */
public class FundingRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public FundingRejectedException(HttpStatus status, ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
