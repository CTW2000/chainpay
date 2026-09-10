package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 类型 2（EIP-1559）交易的官方向量：ethereum/tests 的 TransactionTests/ttEIP1559/GasLimitPriceProductOverflowtMinusOne.json
 * 与 maxFeePerGas00prefix.json（提交 c67e485f，2026-09-09 浅克隆逐字抄录）。前者给出原文、交易哈希、发送方，
 * 我们把原文拆回字段、再用自己的编码器编回去，字节必须一样；再从签名恢复发送方，必须一样。后者的 maxFeePerGas 带前导零，必须拒绝。
 */
@DisplayName("M4-① · EIP-1559 官方向量：拆开、编回、恢复发送方")
class Eip1559VectorTest {

    static final String TXBYTES = "0x02f885018084773594009f02ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82520894"
            + "095e7baea6a6c7c4c2dfeb977efac326af552d878080c080"
            + "a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948"
            + "a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36";
    static final String HASH = "0xdad8bff3ecfcf95169b1d5625b47f3372be795802bc4fe570991cf332f609334";
    static final String SENDER = "0xae2aec498d20869d441eaaf708fb1e375ae1787d";

    static final String LEADING_ZERO_MAX_FEE = "0x02f88701808477359400a100ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff82520894"
            + "095e7baea6a6c7c4c2dfeb977efac326af552d878080c080"
            + "a05cbd172231fc0735e0fb994dd5b1a4939170a260b36f0427a8a80866b063b948"
            + "a07c230f7f578dd61785c93361b9871c0706ebfa6d06e3f4491dc9558c5202ed36";

    @Test
    @DisplayName("★ 拆回字段再编回去：原文与交易哈希逐字节相同")
    void decodeThenReencodeIsByteIdentical() {
        Eip1559Transaction.Decoded decoded = Eip1559Transaction.decode(parse(TXBYTES));
        Eip1559Transaction.Signed signed = decoded.transaction().sign(decoded.signature());

        assertThat(hex(signed.raw())).isEqualTo(TXBYTES);
        assertThat(hex(signed.hash())).isEqualTo(HASH);
    }

    @Test
    @DisplayName("字段读出来是规范说的那些：chainId 1、nonce 0、小费 2 gwei、maxFee 31 字节、gas 21000、value 0、data 空")
    void decodedFieldsReadAsExpected() {
        Eip1559Transaction tx = Eip1559Transaction.decode(parse(TXBYTES)).transaction();

        assertThat(tx.chainId()).isEqualTo(1);
        assertThat(tx.nonce()).isEqualTo(0);
        assertThat(tx.maxPriorityFeePerGas()).isEqualTo(BigInteger.valueOf(0x77359400L));
        assertThat(tx.maxFeePerGas()).isEqualTo(new BigInteger("02" + "ff".repeat(30), 16));
        assertThat(tx.gasLimit()).isEqualTo(21_000);
        assertThat(tx.to()).isEqualTo("0x095e7baea6a6c7c4c2dfeb977efac326af552d87");
        assertThat(tx.value()).isEqualTo(BigInteger.ZERO);
        assertThat(tx.data()).isEmpty();
    }

    @Test
    @DisplayName("★ 从签名恢复出的发送方与规范相同")
    void recoveredSenderMatchesTheSpec() {
        Eip1559Transaction.Decoded decoded = Eip1559Transaction.decode(parse(TXBYTES));

        assertThat(Ecdsa.recoverAddress(decoded.transaction().signingHash(), decoded.signature())).isEqualToIgnoringCase(SENDER);
    }

    @Test
    @DisplayName("★ 整数带前导零的原文被拒绝：规范判它 RLP_LEADING_ZEROS，我们不能比节点宽松")
    void leadingZeroIntegersAreRejected() {
        assertThatThrownBy(() -> Eip1559Transaction.decode(parse(LEADING_ZERO_MAX_FEE)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static byte[] parse(String hex) {
        return HexFormat.of().parseHex(hex.substring(2));
    }

    static String hex(byte[] bytes) {
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
