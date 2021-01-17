/*
 * This file is part of Elektro Meter.
 *
 * Elektro Meter is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Elektro Meter is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Elektro Meter.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.github.unrud.elektrometer;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import java.util.Locale;

public class CameraView extends View {
    private static final int SHOW_TRIGGER_HINT_DURATION = 500; // millis

    private final SettingsActivity.CameraSettings cameraSettings;
    private final Paint windowPaint, triggeredPaint, errorTextPaint, textPaint, textBackgroundPaint,
            imagePaint, imageBackgroundPaint;
    private Bitmap image;
    private int windowFill;
    private boolean triggered;
    private long lastTriggeredMonotonicTime;
    private float fps;

    public CameraView(Context context, AttributeSet attrs) {
        super(context, attrs);
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(getContext());
        cameraSettings = new SettingsActivity.CameraSettings(sharedPreferences);
        windowPaint = new Paint();
        windowPaint.setStyle(Paint.Style.STROKE);
        windowPaint.setColor(Color.RED);
        triggeredPaint = new Paint();
        triggeredPaint.setColor(windowPaint.getColor());
        errorTextPaint = new Paint();
        errorTextPaint.setColor(Color.BLACK);
        errorTextPaint.setAntiAlias(true);
        errorTextPaint.setTextSize((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 22,
                getResources().getDisplayMetrics()));
        errorTextPaint.setTextAlign(Paint.Align.CENTER);
        textPaint = new Paint();
        textPaint.setColor(Color.BLACK);
        textPaint.setAntiAlias(true);
        textPaint.setTextSize((int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                getResources().getDisplayMetrics()));
        textBackgroundPaint = new Paint();
        textBackgroundPaint.setColor(Color.WHITE);
        textBackgroundPaint.setAlpha(200);
        imagePaint = new Paint();
        imagePaint.setColor(Color.WHITE);
        imageBackgroundPaint = new Paint();
        imageBackgroundPaint.setColor(Color.BLACK);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long monotonicTime = CameraService.monotonicTimeMillis();
        if (triggered) {
            lastTriggeredMonotonicTime = monotonicTime;
            triggered = false;
        }
        if (!cameraSettings.load()) {
            canvas.drawText("invalid settings",
                    (float) getWidth() / 2, ((float) getHeight() + errorTextPaint.getTextSize()) / 2,
                    errorTextPaint);
            return;
        }
        if (image == null) {
            canvas.drawText("no image available",
                    (float) getWidth() / 2, ((float) getHeight() + errorTextPaint.getTextSize()) / 2,
                    errorTextPaint);
            return;
        }

        float imageAspect;
        if (cameraSettings.cameraRotation % 180 == 0) {
            imageAspect = (float) image.getWidth() / (float) image.getHeight();
        } else {
            imageAspect = (float) image.getHeight() / (float) image.getWidth();
        }
        float canvasAspect = (float) getWidth() / (float) getHeight();

        canvas.save();
        canvas.scale(getWidth(), getHeight());
        if (imageAspect <= canvasAspect)
            canvas.scale(imageAspect / canvasAspect, 1, 0.5f, 0.5f);
        else
            canvas.scale(1, canvasAspect / imageAspect, 0.5f, 0.5f);
        canvas.drawRect(0, 0, 1, 1, imageBackgroundPaint);
        canvas.save();
        canvas.rotate(cameraSettings.cameraRotation, 0.5f, 0.5f);
        canvas.scale(1 / (float) image.getWidth(), 1 / (float) image.getHeight());
        canvas.drawBitmap(image, 0, 0, imagePaint);
        canvas.restore();
        canvas.drawRect(0, (float) (cameraSettings.windowOffset) / 100, 1,
                (float) (cameraSettings.windowOffset + cameraSettings.windowHeight) / 100,
                windowPaint);
        if (monotonicTime - lastTriggeredMonotonicTime <= SHOW_TRIGGER_HINT_DURATION) {
            canvas.drawRect(0, 0, 1, (float) (cameraSettings.windowOffset) / 100,
                    triggeredPaint);
            canvas.drawRect(0, (float) (cameraSettings.windowOffset + cameraSettings.windowHeight) / 100,
                    1, 1, triggeredPaint);
        }
        canvas.restore();
        String infoText = String.format(Locale.US, "Fill: %d%% Fps: %.1f Res: %dx%d",
                windowFill, fps, image.getWidth(), image.getHeight());
        canvas.drawRect(10 - 5, 10 - 5,
                10 + textPaint.measureText(infoText) + 5, 10 + textPaint.getTextSize() + 5,
                textBackgroundPaint);
        canvas.drawText(infoText, 10, 10 + textPaint.getTextSize(), textPaint);
    }

    public void updateCameraImage(@Nullable Bitmap image, int windowFill, boolean triggered, float fps) {
        this.image = image;
        this.windowFill = windowFill;
        this.triggered |= triggered; // is reset by onDraw
        this.fps = fps;
        invalidate();
    }
}
