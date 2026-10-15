package com.offerpilot.job.application;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A minimal HTTP/1.1 GET that connects to an address the caller already resolved and validated,
 * instead of letting the client resolve the host again. This closes the DNS-rebinding gap: the
 * address checked is the address used. TLS still verifies the certificate against the original
 * host name (SNI + HTTPS endpoint identification), so pinning does not weaken HTTPS.
 */
final class PinnedHttpGet {
    private static final int MAX_HEADER_BYTES = 64 * 1024;

    record Response(int status, Map<String, String> headers, InputStream body, Socket socket) implements AutoCloseable {
        String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
        @Override public void close() { try { socket.close(); } catch (IOException ignored) { } }
    }

    private PinnedHttpGet() {}

    static Response get(URI uri, InetAddress address, Duration connectTimeout, Duration readTimeout, Map<String, String> headers) throws IOException {
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() == -1 ? (https ? 443 : 80) : uri.getPort();
        String host = uri.getHost();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(address, port), (int) connectTimeout.toMillis());
            socket.setSoTimeout((int) readTimeout.toMillis());
            if (https) {
                var ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(socket, host, port, true);
                var params = ssl.getSSLParameters();
                params.setEndpointIdentificationAlgorithm("HTTPS");
                String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
                if (!bare.contains(":") && !bare.matches("[0-9.]+")) params.setServerNames(java.util.List.of(new SNIHostName(bare)));
                ssl.setSSLParameters(params);
                ssl.startHandshake();
                socket = ssl;
            }
            String target = (uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath())
                    + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            var request = new StringBuilder("GET ").append(target).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append(uri.getPort() == -1 ? "" : ":" + port).append("\r\n");
            headers.forEach((k, v) -> request.append(k).append(": ").append(v).append("\r\n"));
            request.append("Accept-Encoding: identity\r\nConnection: close\r\n\r\n");
            OutputStream out = socket.getOutputStream();
            out.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            var in = new BufferedInputStream(socket.getInputStream());
            int[] budget = {MAX_HEADER_BYTES};
            String statusLine = line(in, budget);
            String[] parts = statusLine.split(" ", 3);
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Not an HTTP response");
            int status = Integer.parseInt(parts[1]);
            var responseHeaders = new LinkedHashMap<String, String>();
            for (String header; !(header = line(in, budget)).isEmpty(); ) {
                int colon = header.indexOf(':');
                if (colon > 0) responseHeaders.putIfAbsent(header.substring(0, colon).trim().toLowerCase(Locale.ROOT), header.substring(colon + 1).trim());
            }
            InputStream body;
            String encoding = responseHeaders.getOrDefault("transfer-encoding", "");
            if (encoding.toLowerCase(Locale.ROOT).contains("chunked")) body = new Chunked(in);
            else if (responseHeaders.containsKey("content-length")) body = new Bounded(in, Long.parseLong(responseHeaders.get("content-length")));
            else body = in;
            return new Response(status, responseHeaders, body, socket);
        } catch (IOException | RuntimeException ex) {
            socket.close();
            throw ex instanceof IOException io ? io : new IOException("Malformed HTTP response", ex);
        }
    }

    private static String line(InputStream in, int[] budget) throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (int b; (b = in.read()) != -1; ) {
            if (--budget[0] < 0) throw new IOException("HTTP headers too large");
            if (b == '\n') break;
            if (b != '\r') buffer.write(b);
        }
        return buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static final class Bounded extends FilterInputStream {
        private long remaining;
        Bounded(InputStream in, long length) { super(in); this.remaining = length; }
        @Override public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }

    /** Decodes Transfer-Encoding: chunked; trailers are skipped. */
    private static final class Chunked extends FilterInputStream {
        private long remaining;
        private boolean done;
        Chunked(InputStream in) { super(in); }
        private boolean next() throws IOException {
            if (done) return false;
            if (remaining > 0) return true;
            int[] budget = {1024};
            String size = line(in, budget);
            if (size.isEmpty()) size = line(in, budget); // CRLF that ends the previous chunk
            int semicolon = size.indexOf(';');
            remaining = Long.parseLong((semicolon < 0 ? size : size.substring(0, semicolon)).trim(), 16);
            if (remaining == 0) {
                done = true;
                return false;
            }
            return true;
        }
        @Override public int read() throws IOException {
            if (!next()) return -1;
            int b = in.read();
            if (b >= 0) remaining--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) return -1;
            int n = in.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }
    }
}
