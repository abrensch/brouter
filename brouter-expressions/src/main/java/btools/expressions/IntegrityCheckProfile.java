package btools.expressions;

import java.io.File;

public class IntegrityCheckProfile {

  public static void main(final String[] args) {
    if (args.length != 2) {
      System.out.println("usage: java IntegrityCheckProfile <lookup-file> <profile-folder>");
      return;
    }

    IntegrityCheckProfile test = new IntegrityCheckProfile();
    try {
      File lookupFile = new File(args[0]);
      File profileDir = new File(args[1]);
      test.integrityTestProfiles(lookupFile, profileDir);
    } catch (Exception e) {
      System.err.println(e.getMessage());
    }
  }

  public void integrityTestProfiles(File lookupFile, File profileDir) {
    File[] files = profileDir.listFiles();

    if (files == null) {
      System.err.println("no files " + profileDir);
      return;
    }
    if (!lookupFile.exists()) {
      System.err.println("no lookup file " + lookupFile);
      return;
    }

    for (File f : files) {
      if (f.getName().endsWith(".brf")) {
        BExpressionMetaData meta = new BExpressionMetaData();
        BExpressionContext expctxWay = new BExpressionContextWay(meta);
        BExpressionContext expctxNode = new BExpressionContextNode(meta);
        meta.readMetaData(lookupFile);
        expctxNode.setForeignContext(expctxWay);
        expctxWay.parseFile(f, "global");
        expctxNode.parseFile(f, "global");
        System.out.println("test " + meta.lookupVersion + "." + meta.lookupMinorVersion + " " + f);
      }
    }
  }

}
