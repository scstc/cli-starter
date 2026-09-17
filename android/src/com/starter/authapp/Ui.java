package com.starter.authapp;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

/** 品牌设计系统:与网页版同款的渐变/圆角/描边,纯代码 Drawable,零资源文件。 */
public final class Ui {

    public static final int NAVY = 0xFF101B40;
    public static final int NAVY_MID = 0xFF1B2F75;
    public static final int VIOLET_DEEP = 0xFF3B1D86;
    public static final int BRAND = 0xFF2563EB;
    public static final int BRAND_DARK = 0xFF1D4ED8;
    public static final int VIOLET = 0xFF6D3AED;
    public static final int VIOLET_DARK = 0xFF5B21B6;
    public static final int TEXT = 0xFF17203A;
    public static final int MUTED = 0xFF7A869C;
    public static final int PAGE_BG = 0xFFF5F7FB;
    public static final int BORDER = 0xFFE3E8F0;
    public static final int WHITE = 0xFFFFFFFF;

    private Ui() {
    }

    public static int dp(int v) {
        Resources r = Resources.getSystem();
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, r.getDisplayMetrics()));
    }

    /** 线性渐变(左上→右下);单色时自动复制为双色,LinearGradient 要求至少两种颜色。 */
    public static GradientDrawable gradient(int radiusDp, int... colors) {
        int[] c = colors.length >= 2 ? colors
                : new int[]{colors.length > 0 ? colors[0] : BRAND, colors.length > 0 ? colors[0] : BRAND};
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR, c);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    /** 纯色圆角矩形,可选描边。 */
    public static GradientDrawable rounded(int radiusDp, int color, int strokeDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) {
            d.setStroke(dp(strokeDp), strokeColor);
        }
        return d;
    }

    private static Drawable ripple(Drawable content, int tintColor) {
        return new RippleDrawable(ColorStateList.valueOf(tintColor), content, null);
    }

    /** 主按钮:品牌渐变,按压换深色渐变,带波纹。 */
    public static Drawable btnPrimary() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed},
                gradient(12, BRAND_DARK, VIOLET_DARK));
        s.addState(new int[]{}, ripple(gradient(12, BRAND, VIOLET), 0x33FFFFFF));
        return s;
    }

    /** 次级按钮:白底描边,按压浅灰。 */
    public static Drawable btnOutline() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, rounded(12, 0xFFEDF1F7, dp(1), BORDER));
        s.addState(new int[]{}, rounded(12, WHITE, dp(1), BORDER));
        return s;
    }

    /** 输入框:白底圆角描边,聚焦变品牌蓝描边。 */
    public static Drawable input() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused},
                rounded(12, WHITE, dp(1) + 1, BRAND));
        s.addState(new int[]{}, rounded(12, WHITE, dp(1), BORDER));
        return s;
    }

    /** 品牌头部:深海军蓝 → 紫 渐变。 */
    public static Drawable brandHeader() {
        return gradient(0, NAVY, NAVY_MID, VIOLET_DEEP);
    }

    /** 弹窗标题头:同款渐变,仅顶部圆角(与白卡圆角衔接)。 */
    public static Drawable dialogHeader(int radiusDp) {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{NAVY_MID, VIOLET_DEEP});
        float r = dp(radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    /** 弹窗标题头:厂商品牌纯色,仅顶部圆角。 */
    public static Drawable dialogHeaderSolid(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float r = dp(radiusDp);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        return d;
    }

    /** 状态条:浅灰圆角。 */
    public static Drawable statusBg() {
        return rounded(10, 0xFFEEF2F8, 0, 0);
    }

    /** 统一设置 TextView 的字重/颜色/字号。 */
    public static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        if (bold) {
            tv.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return tv;
    }

    /** 让 view 的前景可点区域反馈(简单按压透明度)。 */
    public static void pressAlpha(View v) {
        v.setAlpha(1f);
        v.setOnTouchListener((vv, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    vv.setAlpha(0.8f);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    vv.setAlpha(1f);
                    break;
            }
            return false;
        });
    }
}
