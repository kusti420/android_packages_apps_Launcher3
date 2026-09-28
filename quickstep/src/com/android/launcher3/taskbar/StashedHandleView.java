/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.taskbar;

import static com.android.launcher3.LauncherAnimUtils.SCALE_PROPERTY;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.ColorInt;
import androidx.core.content.ContextCompat;

import com.android.launcher3.LauncherAnimUtils;
import com.android.launcher3.R;
import com.android.launcher3.taskbar.navbutton.IchthysNavStyle;

/**
 * View to render a handle that changes color based on the background to ensure contrast. Used for
 * the taskbar when stashed as well as the bubble bar when stashed.
 */
public class StashedHandleView extends View {

    private static final long COLOR_CHANGE_DURATION = 120;

    private @ColorInt int mStashedHandleLightColor;
    private @ColorInt int mStashedHandleDarkColor;
    private final Rect mSampledRegion = new Rect();
    // IchthysOS navBar.style = "gestures": the handle is a track (the background colour, clipped
    // by the outline) with an accent segment drawn in the centre of mHandleBounds.
    private boolean mIchthysGestures;
    private @ColorInt int mIchthysAccent;
    private final Rect mHandleBounds = new Rect();
    private final Paint mIchthysPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] mTmpArr = new int[2];

    private @Nullable ObjectAnimator mColorChangeAnim;
    private Boolean mIsRegionDark;

    public StashedHandleView(Context context) {
        this(context, null);
    }

    public StashedHandleView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StashedHandleView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public StashedHandleView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mStashedHandleLightColor = ContextCompat.getColor(context,
                R.color.taskbar_stashed_handle_light_color);
        mStashedHandleDarkColor = ContextCompat.getColor(context,
                R.color.taskbar_stashed_handle_dark_color);
    }

    /**
     * Updates mSampledRegion to be the location of the stashedHandleBounds relative to the screen.
     * @see #getSampledRegion()
     */
    public void updateSampledRegion(Rect stashedHandleBounds) {
        getLocationOnScreen(mTmpArr);
        // Translations are temporary due to animations, remove them for the purpose of determining
        // the final region we want sampled.
        mTmpArr[0] -= Math.round(getTranslationX());
        mTmpArr[1] -= Math.round(getTranslationY());
        mSampledRegion.set(stashedHandleBounds);
        mSampledRegion.offset(mTmpArr[0], mTmpArr[1]);
        if (!mHandleBounds.equals(stashedHandleBounds)) {
            mHandleBounds.set(stashedHandleBounds);
            if (mIchthysGestures) invalidate();
        }
    }

    /**
     * IchthysOS navBar.style "gestures": draw the handle as a track (ink/light track token, picked
     * by the sampled region like the stock colours) with a 22dp {@code accent} centre segment.
     */
    public void setIchthysGestures(boolean enabled, @ColorInt int accent) {
        mIchthysGestures = enabled;
        mIchthysAccent = accent;
        if (enabled) {
            mStashedHandleLightColor = IchthysNavStyle.D_TRACK;
            mStashedHandleDarkColor = IchthysNavStyle.L_TRACK;
        } else {
            mStashedHandleLightColor = ContextCompat.getColor(getContext(),
                    R.color.taskbar_stashed_handle_light_color);
            mStashedHandleDarkColor = ContextCompat.getColor(getContext(),
                    R.color.taskbar_stashed_handle_dark_color);
        }
        // Re-apply the colour for the current region with the new palette.
        final Boolean regionDark = mIsRegionDark;
        mIsRegionDark = null;
        updateHandleColor(regionDark == null || regionDark, false /* animate */);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mIchthysGestures || mHandleBounds.isEmpty()) {
            return;
        }
        final float dp = getResources().getDisplayMetrics().density;
        final float cx = mHandleBounds.exactCenterX();
        final float half = Math.min(11 * dp, mHandleBounds.width() / 2f);
        final float radius = mHandleBounds.height() / 2f;
        mIchthysPaint.setColor(mIchthysAccent);
        canvas.drawRoundRect(cx - half, mHandleBounds.top, cx + half, mHandleBounds.bottom,
                radius, radius, mIchthysPaint);
    }

    public Rect getSampledRegion() {
        return mSampledRegion;
    }

    /**
     * Updates the handle color.
     * @param isRegionDark Whether the background behind the handle is dark, and thus the handle
     *                     should be light (and vice versa).
     * @param animate Whether to animate the change, or apply it immediately.
     */
    public void updateHandleColor(boolean isRegionDark, boolean animate) {
        if (mIsRegionDark != null && mIsRegionDark == isRegionDark) {
            return;
        }
        int newColor = isRegionDark ? mStashedHandleLightColor : mStashedHandleDarkColor;
        mIsRegionDark = isRegionDark;
        if (mColorChangeAnim != null) {
            mColorChangeAnim.cancel();
        }
        if (animate) {
            mColorChangeAnim = ObjectAnimator.ofArgb(this,
                    LauncherAnimUtils.VIEW_BACKGROUND_COLOR, newColor);
            mColorChangeAnim.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    mColorChangeAnim = null;
                }
            });
            mColorChangeAnim.setDuration(COLOR_CHANGE_DURATION);
            mColorChangeAnim.start();
        } else {
            setBackgroundColor(newColor);
        }
    }

    /**
     * Updates the handle scale.
     *
     * @param scale target scale to animate towards (starting from current scale)
     * @param durationMs milliseconds for the animation to take
     */
    public void animateScale(float scale, long durationMs) {
        ObjectAnimator scaleAnim = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_PROPERTY, scale));
        scaleAnim.setDuration(durationMs).setAutoCancel(true);
        scaleAnim.start();
    }
}
