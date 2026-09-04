package com.chainpay.chain.indexer.domain;

/** 白名单里的一个代币。 */
public record ChainToken(String address, String symbol, int decimals, String status) {

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
