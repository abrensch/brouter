package btools.expressions;

import java.io.File;
import java.util.Random;

public final class ProfileTest {
  public static void main(String[] args) {
    if (args.length != 2) {
      System.out.println("usage: java ProfileTest <lookup-file> <profile>");
      return;
    }

    File lookupFile = new File(args[0]);
    File profileFile = new File(args[1]);
    testContext(lookupFile, profileFile, false);
  }

  private static void testContext(File lookupFile, File profileFile, boolean nodeContext) {
    // read lookup.dat + profiles
    BExpressionMetaData meta = new BExpressionMetaData();
    BExpressionContext expctx = nodeContext ? new BExpressionContextNode(meta) : new BExpressionContextWay(meta);
    meta.readMetaData(lookupFile);
    expctx.parseFile(profileFile, "global");

    Random rnd = new Random();
    {
      int[] data = expctx.generateRandomValues(rnd);
      expctx.evaluate(data);
    }
  }
}
