package com.chainpay.chain.rpc;

import java.util.Locale;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

/**
 * 节点地址。URL 的路径里就是提供商的 key，整条按密码对待。
 *
 * <p>为什么不直接 {@code URI.create}：它解析失败时抛的 IllegalArgumentException 会把<b>整条输入连出错位置</b>
 * 放进 message（JDK 的 URISyntaxException 就这么设计），那一行会出现在启动失败的 ERROR 日志里，
 * 而启动失败的日志最容易被整段贴进工单（2026-09-03 质询扫描 4.6，局部实验证实）。
 * 这里解析失败只报变量名与主机名，永不回显原文；也不替人修剪空格换行——那是「强行转换成看起来合理的值」，
 * 粘贴错了就该在这里停下并说清楚。
 */
public record RpcEndpoint(URI uri, String host) {

    public static RpcEndpoint parse(String envName, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(envName + " 为空：节点地址只从这个环境变量来");
        }
        URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(envName + " 不是合法的 URL（原文不回显，路径里是 key；"
                    + "常见原因：粘贴时多了引号、空格或换行）");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!http || host == null || host.isBlank()) {
            throw new IllegalArgumentException(envName + " 必须是 http(s)://主机/…（收到的 scheme=" + scheme
                    + "，host=" + host + "；原文不回显）");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopbackOrPrivate(host)) {
            throw new IllegalArgumentException(envName + " 用 http:// 指向公网主机 " + host
                    + "：URL 里带 key，会明文传输；请改用 https://（只有本机或内网节点允许 http）");
        }
        return new RpcEndpoint(uri, host);
    }

    /** 回环与 RFC 1918 内网地址允许明文 http（本地节点、局域网节点）。只看字面量，不解析 DNS（2026-09-09 扫描补丁）。 */
    static boolean isLoopbackOrPrivate(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("::1") || h.equals("[::1]") || h.startsWith("127.")
                || h.startsWith("10.") || h.startsWith("192.168.")
                || h.matches("172\\.(1[6-9]|2[0-9]|3[01])\\..*");
    }

    /** 可选的地址（审计节点）：空就是空，不是错。 */
    public static Optional<RpcEndpoint> parseOptional(String envName, String raw) {
        return raw == null || raw.isBlank() ? Optional.empty() : Optional.of(parse(envName, raw));
    }

    /** 只给主机名：谁不小心把它打进日志，也漏不出 key。 */
    @Override
    public String toString() {
        return host;
    }
}
