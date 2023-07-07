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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

public class BInstallerView extends View {
  public static final int MASK_SELECTED_RD5 = 1;
  public static final int MASK_DELETED_RD5 = 2;
  public static final int MASK_INSTALLED_RD5 = 4;
  public static final int MASK_CURRENT_RD5 = 8;
  private static final float SCALE_GRID_VISIBLE = 3;
  private final Bitmap bmp;
  private final float[] testVector = new float[2];
  private final int[] tileStatus;
  private final Matrix mat;
  private final GestureDetector mGestureDetector;
  private final ScaleGestureDetector mScaleGestureDetector;
  Paint paintGrid = new Paint();
  Paint paintTiles = new Paint();
  private float viewscale;
  private boolean tilesVisible = false;
  private OnSelectListener mOnSelectListener;

  public BInstallerView(Context context, AttributeSet attrs) {
    super(context, attrs);

    try {
      AssetManager assetManager = getContext().getAssets();
      InputStream istr = assetManager.open("world.png");
      bmp = BitmapFactory.decodeStream(istr);
      istr.close();
    } catch (IOException io) {
      throw new RuntimeException("cannot read world.png from assets");
    }

    tileStatus = new int[72 * 36];
    mat = new Matrix();
    mGestureDetector = new GestureDetector(context, new GestureListener());
    mScaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureListener());
  }

  public void setOnSelectListener(OnSelectListener listener) {
    mOnSelectListener = listener;
  }

  private void setRatio(float ratio, float focusX, float focusY) {
    if (currentScale() * ratio >= 1) {
      mat.postScale(ratio, ratio, focusX, focusY);
      fitBounds();
      boolean landscape = getWidth() > getHeight();
      tilesVisible = currentScale() >= (landscape ? SCALE_GRID_VISIBLE: SCALE_GRID_VISIBLE-1);

      invalidate();
    }
  }

  private void setScale(float scale, float focusX, float focusY) {
    float ratio = scale / currentScale();
    setRatio(ratio, focusX, focusY);
  }

  public void setTileStatus(int tileIndex, int tileMask) {
    if (mOnSelectListener == null) {
      return;
    }
    tileStatus[tileIndex] |= tileMask;
    mOnSelectListener.onSelect();
    invalidate();
  }

  public void toggleTileStatus(int tileIndex, int tileMask) {
    if (mOnSelectListener == null) {
      return;
    }
    tileStatus[tileIndex] ^= tileMask;
    mOnSelectListener.onSelect();
    invalidate();
  }

  public void clearAllTilesStatus(int tileMask) {
    for (int ix = 0; ix < 72; ix++) {
      for (int iy = 0; iy < 36; iy++) {
        int tileIndex = gridPos2Tileindex(ix, iy);
        tileStatus[tileIndex] ^= tileStatus[tileIndex] & tileMask;
      }
    }
    if (mOnSelectListener != null) {
      mOnSelectListener.onSelect();
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
    testVector[0] = 1.f;
    mat.mapVectors(testVector);
    return testVector[0] / viewscale;
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);

    int imgwOrig = getWidth();
    int imghOrig = getHeight();

    float scaleX = imgwOrig / ((float) bmp.getWidth());
    float scaleY = imghOrig / ((float) bmp.getHeight());

    viewscale = Math.max(scaleX, scaleY);

    mat.preScale(viewscale, viewscale, bmp.getWidth() /2f, 0);
    setRatio(1f, bmp.getWidth() /2f, bmp.getHeight() /2f);

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
      paintGrid.setColor(Color.GREEN);
      paintGrid.setStyle(Paint.Style.STROKE);
      for (int ix = 0; ix < 72; ix++) {
        for (int iy = 0; iy < 36; iy++) {
          int tidx = gridPos2Tileindex(ix, iy);
          int tilesize = BInstallerSizes.getRd5Size(tidx);
          if (tilesize > 0) {
            canvas.drawRect(fw * ix, fh * (iy + 1), fw * (ix + 1), fh * iy, paintGrid);
          }
        }
      }

      int mask2 = MASK_SELECTED_RD5 | MASK_DELETED_RD5 | MASK_INSTALLED_RD5;
      int mask3 = mask2 | MASK_CURRENT_RD5;

      paintTiles.setStyle(Paint.Style.STROKE);
      paintTiles.setColor(Color.GRAY);
      paintTiles.setStrokeWidth(1);
      drawSelectedTiles(canvas, paintTiles, fw, fh, MASK_INSTALLED_RD5, mask3);
      paintTiles.setColor(Color.BLUE);
      paintTiles.setStrokeWidth(1);
      drawSelectedTiles(canvas, paintTiles, fw, fh, MASK_INSTALLED_RD5 | MASK_CURRENT_RD5, mask3);
      paintTiles.setColor(Color.GREEN);
      paintTiles.setStrokeWidth(2);
      drawSelectedTiles(canvas, paintTiles, fw, fh, MASK_SELECTED_RD5, mask2);
      paintTiles.setColor(Color.YELLOW);
      paintTiles.setStrokeWidth(2);
      drawSelectedTiles(canvas, paintTiles, fw, fh, MASK_SELECTED_RD5 | MASK_INSTALLED_RD5, mask2);
      paintTiles.setColor(Color.RED);
      paintTiles.setStrokeWidth(2);
      drawSelectedTiles(canvas, paintTiles, fw, fh, MASK_DELETED_RD5 | MASK_INSTALLED_RD5, mask2);
    }
  }

  private void drawSelectedTiles(Canvas canvas, Paint pnt, float fw, float fh, int status, int mask) {
    for (int ix = 0; ix < 72; ix++)
      for (int iy = 0; iy < 36; iy++) {
        int tidx = gridPos2Tileindex(ix, iy);
        if ((tileStatus[tidx] & mask) == status) {
          int tilesize = BInstallerSizes.getRd5Size(tidx);
          if (tilesize > 0) {
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

  interface OnSelectListener {
    void onSelect();
  }

  class GestureListener extends GestureDetector.SimpleOnGestureListener {

    @Override
    public boolean onSingleTapConfirmed(MotionEvent e) {
      if (tilesVisible) {
        Matrix imat = new Matrix();
        if (mat.invert(imat)) {
          float[] touchPoint = {e.getX(), e.getY()};
          imat.mapPoints(touchPoint);

          int tidx = tileIndex(touchPoint[0], touchPoint[1]);
          if (tidx != -1) {
            if ((tileStatus[tidx] & MASK_SELECTED_RD5) != 0) {
              toggleTileStatus(tidx, MASK_SELECTED_RD5);
              if ((tileStatus[tidx] & MASK_INSTALLED_RD5) != 0) {
                setTileStatus(tidx, MASK_DELETED_RD5);
              }
            } else if ((tileStatus[tidx] & MASK_DELETED_RD5) != 0) {
              toggleTileStatus(tidx, MASK_DELETED_RD5);
            } else {
              toggleTileStatus(tidx, MASK_SELECTED_RD5);
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
        setScale(4, e.getX(), e.getY());
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
