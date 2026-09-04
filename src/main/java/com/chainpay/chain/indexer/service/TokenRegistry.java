package com.chainpay.chain.indexer.service;

import com.chainpay.chain.erc20.Erc20Calls;
import com.chainpay.chain.erc20.TokenAmounts;
import com.chainpay.chain.indexer.domain.ChainToken;
import com.chainpay.chain.indexer.repository.ChainTokenRepository;
import java.util.OptionalInt;

/**
 * 代币白名单：登记时问链，使用前核对。
 *
 * <p>为什么要有白名单：Transfer 事件是合约「说」的，只对行为规范的代币等于到账金额。
 * 我们只索引、只入账这里 ACTIVE 的代币；信错了损失是我们的，所以这不是产品选择，是记账正确性的前提。
 */
public final class TokenRegistry {

    /** 来源说明的上限；V14 的 CHECK 用同一个数。 */
    public static final int MAX_NOTE_LENGTH = 500;

    private final Erc20Calls calls;
    private final ChainTokenRepository tokens;

    public TokenRegistry(Erc20Calls calls, ChainTokenRepository tokens) {
        this.calls = calls;
        this.tokens = tokens;
    }

    /** 问链上的 decimals 与 symbol，问得到、且 decimals ≤ 18 才登记。 */
    public ChainToken register(String address) {
        String token = BlockIndexer.requireAddress(address);
        if (tokens.find(token).isPresent()) {                  // 早退只是省两次上链；唯一性由下面的插入裁决
            throw alreadyRegistered(token);
        }
        OptionalInt decimals = calls.decimals(token);
        if (decimals.isEmpty()) {
            throw new IllegalArgumentException("链上问不到 " + token + " 的 decimals()：EIP-20 说它是 OPTIONAL。"
                    + "要登记就用 registerManually 手工填，并注明来源");
        }
        if (decimals.getAsInt() > TokenAmounts.LEDGER_SCALE) {
            throw new IllegalArgumentException("代币 " + token + " 的 decimals=" + decimals.getAsInt()
                    + " 超过账本的 " + TokenAmounts.LEDGER_SCALE + " 位小数，装不下，拒绝登记");
        }
        String symbol = calls.symbol(token).orElse("?");
        ChainToken registered = new ChainToken(token, symbol, decimals.getAsInt(), "ACTIVE");
        if (!tokens.insertIfAbsent(registered, "登记时从链上读取：decimals=" + decimals.getAsInt() + "，symbol=" + symbol)) {
            throw alreadyRegistered(token);                    // 查过「没有」之后有人插了队：check-then-act 第 8 次，让主键说话
        }
        return registered;
    }

    /** 运营手工登记（链上问不到 decimals 的代币），必须注明来源。 */
    public ChainToken registerManually(String address, String symbol, int decimals, String note) {
        String token = BlockIndexer.requireAddress(address);
        if (decimals < 0 || decimals > TokenAmounts.LEDGER_SCALE) {
            throw new IllegalArgumentException("decimals 必须在 0 到 " + TokenAmounts.LEDGER_SCALE + " 之间：" + decimals);
        }
        if (symbol == null || symbol.isBlank() || symbol.length() > Erc20Calls.MAX_SYMBOL_LENGTH) {
            throw new IllegalArgumentException("symbol 必须是 1 到 " + Erc20Calls.MAX_SYMBOL_LENGTH + " 个字符的代号，收到 "
                    + (symbol == null ? "null" : symbol.length() + " 个字符"));
        }
        if (note == null || note.isBlank() || note.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("手工登记必须用 note 注明 decimals 的来源，1 到 " + MAX_NOTE_LENGTH + " 个字符");
        }
        ChainToken registered = new ChainToken(token, symbol, decimals, "ACTIVE");
        if (!tokens.insertIfAbsent(registered, note)) {
            throw alreadyRegistered(token);
        }
        return registered;
    }

    private static IllegalStateException alreadyRegistered(String token) {
        return new IllegalStateException("代币已登记：" + token);
    }

    /** 必须已登记且 ACTIVE。 */
    public ChainToken requireUsable(String address) {
        String token = BlockIndexer.requireAddress(address);
        ChainToken found = tokens.find(token).orElseThrow(() -> new IllegalStateException(
                "代币未登记：" + token + "。只索引、只入账白名单里的代币"));
        if (!found.isActive()) {
            throw new IllegalStateException("代币已停用：" + token);
        }
        return found;
    }

    /**
     * 链上答得出 decimals 时，必须等于表里的；不等就是表被改过或合约被升级，停下。
     * 答不出（手工登记的代币）无从比对，放过。
     */
    public void verifyAgainstChain(String address) {
        String token = BlockIndexer.requireAddress(address);
        ChainToken found = tokens.find(token).orElseThrow(() -> new IllegalStateException("代币未登记：" + token));
        OptionalInt onChain = calls.decimals(token);
        if (onChain.isEmpty()) {
            return;
        }
        if (onChain.getAsInt() != found.decimals()) {
            throw new IllegalStateException("代币 " + token + " 的 decimals 不一致：白名单 " + found.decimals()
                    + "，链上 " + onChain.getAsInt() + "。表被改过或合约被升级，停下叫人");
        }
        tokens.markVerified(token);
    }
}
