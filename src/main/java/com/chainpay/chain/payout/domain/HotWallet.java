package com.chainpay.chain.payout.domain;

/** 热钱包表的一行。{@code nextNonce} 是「我打算发的下一笔编号」——意图；真相在链上，由对账拉回。 */
public record HotWallet(String address, String chain, long nextNonce, String status, String haltReason) {

    public boolean halted() {
        return "HALTED".equals(status);
    }
}
