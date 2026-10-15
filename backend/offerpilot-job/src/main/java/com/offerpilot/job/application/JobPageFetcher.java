package com.offerpilot.job.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fetch a public job page for JB-06. This is a server-side request driven by user input, so every hop
 * (including redirects) is re-validated: http(s) on default ports only, no credentials, and every resolved
 * address must be public. The host is resolved once per hop and the connection goes to that validated
 * address ({@link PinnedHttpGet}), so a DNS answer that changes between check and connect (rebinding)
 * cannot redirect the request to an internal address. Bodies are capped and only HTML / plain text is read.
 */
@Component
public class JobPageFetcher {
    static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 3;
    private static final Pattern CHARSET = Pattern.compile("charset=([\\w-]+)", Pattern.CASE_INSENSITIVE);

    public record Page(String url, String title, String text) {}

    /** Host name resolution, injectable so tests can simulate a DNS answer that changes between lookups. */
    interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
    record Target(URI uri, InetAddress address) {}

    private final boolean enabled;
    private final Resolver resolver;
    private final java.util.function.Predicate<InetAddress> allowed;

    @org.springframework.beans.factory.annotation.Autowired
    public JobPageFetcher(@Value("${offerpilot.job-import.url-fetch-enabled:true}") boolean enabled) {
        this(enabled, InetAddress::getAllByName, JobPageFetcher::isPublic);
    }

    JobPageFetcher(boolean enabled, Resolver resolver, java.util.function.Predicate<InetAddress> allowed) {
        this.enabled = enabled;
        this.resolver = resolver;
        this.allowed = allowed;
    }

    public Page fetch(String url) {
        if (!enabled) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "当前部署未开启网址导入，请直接粘贴岗位描述文本。");
        Target target = target(url);
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = target.uri();
            try (var response = PinnedHttpGet.get(uri, target.address(), Duration.ofSeconds(5), Duration.ofSeconds(10), Map.of(
                    "User-Agent", "Mozilla/5.0 (compatible; OfferPilot-JobImport/1.0)",
                    "Accept", "text/html,text/plain;q=0.9"))) {
                int status = response.status();
                if (status >= 300 && status < 400) {
                    String location = response.header("Location");
                    if (location == null || location.isBlank()) break;
                    target = target(uri.resolve(location).toString());
                    continue;
                }
                if (status != 200)
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "网页返回 " + status + "，无法读取。请直接粘贴岗位描述文本。");
                String type = java.util.Objects.requireNonNullElse(response.header("Content-Type"), "text/html").toLowerCase(Locale.ROOT);
                if (!type.startsWith("text/html") && !type.startsWith("text/plain") && !type.startsWith("application/xhtml"))
                    throw new BusinessException(ErrorCode.VALIDATION_FAILED, "该网址不是网页（" + type.split(";")[0] + "），请直接粘贴岗位描述文本。");
                return page(uri, type, read(response.body(), charset(type)));
            } catch (IOException ex) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法打开该网址，请检查链接，或直接粘贴岗位描述文本。");
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_FAILED, "网址跳转次数过多，请直接粘贴岗位描述文本。");
    }

    private static Page page(URI uri, String type, String body) {
        String title = type.startsWith("text/plain") ? "" : HtmlText.title(body);
        String text = type.startsWith("text/plain") ? body.strip() : HtmlText.text(body);
        if (text.codePointCount(0, text.length()) < 80)
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "没有从该网页读到岗位正文（很多招聘站需要登录或由浏览器脚本渲染）。请在浏览器中复制岗位描述后粘贴。");
        return new Page(uri.toString(), title, text);
    }

    static URI validate(String url) {
        return new JobPageFetcher(true).target(url).uri();
    }

    /** Validates the URL and resolves its host once; the returned address is the one the request must use. */
    Target target(String url) {
        URI uri;
        try { uri = new URI(url == null ? "" : url.strip()); }
        catch (URISyntaxException ex) { throw invalid(); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") || uri.getHost() == null || uri.getRawUserInfo() != null)
            throw invalid();
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw invalid();
        InetAddress[] addresses;
        try { addresses = resolver.resolve(uri.getHost()); }
        catch (UnknownHostException ex) { throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法解析该网址的域名。"); }
        if (addresses.length == 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "无法解析该网址的域名。");
        for (InetAddress address : addresses) if (!allowed.test(address)) throw invalid();
        return new Target(uri, addresses[0]);
    }

    /** Reject loopback, private, link-local (incl. cloud metadata), CGNAT, unique-local and multicast ranges. */
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 0xff, second = b[1] & 0xff;
            if (first == 0 || first == 100 && second >= 64 && second < 128 || first >= 240) return false;
        } else if ((b[0] & 0xfe) == 0xfc) {
            return false;
        } else if (address instanceof Inet6Address && isMappedV4(b)) {
            try { return isPublic(InetAddress.getByAddress(java.util.Arrays.copyOfRange(b, 12, 16))); }
            catch (UnknownHostException ex) { return false; }
        }
        return true;
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, "只支持公开的 http/https 招聘网页地址。");
    }

    private static Charset charset(String type) {
        Matcher m = CHARSET.matcher(type);
        try { return m.find() ? Charset.forName(m.group(1)) : StandardCharsets.UTF_8; }
        catch (RuntimeException ex) { return StandardCharsets.UTF_8; }
    }

    private static String read(InputStream in, Charset charset) {
        try {
            var out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_BYTES) throw new BusinessException(ErrorCode.VALIDATION_FAILED, "网页过大，请直接粘贴岗位描述文本。");
                out.write(buffer, 0, n);
            }
            return out.toString(charset);
        } catch (IOException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "读取网页失败，请直接粘贴岗位描述文本。");
        }
    }

}
