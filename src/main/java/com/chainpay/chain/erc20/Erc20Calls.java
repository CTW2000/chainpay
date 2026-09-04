package com.chainpay.chain.erc20;

import com.chainpay.chain.rpc.ChainReader;
import com.chainpay.chain.rpc.JsonRpcException;
import java.math.BigInteger;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * 对一个 ERC-20 合约问三个问题：decimals、symbol、balanceOf。
 *
 * <p>decimals 与 symbol 的「问不到」有四种形状，都返回空：合约没有这个函数（节点报 revert，带 code）、返回空（"0x"）、
 * 返回值形状不对（MKR 那种 bytes32 的 symbol、长度字溢出）、值超出合理范围（decimals 装不进 uint8，
 * symbol 空白或超过 {@link #MAX_SYMBOL_LENGTH} 个字符）。传输失败照抛——那不是合约的答案，是没问到。
 *
 * <p>balanceOf <b>不</b>降级：「不知道」和「余额为零」在数字上分不开，问不到就抛，调用方自己决定。
 */
public final class Erc20Calls {

    /** symbol 通常三到六个字符。超过这个数的不是代号，当问不到；V14 的 CHECK 用同一个数兜底。 */
    public static final int MAX_SYMBOL_LENGTH = 64;

    private final ChainReader chain;

    public Erc20Calls(ChainReader chain) {
        this.chain = chain;
    }

    /** decimals 是 uint8：0 到 255。不在这个范围也当问不到。 */
    public OptionalInt decimals(String token) {
        Optional<String> raw = tryCall(token, Abi.DECIMALS);
        if (raw.isEmpty()) {
            return OptionalInt.empty();
        }
        try {
            BigInteger value = Abi.decodeUint(raw.get());
            return value.bitLength() <= 8 ? OptionalInt.of(value.intValue()) : OptionalInt.empty();
        } catch (IllegalArgumentException malformed) {
            return OptionalInt.empty();
        }
    }

    public Optional<String> symbol(String token) {
        Optional<String> raw = tryCall(token, Abi.SYMBOL);
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            String symbol = Abi.decodeString(raw.get());
            return symbol.isBlank() || symbol.length() > MAX_SYMBOL_LENGTH ? Optional.empty() : Optional.of(symbol);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /**
     * 某个地址在某个块上的余额。这是合约「做」的，不是它「说」的。
     * 与上面两个不同，这里不降级：revert、形状不对、传输失败都原样抛出——
     * 回一个「空」或「零」会和真实的零余额混在一起。M3 的调用方要自己接。
     */
    public BigInteger balanceOf(String token, String holder, String blockTag) {
        return Abi.decodeUint(chain.call(token, Abi.encodeCall(Abi.BALANCE_OF, holder), blockTag));
    }

    private Optional<String> tryCall(String token, String data) {
        try {
            String result = chain.call(token, data, "latest");
            return result == null || result.isBlank() || result.equals("0x") ? Optional.empty() : Optional.of(result);
        } catch (JsonRpcException e) {
            if (e.code() == null) {
                throw e;                                             // 传输失败：没问到，不是「没有」
            }
            return Optional.empty();                                  // revert：合约没有这个函数
        }
    }
}
