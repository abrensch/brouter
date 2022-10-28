package btools.server;


import org.json.JSONObject;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class RouteServerTest {
  private static final String host = "localhost";
  private static final String port = "17777";
  private static final String baseUrl = "http://" + host + ":" + port + "/";

  @BeforeClass
  public static void setupServer() throws IOException, InterruptedException {
    File workingDir = new File(".").getCanonicalFile();
    File segmentDir = new File(workingDir, "../brouter-map-creator/build/resources/test/tmp/segments");
    File profileDir = new File(workingDir, "../misc/profiles2");
    File customProfileDir = workingDir;

    Runnable runnable = () -> {
      try {
        RouteServer.main(new String[]{segmentDir.getAbsolutePath(), profileDir.getAbsolutePath(), customProfileDir.getAbsolutePath(), port, "1"});
      } catch (Exception e) {
        e.printStackTrace();
      }
    };

    Thread thread = new Thread(runnable);
    thread.start();

    // Busy-wait for server startup
    URL requestUrl = new URL(baseUrl);
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    for (int i = 20; i >= 0; i--) {
      try {
        httpConnection.setConnectTimeout(1000);
        httpConnection.connect();
        break;
      } catch (ConnectException e) {
        if (i == 0) {
          throw e;
        }
        Thread.sleep(100);
      }
    }
  }

  @Test
  public void defaultRouteTrekking() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.723037,50.000491|8.712737,50.002899&nogos=&profile=trekking&alternativeidx=0&format=geojson");

    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("1204", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void overrideParameter() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.723037,50.000491|8.712737,50.002899&nogos=&profile=trekking&alternativeidx=0&format=geojson&profile:avoid_unsafe=1");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("1902", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void robots() throws IOException {
    URL requestUrl = new URL(baseUrl + "robots.txt");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    String content = new String(httpConnection.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    Assert.assertTrue(content.contains("Disallow: /"));
  }

  @Test
  public void invalidUrl() throws IOException {
    URL requestUrl = new URL(baseUrl + "invalid");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, httpConnection.getResponseCode());
  }
}
