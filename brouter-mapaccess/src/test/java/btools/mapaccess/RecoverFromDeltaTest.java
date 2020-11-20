package btools.mapaccess;

import btools.util.ProgressListener;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class RecoverFromDeltaTest {
    private static class MyProgressListener implements ProgressListener {

        @Override
        public void updateProgress(String progress) {
            //System.out.println(progress);
        }

        @Override
        public boolean isCanceled() {
            return false;
        }
    }

    private File originalTilesDir;
    private File diffsDir;
    private File freshGeneratedTilesDir;
    private File updatedTilesDir;

    private void initResources() {

        URL url = this.getClass().getResource("/testtrack0.gpx");
        Assert.assertNotNull("reference result not found: ", url);
        File file = new File(url.getFile());
        File workingDir = file.getParentFile();

        // resources for test update from generation 0 to generation 2 using diff strategy
        originalTilesDir = new File("/home/radim/Asamm-workspaces/brouterClone/brouter/misc/generator/3-8");
        // originalTilesDir = new File(workingDir, "/../../../misc/generator/3-8");
        diffsDir = new File("/home/radim/Asamm-workspaces/brouterClone/brouter/misc/generator/26-10/diff");
        // diffsDir = new File(workingDir, "/../../../misc/generator/26-10/diff");
        freshGeneratedTilesDir = new File("/home/radim/Asamm-workspaces/brouterClone/brouter/misc/generator/26-10");
        // freshGeneratedTilesDir = new File(workingDir, "/../../../misc/generator/26-10");
        updatedTilesDir = new File("/home/radim/Asamm-workspaces/brouterClone/brouter/misc/generator/tmp-gen");
        // updatedTilesDir = new File(workingDir, "/../../../misc/generator/tmp-gen");
    }

    @Test
    public void recoverFromDeltaTest() throws Exception {

        initResources();

        long start = System.currentTimeMillis();
        ProgressListener listener = new MyProgressListener();
        File[] originalTiles = originalTilesDir.listFiles();
        List<String> errors = new ArrayList<String>();

        assert originalTiles != null;
        // param
        for (File originalTile : originalTiles) {
            if(originalTile.isFile()) {
                String originalTileMd5 = Rd5DiffManager.getMD5(originalTile);
                String freshGeneratedTileMd5 = Rd5DiffManager.getMD5(new File(freshGeneratedTilesDir, originalTile.getName()));
                String diffName = originalTileMd5 + ".df5";
                // param
                File diff = new File(diffsDir.getAbsolutePath() + "/" + getBaseNameTile(originalTile.getName()) + "/" + diffName);
                if (diff.exists()) {
                    System.out.println("Applying " + diff.getName() + " to " + originalTile.getName());
                    // param
                    File patchedTile = new File(updatedTilesDir, originalTile.getName());

                    Rd5DiffTool.recoverFromDelta(originalTile, diff, patchedTile, listener);
                    System.out.println("Patching done");
                    String patchedTileMd5 = Rd5DiffManager.getMD5(patchedTile);
                    if (patchedTileMd5.equals(freshGeneratedTileMd5)) {
                        System.out.println("ok");
                    } else {
                        System.err.println("Expected: " + freshGeneratedTileMd5 + ", got: " + patchedTileMd5);
                        System.err.println("Lengths: Expected from fresh: " + new File(freshGeneratedTilesDir, originalTile.getName()).length()
                                + ", got: " + patchedTile.length());
                        errors.add(patchedTile.getName());
                    }
                } else {
                    System.out.println("For " + originalTile.getName() + " there is no diff");
                }
            }
        }
        System.out.println("Errors:");
        for (String e : errors) {
            System.out.println(e);
        }
        System.out.println("The process took: " + ((System.currentTimeMillis() - start) / 1000 / 60) + " minutes");
        assert (errors.isEmpty());
    }

    private String getBaseNameTile(String tile) {
        return tile.substring(0, tile.lastIndexOf("."));
    }

    @Test
    public void listSizesDifference() {

        initResources();

        File[] originalTiles = originalTilesDir.listFiles();

        assert originalTiles != null;
        for (File originalTile : originalTiles) {
            String name = originalTile.getName();
            File freshGeneratedTile = new File(freshGeneratedTilesDir, name);
            long originalSize = originalTile.length();
            long freshGeneratedSize = freshGeneratedTile.length();
            System.out.println((double) freshGeneratedSize / (double) originalSize);
        }
    }

    @Test
    public void generateDiffs2FilesTest() throws Exception {

        initResources();

        ProgressListener listener = new MyProgressListener();
        List<String> errors = new ArrayList<String>();

        File[] originalTiles = originalTilesDir.listFiles();

        assert originalTiles != null;
        for (File originalTile : originalTiles) {

            if (originalTile.length() > 1024 * 1024) {

                String name = originalTile.getName();
                File freshGeneratedTile = new File(freshGeneratedTilesDir, name);
                File tmpDiffFile = new File(updatedTilesDir, "tmp.df5");
                Rd5DiffTool.diff2files(originalTile, freshGeneratedTile, tmpDiffFile);
                File tmpMergedTile = new File(updatedTilesDir, "tmp.rd5");
                Rd5DiffTool.recoverFromDelta(originalTile, tmpDiffFile, tmpMergedTile, listener);
                if (tmpMergedTile.length() != freshGeneratedTile.length()) errors.add(name);
                String expectedMd5 = Rd5DiffManager.getMD5(freshGeneratedTile);
                String actualMd5 = Rd5DiffManager.getMD5(tmpMergedTile);

                if (actualMd5.equals(expectedMd5)) {
                    System.out.println("ok");
                } else {
                    System.err.println("Expected: " + expectedMd5 + ", got: " + actualMd5);
                    errors.add(originalTile.getName());
                }
            }
        }
        System.out.println("Errors:");
        for (String e : errors) {
            System.out.println(e);
        }
        assert(errors.isEmpty());
    }
}
