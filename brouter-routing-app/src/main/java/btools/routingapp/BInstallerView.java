package btools.routingapp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

import btools.router.RoutingHelper;

public class BInstallerView extends View {
  public static final int MASK_SELECTED_RD5 = 1;
  public static final int MASK_DELETED_RD5 = 2;
  public static final int MASK_INSTALLED_RD5 = 4;
  public static final int MASK_CURRENT_RD5 = 8;
  private static final float SCALE_GRID_VISIBLE = 3;
  private final Bitmap bmp;
  private final float[] testVector = new float[2];
  private final int[] tileStatus;
  private final File segmentDir;
  private final Matrix mat;
  private final Matrix matText;
  private final GestureDetector mGestureDetector;
  private final ScaleGestureDetector mScaleGestureDetector;
  Paint pnt_1 = new Paint();
  Paint pnt_2 = new Paint();
  Paint paint = new Paint();
  int btnh = 40;
  int btnw = 160;
  private int imgwOrig;
  private int imghOrig;
  private float scaleOrig;
  private int imgw;
  private int imgh;
  private float viewscale;
  private boolean tilesVisible = false;
  private long availableSize;
  private long totalSize = 0;
  private long rd5Tiles = 0;
  private long delTiles = 0;
  private OnClickListener mOnClickListener;

