package com.github.gcacace.signaturepad.views;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;

import com.github.gcacace.signaturepad.R;
import com.github.gcacace.signaturepad.utils.Bezier;
import com.github.gcacace.signaturepad.utils.ControlTimedPoints;
import com.github.gcacace.signaturepad.utils.SvgBuilder;
import com.github.gcacace.signaturepad.utils.TimedPoint;
import com.github.gcacace.signaturepad.view.ViewCompat;
import com.github.gcacace.signaturepad.view.ViewTreeObserverCompat;

import java.util.ArrayList;
import java.util.List;

public class SignaturePad extends View {
    //View state
    /** 记录一个笔画中，相应绘制点的信息 */
    private List<TimedPoint> mPoints;
    private boolean mIsEmpty;
    private float mLastTouchX;
    private float mLastTouchY;
    private float mLastVelocity;
    private float mLastWidth;
    private RectF mDirtyRect;

    private final SvgBuilder mSvgBuilder = new SvgBuilder();

    // Cache
    /** 用于暂存点的 */
    private List<TimedPoint> mPointsCache = new ArrayList<>();
    /** 曲线控制点 */
    private ControlTimedPoints mControlTimedPointsCached = new ControlTimedPoints();
    private Bezier mBezierCached = new Bezier();

    //Configurable parameters
    private int mMinWidth;
    private int mMaxWidth;
    private float mVelocityFilterWeight;
    private OnSignedListener mOnSignedListener;
    private boolean mClearOnDoubleClick;

    //Click values
    private long mFirstClick;
    private int mCountClick;
    private static final int DOUBLE_CLICK_DELAY_MS = 200;

    //Default attribute values
    /** 笔画最小宽度 */
    private final int DEFAULT_ATTR_PEN_MIN_WIDTH_PX = 10;
    /** 笔画最大宽度 */
    private final int DEFAULT_ATTR_PEN_MAX_WIDTH_PX = 30;
    /** 笔画颜色 */
    private final int DEFAULT_ATTR_PEN_COLOR = Color.BLACK;
    private final float DEFAULT_ATTR_VELOCITY_FILTER_WEIGHT = 0.1f;
    private final boolean DEFAULT_ATTR_CLEAR_ON_DOUBLE_CLICK = false;

    private float DEFAULT_ATTR_PRESURE_FILTER_WEIGHT = 0.5f;
    // 压力指数
    private float mPressureExponent = 2.0f;

    private Paint mPaint = new Paint();
    private Bitmap mSignatureBitmap = null;
    private Canvas mSignatureBitmapCanvas = null;

    private Bitmap mFountainPenBits;
    private Rect mFountainPenBitsFrame;

    public SignaturePad(Context context, AttributeSet attrs) {
        super(context, attrs);

        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs,
                R.styleable.SignaturePad,
                0, 0);

        //Configurable parameters
        try {
            mMinWidth = a.getDimensionPixelSize(R.styleable.SignaturePad_penMinWidth, convertDpToPx(DEFAULT_ATTR_PEN_MIN_WIDTH_PX));
            mMaxWidth = a.getDimensionPixelSize(R.styleable.SignaturePad_penMaxWidth, convertDpToPx(DEFAULT_ATTR_PEN_MAX_WIDTH_PX));
            mPaint.setColor(a.getColor(R.styleable.SignaturePad_penColor, DEFAULT_ATTR_PEN_COLOR));
            mVelocityFilterWeight = a.getFloat(R.styleable.SignaturePad_velocityFilterWeight, DEFAULT_ATTR_VELOCITY_FILTER_WEIGHT);
            mClearOnDoubleClick = a.getBoolean(R.styleable.SignaturePad_clearOnDoubleClick, DEFAULT_ATTR_CLEAR_ON_DOUBLE_CLICK);
        } finally {
            a.recycle();
        }

