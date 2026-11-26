package com.hsy.devupdate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * 扫码取景框：中间一个正方形亮窗，四周压半透明黑，四角画白色 L 形。
 * 只解框内的区域，跟常见扫码 App 的观感一致。
 */
public class ScanOverlay extends View {

    /** 取景框边长占屏幕短边的比例 */
    public static final float FRAME_RATIO = 0.56f;
    /** 四角白线的长度占框边长的比例 */
    private static final float CORNER_LEN_RATIO = 0.16f;

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private float cornerLen;
    private float cornerStroke;

    public ScanOverlay(Context c, AttributeSet a) {
        super(c, a);
        maskPaint.setColor(0xB0000000);
        cornerPaint.setColor(0xFFFFFFFF);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerStroke = 5f * getResources().getDisplayMetrics().density;
        cornerPaint.setStrokeWidth(cornerStroke);
        cornerPaint.setStrokeCap(Paint.Cap.SQUARE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float side = Math.min(w, h) * FRAME_RATIO;
        float left = (w - side) / 2f;
        float top = (h - side) / 2f;
        frame.set(left, top, left + side, top + side);
        cornerLen = side * CORNER_LEN_RATIO;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (frame.isEmpty()) return;

        // 四周压暗，中间留出取景框
        canvas.drawRect(0, 0, w, frame.top, maskPaint);
        canvas.drawRect(0, frame.bottom, w, h, maskPaint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, maskPaint);
        canvas.drawRect(frame.right, frame.top, w, frame.bottom, maskPaint);

        float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;
        // 四个角
        canvas.drawLine(l, t, l + cornerLen, t, cornerPaint);
        canvas.drawLine(l, t, l, t + cornerLen, cornerPaint);
        canvas.drawLine(r, t, r - cornerLen, t, cornerPaint);
        canvas.drawLine(r, t, r, t + cornerLen, cornerPaint);
        canvas.drawLine(l, b, l + cornerLen, b, cornerPaint);
        canvas.drawLine(l, b, l, b - cornerLen, cornerPaint);
        canvas.drawLine(r, b, r - cornerLen, b, cornerPaint);
        canvas.drawLine(r, b, r, b - cornerLen, cornerPaint);
    }

    /** 取景框在控件坐标系里的位置，供解码时裁 ROI 用 */
    public RectF frame() {
        return frame;
    }
}
