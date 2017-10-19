/**
 * Copyright (c) 2015-present, Facebook, Inc.
 * All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the
 * LICENSE file in the root directory of this source tree. An additional grant
 * of patent rights can be found in the PATENTS file in the same directory.
 */

package com.facebook.react.views.view;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathEffect;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.Build;
import com.facebook.react.common.annotations.VisibleForTesting;
import com.facebook.react.uimanager.FloatUtil;
import com.facebook.react.uimanager.Spacing;
import com.facebook.yoga.YogaConstants;
import java.util.Arrays;
import java.util.Locale;
import javax.annotation.Nullable;

/**
 * A subclass of {@link Drawable} used for background of {@link ReactViewGroup}. It supports drawing
 * background color and borders (including rounded borders) by providing a react friendly API
 * (setter for each of those properties).
 *
 * <p>The implementation tries to allocate as few objects as possible depending on which properties
 * are set. E.g. for views with rounded background/borders we allocate {@code
 * mInnerClipPathForBorderRadius} and {@code mInnerClipTempRectForBorderRadius}. In case when view
 * have a rectangular borders we allocate {@code mBorderWidthResult} and similar. When only
 * background color is set we won't allocate any extra/unnecessary objects.
 */
public class ReactViewBackgroundDrawable extends Drawable {

  private static final int DEFAULT_BORDER_COLOR = Color.BLACK;
  private static final int DEFAULT_BORDER_RGB = 0x00FFFFFF & DEFAULT_BORDER_COLOR;
  private static final int DEFAULT_BORDER_ALPHA = (0xFF000000 & DEFAULT_BORDER_COLOR) >>> 24;
  // ~0 == 0xFFFFFFFF, all bits set to 1.
  private static final int ALL_BITS_SET = ~0;
  // 0 == 0x00000000, all bits set to 0.
  private static final int ALL_BITS_UNSET = 0;


  private static enum BorderStyle {
    SOLID,
    DASHED,
    DOTTED;

    public @Nullable PathEffect getPathEffect(float borderWidth) {
      switch (this) {
        case SOLID:
          return null;

        case DASHED:
          return new DashPathEffect(
              new float[] {borderWidth*3, borderWidth*3, borderWidth*3, borderWidth*3}, 0);

        case DOTTED:
          return new DashPathEffect(
              new float[] {borderWidth, borderWidth, borderWidth, borderWidth}, 0);

        default:
          return null;
      }
    }
  };

  /* Value at Spacing.ALL index used for rounded borders, whole array used by rectangular borders */
  private @Nullable Spacing mBorderWidth;
  private @Nullable Spacing mBorderRGB;
  private @Nullable Spacing mBorderAlpha;
  private @Nullable BorderStyle mBorderStyle;

  /* Used for rounded border and rounded background */
  private @Nullable PathEffect mPathEffectForBorderStyle;
  private @Nullable Path mInnerClipPathForBorderRadius;
  private @Nullable Path mOuterClipPathForBorderRadius;
  private @Nullable Path mPathForBorderRadiusOutline;
  private @Nullable Path mPathForBorder;
  private @Nullable RectF mInnerClipTempRectForBorderRadius;
  private @Nullable RectF mOuterClipTempRectForBorderRadius;
  private @Nullable RectF mTempRectForBorderRadiusOutline;
  private @Nullable PointF mInnerTopLeftCorner;
  private @Nullable PointF mInnerTopRightCorner;
  private @Nullable PointF mInnerBottomRightCorner;
  private @Nullable PointF mInnerBottomLeftCorner;
  private boolean mNeedUpdatePathForBorderRadius = false;
  private float mBorderRadius = YogaConstants.UNDEFINED;

  /* Used by all types of background and for drawing borders */
  private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private int mColor = Color.TRANSPARENT;
  private int mAlpha = 255;

  private @Nullable float[] mBorderCornerRadii;

  public enum BorderRadiusLocation {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_RIGHT,
    BOTTOM_LEFT
  }

  @Override
  public void draw(Canvas canvas) {
    updatePathEffect();
    boolean roundedBorders = mBorderCornerRadii != null ||
        (!YogaConstants.isUndefined(mBorderRadius) && mBorderRadius > 0);

    if (!roundedBorders) {
      drawRectangularBackgroundWithBorders(canvas);
    } else {
      drawRoundedBackgroundWithBorders(canvas);
    }
  }

  @Override
  protected void onBoundsChange(Rect bounds) {
    super.onBoundsChange(bounds);
    mNeedUpdatePathForBorderRadius = true;
  }

