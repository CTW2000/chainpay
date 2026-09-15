package com.chainpay.admin.service;

import com.chainpay.common.web.ErrorCode;
import org.springframework.http.HttpStatus;

/** 管理员认证的拒绝：状态码与错误码由业务定。登录失败一律同一句话（不可区分）。 */
public class AdminAuthException extends RuntimeException {

    private final HttpStatus status;
    private final ErrorCode code;

    public AdminAuthException(HttpStatus status, ErrorCode code, String message) {
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