  public BInstallerView(Context context, AttributeSet attrs) {
    super(context, attrs);

    File baseDir = ConfigHelper.getBaseDir(getContext());
    segmentDir = new File(baseDir, "brouter/segments4");

    try {
      AssetManager assetManager = getContext().getAssets();
      InputStream istr = assetManager.open("world.png");
      bmp = BitmapFactory.decodeStream(istr);
      istr.close();
    } catch (IOException io) {
      throw new RuntimeException("cannot read world.png from assets");
    }

    tileStatus = new int[72 * 36];
    matText = new Matrix();
    mat = new Matrix();
    mGestureDetector = new GestureDetector(context, new GestureListener());
    mScaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureListener());
  }

  private void setRatio(float ratio, float focusX, float focusY) {
    mat.postScale(ratio, ratio, focusX, focusY);
    fitBounds();
    tilesVisible = currentScale() >= SCALE_GRID_VISIBLE;

    invalidate();
  }

  private void setScale(float scale, float focusX, float focusY) {
    float ratio = scale / currentScale();
    setRatio(ratio, focusX, focusY);
  }

  public void setAvailableSize(long availableSize) {
    this.availableSize = availableSize;
  }

  public void setTileStatus(int tileIndex, int tileMask) {
    tileStatus[tileIndex] |= tileMask;
  }

  public void clearAllTilesStatus(int tileMask) {
    for (int ix = 0; ix < 72; ix++) {
      for (int iy = 0; iy < 36; iy++) {
        int tileIndex = gridPos2Tileindex(ix, iy);
        tileStatus[tileIndex] ^= tileStatus[tileIndex] & tileMask;
      }
    }
    invalidate();
  }

  public ArrayList<Integer> getSelectedTiles(int tileMask) {
    ArrayList<Integer> selectedTiles = new ArrayList<>();
    for (int ix = 0; ix < 72; ix++) {
      for (int iy = 0; iy < 36; iy++) {
        int tileIndex = gridPos2Tileindex(ix, iy);
        if ((tileStatus[tileIndex] & tileMask) != 0 && BInstallerSizes.getRd5Size(tileIndex) > 0) {
          selectedTiles.add(tileIndex);
        }
      }
    }

    return selectedTiles;
  }

  @Override
  public void setOnClickListener(OnClickListener listener) {
    mOnClickListener = listener;
  }

  private int gridPos2Tileindex(int ix, int iy) {
    return (35 - iy) * 72 + (ix >= 70 ? ix - 70 : ix + 2);
  }

  private int tileIndex(float x, float y) {
    int ix = (int) (72.f * x / bmp.getWidth());
    int iy = (int) (36.f * y / bmp.getHeight());
    if (ix >= 0 && ix < 72 && iy >= 0 && iy < 36) return gridPos2Tileindex(ix, iy);
    return -1;
  }

  // get back the current image scale
  private float currentScale() {
    testVector[1] = 1.f;
    mat.mapVectors(testVector);
    return testVector[1] / viewscale;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);

    imgwOrig = getWidth();
    imghOrig = getHeight();
    int im = Math.max(imgwOrig, imghOrig);

    scaleOrig = im / 480.f;

    matText.preScale(scaleOrig, scaleOrig);

    imgw = (int) (imgwOrig / scaleOrig);
    imgh = (int) (imghOrig / scaleOrig);

    float scaleX = imgwOrig / ((float) bmp.getWidth());
    float scaleY = imghOrig / ((float) bmp.getHeight());

    viewscale = Math.min(scaleX, scaleY);

    mat.postScale(viewscale, viewscale);
    tilesVisible = false;
  }

  @Override
  protected void onDraw(Canvas canvas) {
    canvas.setMatrix(mat);
    canvas.drawBitmap(bmp, 0, 0, null);
    // draw 5*5 lattice starting at scale=3

    int iw = bmp.getWidth();
    int ih = bmp.getHeight();
    float fw = iw / 72.f;
    float fh = ih / 36.f;

    if (tilesVisible) {
      pnt_1.setColor(Color.GREEN);
      pnt_1.setStyle(Paint.Style.STROKE);
      for (int ix = 0; ix < 72; ix++) {
        for (int iy = 0; iy < 36; iy++) {
          int tidx = gridPos2Tileindex(ix, iy);
          int tilesize = BInstallerSizes.getRd5Size(tidx);
          if (tilesize > 0) {
            canvas.drawRect(fw * ix, fh * (iy + 1), fw * (ix + 1), fh * iy, pnt_1);
          }
        }
      }
    }

    rd5Tiles = 0;
    delTiles = 0;
    totalSize = 0;
    int mask2 = MASK_SELECTED_RD5 | MASK_DELETED_RD5 | MASK_INSTALLED_RD5;
    int mask3 = mask2 | MASK_CURRENT_RD5;

    pnt_2.setStyle(Paint.Style.STROKE);
    pnt_2.setColor(Color.GRAY);
    pnt_2.setStrokeWidth(1);
    drawSelectedTiles(canvas, pnt_2, fw, fh, MASK_INSTALLED_RD5, mask3, false, false, tilesVisible);
    pnt_2.setColor(Color.BLUE);
    pnt_2.setStrokeWidth(1);
    drawSelectedTiles(canvas, pnt_2, fw, fh, MASK_INSTALLED_RD5 | MASK_CURRENT_RD5, mask3, false, false, tilesVisible);
    pnt_2.setColor(Color.GREEN);
    pnt_2.setStrokeWidth(2);
    drawSelectedTiles(canvas, pnt_2, fw, fh, MASK_SELECTED_RD5, mask2, true, false, tilesVisible);
    pnt_2.setColor(Color.YELLOW);
    pnt_2.setStrokeWidth(2);
    drawSelectedTiles(canvas, pnt_2, fw, fh, MASK_SELECTED_RD5 | MASK_INSTALLED_RD5, mask2, true, false, tilesVisible);
    pnt_2.setColor(Color.RED);
    pnt_2.setStrokeWidth(2);
    drawSelectedTiles(canvas, pnt_2, fw, fh, MASK_DELETED_RD5 | MASK_INSTALLED_RD5, mask2, false, true, tilesVisible);

    canvas.setMatrix(matText);

    paint.setColor(Color.RED);

    long mb = 1024 * 1024;

    if (!this.tilesVisible) {
      paint.setTextSize(35);
      canvas.drawText("Touch region to zoom in!", 30, (imgh / 3) * 2, paint);
    }
    paint.setTextSize(20);


    String totmb = ((totalSize + mb - 1) / mb) + " MB";
    String freemb = availableSize >= 0 ? ((availableSize + mb - 1) / mb) + " MB" : "?";
    canvas.drawText("Selected segments=" + rd5Tiles, 10, 25, paint);
    canvas.drawText("Size=" + totmb + " Free=" + freemb, 10, 45, paint);


    String btnText = null;
    if (delTiles > 0) btnText = "Delete " + delTiles + " tiles";
    else if (rd5Tiles > 0) btnText = "Start Download";
    else if (this.tilesVisible &&
      rd5Tiles == 0 &&
      RoutingHelper.hasDirectoryAnyDatafiles(segmentDir)) btnText = "Update all";

    if (btnText != null) {
      paint.setStyle(Paint.Style.STROKE);
      canvas.drawRect(imgw - btnw, imgh - btnh, imgw - 2, imgh - 2, paint);
      paint.setStyle(Paint.Style.FILL);
      canvas.drawText(btnText, imgw - btnw + 5, imgh - 10, paint);
    }
  }

  private void drawSelectedTiles(Canvas canvas, Paint pnt, float fw, float fh, int status, int mask, boolean doCount, boolean cntDel, boolean doDraw) {
    for (int ix = 0; ix < 72; ix++)
      for (int iy = 0; iy < 36; iy++) {
        int tidx = gridPos2Tileindex(ix, iy);
        if ((tileStatus[tidx] & mask) == status) {
          int tilesize = BInstallerSizes.getRd5Size(tidx);
          if (tilesize > 0) {
            if (doCount) {
              rd5Tiles++;
              totalSize += BInstallerSizes.getRd5Size(tidx);
            }
            if (cntDel) {
              delTiles++;
              totalSize += BInstallerSizes.getRd5Size(tidx);
            }
            if (!doDraw)
              continue;
            // draw cross
            canvas.drawLine(fw * ix, fh * iy, fw * (ix + 1), fh * (iy + 1), pnt);
            canvas.drawLine(fw * ix, fh * (iy + 1), fw * (ix + 1), fh * iy, pnt);

            // draw frame
            canvas.drawRect(fw * ix, fh * (iy + 1), fw * (ix + 1), fh * iy, pnt);
          }
        }
      }
  }

  private void fitBounds() {
    float[] srcPoints = new float[]{
      0, 0,
      bmp.getWidth(), bmp.getHeight()
    };
    float[] dstPoints = new float[srcPoints.length];
    float transX = 0;
    float transY = 0;
    mat.mapPoints(dstPoints, srcPoints);
    if (dstPoints[0] > 0) {
      transX = -dstPoints[0];
    } else if (dstPoints[2] < getWidth()) {
      transX = getWidth() - dstPoints[2];
    }
    if (dstPoints[1] > 0) {
      transY = -dstPoints[1];
    } else if (dstPoints[3] < getHeight()) {
      transY = getHeight() - dstPoints[3];
    }
    if (transX != 0 || transY != 0) {
      mat.postTranslate(transX, transY);
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    boolean retVal = mScaleGestureDetector.onTouchEvent(event);
    retVal = mGestureDetector.onTouchEvent(event) || retVal;
    return retVal || super.onTouchEvent(event);
  }

  class GestureListener extends GestureDetector.SimpleOnGestureListener {

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
      // Download Button
      if ((delTiles > 0 || rd5Tiles >= 0) && e.getX() > imgwOrig - btnw * scaleOrig && e.getY() > imghOrig - btnh * scaleOrig) {
        if (mOnClickListener != null) {
          mOnClickListener.onClick(null);
        }
        invalidate();
        return true;
      }

      if (tilesVisible) {
        Matrix imat = new Matrix();
        if (mat.invert(imat)) {
          float[] touchpoint = {e.getX(), e.getY()};
          imat.mapPoints(touchpoint);

          int tidx = tileIndex(touchpoint[0], touchpoint[1]);
          if (tidx != -1) {
            if ((tileStatus[tidx] & MASK_SELECTED_RD5) != 0) {
              tileStatus[tidx] ^= MASK_SELECTED_RD5;
              if ((tileStatus[tidx] & MASK_INSTALLED_RD5) != 0) {
                tileStatus[tidx] |= MASK_DELETED_RD5;
              }
            } else if ((tileStatus[tidx] & MASK_DELETED_RD5) != 0) {
              tileStatus[tidx] ^= MASK_DELETED_RD5;
            } else {
              tileStatus[tidx] ^= MASK_SELECTED_RD5;
            }
          }
        }
        invalidate();
      }
      return true;
    }

    @Override
    public boolean onDoubleTap(MotionEvent e) {
      if (!tilesVisible) {
        setScale(5, e.getX(), e.getY());
      } else {
        setScale(1, e.getX(), e.getY());
      }
      return true;
    }

    @Override
    public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
      mat.postTranslate(-distanceX, -distanceY);
      fitBounds();
      invalidate();
      return true;
    }
  }

  class ScaleGestureListener implements ScaleGestureDetector.OnScaleGestureListener {

    @Override
    public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
      return true;
    }

    @Override
    public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
      float focusX = scaleGestureDetector.getFocusX();
      float focusY = scaleGestureDetector.getFocusY();
      float ratio = scaleGestureDetector.getScaleFactor();

      setRatio(ratio, focusX, focusY);

      return true;
    }

    @Override
    public void onScaleEnd(ScaleGestureDetector scaleGestureDetector) {
    }
  }

}