        //Fixed parameters
        mPaint.setAntiAlias(true);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.SQUARE);  //设置笔触风格
        mPaint.setStrokeJoin(Paint.Join.BEVEL); //设置连接处风格

        //Dirty rectangle to update only the changed portion of the view
        mDirtyRect = new RectF();

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ALPHA_8;
        final Resources res = getContext().getResources();
        mFountainPenBits = BitmapFactory.decodeResource(res, R.drawable.fountainpen, opts);
        mFountainPenBitsFrame = new Rect(0, 0, mFountainPenBits.getWidth(), mFountainPenBits.getHeight());

        clear();
    }

    /**
     * 设置画笔的颜色 <br/>
     * Set the pen color from a given resource.
     * If the resource is not found, {@link android.graphics.Color#BLACK} is assumed.
     *
     * @param colorRes the color resource.
     */
    public void setPenColorRes(int colorRes) {
        try {
            setPenColor(getResources().getColor(colorRes));
        } catch (Resources.NotFoundException ex) {
            setPenColor(Color.parseColor("#000000"));
        }
    }

    /**
     * 设置画笔的颜色<br/>
     * Set the pen color from a given color.
     *
     * @param color the color.
     */
    public void setPenColor(int color) {
        mPaint.setColor(color);
    }

    /**
     * 设置画笔的最小宽度<br/>
     * Set the minimum width of the stroke in pixel.
     *
     * @param minWidth the width in dp.
     */
    public void setMinWidth(float minWidth) {
        mMinWidth = convertDpToPx(minWidth);
    }

    /**
     * 设置画笔的最大宽度<br/>
     * Set the maximum width of the stroke in pixel.
     *
     * @param maxWidth the width in dp.
     */
    public void setMaxWidth(float maxWidth) {
        mMaxWidth = convertDpToPx(maxWidth);
    }

    /**
     * Set the velocity filter weight.
     *
     * @param velocityFilterWeight the weight.
     */
    public void setVelocityFilterWeight(float velocityFilterWeight) {
        mVelocityFilterWeight = velocityFilterWeight;
    }

    /**
     * 清除绘制的笔迹
     */
    public void clear() {
        mSvgBuilder.clear();
        mPoints = new ArrayList<>();
        mLastVelocity = 0;
        mLastWidth = (mMinWidth + mMaxWidth) / 2;

        if (mSignatureBitmap != null) {
            mSignatureBitmap = null;
            ensureSignatureBitmap();
        }

        setIsEmpty(true);

        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled())
            return false;

        float eventX = event.getX();
        float eventY = event.getY();
        float eventPresure = event.getPressure();

        Log.d("zrr", "presure = " + event.getPressure());

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                mPoints.clear();
                mLastVelocity = 0;
                mLastWidth = 0;
                if (isDoubleClick()) break;
                mLastTouchX = eventX;
                mLastTouchY = eventY;
                addPoint(getNewPoint(eventX, eventY, eventPresure));
                if(mOnSignedListener != null) mOnSignedListener.onStartSigning();

            case MotionEvent.ACTION_MOVE:
                resetDirtyRect(eventX, eventY);
                addPoint(getNewPoint(eventX, eventY, eventPresure));
                break;

            case MotionEvent.ACTION_UP:
                resetDirtyRect(eventX, eventY);
                addPoint(getNewPoint(eventX, eventY, eventPresure));
                getParent().requestDisallowInterceptTouchEvent(true);
                setIsEmpty(false);
                break;

            default:
                return false;
        }

        //invalidate();
        invalidate(
                (int) (mDirtyRect.left - mMaxWidth),
                (int) (mDirtyRect.top - mMaxWidth),
                (int) (mDirtyRect.right + mMaxWidth),
                (int) (mDirtyRect.bottom + mMaxWidth));

        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mSignatureBitmap != null) {
            canvas.drawBitmap(mSignatureBitmap, 0, 0, mPaint);
        }
    }

    public void setOnSignedListener(OnSignedListener listener) {
        mOnSignedListener = listener;
    }

    public boolean isEmpty() {
        return mIsEmpty;
    }

    public String getSignatureSvg() {
        int width = getTransparentSignatureBitmap().getWidth();
        int height = getTransparentSignatureBitmap().getHeight();
        return mSvgBuilder.build(width, height);
    }

    public Bitmap getSignatureBitmap() {
        Bitmap originalBitmap = getTransparentSignatureBitmap();
        Bitmap whiteBgBitmap = Bitmap.createBitmap(originalBitmap.getWidth(), originalBitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(whiteBgBitmap);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(originalBitmap, 0, 0, null);
        return whiteBgBitmap;
    }

    public void setSignatureBitmap(final Bitmap signature) {
        // View was laid out...
        if (ViewCompat.isLaidOut(this)) {
            clear();
            ensureSignatureBitmap();

            RectF tempSrc = new RectF();
            RectF tempDst = new RectF();

            int dWidth = signature.getWidth();
            int dHeight = signature.getHeight();
            int vWidth = getWidth();
            int vHeight = getHeight();

            // Generate the required transform.
            tempSrc.set(0, 0, dWidth, dHeight);
            tempDst.set(0, 0, vWidth, vHeight);

            Matrix drawMatrix = new Matrix();
            drawMatrix.setRectToRect(tempSrc, tempDst, Matrix.ScaleToFit.CENTER);

            Canvas canvas = new Canvas(mSignatureBitmap);
            canvas.drawBitmap(signature, drawMatrix, null);
            setIsEmpty(false);
            invalidate();
        }
        // View not laid out yet e.g. called from onCreate(), onRestoreInstanceState()...
        else {
            getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    // Remove layout listener...
                    ViewTreeObserverCompat.removeOnGlobalLayoutListener(getViewTreeObserver(), this);

                    // Signature bitmap...
                    setSignatureBitmap(signature);
                }
            });
        }
    }

    public Bitmap getTransparentSignatureBitmap() {
        ensureSignatureBitmap();
        return mSignatureBitmap;
    }

    public Bitmap getTransparentSignatureBitmap(boolean trimBlankSpace) {

        if (!trimBlankSpace) {
            return getTransparentSignatureBitmap();
        }

        ensureSignatureBitmap();

        int imgHeight = mSignatureBitmap.getHeight();
        int imgWidth = mSignatureBitmap.getWidth();

        int backgroundColor = Color.TRANSPARENT;

        int xMin = Integer.MAX_VALUE,
            xMax = Integer.MIN_VALUE,
            yMin = Integer.MAX_VALUE,
            yMax = Integer.MIN_VALUE;

        boolean foundPixel = false;

        // Find xMin
        for (int x = 0; x < imgWidth; x++) {
            boolean stop = false;
            for (int y = 0; y < imgHeight; y++) {
                if (mSignatureBitmap.getPixel(x, y) != backgroundColor) {
                    xMin = x;
                    stop = true;
                    foundPixel = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Image is empty...
        if (!foundPixel)
            return null;

        // Find yMin
        for (int y = 0; y < imgHeight; y++) {
            boolean stop = false;
            for (int x = xMin; x < imgWidth; x++) {
                if (mSignatureBitmap.getPixel(x, y) != backgroundColor) {
                    yMin = y;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Find xMax
        for (int x = imgWidth - 1; x >= xMin; x--) {
            boolean stop = false;
            for (int y = yMin; y < imgHeight; y++) {
                if (mSignatureBitmap.getPixel(x, y) != backgroundColor) {
                    xMax = x;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

        // Find yMax
        for (int y = imgHeight - 1; y >= yMin; y--) {
            boolean stop = false;
            for (int x = xMin; x <= xMax; x++) {
                if (mSignatureBitmap.getPixel(x, y) != backgroundColor) {
                    yMax = y;
                    stop = true;
                    break;
                }
            }
            if (stop)
                break;
        }

      return Bitmap.createBitmap(mSignatureBitmap, xMin, yMin, xMax - xMin, yMax - yMin);
    }

    private boolean isDoubleClick() {
        if (mClearOnDoubleClick) {
            if (mFirstClick != 0 && System.currentTimeMillis() - mFirstClick > DOUBLE_CLICK_DELAY_MS) {
                mCountClick = 0;
            }
            mCountClick++;
            if (mCountClick == 1) {
                mFirstClick = System.currentTimeMillis();
            } else if (mCountClick == 2) {
                long lastClick = System.currentTimeMillis();
                if (lastClick - mFirstClick < DOUBLE_CLICK_DELAY_MS) {
                    this.clear();
                    return true;
                }
            }
        }
        return false;
    }

    private TimedPoint getNewPoint(float x, float y, float presure) {
        int mCacheSize = mPointsCache.size();
        TimedPoint timedPoint;
        if (mCacheSize == 0) {
            // Cache is empty, create a new point
            timedPoint = new TimedPoint();
        } else {
            // Get point from cache
            timedPoint = mPointsCache.remove(mCacheSize-1);
        }

        return timedPoint.set(x, y, presure);
    }

    /**
     * 将点暂时存储起来
     * @param point
     */
    private void recyclePoint(TimedPoint point) {
        mPointsCache.add(point);
    }

    private void addPoint(TimedPoint newPoint) {
        mPoints.add(newPoint);

        int pointsCount = mPoints.size();
        if (pointsCount > 3) {

            ControlTimedPoints tmp = calculateCurveControlPoints(mPoints.get(0), mPoints.get(1), mPoints.get(2));
            TimedPoint c2 = tmp.c2;
            recyclePoint(tmp.c1);

            tmp = calculateCurveControlPoints(mPoints.get(1), mPoints.get(2), mPoints.get(3));
            TimedPoint c3 = tmp.c1;
            recyclePoint(tmp.c2);

            Bezier curve = mBezierCached.set(mPoints.get(1), c2, c3, mPoints.get(2));

            TimedPoint startPoint = curve.startPoint;
            TimedPoint endPoint = curve.endPoint;

            // 1、获取两点之间的平均速度
            float velocity = endPoint.velocityFrom(startPoint);
            velocity = Float.isNaN(velocity) ? 0.0f : velocity;

            // 2、通过速度过滤比重值重新计算曲线绘制的速度(上次速度和这次速度按照比重取值相加得到)
            velocity = mVelocityFilterWeight * velocity
                    + (1 - mVelocityFilterWeight) * mLastVelocity;

            // The new width is a function of the velocity. Higher velocities
            // correspond to thinner strokes.
            // 3、计算新笔画曲线的粗细，速度越快，笔迹越细
            float newWidthVelocity = getStrokeWidthWithVelocity(velocity);
            float newWidthPresure = getStrokeWidthWithPresure(mMinWidth, mMaxWidth, (float) Math.pow(mPoints.get(2).presure, mPressureExponent));

            float newWidth = (1 - DEFAULT_ATTR_PRESURE_FILTER_WEIGHT) * newWidthVelocity +  DEFAULT_ATTR_PRESURE_FILTER_WEIGHT * newWidthPresure;

            // 之前速度为空，表明是新的一笔，采用最大宽度
            if(mLastWidth == 0){
                // TODO: 16/8/23  这里以后可以用压力值计算s
                newWidth = mMaxWidth - mMinWidth;
            }

            // The Bezier's width starts out as last curve's final width, and
            // gradually changes to the stroke width just calculated. The new
            // width calculation is based on the velocity between the Bezier's
            // start and end mPoints.
            addBezier(curve, mLastWidth, newWidth);

            // 记录上一次的绘制速度和曲线宽度
            mLastVelocity = velocity;
            mLastWidth = newWidth;

            // Remove the first element from the list,
            // so that we always have no more than 4 mPoints in mPoints array.
            // 移除最先记录的point，确保mPoints的列表中的点的个数不超过4个
            recyclePoint(mPoints.remove(0));

            recyclePoint(c2);
            recyclePoint(c3);
            
        } else if (pointsCount == 1) {
            // To reduce the initial lag make it work with 3 mPoints
            // by duplicating the first point
            // 为了减少初始的延时，复制第一个点（即action_down所表示的点）
            TimedPoint firstPoint = mPoints.get(0);
            mPoints.add(getNewPoint(firstPoint.x, firstPoint.y, firstPoint.presure));
        }
    }



    /**
     * 通过压力计算笔画的宽度
     * @param minWidth 笔画最小宽度
     * @param maxWidth 笔画最大宽度
     * @param presure 压力值，0～1
     * @return
     */
    public static float getStrokeWidthWithPresure(float minWidth, float maxWidth, float presure) {
        return minWidth + presure * (maxWidth - maxWidth);
    }

    /**
     * 根据计算的贝赛尔曲线绘制每个点的宽度
     * @param curve
     * @param startWidth
     * @param endWidth
     */
    private void addBezier(Bezier curve, float startWidth, float endWidth) {
        mSvgBuilder.append(curve, (startWidth + endWidth) / 2);
        ensureSignatureBitmap();
        float originalWidth = mPaint.getStrokeWidth();
        float widthDelta = endWidth - startWidth;
        // 返回小于或等于曲线长度的整数，表示当前曲线需要绘制多少个点
        float drawSteps = (float) Math.floor(curve.length());

        // 三阶贝赛尔曲线计算绘制每一个点
        for (int i = 0; i < drawSteps; i++) {
            // Calculate the Bezier (x, y) coordinate for this step.
            float t = ((float) i) / drawSteps;
            float tt = t * t;
            float ttt = tt * t;
            float u = 1 - t;
            float uu = u * u;
            float uuu = uu * u;

            float x = uuu * curve.startPoint.x;
            x += 3 * uu * t * curve.control1.x;
            x += 3 * u * tt * curve.control2.x;
            x += ttt * curve.endPoint.x;

            float y = uuu * curve.startPoint.y;
            y += 3 * uu * t * curve.control1.y;
            y += 3 * u * tt * curve.control2.y;
            y += ttt * curve.endPoint.y;

//            // Set the incremental stroke width and draw.
//            mPaint.setStrokeWidth(startWidth + ttt * widthDelta);
//            mSignatureBitmapCanvas.drawPoint(x, y, mPaint);

            float r = (startWidth + ttt * widthDelta)/2f;
            tmpRF.set(x-r,y-r,x+r,y+r);
            if (mFountainPenBits == null || mFountainPenBitsFrame == null) {
                throw new RuntimeException("Slate.drawStrokePoint: no fountainpen bitmap - frame=" + mFountainPenBitsFrame);
            }
            mSignatureBitmapCanvas.drawBitmap(mFountainPenBits, mFountainPenBitsFrame, tmpRF, mPaint);

            expandDirtyRect(x, y);
        }

        mPaint.setStrokeWidth(originalWidth);
    }

    private final RectF tmpRF = new RectF();



    /**
     * 计算曲线的控制点
     * @param s1
     * @param s2
     * @param s3
     * @return
     */
    private ControlTimedPoints calculateCurveControlPoints(TimedPoint s1, TimedPoint s2, TimedPoint s3) {
        float dx1 = s1.x - s2.x;
        float dy1 = s1.y - s2.y;
        float dx2 = s2.x - s3.x;
        float dy2 = s2.y - s3.y;

        float m1X = (s1.x + s2.x) / 2.0f;
        float m1Y = (s1.y + s2.y) / 2.0f;
        float m2X = (s2.x + s3.x) / 2.0f;
        float m2Y = (s2.y + s3.y) / 2.0f;

        float l1 = (float) Math.sqrt(dx1 * dx1 + dy1 * dy1);
        float l2 = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2);

        float dxm = (m1X - m2X);
        float dym = (m1Y - m2Y);
        float k = l2 / (l1 + l2);
        if (Float.isNaN(k)) k = 0.0f;
        float cmX = m2X + dxm * k;
        float cmY = m2Y + dym * k;

        float tx = s2.x - cmX;
        float ty = s2.y - cmY;

        float presure12 = (s1.presure + s2.presure) / 2.0f;
        float presure23 = (s2.presure + s3.presure) / 2.0f;

        return mControlTimedPointsCached.set(getNewPoint(m1X + tx, m1Y + ty, presure12), getNewPoint(m2X + tx, m2Y + ty, presure23));
    }

    /**
     * 计算笔画的宽度
     * @param velocity 过滤速度
     * @return
     */
    private float getStrokeWidthWithVelocity(float velocity) {
        return Math.max(mMaxWidth / (velocity + 1), mMinWidth);
    }

    /**
     * 重新计算要绘制的区域<br/>
     * Called when replaying history to ensure the dirty region includes all
     * mPoints.
     *
     * @param historicalX the previous x coordinate.
     * @param historicalY the previous y coordinate.
     */
    private void expandDirtyRect(float historicalX, float historicalY) {
        if (historicalX < mDirtyRect.left) {
            mDirtyRect.left = historicalX;
        } else if (historicalX > mDirtyRect.right) {
            mDirtyRect.right = historicalX;
        }
        if (historicalY < mDirtyRect.top) {
            mDirtyRect.top = historicalY;
        } else if (historicalY > mDirtyRect.bottom) {
            mDirtyRect.bottom = historicalY;
        }
    }

    /**
     * 重新设置绘制区域<br/>
     * Resets the dirty region when the motion event occurs.
     *
     * @param eventX the event x coordinate.
     * @param eventY the event y coordinate.
     */
    private void resetDirtyRect(float eventX, float eventY) {

        // The mLastTouchX and mLastTouchY were set when the ACTION_DOWN motion event occurred.
        mDirtyRect.left = Math.min(mLastTouchX, eventX);
        mDirtyRect.right = Math.max(mLastTouchX, eventX);
        mDirtyRect.top = Math.min(mLastTouchY, eventY);
        mDirtyRect.bottom = Math.max(mLastTouchY, eventY);
    }

    private void setIsEmpty(boolean newValue) {
        mIsEmpty = newValue;
        if (mOnSignedListener != null) {
            if (mIsEmpty) {
                mOnSignedListener.onClear();
            } else {
                mOnSignedListener.onSigned();
            }
        }
    }

    /**
     * 将屏幕上当前的绘制内容保存为bitmap放在内存中
     */
    private void ensureSignatureBitmap() {
        if (mSignatureBitmap == null) {
            mSignatureBitmap = Bitmap.createBitmap(getWidth(), getHeight(),
                    Bitmap.Config.ARGB_8888);
            mSignatureBitmapCanvas = new Canvas(mSignatureBitmap);
        }
    }

    /**
     * dp转px
     * @param dp
     * @return
     */
    private int convertDpToPx(float dp){
        return Math.round(getContext().getResources().getDisplayMetrics().density * dp);
    }

    public interface OnSignedListener {
        void onStartSigning();
        void onSigned();
        void onClear();
    }
}
