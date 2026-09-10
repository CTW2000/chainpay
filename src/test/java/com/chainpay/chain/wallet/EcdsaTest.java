package com.chainpay.chain.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 签名与恢复的边界：私钥范围、哈希长度、两种奇偶都能恢复、篡改后恢复出的是别人。 */
@DisplayName("M4-① · ECDSA 的边界")
class EcdsaTest {

    static final BigInteger KEY = new BigInteger("ac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80", 16);
    static final String ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    @Test
    @DisplayName("★ 私钥必须在 [1, n−1]：0 与 n 都拒绝")
    void rejectsPrivateKeysOutOfRange() {
        byte[] hash = Keccak256.hash("x".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> Ecdsa.sign(hash, BigInteger.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Ecdsa.sign(hash, Secp256k1.n())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("签的必须是 32 字节的哈希，不是任意长度的消息")
    void hashMustBeThirtyTwoBytes() {
        assertThatThrownBy(() -> Ecdsa.sign(new byte[31], KEY)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("★ 两种奇偶位都会出现，每一种都能恢复出同一个地址；s 永远 ≤ n/2")
    void bothParitiesOccurAndRecover() {
        Set<Integer> parities = new HashSet<>();
        for (int i = 0; i < 24; i++) {
            byte[] hash = Keccak256.hash(("message-" + i).getBytes(StandardCharsets.UTF_8));
            Ecdsa.Signature signature = Ecdsa.sign(hash, KEY);
            parities.add(signature.yParity());
            assertThat(signature.s()).isLessThanOrEqualTo(Secp256k1.n().shiftRight(1));
            assertThat(Ecdsa.recoverAddress(hash, signature)).as("message-" + i).isEqualToIgnoringCase(ADDRESS);
        }
        assertThat(parities).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    @DisplayName("★ 哈希被改一个比特，恢复出的就是别人：签名不会为改过的内容背书")
    void tamperedHashRecoversSomeoneElse() {
        byte[] hash = Keccak256.hash("pay 1 LINK".getBytes(StandardCharsets.UTF_8));
        Ecdsa.Signature signature = Ecdsa.sign(hash, KEY);
        byte[] tampered = hash.clone();
        tampered[0] ^= 1;

        assertThat(Ecdsa.recoverAddress(tampered, signature)).isNotEqualToIgnoringCase(ADDRESS);
    }
}
