// context for simple expression
// context means:
// - the local variables
// - the local variable names
// - the lookup-input variables

package btools.expressions;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;


public final class BExpressionMetaData {
  private static final String CONTEXT_TAG = "---context:";
  private static final String VERSION_TAG = "---lookupversion:";
  private static final String MINOR_VERSION_TAG = "---minorversion:";
  private static final String VARLENGTH_TAG = "---readvarlength";
  private static final String MIN_APP_VERSION_TAG = "---minappversion:";

  public short lookupVersion = -1;
  public short lookupMinorVersion = -1;
  public short minAppVersion = -1;

  private Map<String, BExpressionContext> listeners = new HashMap<>();

  public void registerListener(String context, BExpressionContext ctx) {
    listeners.put(context, ctx);
  }

  public void readMetaData(File lookupsFile) {
    try {
      BufferedReader br = new BufferedReader(new FileReader(lookupsFile));

      BExpressionContext ctx = null;

      for (; ; ) {
        String line = br.readLine();
        if (line == null) break;
        line = line.trim();
        if (line.length() == 0 || line.startsWith("#")) continue;
        if (line.startsWith(CONTEXT_TAG)) {
          ctx = listeners.get(line.substring(CONTEXT_TAG.length()));
          continue;
        }
        if (line.startsWith(VERSION_TAG)) {
          lookupVersion = Short.parseShort(line.substring(VERSION_TAG.length()));
          continue;
        }
        if (line.startsWith(MINOR_VERSION_TAG)) {
          lookupMinorVersion = Short.parseShort(line.substring(MINOR_VERSION_TAG.length()));
          continue;
        }
        if (line.startsWith(MIN_APP_VERSION_TAG)) {
          minAppVersion = Short.parseShort(line.substring(MIN_APP_VERSION_TAG.length()));
          continue;
        }
        if (line.startsWith(VARLENGTH_TAG)) { // tag removed...
          continue;
        }
        if (ctx != null) ctx.parseMetaLine(line);
      }
      br.close();

      for (BExpressionContext c : listeners.values()) {
        c.finishMetaParsing();
      }

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
