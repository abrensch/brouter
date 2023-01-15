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
import java.io.OutputStream;
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
    // Copy required files to temporary dir because profile upload will create files
    Files.copy(Paths.get(profileSourceDir.getAbsolutePath(), "lookups.dat"), Paths.get(profileDir.getRoot().getAbsolutePath(), "lookups.dat"));
    Files.copy(Paths.get(profileSourceDir.getAbsolutePath(), "trekking.brf"), Paths.get(profileDir.getRoot().getAbsolutePath(), "trekking.brf"));
    String customProfileDir = "custom";

    Runnable runnable = () -> {
      try {
        RouteServer.main(new String[]{segmentDir.getAbsolutePath(), profileDir.getRoot().getAbsolutePath(), customProfileDir, port, "1"});
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
  public void directRoutingFirst() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.718354,50.001514|8.718917,50.001361|8.716986,50.000105|8.718306,50.00145&nogos=&profile=trekking&alternativeidx=0&format=geojson&straight=0&timode=3");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("521", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void directRoutingLast() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.718306,50.00145|8.717464,50.000405|8.718917,50.001361|8.718354,50.001514&nogos=&profile=trekking&alternativeidx=0&format=geojson&straight=2&timode=3");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("520", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void directRoutingMiddle() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.718539,50.006581|8.718198,50.006065,d|8.71785,50.006034|8.7169,50.004456&nogos=&profile=trekking&alternativeidx=0&format=geojson&timode=3");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("350", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void misplacedPoints() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter?lonlats=8.708678,49.999188|8.71145,49.999761|8.715801,50.00065&nogos=&profile=trekking&alternativeidx=0&format=geojson&correctMisplacedViaPoints=1&timode=3");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();
    httpConnection.connect();

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());

    InputStream inputStream = httpConnection.getInputStream();
    JSONObject geoJson = new JSONObject(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
    Assert.assertEquals("598", geoJson.query("/features/0/properties/track-length"));
  }

  @Test
  public void uploadValidProfile() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter/profile");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();

    httpConnection.setRequestMethod("POST");
    httpConnection.setDoOutput(true);
    String dummyProfile = "---context:global   # following code refers to global config\n" +
      "\n" +
      "# this prevents suppression of unused tags, so they are visibly in the data tab\n" +
      "assign processUnusedTags = true\n" +
      "assign validForFoot = true\n" +
      "\n" +
      "---context:way   # following code refers to way-tags\n" +
      "\n" +
      "assign costfactor\n" +
      "  switch and highway= not route=ferry  100000 1\n" +
      "\n" +
      "---context:node  # following code refers to node tags\n" +
      "assign initialcost = 0\n";
    try (OutputStream outputStream = httpConnection.getOutputStream()) {
      outputStream.write(dummyProfile.getBytes(StandardCharsets.UTF_8));
    }

    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());
    InputStream inputStream = httpConnection.getInputStream();
    String response = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    JSONObject jsonResponse = new JSONObject(response);
    Assert.assertTrue(jsonResponse.query("/profileid").toString().startsWith("custom_"));
    Assert.assertFalse(jsonResponse.has("error"));
  }

  @Test
  public void uploadInvalidProfile() throws IOException {
    URL requestUrl = new URL(baseUrl + "brouter/profile");
    HttpURLConnection httpConnection = (HttpURLConnection) requestUrl.openConnection();

    httpConnection.setRequestMethod("POST");
    httpConnection.setDoOutput(true);
    String invalidProfile = "";
    try (OutputStream outputStream = httpConnection.getOutputStream()) {
      outputStream.write(invalidProfile.getBytes(StandardCharsets.UTF_8));
    }

    // It would be better if RouteServer would return "400 Bad Request"
    Assert.assertEquals(HttpURLConnection.HTTP_OK, httpConnection.getResponseCode());
    InputStream inputStream = httpConnection.getInputStream();
    String response = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    JSONObject jsonResponse = new JSONObject(response);
    // Returns profileid, but profile isn't valid
    Assert.assertTrue(jsonResponse.query("/profileid").toString().startsWith("custom_"));
    Assert.assertTrue(jsonResponse.has("error"));
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
