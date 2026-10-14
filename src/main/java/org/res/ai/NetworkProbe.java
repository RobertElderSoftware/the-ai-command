package org.res.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** A credential-free HEAD request, isolated in a killable process to bound DNS too. */
final class NetworkProbe {
    private NetworkProbe() { }

    static int seconds(String property, int fallback) {
        String value = System.getProperty(property, Integer.toString(fallback));
        try {
            int seconds = Integer.parseInt(value);
            if (seconds > 0 && seconds <= 3600) return seconds;
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(property + " must be an integer between 1 and 3600");
    }

    static URI endpoint(String value) {
        try {
            URI uri = URI.create(value);
            if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null
                    || uri.getPort() < -1 || uri.getPort() == 0 || uri.getPort() > 65535)
                throw new IllegalArgumentException();
            return uri;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Network endpoint must be an http(s) URL without credentials, query, or fragment");
        }
    }

    static String display(URI endpoint) {
        // Exclude path, which may contain a deployment identifier or other private metadata.
        return endpoint.getScheme() + "://" + endpoint.getRawAuthority();
    }

    static Proxy javaProxy(URI endpoint) {
        ProxySelector selector = ProxySelector.getDefault();
        List<Proxy> proxies = selector == null ? List.of(Proxy.NO_PROXY) : selector.select(endpoint);
        if (proxies == null || proxies.isEmpty()) return Proxy.NO_PROXY;
        if (proxies.size() != 1)
            throw new IllegalArgumentException("Network preflight requires a single Java proxy route");
        return proxies.get(0);
    }

    /** null proxy inherits curl's HTTP(S)_PROXY/ALL_PROXY/NO_PROXY routing for Codex. */
    static void check(String backend, URI endpoint, Proxy proxy) throws IOException {
        int seconds = seconds("ai.network.probeSeconds", 10);
        run(command(endpoint, proxy, seconds), backend, endpoint, seconds);
    }

    static List<String> command(URI endpoint, Proxy proxy, int seconds) {
        List<String> command = new ArrayList<>(List.of("curl", "--disable", "--silent",
                "--head", "--output", System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null",
                "--write-out", "%{http_code} %{http_connect}", "--max-time", Integer.toString(seconds),
                "--connect-timeout", Integer.toString(seconds), "--max-redirs", "0"));
        if (proxy != null) {
            command.add("--noproxy");
            command.add(""); // Match the SDK's explicitly selected proxy, including Java exclusions.
            command.add("--proxy");
            if (proxy.type() == Proxy.Type.DIRECT) command.add("");
            else {
                if (!(proxy.address() instanceof InetSocketAddress address))
                    throw new IllegalArgumentException("Unsupported Java proxy address");
                String host = address.getHostString();
                if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
                command.add((proxy.type() == Proxy.Type.SOCKS ? "socks5h://" : "http://")
                        + host + ":" + address.getPort());
            }
        }
        command.add("--url");
        command.add(endpoint.toASCIIString());
        return List.copyOf(command);
    }

    static void run(List<String> command, String backend, URI endpoint, int seconds) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Network probe cancelled");
        // HEAD headers are discarded too; neither provider credentials nor response contents are logged.
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        Thread shutdown = new Thread(process::destroyForcibly, "network-probe-shutdown");
        boolean hooked = false;
        try (var probeOutput = process.getInputStream(); var probeError = process.getErrorStream();
                var probeInput = process.getOutputStream()) {
            try {
                Runtime.getRuntime().addShutdownHook(shutdown);
                hooked = true;
                probeInput.close();
                if (!process.waitFor(seconds, TimeUnit.SECONDS))
                    throw new ProviderNetworkException(backend, display(endpoint),
                            ProviderNetworkException.Phase.PREFLIGHT);
                int code = process.exitValue();
                String status = new String(probeOutput.readNBytes(32), java.nio.charset.StandardCharsets.US_ASCII);
                if (status.contains("407"))
                    throw new IOException("Network proxy requires authentication; no prompt was submitted");
                if (code == 0) return; // Any HTTP response proves reachability, including 401/403/405/429.
                if (List.of(5, 6, 7, 18, 28, 52, 55, 56).contains(code))
                    throw new ProviderNetworkException(backend, display(endpoint),
                            ProviderNetworkException.Phase.PREFLIGHT);
                throw new IOException("Network probe failed (curl exit " + code
                        + "); check curl, proxy and TLS configuration. No prompt was submitted.");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Network probe cancelled");
            } finally {
                try {
                    process.descendants().forEach(child -> {
                        try { child.destroyForcibly(); } catch (RuntimeException ignored) { }
                    });
                } catch (RuntimeException ignored) { }
                process.destroyForcibly();
                try {
                    if (!process.waitFor(1, TimeUnit.SECONDS))
                        throw new IOException("Could not stop the network probe; no prompt was submitted");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                } finally {
                    try { if (hooked) Runtime.getRuntime().removeShutdownHook(shutdown); }
                    catch (IllegalStateException shuttingDown) { }
                }
            }
        }
    }
}
