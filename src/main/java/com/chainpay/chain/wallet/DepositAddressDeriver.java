package com.chainpay.chain.wallet;

import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 从账户层 xpub（m/44'/60'/0'，深度 3）派生第 i 个收款地址 m/44'/60'/0'/0/i。服务端唯一需要的入口。
 *
 * <p>xpub 必填：容器按 {@code chainpay.deposit.xpub}（环境变量 CHAINPAY_DEPOSIT_XPUB）造它，没配或留空就拒绝启动——
 * 收款地址是必需的，不能半装配。离线工具与测试直接 new。
 *
 * <p>深度不是 3 就拒绝：配了根 xpub 或找零层 xpub，派出来的地址没有任何钱包会持有它的私钥，打进去的钱永远丢失。
 * 深度只能证明「是第三层」，证明不了「是 44'/60'/0' 这一支」（硬化路径从 xpub 推不回来），
 * 那一环靠工具打印的前三个地址、以及启动日志里的指纹与 0/0 地址，与钱包对照。
 */
@Component
public final class DepositAddressDeriver {

    private static final int ACCOUNT_DEPTH = 3;
    private static final long EXTERNAL_CHAIN = 0;     // BIP-44 的「找零」层：0 = 对外收款，1 = 找零

    private final ExtendedPublicKey externalChain;
    private final String accountFingerprint;

    /** 默认值是空串：没配与留空走同一条路，报一句说得清的话，而不是「占位符解析不了」。 */
    public DepositAddressDeriver(@Value("${chainpay.deposit.xpub:}") String accountXpub) {
        if (accountXpub == null || accountXpub.isBlank()) {
            throw new IllegalArgumentException("没配收款 xpub：设 CHAINPAY_DEPOSIT_XPUB（账户层 m/44'/60'/0'，断网跑 tools/xpub.sh 得到）");
        }
        ExtendedPublicKey account = ExtendedPublicKey.parse(accountXpub);
        if (account.depth() != ACCOUNT_DEPTH) {
            throw new IllegalArgumentException("收款地址要从账户层 xpub 派生（m/44'/60'/0'，深度 " + ACCOUNT_DEPTH
                    + "），收到深度 " + account.depth() + "：配错层派出来的地址没有人持有私钥，钱会永远丢失");
        }
        this.accountFingerprint = HexFormat.of().formatHex(account.fingerprint());
        this.externalChain = account.deriveChild(EXTERNAL_CHAIN);
    }

    /** m/44'/60'/0'/0/index 的地址，EIP-55 写法。 */
    public String addressAt(long index) {
        return externalChain.deriveChild(index).ethAddress();
    }

    /** 账户层 xpub 的指纹（十六进制）：启动日志给运营对照用，xpub 本身不进日志。 */
    public String accountFingerprint() {
        return accountFingerprint;
    }
}
