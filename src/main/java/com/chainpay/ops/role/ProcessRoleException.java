package com.chainpay.ops.role;

/** 进程角色不成立：没有角色、两个角色，或者环境里有这个角色不该拿的凭证。报错只含变量名，永不含值。 */
public final class ProcessRoleException extends IllegalStateException {

    public ProcessRoleException(String message) {
        super(message);
    }
}
