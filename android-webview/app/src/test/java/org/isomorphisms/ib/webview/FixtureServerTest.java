package org.isomorphisms.ib.webview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import org.junit.Test;

public final class FixtureServerTest {
    @Test
    public void session_cookie_gates_form_and_probe() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            server.start();

            HttpURLConnection unauthenticated_probe = open(server.probe_url(), null);
            assertEquals(401, unauthenticated_probe.getResponseCode());
            unauthenticated_probe.disconnect();

            HttpURLConnection start = open(server.start_url(), null);
            start.setInstanceFollowRedirects(false);
            assertEquals(302, start.getResponseCode());
            String cookie = start.getHeaderField("Set-Cookie");
            assertNotNull(cookie);
            start.disconnect();

            String cookie_header = cookie.split(";", 2)[0];
            HttpURLConnection form = open(server.form_url(), cookie_header);
            assertEquals(200, form.getResponseCode());
            String body;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(form.getInputStream(), StandardCharsets.UTF_8))) {
                body = reader.lines().collect(Collectors.joining("\n"));
            }
            assertTrue(body.contains("ordinary_field"));
            assertTrue(body.contains("secret_field"));
            form.disconnect();

            HttpURLConnection authenticated_probe = open(server.probe_url(), cookie_header);
            assertEquals(200, authenticated_probe.getResponseCode());
            authenticated_probe.disconnect();
        }
    }

    @Test
    public void heavy_page_exposes_useful_content_before_bounded_junk_assets() throws Exception {
        try (FixtureServer server = new FixtureServer()) {
            server.start();

            HttpURLConnection page = open(server.heavy_url(), null);
            assertEquals(200, page.getResponseCode());
            String body;
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(page.getInputStream(), StandardCharsets.UTF_8))) {
                body = reader.lines().collect(Collectors.joining("\n"));
            }
            page.disconnect();

            assertTrue(body.contains("useful-table"));
            assertTrue(body.contains("useful-control"));
            assertTrue(body.contains("IBLongView.firstUseful('heavy-v1')"));
            assertTrue(body.contains("/junk/0.svg"));
            assertTrue(body.contains("/junk/7.svg"));

            HttpURLConnection junk = open(
                server.heavy_url().replace("/heavy", "/junk/0.svg"),
                null
            );
            assertEquals(200, junk.getResponseCode());
            int junk_bytes = 0;
            try (java.io.InputStream input = junk.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    junk_bytes += count;
                }
            }
            junk.disconnect();

            assertTrue(junk_bytes >= 256 * 1024);
            assertTrue(server.served_response_count() >= 2);
            assertTrue(server.served_body_bytes() >= junk_bytes);
        }
    }

    private static HttpURLConnection open(String url, String cookie) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(1500);
        connection.setReadTimeout(1500);
        if (cookie != null) {
            connection.setRequestProperty("Cookie", cookie);
        }
        return connection;
    }
}
