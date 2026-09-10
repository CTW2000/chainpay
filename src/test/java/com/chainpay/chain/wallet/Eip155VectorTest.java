package com.chainpay.chain.wallet;

import static com.chainpay.chain.wallet.Rlp.bytes;
import static com.chainpay.chain.wallet.Rlp.integer;
import static com.chainpay.chain.wallet.Rlp.list;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EIP-155 正文里的算例（github.com/ethereum/EIPs，EIPS/eip-155.md「Example」，2026-09-09 浅克隆逐字抄录）。
 * 它是旧式（类型 0）交易，但一次证明三件事：RLP 编码逐字节对、Keccak 对、用私钥签出的 (r, s) 与规范逐位相同——
 * 后者只有在 k 由 RFC 6979 确定地算出、且 s 取 low-s 时才可能成立。
 */
@DisplayName("M4-① · EIP-155 算例：RLP、Keccak、确定性签名一次证明")
class Eip155VectorTest {

    static final String SIGNING_DATA = "0xec098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a764000080018080";
    static final String SIGNING_HASH = "0xdaf5a779ae972f972197303d7b574746c7ef83eadac0f2791ad23db92e4c8e53";
    static final BigInteger PRIVATE_KEY = new BigInteger("4646464646464646464646464646464646464646464646464646464646464646", 16);
    static final BigInteger R = new BigInteger("18515461264373351373200002665853028612451056578545711640558177340181847433846");
    static final BigInteger S = new BigInteger("46948507304638947509940763649030358759909902576025900602547168820602576006531");
    static final int V = 37;
    static final String SIGNED = "0xf86c098504a817c800825208943535353535353535353535353535353535353535880de0b6b3a76400008025"
            + "a028ef61340bd939bc2195fe537567866003e1a15d3c71ff63e1590620aa636276"
            + "a067cbe9d8997f761aecb703304b3800ccf555c9f3dc64214b297fb1966a3b6d83";

    static final byte[] TO = HexFormat.of().parseHex("3535353535353535353535353535353535353535");
    static final BigInteger ONE_ETHER = BigInteger.TEN.pow(18);
    static final int CHAIN_ID = 1;

    /** nonce = 9, gasprice = 20 gwei, startgas = 21000, to, value = 1 ether, data = ''，再按 EIP-155 接上 chainId, 0, 0。 */
    static Rlp.Item unsigned() {
        return list(integer(9), integer(20_000_000_000L), integer(21_000), bytes(TO), integer(ONE_ETHER), bytes(new byte[0]),
                integer(CHAIN_ID), integer(0), integer(0));
    }

    @Test
    @DisplayName("★ signing data：九个字段的 RLP 与规范逐字节相同")
    void signingDataMatchesTheSpec() {
        assertThat(hex(Rlp.encode(unsigned()))).isEqualTo(SIGNING_DATA);
    }

    @Test
    @DisplayName("★ signing hash：Keccak-256 与规范相同")
    void signingHashMatchesTheSpec() {
        assertThat(hex(Keccak256.hash(Rlp.encode(unsigned())))).isEqualTo(SIGNING_HASH);
    }

    @Test
    @DisplayName("★ 用私钥 0x46…46 签名，(r, s) 与规范逐位相同；v = 37 对应 yParity 0")
    void signatureMatchesTheSpec() {
        Ecdsa.Signature signature = Ecdsa.sign(Keccak256.hash(Rlp.encode(unsigned())), PRIVATE_KEY);

        assertThat(signature.r()).isEqualTo(R);
        assertThat(signature.s()).isEqualTo(S);
        assertThat(signature.yParity()).as("v = 35 + 2·chainId + yParity").isEqualTo(V - 35 - 2 * CHAIN_ID);
    }

    @Test
    @DisplayName("★ 签好的交易：九个字段换成 v, r, s 后的 RLP 与规范逐字节相同")
    void signedTransactionMatchesTheSpec() {
        Ecdsa.Signature signature = Ecdsa.sign(Keccak256.hash(Rlp.encode(unsigned())), PRIVATE_KEY);
        Rlp.Item signed = list(integer(9), integer(20_000_000_000L), integer(21_000), bytes(TO), integer(ONE_ETHER), bytes(new byte[0]),
                integer(35 + 2 * CHAIN_ID + signature.yParity()), integer(signature.r()), integer(signature.s()));

        assertThat(hex(Rlp.encode(signed))).isEqualTo(SIGNED);
    }

    @Test
    @DisplayName("从签名恢复出的地址就是这把私钥的地址：以太坊交易没有 from 字段，from 是这样来的")
    void recoveryGivesBackTheSigner() {
        byte[] hash = Keccak256.hash(Rlp.encode(unsigned()));
        Ecdsa.Signature signature = Ecdsa.sign(hash, PRIVATE_KEY);

        assertThat(Ecdsa.recoverAddress(hash, signature)).isEqualToIgnoringCase(EthAddress.fromPublicKey(Secp256k1.point(PRIVATE_KEY)));
    }

    static String hex(byte[] bytes) {
        return "0x" + HexFormat.of().formatHex(bytes);
    }
}
