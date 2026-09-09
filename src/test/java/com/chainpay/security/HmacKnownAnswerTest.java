package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.service.ApiCredentialService;
import com.chainpay.security.service.ApiCredentialService.SignedRequest;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 签名算法的<b>已知答案测试</b>（Known-Answer Test）。
 *
 * <p><b>为什么其他签名测试都守不住算法（质询扫描 5.3）：</b>
 * 它们的期望值全部由 {@code ApiCredentialService.sign()} 自己算出来——
 * 测试端签一次、服务端签一次、比较相等。两边是同一个函数，它算出什么两边就都是什么。
 * 把 HmacSHA256 换成 HmacSHA1，73 个测试全绿；换成十六进制编码，全绿；
 * 密钥编码从 UTF-8 换成平台默认，全绿。它们只证明了「确定性」，没证明「对」。
 *
 * <p><b>这个类给实现一个外部参照物</b>：固定输入 → 固定输出，
 * 期望值不由被测代码算，来自两个独立来源——
 * <ul>
 *   <li>RFC 4231（2005，IETF）的官方测试向量：守<b>算法</b>本身</li>
 *   <li>用我们的 prehash 格式、由 Python 标准库独立算出的向量：守<b>拼接 + 编码 + 算法</b>整条流水线</li>
 * </ul>
 *
 * <p>将来要合法地换算法（比如升到 SHA-512），必须同时更新这里的向量——
 * 这正是目的：<b>算法变更不可能悄悄发生</b>。
 */
@DisplayName("M1 · 签名算法已知答案")
class HmacKnownAnswerTest {

    // ---- RFC 4231 §4：HMAC-SHA-256 官方测试向量 ----
    // 两个向量的值在写入前用 Python hmac/hashlib 独立复核过，与 RFC 一致。

    @Test
    @DisplayName("★ RFC 4231 测试用例 1：Key=0x0b×20, Data=\"Hi There\"")
    void rfc4231TestCase1() {
        // 20 个 0x0b 字节；U+000B 在 UTF-8 下就是单字节 0x0b，所以能经由 String 传入
        String key = String.valueOf((char) 0x0b).repeat(20);
        String signature = ApiCredentialService.sign("Hi There", key);

        assertThat(hex(signature))
                .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");
    }

    @Test
    @DisplayName("★ RFC 4231 测试用例 2：Key=\"Jefe\", Data=\"what do ya want for nothing?\"")
    void rfc4231TestCase2() {
        String signature = ApiCredentialService.sign("what do ya want for nothing?", "Jefe");

        assertThat(hex(signature))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    // ---- 项目形态向量（CP2 规范串）：版本标签、五段各占一行、请求体换成 SHA-256；Base64 输出 ----

    static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final String NONCE = "00000000000000000000000000000000";

    @Test
    @DisplayName("★ CP2 向量：POST + 非 ASCII 请求体 —— 钉住版本标签、换行分隔、UTF-8、请求体哈希、Base64、算法")
    void cp2VectorWithBody() {
        // 期望值由 Python 3 标准库独立算出（不从 Java 实现里抄）：
        //   canonical = "CP2\n" + ts + "\n" + nonce + "\n" + method + "\n" + path + "\n" + sha256hex(body)
        //   base64(hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).digest())
        var request = new SignedRequest("ak_x", 1700000000000L, NONCE, "POST", "/api/v1/transfers?x=1",
                "{\"memo\":\"转账\"}", "");

        assertThat(ApiCredentialService.prehash(request))
                .isEqualTo("CP2\n1700000000000\n" + NONCE + "\nPOST\n/api/v1/transfers?x=1\n"
                        + "2f7b48d0a4782735c4e9348eac8dc7590296053e588f7fb2c4c758055e876d41");
        assertThat(ApiCredentialService.sign(ApiCredentialService.prehash(request), SECRET))
                .isEqualTo("YBzjg+lssuTNrnJfzspc9+zO4XFTcJOO9D63KNpRAOM=");
    }

    @Test
    @DisplayName("★ CP2 向量：GET 空请求体 —— 空体的哈希是固定值 e3b0c442…，GET 与 POST 走同一条规则")
    void cp2VectorWithEmptyBody() {
        var request = new SignedRequest("ak_x", 1700000000000L, NONCE, "GET", "/api/v1/deposits/balance?token=0xabc", "", "");

        assertThat(ApiCredentialService.prehash(request)).endsWith("\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(ApiCredentialService.sign(ApiCredentialService.prehash(request), SECRET))
                .isEqualTo("QvReKUMPv+0Hwsj1NFcfz0K3zySNlJ+iu0I6p9ivibA=");
    }

    @Test
    @DisplayName("★ 边界不能滑动：路径末尾的字符挪进请求体开头，签名串必须不同（无分隔符拼接时它们完全相同）")
    void movingBytesAcrossThePathBodyBoundaryChangesTheCanonicalString() {
        var original = new SignedRequest("ak_x", 1700000000000L, NONCE, "POST", "/api/v1/transfers", "{\"memo\":\"x\"}", "");
        var shifted = new SignedRequest("ak_x", 1700000000000L, NONCE, "POST", "/api/v1/transfers{", "\"memo\":\"x\"}", "");

        // 缺陷本身先钉成事实：五段直接拼接时，两个不同的请求是同一串字节
        assertThat(original.path() + original.body()).isEqualTo(shifted.path() + shifted.body());

        assertThat(ApiCredentialService.prehash(original))
                .as("请求到签名串的映射必须是单射：不同的请求不能共享一个签名")
                .isNotEqualTo(ApiCredentialService.prehash(shifted));
    }

    /** 我们的 sign() 输出 Base64；RFC 给的是十六进制，解码后转成同一种表示再比。 */
    private static String hex(String base64Signature) {
        return HexFormat.of().formatHex(Base64.getDecoder().decode(base64Signature));
    }
}
