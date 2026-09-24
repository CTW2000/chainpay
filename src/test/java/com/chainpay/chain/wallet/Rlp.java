package com.chainpay.chain.wallet;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.web3j.rlp.RlpDecoder;
import org.web3j.rlp.RlpEncoder;
import org.web3j.rlp.RlpList;
import org.web3j.rlp.RlpString;
import org.web3j.rlp.RlpType;

/**
 * RLP（Recursive Length Prefix）：以太坊的序列化，只认字节串和列表，规则全是「先写说明长度的前缀，再写内容」。
 *
 * <p>测试用的小接口，生产代码不直接碰 RLP：{@code Eip1559Transaction} 经 web3j 的 {@code TransactionEncoder} /
 * {@code TransactionDecoder} 编解码，非规范编码（整数前导零之类）靠「解码后编回必须与原文逐字节相同」拒绝。
 * 这里把 web3j 的 {@code rlp} 模块包成不依赖 web3j 类型的 {@link Item}，给两类测试用：官方的 28 个 RLP 向量
 * （src/test/resources/vectors）验收这个库——TransactionEncoder 底下用的就是它；EIP-155 向量测试用它拼交易、核对签名。
 */
public final class Rlp {

    private Rlp() {}

    public sealed interface Item permits Bytes, Items {}

    public record Bytes(byte[] value) implements Item {
        public Bytes {
            Objects.requireNonNull(value, "value");
        }
    }

    public record Items(List<Item> value) implements Item {
        public Items {
            value = List.copyOf(value);
        }
    }

    public static Item bytes(byte[] value) {
        return new Bytes(value.clone());
    }

    /** 非负整数 → 去前导零的大端字节串；0 → 空串（RlpString.create(BigInteger) 的规则，我们只挡负数）。 */
    public static Item integer(BigInteger value) {
        if (value.signum() < 0) {
            throw new IllegalArgumentException("RLP 只编码非负整数：" + value);
        }
        return new Bytes(RlpString.create(value).getBytes());
    }

    public static Item integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    public static Item list(Item... items) {
        return new Items(List.of(items));
    }

    public static Item list(List<Item> items) {
        return new Items(items);
    }

    public static byte[] encode(Item item) {
        return RlpEncoder.encode(toWeb3j(item));
    }

    /** 解码一整串字节（顶层恰好一个元素）。 */
    public static Item decode(byte[] encoded) {
        RlpList outer = RlpDecoder.decode(encoded);
        if (outer.getValues().size() != 1) {
            throw new IllegalArgumentException("RLP 顶层应恰好有一个元素，得到 " + outer.getValues().size());
        }
        return fromWeb3j(outer.getValues().get(0));
    }

    public static byte[] toBytes(Item item) {
        if (!(item instanceof Bytes b)) {
            throw new IllegalArgumentException("期望字节串，得到列表");
        }
        return b.value().clone();
    }

    private static RlpType toWeb3j(Item item) {
        return switch (item) {
            case Bytes b -> RlpString.create(b.value());
            case Items l -> {
                List<RlpType> children = new ArrayList<>(l.value().size());
                for (Item child : l.value()) {
                    children.add(toWeb3j(child));
                }
                yield new RlpList(children);
            }
        };
    }

    private static Item fromWeb3j(RlpType type) {
        if (type instanceof RlpString s) {
            return new Bytes(Arrays.copyOf(s.getBytes(), s.getBytes().length));
        }
        List<Item> items = new ArrayList<>();
        for (RlpType child : ((RlpList) type).getValues()) {
            items.add(fromWeb3j(child));
        }
        return new Items(items);
    }
}
