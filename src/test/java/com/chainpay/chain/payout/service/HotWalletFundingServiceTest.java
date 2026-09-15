package com.chainpay.chain.payout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chainpay.chain.deposit.service.AbstractDepositPostingTest;
import com.chainpay.chain.payout.service.HotWalletFundingService.Funding;
import com.chainpay.common.web.ErrorCode;
import java.math.BigInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * M6-② · 注资登记：运营往热钱包充的币要有一行记录，托管等式才能解释它。
 * 金额、块、代币全部从索引器已经记下的日志里读，运营只指认「是哪一笔」——数字不由人填。
 */
@DisplayName("M6-② · 注资登记")
class HotWalletFundingServiceTest extends AbstractDepositPostingTest {

    static final String HOT = "0x3c44cdddb6a900fa2b585dd299e03d12fa4293bc";
    static final String TX_FINAL = "0x" + "a".repeat(64);
    static final String TX_YOUNG = "0x" + "b".repeat(64);
    static final String TX_TWICE = "0x" + "c".repeat(64);
    static final String TX_TO_DEPOSIT = "0x" + "d".repeat(64);
    static final String TX_INTERNAL = "0x" + "e".repeat(64);

    private HotWalletFundingService service;

    @BeforeEach
    void seed() {
        chain.addTransfer(LINK, 30, ALICE, HOT, ONE_LINK, TX_FINAL);                                   // 外部注资，已 finalized
        chain.addTransfer(LINK, 60, ALICE, HOT, ONE_LINK, TX_YOUNG);                                   // 外部注资，还没 finalized（F = 50）
        chain.addTransfer(LINK, 31, ALICE, HOT, ONE_LINK, TX_TWICE);                                   // 同一笔交易里两条转给热钱包的日志
        chain.addTransfer(LINK, 31, BOB, HOT, ONE_LINK.multiply(BigInteger.TWO), TX_TWICE);
        chain.addTransfer(LINK, 32, ALICE, ACME_ADDRESS, ONE_LINK, TX_TO_DEPOSIT);                     // 打给收款地址的：那是入账，不是注资
        chain.addTransfer(LINK, 33, ACME_ADDRESS, HOT, ONE_LINK, TX_INTERNAL);                         // 收款地址 → 热钱包：内部挪动（归集），不是注资
        pay(5, TEN_LINK);
        indexUpTo(100, 90, 50);
        jdbc.sql("INSERT INTO hot_wallet (address, chain, next_nonce) VALUES (:a, 'test', 0)").param("a", HOT).update();
        service = new HotWalletFundingService(systemLedger);
    }

    @AfterEach
    void clean() {
        jdbc.sql("TRUNCATE hot_wallet CASCADE").update();
    }

    @Test
    @DisplayName("★ 金额、块、代币从日志来，不从人来；登记后能列出")
    void registersFromTheLogNotFromTheOperator() {
        Funding f = service.register(TX_FINAL, null, "水龙头充币");

        assertThat(f.rawValue()).isEqualTo(ONE_LINK);
        assertThat(f.blockNumber()).isEqualTo(30);
        assertThat(f.token()).isEqualTo(LINK);
        assertThat(f.hotWallet()).isEqualTo(HOT);
        assertThat(f.note()).isEqualTo("水龙头充币");
        assertThat(service.list()).extracting(Funding::txHash).containsExactly(TX_FINAL);
    }

    @Test
    @DisplayName("同一条日志登记两次：返回同一行，不重复计")
    void sameLogTwiceReturnsTheSameRow() {
        Funding first = service.register(TX_FINAL, null, "一");
        Funding second = service.register(TX_FINAL, null, "二");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(service.list()).hasSize(1);
    }

    @Test
    @DisplayName("没有这条日志：404；大小写不同的哈希是同一条")
    void unknownHashIsNotFound() {
        assertThatThrownBy(() -> service.register("0x" + "9".repeat(64), null, null))
                .isInstanceOf(FundingRejectedException.class)
                .satisfies(e -> assertThat(((FundingRejectedException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));
        assertThat(service.register(TX_FINAL.toUpperCase().replace("0X", "0x"), null, null).blockNumber()).isEqualTo(30);
    }

    @Test
    @DisplayName("★ 还没 finalized 的不登记：会被重组翻掉的钱不能进等式（409）")
    void notYetFinalizedIsRefused() {
        assertThatThrownBy(() -> service.register(TX_YOUNG, null, null))
                .isInstanceOf(FundingRejectedException.class)
                .satisfies(e -> {
                    assertThat(((FundingRejectedException) e).status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((FundingRejectedException) e).code()).isEqualTo(ErrorCode.NOT_FINALIZED);
                });
    }

    @Test
    @DisplayName("★ 收款方不是热钱包的不是注资（那是入账）；发起方是平台自己地址的也不是（那是归集）：都 400")
    void onlyExternalTransfersToTheHotWalletCount() {
        assertThatThrownBy(() -> service.register(TX_TO_DEPOSIT, null, null)).isInstanceOf(FundingRejectedException.class)
                .satisfies(e -> assertThat(((FundingRejectedException) e).status()).isEqualTo(HttpStatus.NOT_FOUND));   // 没有「转给热钱包」的日志
        assertThatThrownBy(() -> service.register(TX_INTERNAL, null, null)).isInstanceOf(FundingRejectedException.class)
                .hasMessageContaining("内部")
                .satisfies(e -> assertThat(((FundingRejectedException) e).status()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    @DisplayName("一笔交易里有多条转给热钱包的日志：不指定 logIndex 就 400，指定了各登记各的")
    void ambiguousTxHashNeedsALogIndex() {
        assertThatThrownBy(() -> service.register(TX_TWICE, null, null)).isInstanceOf(FundingRejectedException.class)
                .hasMessageContaining("logIndex");
        int first = jdbc.sql("SELECT min(log_index) FROM chain_transfer_log WHERE tx_hash = :h").param("h", TX_TWICE).query(Integer.class).single();
        int second = jdbc.sql("SELECT max(log_index) FROM chain_transfer_log WHERE tx_hash = :h").param("h", TX_TWICE).query(Integer.class).single();
        assertThat(service.register(TX_TWICE, first, null).rawValue()).isEqualTo(ONE_LINK);
        assertThat(service.register(TX_TWICE, second, null).rawValue()).isEqualTo(ONE_LINK.multiply(BigInteger.TWO));
        assertThat(service.list()).hasSize(2);
    }
}
