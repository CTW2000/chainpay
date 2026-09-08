package com.chainpay.chain.deposit.domain;

/** 一个收款地址：谁的、收哪种币、记到哪个账本账户、第几个派生序号。address 存小写。 */
public record DepositAddress(String address, long merchantId, String token, long accountId, long derivationIndex, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
