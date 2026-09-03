package com.chainpay.chain.indexer;

/** 一次 {@link BlockIndexer#indexNextBatch()} 的三种结局。 */
public enum BatchOutcome {
    /** 写入了事件并推进了书签。写入数可以是 0：重放时全是重复，DO NOTHING 吃掉了。 */
    INDEXED,
    /** 链头不比书签新：节点落后，或者链没出新块。书签不动。 */
    UP_TO_DATE,
    /** 锁内重读发现书签已被别的实例推走：这一批作废，什么都没写。 */
    SKIPPED_CURSOR_MOVED
}
