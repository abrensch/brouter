package btools.server.request;

import btools.router.RoutingContext;
import btools.router.RoutingEngine;
import btools.server.ServiceContext;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * Profile download
 */
public class ProfileDownloadHandler {

  private ServiceContext serviceContext;
  private String profileId;

  public ProfileDownloadHandler(ServiceContext serviceContext, String profileId) {
    this.serviceContext = serviceContext;
    this.profileId = profileId;
  }

  public boolean exists() {
    File file = new File(serviceContext.profileDir, profileId + ".brf");
    return file.exists();
  }

  public void handleGetRequest(BufferedReader br, BufferedWriter response) throws IOException {
    String id;
    File file = new File(serviceContext.profileDir, profileId + ".brf");
    FileReader fileReader = new FileReader(file);
    BufferedReader bufferedReader = new BufferedReader(fileReader);

    String line;
    while ((line = bufferedReader.readLine()) != null) {
      response.write(line);
      response.newLine();
    }
  }
}