  @Override
  public void setAlpha(int alpha) {
    if (alpha != mAlpha) {
      mAlpha = alpha;
      invalidateSelf();
    }
  }

  @Override
  public int getAlpha() {
    return mAlpha;
  }

  @Override
  public void setColorFilter(ColorFilter cf) {
    // do nothing
  }

  @Override
  public int getOpacity() {
    return ColorUtil.getOpacityFromColor(ColorUtil.multiplyColorAlpha(mColor, mAlpha));
  }

  /* Android's elevation implementation requires this to be implemented to know where to draw the shadow. */
  @Override
  public void getOutline(Outline outline) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      super.getOutline(outline);
      return;
    }
    if ((!YogaConstants.isUndefined(mBorderRadius) && mBorderRadius > 0) || mBorderCornerRadii != null) {
      updatePath();

      outline.setConvexPath(mPathForBorderRadiusOutline);
    } else {
      outline.setRect(getBounds());
    }
  }

  public void setBorderWidth(int position, float width) {
    if (mBorderWidth == null) {
      mBorderWidth = new Spacing();
    }
    if (!FloatUtil.floatsEqual(mBorderWidth.getRaw(position), width)) {
      mBorderWidth.set(position, width);
      switch (position) {
        case Spacing.ALL:
        case Spacing.LEFT:
        case Spacing.BOTTOM:
        case Spacing.RIGHT:
        case Spacing.TOP:
          mNeedUpdatePathForBorderRadius = true;
      }
      invalidateSelf();
    }
  }

  public void setBorderColor(int position, float rgb, float alpha) {
    this.setBorderRGB(position, rgb);
    this.setBorderAlpha(position, alpha);
  }

  private void setBorderRGB(int position, float rgb) {
    // set RGB component
    if (mBorderRGB == null) {
      mBorderRGB = new Spacing(DEFAULT_BORDER_RGB);
    }
    if (!FloatUtil.floatsEqual(mBorderRGB.getRaw(position), rgb)) {
      mBorderRGB.set(position, rgb);
      invalidateSelf();
    }
  }

  private void setBorderAlpha(int position, float alpha) {
    // set Alpha component
    if (mBorderAlpha == null) {
      mBorderAlpha = new Spacing(DEFAULT_BORDER_ALPHA);
    }
    if (!FloatUtil.floatsEqual(mBorderAlpha.getRaw(position), alpha)) {
      mBorderAlpha.set(position, alpha);
      invalidateSelf();
    }
  }

  public void setBorderStyle(@Nullable String style) {
    BorderStyle borderStyle = style == null
        ? null
        : BorderStyle.valueOf(style.toUpperCase(Locale.US));
    if (mBorderStyle != borderStyle) {
      mBorderStyle = borderStyle;
      mNeedUpdatePathForBorderRadius = true;
      invalidateSelf();
    }
  }

  public void setRadius(float radius) {
    if (!FloatUtil.floatsEqual(mBorderRadius,radius)) {
      mBorderRadius = radius;
      mNeedUpdatePathForBorderRadius = true;
      invalidateSelf();
    }
  }

  public void setRadius(float radius, int position) {
    if (mBorderCornerRadii == null) {
      mBorderCornerRadii = new float[4];
      Arrays.fill(mBorderCornerRadii, YogaConstants.UNDEFINED);
    }

    if (!FloatUtil.floatsEqual(mBorderCornerRadii[position], radius)) {
      mBorderCornerRadii[position] = radius;
      mNeedUpdatePathForBorderRadius = true;
      invalidateSelf();
    }
  }

  public float getFullBorderRadius() {
    return YogaConstants.isUndefined(mBorderRadius) ? 0 : mBorderRadius;
  }

  public float getBorderRadiusOrDefaultTo(
      final float defaultValue, final BorderRadiusLocation location) {
    if (mBorderCornerRadii == null) {
      return defaultValue;
    }

    final float radius = mBorderCornerRadii[location.ordinal()];

    if (YogaConstants.isUndefined(radius)) {
      return defaultValue;
    }

    return radius;
  }

  public void setColor(int color) {
    mColor = color;
    invalidateSelf();
  }

  @VisibleForTesting
  public int getColor() {
    return mColor;
  }

  private void drawRoundedBackgroundWithBorders(Canvas canvas) {
    updatePath();
    canvas.save();

    int useColor = ColorUtil.multiplyColorAlpha(mColor, mAlpha);
    if (Color.alpha(useColor) != 0) { // color is not transparent
      mPaint.setColor(useColor);
      mPaint.setStyle(Paint.Style.FILL);
      canvas.drawPath(mInnerClipPathForBorderRadius, mPaint);
    }

    final float borderWidth = getBorderWidthOrDefaultTo(0, Spacing.ALL);
    final float borderTopWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.TOP);
    final float borderBottomWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.BOTTOM);
    final float borderLeftWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.LEFT);
    final float borderRightWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.RIGHT);

    if (borderTopWidth > 0
        || borderBottomWidth > 0
        || borderLeftWidth > 0
        || borderRightWidth > 0) {
      mPaint.setStyle(Paint.Style.FILL);

      // Draw border
      canvas.clipPath(mOuterClipPathForBorderRadius, Region.Op.INTERSECT);
      canvas.clipPath(mInnerClipPathForBorderRadius, Region.Op.DIFFERENCE);

      final int colorLeft = getBorderColor(Spacing.LEFT);
      final int colorTop = getBorderColor(Spacing.TOP);
      final int colorRight = getBorderColor(Spacing.RIGHT);
      final int colorBottom = getBorderColor(Spacing.BOTTOM);

      final float left = mOuterClipTempRectForBorderRadius.left;
      final float right = mOuterClipTempRectForBorderRadius.right;
      final float top = mOuterClipTempRectForBorderRadius.top;
      final float bottom = mOuterClipTempRectForBorderRadius.bottom;

      if (borderLeftWidth > 0) {
        final float x1 = left;
        final float y1 = top;
        final float x2 = mInnerTopLeftCorner.x;
        final float y2 = mInnerTopLeftCorner.y;
        final float x3 = mInnerBottomLeftCorner.x;
        final float y3 = mInnerBottomLeftCorner.y;
        final float x4 = left;
        final float y4 = bottom;

        drawQuadrilateral(canvas, colorLeft, x1, y1, x2, y2, x3, y3, x4, y4);
      }

      if (borderTopWidth > 0) {
        final float x1 = left;
        final float y1 = top;
        final float x2 = mInnerTopLeftCorner.x;
        final float y2 = mInnerTopLeftCorner.y;
        final float x3 = mInnerTopRightCorner.x;
        final float y3 = mInnerTopRightCorner.y;
        final float x4 = right;
        final float y4 = top;

        drawQuadrilateral(canvas, colorTop, x1, y1, x2, y2, x3, y3, x4, y4);
      }

      if (borderRightWidth > 0) {
        final float x1 = right;
        final float y1 = top;
        final float x2 = mInnerTopRightCorner.x;
        final float y2 = mInnerTopRightCorner.y;
        final float x3 = mInnerBottomRightCorner.x;
        final float y3 = mInnerBottomRightCorner.y;
        final float x4 = right;
        final float y4 = bottom;

        drawQuadrilateral(canvas, colorRight, x1, y1, x2, y2, x3, y3, x4, y4);
      }

      if (borderBottomWidth > 0) {
        final float x1 = left;
        final float y1 = bottom;
        final float x2 = mInnerBottomLeftCorner.x;
        final float y2 = mInnerBottomLeftCorner.y;
        final float x3 = mInnerBottomRightCorner.x;
        final float y3 = mInnerBottomRightCorner.y;
        final float x4 = right;
        final float y4 = bottom;

        drawQuadrilateral(canvas, colorBottom, x1, y1, x2, y2, x3, y3, x4, y4);
      }
    }

    canvas.restore();
  }

  private void updatePath() {
    if (!mNeedUpdatePathForBorderRadius) {
      return;
    }

    mNeedUpdatePathForBorderRadius = false;

    if (mInnerClipPathForBorderRadius == null) {
      mInnerClipPathForBorderRadius = new Path();
    }

    if (mOuterClipPathForBorderRadius == null) {
      mOuterClipPathForBorderRadius = new Path();
    }

    if (mPathForBorderRadiusOutline == null) {
      mPathForBorderRadiusOutline = new Path();
    }

    if (mInnerClipTempRectForBorderRadius == null) {
      mInnerClipTempRectForBorderRadius = new RectF();
    }

    if (mOuterClipTempRectForBorderRadius == null) {
      mOuterClipTempRectForBorderRadius = new RectF();
    }

    if (mTempRectForBorderRadiusOutline == null) {
      mTempRectForBorderRadiusOutline = new RectF();
    }

    mInnerClipPathForBorderRadius.reset();
    mOuterClipPathForBorderRadius.reset();
    mPathForBorderRadiusOutline.reset();

    mInnerClipTempRectForBorderRadius.set(getBounds());
    mOuterClipTempRectForBorderRadius.set(getBounds());
    mTempRectForBorderRadiusOutline.set(getBounds());

    final float borderWidth = getBorderWidthOrDefaultTo(0, Spacing.ALL);
    final float borderTopWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.TOP);
    final float borderBottomWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.BOTTOM);
    final float borderLeftWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.LEFT);
    final float borderRightWidth = getBorderWidthOrDefaultTo(borderWidth, Spacing.RIGHT);

    mInnerClipTempRectForBorderRadius.top += borderTopWidth;
    mInnerClipTempRectForBorderRadius.bottom -= borderBottomWidth;
    mInnerClipTempRectForBorderRadius.left += borderLeftWidth;
    mInnerClipTempRectForBorderRadius.right -= borderRightWidth;

    final float borderRadius = getFullBorderRadius();
    final float topLeftRadius =
        getBorderRadiusOrDefaultTo(borderRadius, BorderRadiusLocation.TOP_LEFT);
    final float topRightRadius =
        getBorderRadiusOrDefaultTo(borderRadius, BorderRadiusLocation.TOP_RIGHT);
    final float bottomLeftRadius =
        getBorderRadiusOrDefaultTo(borderRadius, BorderRadiusLocation.BOTTOM_LEFT);
    final float bottomRightRadius =
        getBorderRadiusOrDefaultTo(borderRadius, BorderRadiusLocation.BOTTOM_RIGHT);


    final float innerTopLeftRadiusX = Math.max(topLeftRadius - borderLeftWidth, 0);
    final float innerTopLeftRadiusY = Math.max(topLeftRadius - borderTopWidth, 0);
    final float innerTopRightRadiusX = Math.max(topRightRadius - borderRightWidth, 0);
    final float innerTopRightRadiusY = Math.max(topRightRadius - borderTopWidth, 0);
    final float innerBottomRightRadiusX = Math.max(bottomRightRadius - borderRightWidth, 0);
    final float innerBottomRightRadiusY = Math.max(bottomRightRadius - borderBottomWidth, 0);
    final float innerBottomLeftRadiusX = Math.max(bottomLeftRadius - borderLeftWidth, 0);
    final float innerBottomLeftRadiusY = Math.max(bottomLeftRadius - borderBottomWidth, 0);

    mInnerClipPathForBorderRadius.addRoundRect(
        mInnerClipTempRectForBorderRadius,
        new float[] {
          innerTopLeftRadiusX,
          innerTopLeftRadiusY,
          innerTopRightRadiusX,
          innerTopRightRadiusY,
          innerBottomRightRadiusX,
          innerBottomRightRadiusY,
          innerBottomLeftRadiusX,
          innerBottomLeftRadiusY,
        },
        Path.Direction.CW);

    mOuterClipPathForBorderRadius.addRoundRect(
        mOuterClipTempRectForBorderRadius,
        new float[] {
          topLeftRadius,
          topLeftRadius,
          topRightRadius,
          topRightRadius,
          bottomRightRadius,
          bottomRightRadius,
          bottomLeftRadius,
          bottomLeftRadius
        },
        Path.Direction.CW);


    float extraRadiusForOutline = 0;

    if (mBorderWidth != null) {
      extraRadiusForOutline = mBorderWidth.get(Spacing.ALL) / 2f;
    }

    mPathForBorderRadiusOutline.addRoundRect(
      mTempRectForBorderRadiusOutline,
      new float[] {
        topLeftRadius + extraRadiusForOutline,
        topLeftRadius + extraRadiusForOutline,
        topRightRadius + extraRadiusForOutline,
        topRightRadius + extraRadiusForOutline,
        bottomRightRadius + extraRadiusForOutline,
        bottomRightRadius + extraRadiusForOutline,
        bottomLeftRadius + extraRadiusForOutline,
        bottomLeftRadius + extraRadiusForOutline
      },
      Path.Direction.CW);

    /**
     * Rounded Multi-Colored Border Algorithm:
     *
     * <p>Let O (for outer) = (top, left, bottom, right) be the rectangle that represents the size
     * and position of a view V. Since the box-sizing of all React Native views is border-box, any
     * border of V will render inside O.
     *
     * <p>Let BorderWidth = (borderTop, borderLeft, borderBottom, borderRight).
     * <p>Let I (for inner) = O - BorderWidth.
     *
     * <p>Then, remembering that O and I are rectangles and that I is inside O, O - I gives us the
     * border of V. Therefore, we can use canvas.clipPath to draw V's border.
     *
     * <p>canvas.clipPath(O, Region.OP.INTERSECT);
     * <p>canvas.clipPath(I, Region.OP.DIFFERENCE);
     * <p>canvas.drawRect(O, paint);
     *
     * <p>This lets us draw non-rounded single-color borders.
     *
     * <p>To extend this algorithm to rounded single-color borders, we:
     * <p>1. Curve the corners of O by the (border radii of V) using Path#addRoundRect.
     * <p>2. Curve the corners of I by (border radii of V - border widths of V) using
     * Path#addRoundRect.
     *
     * <p>Let O' = curve(O, border radii of V).
     * <p>Let I' = curve(I, border radii of V - border widths of V)
     *
     * <p>The rationale behind this decision is the (first sentence of the) following section in the
     * CSS Backgrounds and Borders Module Level 3:
     * https://www.w3.org/TR/css3-background/#the-border-radius.
     *
     * <p>After both O and I have been curved, we can execute the following lines once again to
     * render curved single-color borders:
     *
     * <p>canvas.clipPath(O, Region.OP.INTERSECT);
     * <p>canvas.clipPath(I, Region.OP.DIFFERENCE);
     * <p>canvas.drawRect(O, paint);
     *
     * <p>To extend this algorithm to rendering multi-colored rounded borders, we render each side
     * of the border as its own quadrilateral. Suppose that we were handling the case where all the
     * border radii are 0. Then, the four quadrilaterals would be:
     *
     * <p>Left: (O.left, O.top), (I.left, I.top), (I.left, I.bottom), (O.left, O.bottom)
     * <p>Top: (O.left, O.top), (I.left, I.top), (I.right, I.top), (O.right, O.top)
     * <p>Right: (O.right, O.top), (I.right, I.top), (I.right, I.bottom), (O.right, O.bottom)
     * <p>Bottom: (O.right, O.bottom), (I.right, I.bottom), (I.left, I.bottom), (O.left, O.bottom)
     *
     * <p>Now, lets consider what happens when we render a rounded border (radii != 0). For the sake
     * of simplicity, let's focus on the top edge of the Left border:
     *
     * <p>Let borderTopLeftRadius = 5. Let borderLeftWidth = 1. Let borderTopWidth = 2.
     *
     * <p>We know that O is curved by the ellipse E_O (a = 5, b = 5). We know that I is curved by
     * the ellipse E_I (a = 5 - 1, b = 5 - 2).
     *
     * <p>Since we have clipping, it should be safe to set the top-left point of the Left
     * quadrilateral's top edge to (O.left, O.top).
     *
     * <p>But, what should the top-right point be?
     *
     * <p>The fact that the border is curved shouldn't change the slope (nor the position) of the
     * line connecting the top-left and top-right points of the Left quadrilateral's top edge.
     * Therefore, The top-right point should lie somewhere on the line L = (1 - a) * (O.left, O.top)
     * + a * (I.left, I.top).
     *
     * <p>a != 0, because then the top-left and top-right points would be the same and
     * borderLeftWidth = 1. a != 1, because then the top-right point would not touch an edge of the
     * ellipse E_I. We want the top-right point to touch an edge of the inner ellipse because the
     * border curves with E_I on the top-left corner of V.
     *
     * <p>Therefore, it must be the case that a > 1. Two natural locations of the top-right point
     * exist: 1. The first intersection of L with E_I. 2. The second intersection of L with E_I.
     *
     * <p>We choose the top-right point of the top edge of the Left quadrilateral to be an arbitrary
     * intersection of L with E_I.
     */
    if (mInnerTopLeftCorner == null) {
      mInnerTopLeftCorner = new PointF();
    }

    /** Compute mInnerTopLeftCorner */
    mInnerTopLeftCorner.x = mInnerClipTempRectForBorderRadius.left;
    mInnerTopLeftCorner.y = mInnerClipTempRectForBorderRadius.top;

    getEllipseIntersectionWithLine(
        // Ellipse Bounds
        mInnerClipTempRectForBorderRadius.left,
        mInnerClipTempRectForBorderRadius.top,
        mInnerClipTempRectForBorderRadius.left + 2 * innerTopLeftRadiusX,
        mInnerClipTempRectForBorderRadius.top + 2 * innerTopLeftRadiusY,

        // Line Start
        mOuterClipTempRectForBorderRadius.left,
        mOuterClipTempRectForBorderRadius.top,

        // Line End
        mInnerClipTempRectForBorderRadius.left,
        mInnerClipTempRectForBorderRadius.top,

        // Result
        mInnerTopLeftCorner);

    /** Compute mInnerBottomLeftCorner */
    if (mInnerBottomLeftCorner == null) {
      mInnerBottomLeftCorner = new PointF();
    }

    mInnerBottomLeftCorner.x = mInnerClipTempRectForBorderRadius.left;
    mInnerBottomLeftCorner.y = mInnerClipTempRectForBorderRadius.bottom;

    getEllipseIntersectionWithLine(
        // Ellipse Bounds
        mInnerClipTempRectForBorderRadius.left,
        mInnerClipTempRectForBorderRadius.bottom - 2 * innerBottomLeftRadiusY,
        mInnerClipTempRectForBorderRadius.left + 2 * innerBottomLeftRadiusX,
        mInnerClipTempRectForBorderRadius.bottom,

        // Line Start
        mOuterClipTempRectForBorderRadius.left,
        mOuterClipTempRectForBorderRadius.bottom,

        // Line End
        mInnerClipTempRectForBorderRadius.left,
        mInnerClipTempRectForBorderRadius.bottom,

        // Result
        mInnerBottomLeftCorner);

    /** Compute mInnerTopRightCorner */
    if (mInnerTopRightCorner == null) {
      mInnerTopRightCorner = new PointF();
    }

    mInnerTopRightCorner.x = mInnerClipTempRectForBorderRadius.right;
    mInnerTopRightCorner.y = mInnerClipTempRectForBorderRadius.top;

    getEllipseIntersectionWithLine(
        // Ellipse Bounds
        mInnerClipTempRectForBorderRadius.right - 2 * innerTopRightRadiusX,
        mInnerClipTempRectForBorderRadius.top,
        mInnerClipTempRectForBorderRadius.right,
        mInnerClipTempRectForBorderRadius.top + 2 * innerTopRightRadiusY,

        // Line Start
        mOuterClipTempRectForBorderRadius.right,
        mOuterClipTempRectForBorderRadius.top,

        // Line End
        mInnerClipTempRectForBorderRadius.right,
        mInnerClipTempRectForBorderRadius.top,

        // Result
        mInnerTopRightCorner);

    /** Compute mInnerBottomRightCorner */
    if (mInnerBottomRightCorner == null) {
      mInnerBottomRightCorner = new PointF();
    }

    mInnerBottomRightCorner.x = mInnerClipTempRectForBorderRadius.right;
    mInnerBottomRightCorner.y = mInnerClipTempRectForBorderRadius.bottom;

    getEllipseIntersectionWithLine(
        // Ellipse Bounds
        mInnerClipTempRectForBorderRadius.right - 2 * innerBottomRightRadiusX,
        mInnerClipTempRectForBorderRadius.bottom - 2 * innerBottomRightRadiusY,
        mInnerClipTempRectForBorderRadius.right,
        mInnerClipTempRectForBorderRadius.bottom,

        // Line Start
        mOuterClipTempRectForBorderRadius.right,
        mOuterClipTempRectForBorderRadius.bottom,

        // Line End
        mInnerClipTempRectForBorderRadius.right,
        mInnerClipTempRectForBorderRadius.bottom,

        // Result
        mInnerBottomRightCorner);
  }

  private static void getEllipseIntersectionWithLine(
      double ellipseBoundsLeft,
      double ellipseBoundsTop,
      double ellipseBoundsRight,
      double ellipseBoundsBottom,
      double lineStartX,
      double lineStartY,
      double lineEndX,
      double lineEndY,
      PointF result) {
    final double ellipseCenterX = (ellipseBoundsLeft + ellipseBoundsRight) / 2;
    final double ellipseCenterY = (ellipseBoundsTop + ellipseBoundsBottom) / 2;

    /**
     * Step 1:
     *
     * Translate the line so that the ellipse is at the origin.
     *
     * Why? It makes the math easier by changing the ellipse equation from
     * ((x - ellipseCenterX)/a)^2 + ((y - ellipseCenterY)/b)^2 = 1 to
     * (x/a)^2 + (y/b)^2 = 1.
     */
    lineStartX -= ellipseCenterX;
    lineStartY -= ellipseCenterY;
    lineEndX -= ellipseCenterX;
    lineEndY -= ellipseCenterY;

    /**
     * Step 2:
     *
     * Ellipse equation: (x/a)^2 + (y/b)^2 = 1
     * Line equation: y = mx + c
     */
    final double a = Math.abs(ellipseBoundsRight - ellipseBoundsLeft) / 2;
    final double b = Math.abs(ellipseBoundsBottom - ellipseBoundsTop) / 2;
    final double m = (lineEndY - lineStartY) / (lineEndX - lineStartX);
    final double c = lineStartY - m * lineStartX; // Just a point on the line

    /**
     * Step 3:
     *
     * Substitute the Line equation into the Ellipse equation. Solve for x.
     * Eventually, you'll have to use the quadratic formula.
     *
     * Quadratic formula: Ax^2 + Bx + C = 0
     */
    final double A = (b * b + a * a * m * m);
    final double B = 2 * a * a * c * m;
    final double C = (a * a * (c * c - b * b));

    /**
     * Step 4:
     *
     * Apply Quadratic formula. D = determinant / 2A
     */
    final double D = Math.sqrt(-C / A + Math.pow(B / (2 * A), 2));
    final double x2 = -B / (2 * A) - D;
    final double y2 = m * x2 + c;

    /**
     * Step 5:
     *
     * Undo the space transformation in Step 5.
     */
    final double x = x2 + ellipseCenterX;
    final double y = y2 + ellipseCenterY;

    if (!Double.isNaN(x) && !Double.isNaN(y)) {
      result.x = (float) x;
      result.y = (float) y;
    }
  }

  public float getBorderWidthOrDefaultTo(final float defaultValue, final int spacingType) {
    if (mBorderWidth == null) {
      return defaultValue;
    }

    final float width = mBorderWidth.getRaw(spacingType);

    if (YogaConstants.isUndefined(width)) {
      return defaultValue;
    }

    return width;
  }

  /**
   * Set type of border
   */
  private void updatePathEffect() {
    mPathEffectForBorderStyle = mBorderStyle != null
        ? mBorderStyle.getPathEffect(getFullBorderWidth())
        : null;

    mPaint.setPathEffect(mPathEffectForBorderStyle);
  }

  /** For rounded borders we use default "borderWidth" property. */
  public float getFullBorderWidth() {
    return (mBorderWidth != null && !YogaConstants.isUndefined(mBorderWidth.getRaw(Spacing.ALL))) ?
        mBorderWidth.getRaw(Spacing.ALL) : 0f;
  }

  /**
   * Quickly determine if all the set border colors are equal.  Bitwise AND all the set colors
   * together, then OR them all together.  If the AND and the OR are the same, then the colors
   * are compatible, so return this color.
   *
   * Used to avoid expensive path creation and expensive calls to canvas.drawPath
   *
   * @return A compatible border color, or zero if the border colors are not compatible.
   */
  private static int fastBorderCompatibleColorOrZero(
      int borderLeft,
      int borderTop,
      int borderRight,
      int borderBottom,
      int colorLeft,
      int colorTop,
      int colorRight,
      int colorBottom) {
    int andSmear = (borderLeft > 0 ? colorLeft : ALL_BITS_SET) &
        (borderTop > 0 ? colorTop : ALL_BITS_SET) &
        (borderRight > 0 ? colorRight : ALL_BITS_SET) &
        (borderBottom > 0 ? colorBottom : ALL_BITS_SET);
    int orSmear = (borderLeft > 0 ? colorLeft : ALL_BITS_UNSET) |
        (borderTop > 0 ? colorTop : ALL_BITS_UNSET) |
        (borderRight > 0 ? colorRight : ALL_BITS_UNSET) |
        (borderBottom > 0 ? colorBottom : ALL_BITS_UNSET);
    return andSmear == orSmear ? andSmear : 0;
  }

  private void drawRectangularBackgroundWithBorders(Canvas canvas) {
    int useColor = ColorUtil.multiplyColorAlpha(mColor, mAlpha);
    if (Color.alpha(useColor) != 0) { // color is not transparent
      mPaint.setColor(useColor);
      mPaint.setStyle(Paint.Style.FILL);
      canvas.drawRect(getBounds(), mPaint);
    }
    // maybe draw borders?
    if (getBorderWidth(Spacing.LEFT) > 0 || getBorderWidth(Spacing.TOP) > 0 ||
        getBorderWidth(Spacing.RIGHT) > 0 || getBorderWidth(Spacing.BOTTOM) > 0) {
      Rect bounds = getBounds();

      int borderLeft = getBorderWidth(Spacing.LEFT);
      int borderTop = getBorderWidth(Spacing.TOP);
      int borderRight = getBorderWidth(Spacing.RIGHT);
      int borderBottom = getBorderWidth(Spacing.BOTTOM);
      int colorLeft = getBorderColor(Spacing.LEFT);
      int colorTop = getBorderColor(Spacing.TOP);
      int colorRight = getBorderColor(Spacing.RIGHT);
      int colorBottom = getBorderColor(Spacing.BOTTOM);

      int left = bounds.left;
      int top = bounds.top;

      // Check for fast path to border drawing.
      int fastBorderColor = fastBorderCompatibleColorOrZero(
          borderLeft,
          borderTop,
          borderRight,
          borderBottom,
          colorLeft,
          colorTop,
          colorRight,
          colorBottom);
      if (fastBorderColor != 0) {
        if (Color.alpha(fastBorderColor) != 0) {
          // Border color is not transparent.
          int right = bounds.right;
          int bottom = bounds.bottom;

          mPaint.setColor(fastBorderColor);
          if (borderLeft > 0) {
            int leftInset = left + borderLeft;
            canvas.drawRect(left, top, leftInset, bottom - borderBottom, mPaint);
          }
          if (borderTop > 0) {
            int topInset = top + borderTop;
            canvas.drawRect(left + borderLeft, top, right, topInset, mPaint);
          }
          if (borderRight > 0) {
            int rightInset = right - borderRight;
            canvas.drawRect(rightInset, top + borderTop, right, bottom, mPaint);
          }
          if (borderBottom > 0) {
            int bottomInset = bottom - borderBottom;
            canvas.drawRect(left, bottomInset, right - borderRight, bottom, mPaint);
          }
        }
      } else {
        // If the path drawn previously is of the same color,
        // there would be a slight white space between borders
        // with anti-alias set to true.
        // Therefore we need to disable anti-alias, and
        // after drawing is done, we will re-enable it.

        mPaint.setAntiAlias(false);

        int width = bounds.width();
        int height = bounds.height();

        if (borderLeft > 0) {
          final float x1 = left;
          final float y1 = top;
          final float x2 = left + borderLeft;
          final float y2 = top + borderTop;
          final float x3 = left + borderLeft;
          final float y3 = top + height - borderBottom;
          final float x4 = left;
          final float y4 = top + height;

          drawQuadrilateral(canvas, colorLeft, x1, y1, x2, y2, x3, y3, x4, y4);
        }

        if (borderTop > 0) {
          final float x1 = left;
          final float y1 = top;
          final float x2 = left + borderLeft;
          final float y2 = top + borderTop;
          final float x3 = left + width - borderRight;
          final float y3 = top + borderTop;
          final float x4 = left + width;
          final float y4 = top;

          drawQuadrilateral(canvas, colorTop, x1, y1, x2, y2, x3, y3, x4, y4);
        }

        if (borderRight > 0) {
          final float x1 = left + width;
          final float y1 = top;
          final float x2 = left + width;
          final float y2 = top + height;
          final float x3 = left + width - borderRight;
          final float y3 = top + height - borderBottom;
          final float x4 = left + width - borderRight;
          final float y4 = top + borderTop;

          drawQuadrilateral(canvas, colorRight, x1, y1, x2, y2, x3, y3, x4, y4);
        }

        if (borderBottom > 0) {
          final float x1 = left;
          final float y1 = top + height;
          final float x2 = left + width;
          final float y2 = top + height;
          final float x3 = left + width - borderRight;
          final float y3 = top + height - borderBottom;
          final float x4 = left + borderLeft;
          final float y4 = top + height - borderBottom;

          drawQuadrilateral(canvas, colorBottom, x1, y1, x2, y2, x3, y3, x4, y4);
        }

        // re-enable anti alias
        mPaint.setAntiAlias(true);
      }
    }
  }

  private void drawQuadrilateral(
      Canvas canvas,
      int fillColor,
      float x1,
      float y1,
      float x2,
      float y2,
      float x3,
      float y3,
      float x4,
      float y4) {
    if (fillColor == Color.TRANSPARENT) {
      return;
    }

    if (mPathForBorder == null) {
      mPathForBorder = new Path();
    }

    mPaint.setColor(fillColor);
    mPathForBorder.reset();
    mPathForBorder.moveTo(x1, y1);
    mPathForBorder.lineTo(x2, y2);
    mPathForBorder.lineTo(x3, y3);
    mPathForBorder.lineTo(x4, y4);
    mPathForBorder.lineTo(x1, y1);
    canvas.drawPath(mPathForBorder, mPaint);
  }

  private int getBorderWidth(int position) {
    return mBorderWidth != null ? Math.round(mBorderWidth.get(position)) : 0;
  }

  private static int colorFromAlphaAndRGBComponents(float alpha, float rgb) {
    int rgbComponent = 0x00FFFFFF & (int)rgb;
    int alphaComponent = 0xFF000000 & ((int)alpha) << 24;

    return rgbComponent | alphaComponent;
  }

  private int getBorderColor(int position) {
    float rgb = mBorderRGB != null ? mBorderRGB.get(position) : DEFAULT_BORDER_RGB;
    float alpha = mBorderAlpha != null ? mBorderAlpha.get(position) : DEFAULT_BORDER_ALPHA;

    return ReactViewBackgroundDrawable.colorFromAlphaAndRGBComponents(alpha, rgb);
  }
}