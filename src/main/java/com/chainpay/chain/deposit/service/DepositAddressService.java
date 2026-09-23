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
import org.springframework.transaction.annotation.Transactional;

/**
 * 分配收款地址：一户一币一址。
 *
 * <p>顺序：白名单核对 → 商户核对 → 已有就返回 → 确保账本账户 → 取序号 → 派生 → 插入。
 * 最后一步的唯一性由约束裁决：两个实例同时为同一商户申请，一个插进去、另一个 ON CONFLICT 什么都不做，
 * 然后读回赢家的地址；输的一方取走的序号作废（跳号无害）。地址主键冲突则不同：那是序号被重用或 xpub 配错，报出来。
 *
 * <p>整个过程在调用方的事务里（REQUIRED）：HTTP 调用方在 asMerchant 的事务中，RLS 变量已经设好；
 * 地址派生是纯计算，没有网络 IO，所以放在事务里不违反「网络在事务外」。
 *
 * <p><b>事务靠 Spring 代理生效</b>（见 CLAUDE.md「事务的两种写法」），代价是三条纪律，违反时响的程度各不相同：
 * <ul>
 *   <li>本类与 {@link #allocate} 都不能加 final：final 类生成不了代理，启动就失败；final 方法代理拦不住，启动只打一行 WARN，
 *       调用时方法体跑在代理对象上、字段全是 null，第一行就空指针，而且不在事务里；</li>
 *   <li>本类里的其它方法不能通过 this 调 {@link #allocate}：自己调自己不经过代理，<b>不报错、悄悄没有事务</b>；</li>
 *   <li>在容器外 new 出来的实例没有代理：同样<b>不报错、悄悄没有事务</b>，测试里这样用时，事务必须由外层（如 asMerchant）提供。</li>
 * </ul>
 * 删掉注解本身也是静默的（唯一的调用方外面包着 asMerchant 的事务），
 * 所以「容器给的是代理、方法带 REQUIRED、类与方法都不是 final」由 DepositAddressServiceTest 的守卫测试钉住。
 */
public class DepositAddressService {

    /** 代币不在白名单里或已停用：对外是 2008，换个代币再来。 */
    public static class UnsupportedTokenException extends RuntimeException {
        public UnsupportedTokenException(String message) {
            super(message);
        }
    }

    private final DepositAddressDeriver deriver;
    private final DepositAddressRepository addresses;
    private final ChainTokenRepository tokens;

    public DepositAddressService(DepositAddressDeriver deriver, DepositAddressRepository addresses, ChainTokenRepository tokens) {
        this.deriver = deriver;
        this.addresses = addresses;
        this.tokens = tokens;
    }

    @Transactional
    public DepositAddress allocate(long merchantId, String tokenAddress) {
        String token = EthAddress.lowercase(tokenAddress);
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
    }
}
