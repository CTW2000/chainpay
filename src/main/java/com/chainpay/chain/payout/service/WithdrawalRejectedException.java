package com.chainpay.chain.payout.service;

import com.chainpay.common.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** 申请提现被业务规则拒绝：状态码与错误码由抛的地方定，消息可以给商户看（不含内部结构）。 */
public class WithdrawalRejectedException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public WithdrawalRejectedException(HttpStatus status, ErrorCode code, String message) {
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
