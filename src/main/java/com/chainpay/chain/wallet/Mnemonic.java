package com.chainpay.chain.wallet;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * BIP-39 助记词：熵 → 词、词 → 校验。
 *
 * <p>规则全部来自规范：128 位熵取 SHA-256 的前 4 位作校验位，拼成 132 位，每 11 位一个数（0–2047）查词表得 12 个词；
 * 256 位熵 → 8 位校验 → 24 个词。校验位能抓住抄错、漏抄、调换顺序，但**只有 1/16（12 词）的概率放过一次随机错误**——
 * 它是防手误的，不是防篡改的。词表逐字取自 bitcoin/bips 的 english.txt（2048 个词，已排序），{@code MnemonicTest} 核 sha256。
 * 生成用 {@link SecureRandom}：助记词的全部安全性就是这 128 位随机数，用 {@code java.util.Random} 等于把种子交给时钟。
 */
public final class Mnemonic {

    private static final List<String> WORDS = load();

    private Mnemonic() {}

    public static List<String> words() {
        return WORDS;
    }

    /** 熵必须是 16、20、24、28 或 32 字节（12 到 24 个词）；本项目只用 16。 */
    public static String fromEntropy(byte[] entropy) {
        if (entropy == null || entropy.length < 16 || entropy.length > 32 || entropy.length % 4 != 0) {
            throw new IllegalArgumentException("熵必须是 16 到 32 字节且是 4 的倍数");
        }
        int entropyBits = entropy.length * 8;
        int checksumBits = entropyBits / 32;
        byte[] hash = sha256(entropy);
        boolean[] bits = new boolean[entropyBits + checksumBits];
        for (int i = 0; i < entropyBits; i++) {
            bits[i] = (entropy[i / 8] >> (7 - i % 8) & 1) == 1;
        }
        for (int i = 0; i < checksumBits; i++) {
            bits[entropyBits + i] = (hash[i / 8] >> (7 - i % 8) & 1) == 1;
        }
        StringBuilder out = new StringBuilder();
        for (int w = 0; w < bits.length / 11; w++) {
            int index = 0;
            for (int b = 0; b < 11; b++) {
                index = index << 1 | (bits[w * 11 + b] ? 1 : 0);
            }
            out.append(w == 0 ? "" : " ").append(WORDS.get(index));
        }
        return out.toString();
    }

    /** 12 个词 = 128 位安全随机熵。 */
    public static String generate12() {
        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        try {
            return fromEntropy(entropy);
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    /** 词数对、每个词都在词表里、最后几位校验位与前面的熵算出来的一致。 */
    public static boolean checksumValid(String mnemonic) {
        if (mnemonic == null) {
            return false;
        }
        String[] words = mnemonic.trim().toLowerCase(Locale.ROOT).split("\\s+");
        if (words.length % 3 != 0 || words.length < 12 || words.length > 24) {
            return false;
        }
        int totalBits = words.length * 11;
        int checksumBits = totalBits / 33;
        int entropyBits = totalBits - checksumBits;
        boolean[] bits = new boolean[totalBits];
        for (int w = 0; w < words.length; w++) {
            int index = Arrays.binarySearch(WORDS.toArray(), words[w]);
            if (index < 0) {
                return false;
            }
            for (int b = 0; b < 11; b++) {
                bits[w * 11 + b] = (index >> (10 - b) & 1) == 1;
            }
        }
        byte[] entropy = new byte[entropyBits / 8];
        for (int i = 0; i < entropyBits; i++) {
            if (bits[i]) {
                entropy[i / 8] |= (byte) (1 << (7 - i % 8));
            }
        }
        byte[] hash = sha256(entropy);
        for (int i = 0; i < checksumBits; i++) {
            if (bits[entropyBits + i] != ((hash[i / 8] >> (7 - i % 8) & 1) == 1)) {
                return false;
            }
        }
        return true;
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    private static List<String> load() {
        try (InputStream in = Mnemonic.class.getResourceAsStream("/bip39/english.txt")) {
            if (in == null) {
                throw new IllegalStateException("缺少 /bip39/english.txt");
            }
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n");
            List<String> words = new ArrayList<>(2048);
            for (String line : lines) {
                if (!line.isBlank()) {
                    words.add(line.trim());
                }
            }
            if (words.size() != 2048) {
                throw new IllegalStateException("词表应有 2048 个词，读到 " + words.size());
            }
            return List.copyOf(words);
        } catch (IOException e) {
            throw new IllegalStateException("读不了词表", e);
        }
    }
}
