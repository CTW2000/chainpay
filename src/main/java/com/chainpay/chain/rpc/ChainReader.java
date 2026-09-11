package com.chainpay.chain.rpc;

import java.math.BigInteger;
import java.util.List;

/**
 * 索引器眼中的「链」：五个只读问题。
 *
 * <p>生产实现是 {@link EthRpc}（真节点）；测试实现是内存里的 FakeChain——
 * 想让链长什么样就长什么样：头落后、哈希突变、日志缺失，都是一行代码的事。
 * 用 HTTP 假节点也能做到，但每个场景都要拼 JSON，重得没人愿意多写一个用例。
 * 这是依赖注入的第二个用处：第一次是换端点，这次是换整条链。
 */
public interface ChainReader {

    /** 节点眼中的最新区块号（{@code latest}）。 */
    long blockNumber();

    /** 按区块号（十六进制字符串）或标签（latest / safe / finalized）取区块头。 */
    BlockHeader block(String numberOrTag);

    BlockHeader block(long number);

    /** 某合约在 [from, to] 内、topic0 匹配的全部日志，原样。这是<b>索引</b>路径，可能静默漏。 */
    List<RawLog> logs(long fromBlock, long toBlock, String address, String topic0);

    /**
     * 一个块里全部交易回执中的全部日志，原样、不筛选。这是<b>事实源</b>路径：
     * 回执是执行的结果，getLogs 只是从 bloom 建的索引。对账用它。
     */
    List<RawLog> blockReceipts(long number);

    /**
     * {@code eth_call}：让节点在某个状态上<b>模拟执行</b>一次合约调用，不上链、不花 gas，返回 ABI 编码的返回值。
     * 这是第一次「调合约」而不是「读日志」：decimals()、symbol()、balanceOf() 都走它。
     *
     * @param data     选择器 + ABI 编码的参数（见 {@code chain.erc20.Abi}）
     * @param blockTag latest / finalized / 十六进制块号
     * @return 十六进制返回值；合约没有这个函数、或执行 revert，节点以带 code 的错误报出
     */
    String call(String to, String data, String blockTag);

    /**
     * 该地址已发出的交易数（M4-②）。{@code latest} = 已上链的笔数，是确定的事实；{@code pending} 把<b>这个节点</b>内存池里
     * 排队的也算上，是它此刻的视角。付款的对账用 latest。
     */
    BigInteger transactionCount(String address, String tag);

    /** {@code eth_estimateGas}：节点模拟执行一次。合约 revert（比如热钱包代币不够）以带 code 的异常抛出——在花任何 gas 之前就知道发不出去。 */
    BigInteger estimateGas(String from, String to, String data);

    /** 最新块的基础费与节点建议的小费。 */
    FeeQuote feeQuote();

    /** 节点知不知道这笔交易（内存池里或已上链）。「nonce too low」时用它分辨是我们那笔已上链，还是别人用了编号。 */
    boolean transactionKnown(String txHash);

    /** 回执：空 = 还没上链（在内存池里、或节点已经忘了它）。有回执 = 已写进某个块，status 0 也算上链。 */
    java.util.Optional<TransactionReceipt> transactionReceipt(String txHash);
}
