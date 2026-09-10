package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.Sign;
import org.web3j.crypto.SignedRawTransaction;
import org.web3j.crypto.TransactionDecoder;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.crypto.transaction.type.Transaction1559;
import org.web3j.utils.Numeric;

/**
 * 类型 2（EIP-1559）交易：九个内容字段。签名载荷 = 0x02 ‖ RLP(九个字段)；签好后的原文 = 0x02 ‖ RLP(九个字段 + yParity, r, s)。
 *
 * <p>两个哈希不是一回事：{@link #signingHash} 只盖住九个字段，是私钥要签的东西；
 * {@link Signed#hash} 盖住带签名的原文，是节点与区块浏览器指认这笔交易的名字。原文在广播前就能算出交易哈希，
 * 这是 M4-②「先落库再广播」的基础。
 *
 * <p>字段布局与编码委托给 web3j（2026-09-09 改判）；本类保留字段校验（地址形状、非负、小费上限 ≤ 总费率上限）和一条库不做的检查：
 * {@link #decode} 拆回字段后<b>再编回去必须与原文逐字节相同</b>——库的解码器不拒绝非规范形式（整数前导零之类），节点会拒，我们不能比它宽松。
 * {@code to} 是<b>代币合约</b>的地址，收款人在 {@code data} 里；转代币时 {@code value} 是 0；accessList 固定为空。
 */
public record Eip1559Transaction(long chainId, long nonce, BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
                                 long gasLimit, String to, BigInteger value, byte[] data) {

    static final byte TYPE = 0x02;

    public Eip1559Transaction {
        if (chainId <= 0) {
            throw new IllegalArgumentException("chainId 必须为正：" + chainId);
        }
        if (nonce < 0) {
            throw new IllegalArgumentException("nonce 不能为负：" + nonce);
        }
        if (gasLimit <= 0) {
            throw new IllegalArgumentException("gasLimit 必须为正：" + gasLimit);
        }
        requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
        requireNonNegative(maxFeePerGas, "maxFeePerGas");
        requireNonNegative(value, "value");
        if (maxPriorityFeePerGas.compareTo(maxFeePerGas) > 0) {
            throw new IllegalArgumentException("小费上限不能高于总费率上限");
        }
        to = EthAddress.lowercase(to);
        if (data == null) {
            throw new IllegalArgumentException("data 不能为 null（没有参数就给空数组）");
        }
        data = data.clone();
    }

    /** 签好的原文与它的交易哈希。 */
    public record Signed(byte[] raw, byte[] hash) {}

    /** 从原文拆回来的九个字段与签名。 */
    public record Decoded(Eip1559Transaction transaction, Ecdsa.Signature signature) {}

    private RawTransaction raw() {
        return RawTransaction.createTransaction(chainId, BigInteger.valueOf(nonce), BigInteger.valueOf(gasLimit), to, value,
                "0x" + HexFormat.of().formatHex(data), maxPriorityFeePerGas, maxFeePerGas);
    }

    /** 0x02 ‖ RLP(九个字段)：私钥签的是它的 Keccak。 */
    public byte[] signingPayload() {
        return TransactionEncoder.encode(raw());
    }

    public byte[] signingHash() {
        return Keccak256.hash(signingPayload());
    }

    /** 接上签名：0x02 ‖ RLP(九个字段 + yParity, r, s)，交易哈希是它的 Keccak。 */
    public Signed sign(Ecdsa.Signature signature) {
        Sign.SignatureData data = new Sign.SignatureData((byte) (27 + signature.yParity()),
                Ecdsa.toFixed32(signature.r()), Ecdsa.toFixed32(signature.s()));         // web3j 用 27 / 28 表示奇偶
        byte[] raw = TransactionEncoder.encode(raw(), data);
        return new Signed(raw, Keccak256.hash(raw));
    }

    /** 从原文拆回字段与签名；拆回再编回必须与原文逐字节相同，否则拒绝（非规范编码、多余字节、非空 accessList）。 */
    public static Decoded decode(byte[] raw) {
        if (raw == null || raw.length == 0 || raw[0] != TYPE) {
            throw new IllegalArgumentException("不是类型 2 交易：首字节应为 0x02");
        }
        RawTransaction decoded;
        try {
            decoded = TransactionDecoder.decode(Numeric.toHexString(raw));
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("原文无法解析：" + malformed.getMessage(), malformed);
        }
        if (!(decoded instanceof SignedRawTransaction signed) || !(decoded.getTransaction() instanceof Transaction1559 tx)) {
            throw new IllegalArgumentException("不是签过名的类型 2 交易");
        }
        Sign.SignatureData data = signed.getSignatureData();
        int v = new BigInteger(1, data.getV()).intValue();
        int yParity = v >= 27 ? v - 27 : v;
        Eip1559Transaction transaction;
        Ecdsa.Signature signature;
        try {
            transaction = new Eip1559Transaction(tx.getChainId(), tx.getNonce().longValueExact(), tx.getMaxPriorityFeePerGas(),
                    tx.getMaxFeePerGas(), tx.getGasLimit().longValueExact(), Numeric.prependHexPrefix(tx.getTo()), tx.getValue(),
                    Numeric.hexStringToByteArray(tx.getData()));
            signature = new Ecdsa.Signature(new BigInteger(1, data.getR()), new BigInteger(1, data.getS()), yParity);
        } catch (ArithmeticException tooLarge) {
            throw new IllegalArgumentException("字段超出可表示范围：" + tooLarge.getMessage(), tooLarge);
        }
        if (!Arrays.equals(transaction.sign(signature).raw(), raw)) {
            throw new IllegalArgumentException("原文不是规范编码：拆回再编回与原文不同（整数前导零、多余字节或非空 accessList）");
        }
        return new Decoded(transaction, signature);
    }

    private static void requireNonNegative(BigInteger value, String name) {
        if (value == null || value.signum() < 0) {
            throw new IllegalArgumentException(name + " 必须是非负整数：" + value);
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Eip1559Transaction t && Arrays.equals(signingPayload(), t.signingPayload());
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(signingPayload());
    }

    @Override
    public String toString() {
        return "Eip1559Transaction[chainId=" + chainId + ", nonce=" + nonce + ", to=" + to + ", gasLimit=" + gasLimit
                + ", data=" + data.length + " bytes]";
    }
}
