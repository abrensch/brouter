package btools.routingapp;

import java.util.StringTokenizer;
import java.util.TreeSet;


/**
 * Decsription of a service config
 */
public class ServiceModeConfig {
  public String mode;
  public String profile;
  public String params;
  public TreeSet<String> nogoVetos;

  public ServiceModeConfig(String line) {
    StringTokenizer tk = new StringTokenizer(line);
    mode = tk.nextToken();
    profile = tk.nextToken();
    if (tk.hasMoreTokens()) params = tk.nextToken();
    else params = "noparams";
    nogoVetos = new TreeSet<>();
    while (tk.hasMoreTokens()) {
      nogoVetos.add(tk.nextToken());
    }
  }

  public ServiceModeConfig(String mode, String profile, String params) {
    this.mode = mode;
    this.profile = profile;
    this.params = params;
    nogoVetos = new TreeSet<>();
  }

  public String toLine() {
    StringBuilder sb = new StringBuilder(100);
    sb.append(mode).append(' ').append(profile);
    sb.append(' ').append(params);
    for (String veto : nogoVetos) sb.append(' ').append(veto);
    return sb.toString();
  }

  public String toString() {
    StringBuilder sb = new StringBuilder(100);
    sb.append(mode).append("->").append(profile);
    sb.append(" [" + nogoVetos.size() + "]" + (params.equals("noparams")?"":" +p"));
    return sb.toString();
  }
}
