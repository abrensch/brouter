package btools.server;


import org.json.JSONObject;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

public class RouteServerTest {
  private static final String host = "localhost";
  private static final String port = "17777";
  private static final String baseUrl = "http://" + host + ":" + port + "/";

  @ClassRule
  public static TemporaryFolder profileDir = new TemporaryFolder();

  @BeforeClass
  public static void setupServer() throws IOException, InterruptedException {
    File workingDir = new File(".").getCanonicalFile();
    File segmentDir = new File(workingDir, "../brouter-map-creator/build/resources/test/tmp/segments");
    File profileSourceDir = new File(workingDir, "../misc/profiles2");
    // Copy required files to temporary dir to keep test inputs isolated
    Files.copy(Paths.get(profileSourceDir.getAbsolutePath(), "lookups.dat"), Paths.get(profileDir.getRoot().getAbsolutePath(), "lookups.dat"));
    Files.copy(Paths.get(profileSourceDir.getAbsolutePath(), "trekking.brf"), Paths.get(profileDir.getRoot().getAbsolutePath(), "trekking.brf"));

    Runnable runnable = () -> {
      try {
        RouteServer.main(new String[]{segmentDir.getAbsolutePath(), profileDir.getRoot().getAbsolutePath(), port, "1"});
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
    Assert.assertEquals("1169", geoJson.query("/features/0/properties/track-length"));
    Assert.assertEquals("-15", geoJson.query("/features/0/properties/plain-ascend"));
    Assert.assertEquals("4", geoJson.query("/features/0/properties/filtered ascend"));
  }

  @Test
  public void overrideParameter() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.723037,50.000491|8.712737,50.002899&nogos=&profile=trekking&alternativeidx=0&format=geojson&profile:avoid_unsafe=1");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("1570", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void voiceHints() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.705796,50.003124|8.705859,50.0039599&nogos=&profile=trekking&alternativeidx=0&format=geojson&timode=2");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals(2, geoJson.query("/features/0/properties/voicehints/0/1")); // TL
  }

  @Test
  public void invalidUrl() throws IOException {
    URL requestUrl = new URL(baseUrl + "invalid");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_NOT_FOUND, httpConnection.getResponseCode());
  }
}
