package com.chainpay.chain.deposit.service;

import com.chainpay.chain.deposit.domain.DepositAddress;
import com.chainpay.chain.deposit.repository.DepositAddressRepository;
import com.chainpay.chain.deposit.repository.DepositAddressRepository.MerchantRow;
import com.chainpay.chain.indexer.domain.ChainToken;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import com.chainpay.chain.wallet.DepositAddressDeriver;
import com.chainpay.chain.wallet.EthAddress;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分配收款地址：一户一币一址。
 *
 * <p>顺序：白名单核对 → 商户核对 → 已有就返回 → 确保账本账户 → 取序号 → 派生 → 插入。
 * 最后一步的唯一性由约束裁决：两个实例同时为同一商户申请，一个插进去、另一个 ON CONFLICT 什么都不做，
 * 然后读回赢家的地址；输的一方取走的序号作废（跳号无害）。地址主键冲突则不同：那是序号被重用或 xpub 配错，报出来。
 *
 * <p>整个过程在调用方的事务里（REQUIRED）：HTTP 调用方在 asMerchant 的事务中，RLS 变量已经设好；
 * 地址派生是纯计算，没有网络 IO，所以放在事务里不违反「网络在事务外」。
 */
public final class DepositAddressService {

    /** 代币不在白名单里或已停用：对外是 2008，换个代币再来。 */
    public static class UnsupportedTokenException extends RuntimeException {
        public UnsupportedTokenException(String message) {
            super(message);
        }
    }

    private final DepositAddressDeriver deriver;
    private final DepositAddressRepository addresses;
    private final ChainTokenRepository tokens;
    private final TransactionTemplate tx;

    public DepositAddressService(DepositAddressDeriver deriver, DepositAddressRepository addresses,
                                 ChainTokenRepository tokens, TransactionTemplate tx) {
        this.deriver = deriver;
        this.addresses = addresses;
        this.tokens = tokens;
        this.tx = tx;
    }

    public DepositAddress allocate(long merchantId, String tokenAddress) {
        String token = EthAddress.lowercase(tokenAddress);
        return tx.execute(status -> {
            ChainToken chainToken = tokens.find(token)
                    .orElseThrow(() -> new UnsupportedTokenException("代币未登记：" + token + "。只为白名单里的代币分配收款地址"));
            if (!chainToken.isActive()) {
                throw new UnsupportedTokenException("代币已停用：" + token);
            }
            MerchantRow merchant = addresses.findMerchant(merchantId)
                    .orElseThrow(() -> new IllegalStateException("商户不存在：" + merchantId));
            if (!"ACTIVE".equals(merchant.status())) {
                throw new IllegalStateException("商户已停用：" + merchantId);
            }
            Optional<DepositAddress> existing = addresses.find(merchantId, token);
            if (existing.isPresent()) {
                return existing.get();
            }
            long accountId = addresses.ensureAccount(merchantId, merchant.code(), chainToken.symbol());
            long index = addresses.nextIndex();
            String address = EthAddress.lowercase(deriver.addressAt(index));
            DepositAddress candidate = new DepositAddress(address, merchantId, token, accountId, index, "ACTIVE");
            try {
                if (addresses.insertIfAbsent(candidate)) {
                    return candidate;
                }
            } catch (DuplicateKeyException e) {
                throw new IllegalStateException("序号 " + index + " 派生出的地址 " + address + " 已被占用："
                        + "序号被重用，或 CHAINPAY_DEPOSIT_XPUB 配错（与别的环境共用了同一把 xpub）。不分配、不猜", e);
            }
            // 查过「没有」之后有人插了队：赢家的行已经提交，读回它
            return addresses.find(merchantId, token)
                    .orElseThrow(() -> new IllegalStateException("并发分配后读不到地址：" + merchantId + " " + token));
        });
    }
}
