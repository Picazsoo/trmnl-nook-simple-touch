package com.bpmct.trmnl_nook_simple_touch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * Small status badge for the top-left corner of the display screen.
 *
 * AWAKE    - a dot: the app is awake and responding to touch.
 * FORWARD  - two right-pointing triangles: loading the next screen.
 * BACKWARD - two left-pointing triangles: loading the previous screen.
 * NONE     - hidden.
 *
 * Drawn with canvas shapes rather than text glyphs, since the Android 2.1 fonts
 * on the NOOK don't reliably include arrow characters. Pure black and white for
 * e-ink.
 */
public class CornerIndicatorView extends View {

    public static final int NONE = 0;
    public static final int AWAKE = 1;
    public static final int FORWARD = 2;
    public static final int BACKWARD = 3;

    private int mode = NONE;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public CornerIndicatorView(Context context) {
        super(context);
        fill.setColor(0xFF000000);
        fill.setStyle(Paint.Style.FILL);
        stroke.setColor(0xFF000000);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(2f);
        background.setColor(0xFFFFFFFF);
        background.setStyle(Paint.Style.FILL);
        setVisibility(GONE);
    }

    public int getMode() {
        return mode;
    }

    public void setMode(int newMode) {
        if (newMode == mode) return;
        mode = newMode;
        setVisibility(mode == NONE ? GONE : VISIBLE);
        invalidate();
    }

    protected void onDraw(Canvas canvas) {
        if (mode == NONE) return;
        float w = getWidth();
        float h = getHeight();
        rect.set(1f, 1f, w - 1f, h - 1f);
        canvas.drawRoundRect(rect, 6f, 6f, background);
        canvas.drawRoundRect(rect, 6f, 6f, stroke);

        float cx = w / 2f;
        float cy = h / 2f;
        if (mode == AWAKE) {
            canvas.drawCircle(cx, cy, Math.min(w, h) * 0.16f, fill);
            return;
        }
        // Two triangles side by side, like a fast-forward / rewind symbol.
        float th = h * 0.44f;          // triangle height
        float tw = w * 0.26f;          // triangle width
        float left = cx - tw;          // pair spans [cx - tw, cx + tw]
        boolean forward = (mode == FORWARD);
        drawTriangle(canvas, left, cy, tw, th, forward);
        drawTriangle(canvas, left + tw, cy, tw, th, forward);
    }

    private void drawTriangle(Canvas canvas, float x, float cy, float tw, float th, boolean pointRight) {
        path.reset();
        if (pointRight) {
            path.moveTo(x, cy - th / 2f);
            path.lineTo(x + tw, cy);
            path.lineTo(x, cy + th / 2f);
        } else {
            path.moveTo(x + tw, cy - th / 2f);
            path.lineTo(x, cy);
            path.lineTo(x + tw, cy + th / 2f);
        }
        path.close();
        canvas.drawPath(path, fill);
    }
}
