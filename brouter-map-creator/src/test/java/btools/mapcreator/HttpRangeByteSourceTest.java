package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The remote range-read path is the only way the 700 GB planet archive is consumed, so
 * its status handling gets its own tests against a local in-process HTTP server: happy
 * 206 slicing, no-retry on deterministic 4xx, and retry on 5xx.
 */
public class HttpRangeByteSourceTest {

  private HttpServer server;
  private String baseUrl;
  private final AtomicInteger hits = new AtomicInteger();

  private static final byte[] DATA = "0123456789abcdefghij".getBytes();

  @Before
  public void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
    baseUrl = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort();
    server.start();
  }

  @After
  public void stopServer() {
    server.stop(0);
  }

  @Test
  public void servesRangeRequests() throws IOException {
    server.createContext("/ok", exchange -> {
      hits.incrementAndGet();
      String range = exchange.getRequestHeaders().getFirst("Range"); // bytes=a-b
      String[] parts = range.substring(6).split("-");
      int from = Integer.parseInt(parts[0]);
      int to = Integer.parseInt(parts[1]);
      byte[] body = java.util.Arrays.copyOfRange(DATA, from, to + 1);
      exchange.sendResponseHeaders(206, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/ok");
    assertArrayEquals("3456789".getBytes(), src.read(3, 7));
    assertEquals(1, hits.get());
  }

  /**
   * A deterministic 404 must fail immediately: retrying it three times with sleeps
   * would only stall the build.
   */
  @Test
  public void notFoundIsNotRetried() throws IOException {
    server.createContext("/gone", exchange -> {
      hits.incrementAndGet();
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/gone");
    try {
      src.read(0, 4);
      fail("expected an IOException for 404");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("404"));
    }
    assertEquals("no retries for a deterministic failure", 1, hits.get());
  }

  /**
   * 429 is retryable: the source honours Retry-After (capped) and succeeds after the
   * limiter clears.
   */
  @Test
  public void rateLimitIsRetriedHonouringRetryAfter() throws IOException {
    server.createContext("/limited", exchange -> {
      if (hits.incrementAndGet() < 2) {
        exchange.getResponseHeaders().set("Retry-After", "0");
        exchange.sendResponseHeaders(429, -1);
        exchange.close();
        return;
      }
      byte[] body = java.util.Arrays.copyOfRange(DATA, 5, 9);
      exchange.sendResponseHeaders(206, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/limited");
    assertArrayEquals("5678".getBytes(), src.read(5, 4));
    assertEquals(2, hits.get());
  }

  /**
   * A transient 500 is retried and the read succeeds once the server recovers.
   */
  @Test
  public void serverErrorIsRetried() throws IOException {
    server.createContext("/flaky", exchange -> {
      if (hits.incrementAndGet() < 3) {
        exchange.sendResponseHeaders(500, -1);
        exchange.close();
        return;
      }
      byte[] body = java.util.Arrays.copyOfRange(DATA, 0, 4);
      exchange.sendResponseHeaders(206, body.length);
      try (OutputStream os = exchange.getResponseBody()) {
        os.write(body);
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/flaky");
    assertArrayEquals("0123".getBytes(), src.read(0, 4));
    assertEquals(3, hits.get());
  }
}
