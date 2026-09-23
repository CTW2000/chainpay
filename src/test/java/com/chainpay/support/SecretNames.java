package com.chainpay.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 「什么样的变量名算密钥」只写一份，在 {@code tools/image-check.sh}：它在生产里按这个名单逐个按值扫描镜像，是这条规矩真正起作用的地方。
 * 测试从那里读，不另抄（进程拆分 ①，2026-09-23 收口）。此前 ContainerGuardTest 抄了一份，两份一起漏掉了告警地址与测试探针的节点地址。
 */
public final class SecretNames {

    /** image-check.sh 里的那一行：{@code [[ $k =~ ^CHAINPAY_…$ ]] || continue}。 */
    private static final Pattern SCRIPT_LINE = Pattern.compile("\\[\\[ \\$k =~ (\\S+) \\]\\]");

    private SecretNames() {
    }

    /** 整个变量名是不是密钥形态（锚定首尾，配 {@code matches()} 用）。 */
    public static Pattern wholeName() {
        return Pattern.compile(fromScript());
    }

    /** 在一行文字里找密钥形态的变量名（去掉首尾锚点、两头加单词边界，配 {@code find()} 用）。 */
    public static Pattern inText() {
        String core = fromScript().replaceFirst("^\\^", "").replaceFirst("\\$$", "");
        return Pattern.compile("\\b" + core + "\\b");
    }

    private static String fromScript() {
        try {
            Matcher m = SCRIPT_LINE.matcher(Files.readString(Path.of("tools/image-check.sh")));
            if (!m.find()) {
                throw new IllegalStateException("tools/image-check.sh 里找不到「[[ $k =~ … ]]」那一行：密钥名单换了写法，这里要跟着改");
            }
            return m.group(1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
