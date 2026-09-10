package com.chainpay.chain.wallet;

import static com.chainpay.chain.wallet.Rlp.bytes;
import static com.chainpay.chain.wallet.Rlp.integer;
import static com.chainpay.chain.wallet.Rlp.list;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 我们自己要发的那种交易：Sepolia 上付 1 LINK。字段的排列、类型字节、两个哈希、签名后的原文，
 * 以及「从原文能恢复出签名者」这条自洽性。私钥用公开的 Hardhat 测试私钥 #0（其地址 M3 已做过 KAT）。
 */
@DisplayName("M4-① · 类型 2 交易：从九个字段到原文")
class Eip1559TransactionTest {

    static final BigInteger HARDHAT_KEY_0 = new BigInteger("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", 16);
    static final String HARDHAT_ADDRESS_0 = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";
    static final String LINK = "0x779877a7b0d9e8603169ddbd7836e478b4624789";
    static final String RECIPIENT = "0x70997970c51812dc3a010c7d01b50e0d17dc79c8";
    static final long SEPOLIA = 11_155_111L;
    static final BigInteger ONE_LINK = BigInteger.TEN.pow(18);

    static final byte[] CALLDATA = transferCalldata(RECIPIENT, ONE_LINK);
    static final Eip1559Transaction TX = new Eip1559Transaction(SEPOLIA, 12, BigInteger.valueOf(1_500_000_000L),
            BigInteger.valueOf(30_000_000_000L), 65_000, LINK, BigInteger.ZERO, CALLDATA);

    @Test
    @DisplayName("★ 签名载荷 = 类型字节 0x02 ‖ RLP(九个字段)，accessList 是空列表")
    void signingPayloadIsTypeBytePlusRlpOfNineFields() {
        byte[] payload = TX.signingPayload();

        assertThat(payload[0]).isEqualTo((byte) 0x02);
        byte[] expectedBody = Rlp.encode(list(integer(SEPOLIA), integer(12), integer(1_500_000_000L), integer(30_000_000_000L),
                integer(65_000), bytes(HexFormat.of().parseHex(LINK.substring(2))), integer(0), bytes(CALLDATA), list()));
        assertThat(Arrays.copyOfRange(payload, 1, payload.length)).isEqualTo(expectedBody);
        assertThat(TX.signingHash()).isEqualTo(Keccak256.hash(payload));
    }

    @Test
    @DisplayName("★ 签完名：原文以 0x02 开头，交易哈希 = Keccak(原文)，从签名恢复出的地址就是签名者，拆回去还是这九个字段")
    void signedRawHashAndRecovery() {
        Ecdsa.Signature signature = Ecdsa.sign(TX.signingHash(), HARDHAT_KEY_0);
        Eip1559Transaction.Signed signed = TX.sign(signature);

        assertThat(signed.raw()[0]).isEqualTo((byte) 0x02);
        assertThat(signed.hash()).isEqualTo(Keccak256.hash(signed.raw()));
        assertThat(Ecdsa.recoverAddress(TX.signingHash(), signature)).isEqualToIgnoringCase(HARDHAT_ADDRESS_0);
        Eip1559Transaction.Decoded decoded = Eip1559Transaction.decode(signed.raw());
        assertThat(decoded.transaction().signingPayload()).isEqualTo(TX.signingPayload());
        assertThat(decoded.signature()).isEqualTo(signature);
    }

    @Test
    @DisplayName("★ 签名是确定的：同一笔签两次得到同一个签名（RFC 6979），且 s 取小的那个（≤ n/2）")
    void signingIsDeterministicAndLowS() {
        Ecdsa.Signature first = Ecdsa.sign(TX.signingHash(), HARDHAT_KEY_0);
        Ecdsa.Signature second = Ecdsa.sign(TX.signingHash(), HARDHAT_KEY_0);

        assertThat(second).isEqualTo(first);
        assertThat(first.s()).isLessThanOrEqualTo(Secp256k1.n().shiftRight(1));
    }

    @Test
    @DisplayName("to 必须是 0x + 40 位十六进制；data 不能是 null")
    void rejectsMalformedFields() {
        assertThatThrownBy(() -> new Eip1559Transaction(SEPOLIA, 0, BigInteger.ONE, BigInteger.ONE, 21_000, "0x1234", BigInteger.ZERO, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Eip1559Transaction(SEPOLIA, 0, BigInteger.ONE, BigInteger.ONE, 21_000, LINK, BigInteger.ZERO, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Eip1559Transaction(SEPOLIA, -1, BigInteger.ONE, BigInteger.ONE, 21_000, LINK, BigInteger.ZERO, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("ERC-20 transfer 的 data：4 字节选择子 + 左补零的 32 字节地址 + 32 字节金额，共 68 字节")
    void erc20TransferCalldataLayout() {
        assertThat(CALLDATA).hasSize(68);
        assertThat(HexFormat.of().formatHex(Arrays.copyOfRange(CALLDATA, 0, 4))).isEqualTo("a9059cbb");
        assertThat(Arrays.copyOfRange(CALLDATA, 4, 16)).containsOnly((byte) 0);
        assertThat(HexFormat.of().formatHex(Arrays.copyOfRange(CALLDATA, 16, 36))).isEqualTo(RECIPIENT.substring(2));
        assertThat(new BigInteger(1, Arrays.copyOfRange(CALLDATA, 36, 68))).isEqualTo(ONE_LINK);
    }

    /** transfer(address,uint256) 的 calldata：选择子 a9059cbb，两个参数各 32 字节左补零。 */
    static byte[] transferCalldata(String to, BigInteger amount) {
        byte[] out = new byte[68];
        System.arraycopy(HexFormat.of().parseHex("a9059cbb"), 0, out, 0, 4);
        System.arraycopy(HexFormat.of().parseHex(to.substring(2)), 0, out, 16, 20);
        byte[] amountBytes = amount.toByteArray();
        int start = amountBytes[0] == 0 ? 1 : 0;                       // BigInteger 可能多带一个符号字节
        System.arraycopy(amountBytes, start, out, 68 - (amountBytes.length - start), amountBytes.length - start);
        return out;
    }
}
