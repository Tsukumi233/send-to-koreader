package com.tsukumi.sendtokoreader;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Minimal WebDAV client: just what sending a book needs. */
final class WebDav {
    static final String PREFS = "target";

    interface Progress {
        void onBytes(long sent);
    }

    final String folderUrl;
    final String user;
    final String password;

    WebDav(String folderUrl, String user, String password) {
        this.folderUrl = folderUrl.replaceAll("/+$", "");
        this.user = user;
        this.password = password;
    }

    static WebDav load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String folder = p.getString("folder", "");
        if (folder.isEmpty()) return null;
        return new WebDav(folder, p.getString("user", ""), p.getString("password", ""));
    }

    /** Percent-encode each path segment; keep scheme, host and slashes. */
    static String encodePath(String url) {
        int start = url.indexOf("://");
        int pathStart = start < 0 ? -1 : url.indexOf('/', start + 3);
        if (pathStart < 0) return url;
        StringBuilder sb = new StringBuilder(url.substring(0, pathStart));
        for (String seg : url.substring(pathStart + 1).split("/", -1)) {
            sb.append('/').append(encodeSegment(decodeSegment(seg)));
        }
        return sb.toString();
    }

    static String encodeSegment(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String decodeSegment(String s) {
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private HttpURLConnection open(String url, String method) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setInstanceFollowRedirects(false);
        if (!user.isEmpty()) {
            String token = Base64.encodeToString(
                    (user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            c.setRequestProperty("Authorization", "Basic " + token);
        }
        return c;
    }

    private static int finish(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        InputStream body = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (body != null) {
            byte[] buf = new byte[4096];
            while (body.read(buf) != -1) { /* drain */ }
            body.close();
        }
        c.disconnect();
        return code;
    }

    /**
     * Writes and deletes a small probe file. HttpURLConnection refuses
     * PROPFIND, and this also proves we may write. 2xx = folder is usable.
     */
    int checkFolder() throws IOException {
        String probe = ".send-to-koreader-test";
        byte[] data = "ok".getBytes(StandardCharsets.UTF_8);
        int code = put(probe, new java.io.ByteArrayInputStream(data), data.length, s -> { });
        if (code / 100 == 2) {
            finish(open(encodePath(folderUrl) + "/" + probe, "DELETE"));
        }
        return code;
    }

    /** MKCOL over a raw socket, since HttpURLConnection refuses the method. */
    int createFolder() throws IOException {
        URL u = new URL(encodePath(folderUrl) + "/");
        boolean tls = "https".equalsIgnoreCase(u.getProtocol());
        int port = u.getPort() > 0 ? u.getPort() : u.getDefaultPort();
        java.net.Socket s;
        if (tls) {
            javax.net.ssl.SSLSocket ss = (javax.net.ssl.SSLSocket)
                    javax.net.ssl.SSLSocketFactory.getDefault().createSocket(u.getHost(), port);
            javax.net.ssl.SSLParameters params = ss.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS"); // verify hostname/IP
            ss.setSSLParameters(params);
            ss.startHandshake();
            s = ss;
        } else {
            s = new java.net.Socket(u.getHost(), port);
        }
        try {
            s.setSoTimeout(30000);
            StringBuilder req = new StringBuilder();
            req.append("MKCOL ").append(u.getPath()).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(u.getHost()).append(u.getPort() > 0 ? ":" + port : "").append("\r\n")
                    .append("Content-Length: 0\r\nConnection: close\r\n");
            if (!user.isEmpty()) {
                req.append("Authorization: Basic ").append(Base64.encodeToString(
                        (user + ":" + password).getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP)).append("\r\n");
            }
            req.append("\r\n");
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.ISO_8859_1));
            String status = r.readLine(); // HTTP/1.1 201 Created
            if (status == null) throw new IOException("服务器无响应");
            String[] parts = status.split(" ");
            return Integer.parseInt(parts[1]);
        } finally {
            s.close();
        }
    }

    /** Upload one file; returns the HTTP status. */
    int put(String name, InputStream in, long size, Progress progress) throws IOException {
        HttpURLConnection c = open(encodePath(folderUrl) + "/" + encodeSegment(name), "PUT");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/octet-stream");
        c.setRequestProperty("Expect", "100-continue"); // let the server refuse before the body
        if (size >= 0) {
            c.setFixedLengthStreamingMode(size);
        } else {
            c.setChunkedStreamingMode(64 * 1024);
        }
        byte[] buf = new byte[64 * 1024];
        long sent = 0;
        try (OutputStream out = c.getOutputStream()) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                sent += n;
                progress.onBytes(sent);
            }
        } catch (IOException e) {
            // The server may reject early (401/409…) and close the socket
            // mid-upload; report its status rather than a broken pipe.
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException ignored) {
                throw e;
            }
            if (code / 100 == 2) throw e;
            c.disconnect();
            return code;
        }
        return finish(c);
    }
}
