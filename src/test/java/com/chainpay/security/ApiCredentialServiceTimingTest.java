package com.chainpay.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.chainpay.security.crypto.SecretCipher;
import com.chainpay.security.service.ApiCredentialService;
import com.chainpay.security.service.ApiCredentialService.SignedRequest;
import com.chainpay.support.AbstractPostgresTest;
import com.chainpay.support.SignedRequests;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 扫描补丁（2026-09-09）：key 不存在时在解密与 HMAC 之前就返回，两条失败路径耗时不同，
 * 计时能确认「这个 api_key 是活的」。响应已经做到不可区分，耗时也要。
 */
@SpringBootTest
@DisplayName("扫描补丁 · 凭证查找的耗时不能泄露 key 是否存在")
class ApiCredentialServiceTimingTest extends AbstractPostgresTest {

    @Autowired
    private JdbcClient appJdbc;

    @Value("${chainpay.secret-key}")
    private String base64Key;

    static final class CountingCipher extends SecretCipher {
        int decrypts;

        CountingCipher(String key) {
            super(key);
        }

        @Override
        public String decrypt(String base64Combined) {
            decrypts++;
            return super.decrypt(base64Combined);
        }
    }

    @Test
    @DisplayName("★ key 不存在时也做一次解密与 HMAC：两条路径同样贵，计时分不出「不存在」与「签名错」")
    void unknownKeyCostsTheSameAsAWrongSignature() {
        CountingCipher cipher = new CountingCipher(base64Key);
        jdbc.sql("DELETE FROM api_credential").update();
        jdbc.sql("DELETE FROM merchant").update();
        long merchant = jdbc.sql("INSERT INTO merchant(code, name) VALUES ('acme', 'Acme') RETURNING id").query(Long.class).single();
        String knownKey = "ak_" + "a".repeat(32);
        jdbc.sql("INSERT INTO api_credential(merchant_id, api_key, secret_encrypted) VALUES (:m, :k, :s)")
                .param("m", merchant).param("k", knownKey).param("s", cipher.encrypt("real-secret")).update();
        ApiCredentialService service = new ApiCredentialService(appJdbc, cipher);
        int setupDecrypts = cipher.decrypts;
        long ts = System.currentTimeMillis();

        assertThat(service.authenticate(new SignedRequest("ak_" + "f".repeat(32), ts, SignedRequests.newNonce(),
                "GET", "/api/v1/x", "", "AAAA"))).isEmpty();
        int unknownKeyCost = cipher.decrypts - setupDecrypts;
        assertThat(service.authenticate(new SignedRequest(knownKey, ts, SignedRequests.newNonce(),
                "GET", "/api/v1/x", "", "AAAA"))).isEmpty();
        int wrongSignatureCost = cipher.decrypts - setupDecrypts - unknownKeyCost;

        assertThat(wrongSignatureCost).as("签名错：解密一次").isEqualTo(1);
        assertThat(unknownKeyCost).as("key 不存在：也要解密一次，否则耗时就是存在性预言机").isEqualTo(1);
    }
}
