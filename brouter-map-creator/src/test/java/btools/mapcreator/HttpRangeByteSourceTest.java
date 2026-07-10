package btools.mapcreator;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final byte[] DATA = "0123456789abcdefghij".getBytes(StandardCharsets.US_ASCII);

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
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/ok");
    assertArrayEquals("3456789".getBytes(StandardCharsets.US_ASCII), src.read(3, 7));
    assertEquals(1, hits.get());
  }

  @Test
  public void exposesMetadataAfterSuccessfulRead() throws IOException {
    server.createContext("/metadata", exchange -> {
      hits.incrementAndGet();
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/metadata");

    assertSnapshotUnavailable(src);
    src.read(0, 4);

    assertEquals(DATA.length, src.size());
    String versionId = src.versionId();
    assertTrue(versionId, versionId.matches("http-sha256:[0-9a-f]{64}"));
    assertEquals(versionId, src.versionId());
  }

  @Test
  public void versionIdsDifferAcrossUrlsWithSameEtagAndLength() throws IOException {
    server.createContext("/version-one", exchange -> sendRange(exchange, DATA, "\"v1\""));
    server.createContext("/version-two", exchange -> sendRange(exchange, DATA, "\"v1\""));
    PmTilesArchive.HttpRangeByteSource one =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/version-one");
    PmTilesArchive.HttpRangeByteSource two =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/version-two");

    one.read(0, 4);
    two.read(0, 4);

    assertNotEquals(one.versionId(), two.versionId());
  }

  @Test
  public void secondRequestSendsIfMatch() throws IOException {
    AtomicReference<String> firstIfMatch = new AtomicReference<>();
    AtomicReference<String> secondIfMatch = new AtomicReference<>();
    server.createContext("/if-match", exchange -> {
      int hit = hits.incrementAndGet();
      if (hit == 1) {
        firstIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
      } else {
        secondIfMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
      }
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/if-match");

    src.read(0, 4);
    src.read(4, 4);

    assertNull(firstIfMatch.get());
    assertEquals("\"v1\"", secondIfMatch.get());
  }

  @Test
  public void requestsIdentityContentEncoding() throws IOException {
    AtomicReference<String> acceptEncoding = new AtomicReference<>();
    server.createContext("/identity", exchange -> {
      hits.incrementAndGet();
      acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/identity");

    src.read(0, 4);

    assertEquals("identity", acceptEncoding.get());
  }

  @Test
  public void changedEtagFails() throws IOException {
    server.createContext("/changed-etag", exchange -> {
      String etag = hits.incrementAndGet() == 1 ? "\"v1\"" : "\"v2\"";
      sendRange(exchange, DATA, etag);
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/changed-etag");

    src.read(0, 4);
    try {
      src.read(4, 4);
      fail("expected changed ETag rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("changed"));
    }
    assertEquals(2, hits.get());
  }

  @Test
  public void weakEtagFails() throws IOException {
    server.createContext("/weak-etag", exchange -> {
      hits.incrementAndGet();
      sendRange(exchange, DATA, "W/\"v1\"");
    });
    assertPermanentFailure("/weak-etag", "ETag");
  }

  @Test
  public void absentEtagFails() throws IOException {
    server.createContext("/absent-etag", exchange -> {
      hits.incrementAndGet();
      sendPartial(exchange, Arrays.copyOfRange(DATA, 0, 4), null, "bytes 0-3/20");
    });
    assertPermanentFailure("/absent-etag", "ETag");
  }

  @Test
  public void wrongContentRangeFailsEvenWhenBodyLengthIsCorrect() throws IOException {
    server.createContext("/wrong-range", exchange -> {
      hits.incrementAndGet();
      sendPartial(exchange, Arrays.copyOfRange(DATA, 4, 8), "\"v1\"", "bytes 5-8/20");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/wrong-range");
    try {
      src.read(4, 4);
      fail("expected Content-Range rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("Content-Range"));
    }
    assertEquals(1, hits.get());
  }

  @Test
  public void changedTotalLengthFails() throws IOException {
    server.createContext("/changed-total", exchange -> {
      int hit = hits.incrementAndGet();
      if (hit == 1) {
        sendRange(exchange, DATA, "\"v1\"");
      } else {
        sendPartial(exchange, Arrays.copyOfRange(DATA, 4, 8), "\"v1\"", "bytes 4-7/21");
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/changed-total");

    src.read(0, 4);
    try {
      src.read(4, 4);
      fail("expected changed source length rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("changed"));
    }
    assertEquals(2, hits.get());
  }

  @Test
  public void preconditionFailedIsNotRetried() throws IOException {
    server.createContext("/precondition", exchange -> {
      if (hits.incrementAndGet() == 1) {
        sendRange(exchange, DATA, "\"v1\"");
      } else {
        exchange.sendResponseHeaders(412, -1);
        exchange.close();
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/precondition");

    src.read(0, 4);
    try {
      src.read(4, 4);
      fail("expected an IOException for 412");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("412"));
    }
    assertEquals("no retry after a failed snapshot precondition", 2, hits.get());
  }

  @Test
  public void rangeOverflowFailsBeforeOpeningConnection() throws IOException {
    server.createContext("/overflow", exchange -> {
      hits.incrementAndGet();
      exchange.sendResponseHeaders(500, -1);
      exchange.close();
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/overflow");

    try {
      src.read(Long.MAX_VALUE, 2);
      fail("expected range overflow rejection");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("range"));
    }
    assertEquals(0, hits.get());
  }

  @Test
  public void encodedResponseFails() throws IOException {
    server.createContext("/encoded", exchange -> {
      hits.incrementAndGet();
      exchange.getResponseHeaders().set("Content-Encoding", "gzip");
      sendRange(exchange, DATA, "\"v1\"");
    });
    assertPermanentFailure("/encoded", "Content-Encoding");
  }

  @Test
  public void successfulResponseThatIgnoresRangeIsNotRetried() throws IOException {
    server.createContext("/range-ignored", exchange -> {
      hits.incrementAndGet();
      exchange.sendResponseHeaders(200, DATA.length);
      try (OutputStream body = exchange.getResponseBody()) {
        body.write(DATA);
      }
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/range-ignored");

    try {
      src.read(0, 4);
      fail("expected HTTP 200 range rejection");
    } catch (IOException expected) {
      // A full response cannot satisfy a range read, even when its body has enough bytes.
    }
    assertEquals("a server that ignores Range must not be retried", 1, hits.get());
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
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/limited");
    assertArrayEquals("5678".getBytes(StandardCharsets.US_ASCII), src.read(5, 4));
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
      sendRange(exchange, DATA, "\"v1\"");
    });
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + "/flaky");
    assertArrayEquals("0123".getBytes(StandardCharsets.US_ASCII), src.read(0, 4));
    assertEquals(3, hits.get());
  }

  private void assertPermanentFailure(String path, String messagePart) throws IOException {
    PmTilesArchive.HttpRangeByteSource src =
      new PmTilesArchive.HttpRangeByteSource(baseUrl + path);
    try {
      src.read(0, 4);
      fail("expected permanent HTTP snapshot failure");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains(messagePart));
    }
    assertEquals(1, hits.get());
  }

  private static void assertSnapshotUnavailable(PmTilesArchive.HttpRangeByteSource src)
      throws IOException {
    try {
      src.size();
      fail("expected unavailable snapshot metadata");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("successful read"));
    }
    try {
      src.versionId();
      fail("expected unavailable snapshot metadata");
    } catch (IOException e) {
      assertTrue(e.getMessage(), e.getMessage().contains("successful read"));
    }
  }

  private static void sendRange(HttpExchange exchange, byte[] data, String etag)
      throws IOException {
    String range = exchange.getRequestHeaders().getFirst("Range");
    String[] parts = range.substring("bytes=".length()).split("-", -1);
    int from = Integer.parseInt(parts[0]);
    int to = Integer.parseInt(parts[1]);
    byte[] body = Arrays.copyOfRange(data, from, to + 1);
    sendPartial(exchange, body, etag, "bytes " + from + "-" + to + "/" + data.length);
  }

  private static void sendPartial(HttpExchange exchange, byte[] body, String etag,
      String contentRange) throws IOException {
    if (etag != null) {
      exchange.getResponseHeaders().set("ETag", etag);
    }
    exchange.getResponseHeaders().set("Content-Range", contentRange);
    exchange.sendResponseHeaders(206, body.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(body);
    }
  }
}
