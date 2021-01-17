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

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.Locale;
import java.util.WeakHashMap;

public class CameraService extends Service {
    public static final String[] REQUIRED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private static final String TAG = "elektrometer::CameraService";
    private static final String NOTIFICATION_CHANNEL_ID = TAG + "::NotificationChannel";
    private static final String WAKE_LOCK_ID = TAG + "::WakeLock";
    private static final int NOTIFICATION_ID = 100;
    private static final int PREFERRED_CAMERA_IMAGE_RESOLUTION = 320 * 240 * 4;  // u and v plane have quarter resolution
    private static final int CAMERA_IMAGE_FORMAT = ImageFormat.YUV_420_888;
    private static final Charset FILE_CHARSET = StandardCharsets.UTF_8;
    private final CameraBinder binder = new CameraBinder();
    private final Collection<CameraImageListener> externalCameraImageListener =
            Collections.newSetFromMap(new WeakHashMap<>());
    private SettingsActivity.CameraSettings cameraSettings;
    private Size cameraImageSize = null;
    private CameraDevice cameraDevice = null;
    private ImageReader cameraImageReader = null;
    private CameraCharacteristics activeCameraCharacteristics;
    private CameraCaptureSession activeCameraCaptureSession;
    private CaptureRequest.Builder activeCameraCaptureRequestBuilder;
    private final CameraCaptureSession.StateCallback cameraCaptureSessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    activeCameraCaptureSession = session;
                    updateCameraCaptureRequest();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    fatal("camera configure failed");
                }
            };
    private Notification notification;
    private PowerManager.WakeLock wakeLock;
    private boolean serviceRunning;
    private FileOutputStream detectionLog;
    private long lastImageMonotonicTime;
    private long triggerResetTimeRunningSinceMonotonicTime;
    private boolean triggerArmed;
    private boolean dumpRequested;
    private final ImageReader.OnImageAvailableListener cameraImageListener = reader -> {
        Image image = reader.acquireLatestImage();
        if (image != null) {
            handleCameraImage(image);
            image.close();
        }
        updateCameraCaptureRequest();
    };
    private final CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                cameraImageReader = ImageReader.newInstance(
                        cameraImageSize.getWidth(), cameraImageSize.getHeight(),
                        CAMERA_IMAGE_FORMAT, 2);
                cameraImageReader.setOnImageAvailableListener(cameraImageListener, null);
                ArrayList<Surface> surfaces = new ArrayList<>();
                surfaces.add(cameraImageReader.getSurface());
                cameraDevice.createCaptureSession(surfaces, cameraCaptureSessionCallback, null);
            } catch (CameraAccessException e) {
                fatal("failed to open camera", e);
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            fatal("camera disconnected");
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            fatal("camera error");
        }
    };

    public static long monotonicTimeMillis() {
        return System.nanoTime() / 1000000;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startService();
        return START_STICKY;
    }

    private void updateCameraCaptureRequest() {
        CameraCaptureSession session = activeCameraCaptureSession;
        CaptureRequest.Builder requestBuilder = activeCameraCaptureRequestBuilder;
        if (requestBuilder == null) {
            try {
                requestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            } catch (CameraAccessException e) {
                fatal("failed to open camera", e);
                throw new AssertionError("unreachable"); // fixes compiler warning
            }
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_MACRO);
            // CONTROL_AE_MODE_ON required for FLASH_MODE:
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            // AE_LOCK required for manual exposure with CONTROL_AE_EXPOSURE_COMPENSATION
            requestBuilder.set(CaptureRequest.CONTROL_AE_LOCK, true);
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT);
            requestBuilder.addTarget(cameraImageReader.getSurface());
        }
        boolean flash = false;
        int exposureCompensation = 0;
        if (cameraSettings.load()) {
            flash = cameraSettings.cameraFlash;
            exposureCompensation = cameraSettings.cameraExposureCompensation;
        }
        Range<Integer> exposureCompensationRange = activeCameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        exposureCompensation = exposureCompensationRange.clamp(exposureCompensation);
        if (!activeCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) {
            flash = false;
        }
        int flash_mode = flash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF;
        if (activeCameraCaptureRequestBuilder != null &&
                requestBuilder.get(CaptureRequest.FLASH_MODE) == flash_mode &&
                requestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION) == exposureCompensation) {
            return;
        }
        requestBuilder.set(CaptureRequest.FLASH_MODE, flash_mode);
        requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposureCompensation);
        try {
            session.setRepeatingRequest(requestBuilder.build(), null, null);
        } catch (CameraAccessException e) {
            fatal("failed to open camera", e);
        }
        activeCameraCaptureRequestBuilder = requestBuilder;
    }

    @SuppressLint("WakelockTimeout")
    private void startService() {
        if (serviceRunning) {
            return;
        }
        Log.i(TAG, "starting service");
        serviceRunning = true;
        startForeground(NOTIFICATION_ID, notification);
        wakeLock.acquire();
        File detectionLogFile = new File(Environment.getExternalStorageDirectory(), "ElektroMeter.log");
        try {
            detectionLog = new FileOutputStream(detectionLogFile, true);
        } catch (FileNotFoundException e) {
            fatal("failed to open detection log file", e);
        }
        startCamera();
    }

    private void handleCameraImage(@NonNull Image image) {
        if (image.getFormat() != ImageFormat.YUV_420_888) {
            fatal("unsupported image format");
        }
        long monotonicTime = monotonicTimeMillis();
        long wallClockTime = System.currentTimeMillis();
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer yBuffer = yPlane.getBuffer();
        Image.Plane uPlane = image.getPlanes()[1];
        ByteBuffer uBuffer = uPlane.getBuffer();
        Image.Plane vPlane = image.getPlanes()[2];
        ByteBuffer vBuffer = vPlane.getBuffer();

        if (dumpRequested) {
            dumpRequested = false;
            File dumpFile = new File(Environment.getExternalStorageDirectory(), "ElektroMeter.dump");
            try (
                    FileOutputStream dumpStream = new FileOutputStream(dumpFile)) {
                dumpStream.write(("" +
                        "format:YUV_420_888\n" +
                        "width:" + image.getWidth() + "\n" +
                        "height:" + image.getHeight() + "\n" +
                        "y_row_stride:" + yPlane.getRowStride() + "\n" +
                        "y_pixel_stride:" + yPlane.getPixelStride() + "\n" +
                        "y_length:" + yBuffer.remaining() + "\n" +
                        "u_row_stride:" + uPlane.getRowStride() + "\n" +
                        "u_pixel_stride:" + uPlane.getPixelStride() + "\n" +
                        "u_length:" + uBuffer.remaining() + "\n" +
                        "v_row_stride:" + vPlane.getRowStride() + "\n" +
                        "v_pixel_stride:" + vPlane.getPixelStride() + "\n" +
                        "v_length:" + vBuffer.remaining() + "\n").getBytes(FILE_CHARSET));
                dumpStream.getChannel().write(yBuffer);
                yBuffer.rewind();
                dumpStream.getChannel().write(uBuffer);
                uBuffer.rewind();
                dumpStream.getChannel().write(vBuffer);
                vBuffer.rewind();
            } catch (FileNotFoundException e) {
                fatal("failed to open dump file", e);
            } catch (IOException e) {
                fatal("failed to write dump file", e);
            }
            Toast.makeText(this, "dumped to " + dumpFile.getPath(), Toast.LENGTH_LONG).show();
        }

        if (!cameraSettings.load()) {
            Log.w(TAG, "invalid settings");
            for (CameraImageListener listener : externalCameraImageListener) {
                listener.onCameraImage(null, 0, false, 0);
            }
            return;
        }
        float fps = 1000 / (float) (monotonicTime - lastImageMonotonicTime);
        lastImageMonotonicTime = monotonicTime;
        int xStart, xEnd, yStart, yEnd;
        if (cameraSettings.cameraRotation % 180 == 0) {
            xStart = 0;
            xEnd = image.getWidth();
            if (cameraSettings.cameraRotation == 0) {
                yStart = image.getHeight() * cameraSettings.windowOffset / 100;
                yEnd = yStart + image.getHeight() * cameraSettings.windowHeight / 100;
            } else {
                yEnd = image.getHeight() * (100 - cameraSettings.windowOffset) / 100;
                yStart = yEnd - image.getHeight() * cameraSettings.windowHeight / 100;
            }
        } else {
            yStart = 0;
            yEnd = image.getHeight();
            if (cameraSettings.cameraRotation == 90) {
                xStart = image.getWidth() * cameraSettings.windowOffset / 100;
                xEnd = xStart + image.getWidth() * cameraSettings.windowHeight / 100;
            } else {
                xEnd = image.getWidth() * (100 - cameraSettings.windowOffset) / 100;
                xStart = xEnd - image.getWidth() * cameraSettings.windowHeight / 100;
            }
        }

        xStart = Math.max(0, xStart);
        xEnd = Math.min(image.getWidth(), xEnd);
        yStart = Math.max(0, yStart);
        yEnd = Math.min(image.getHeight(), yEnd);
        int windowResolution = (xEnd - xStart) * (yEnd - yStart);
        int windowFillCount = 0;

        int distanceThreshold = cameraSettings.colorDistanceThreshold * 255 / 100;
        int distanceThresholdSquared = distanceThreshold * distanceThreshold;
        int yThreshold = cameraSettings.colorLumaThreshold * 255 / 100;
        int searchU = cameraSettings.colorBlueProjection * 255 / 100;
        int searchV = cameraSettings.colorRedProjection * 255 / 100;
        // Skip lines and columns in detection window when resolution is multiple of preferred resolution
        int imageResolution = image.getWidth() * image.getHeight();
        int lineAndColumnFeed = Math.max(1, (int) Math.sqrt(
                (float) imageResolution / (float) PREFERRED_CAMERA_IMAGE_RESOLUTION));
        lineAndColumnFeed *= 2;  // u and v plane have quarter resolution
        for (int y = yStart; y < yEnd; y += lineAndColumnFeed) {
            for (int x = xStart; x < xEnd; x += lineAndColumnFeed) {
                int pixelY = yBuffer.get(y * yPlane.getRowStride() + x * yPlane.getPixelStride()) & 0xff;
                if (pixelY < yThreshold) {
                    continue;
                }
                int pixelU = uBuffer.get((y / 2) * uPlane.getRowStride() + (x / 2) * uPlane.getPixelStride()) & 0xff;
                int pixelV = vBuffer.get((y / 2) * vPlane.getRowStride() + (x / 2) * vPlane.getPixelStride()) & 0xff;
                int diffU = searchU - pixelU;
                int diffV = searchV - pixelV;
                int distanceSquared = diffU * diffU + diffV * diffV;
                if (distanceSquared <= distanceThresholdSquared) {
                    windowFillCount += lineAndColumnFeed * lineAndColumnFeed;
                }
            }
        }
        int windowFill = 0;
        if (windowResolution > 0) {
            windowFill = 100 * windowFillCount / windowResolution;
        }
        // Arm trigger when windowFill falls below triggerFillResetThreshold and
        // at least triggerResetTime has passed since it was at or above triggerFillResetThreshold
        if (windowFill >= Math.min(cameraSettings.triggerFillThreshold,
                cameraSettings.triggerFillResetThreshold)) {
            triggerResetTimeRunningSinceMonotonicTime = monotonicTime;
        } else if (monotonicTime - triggerResetTimeRunningSinceMonotonicTime > cameraSettings.triggerResetTime) {
            triggerArmed = true;
        }
        boolean triggered = triggerArmed && windowFill >= cameraSettings.triggerFillThreshold;
        if (triggered) {
            triggerArmed = false;
            try {
                // write unix time stamp
                detectionLog.write(String.format(Locale.US, "%d\n", wallClockTime / 1000)
                        .getBytes(FILE_CHARSET));
                detectionLog.flush();
                detectionLog.getFD().sync();
            } catch (IOException e) {
                fatal("failed to write detection log file", e);
            }
        }

        if (externalCameraImageListener.size() > 0) {
            Bitmap grayscale = Bitmap.createBitmap(image.getWidth(), image.getHeight(), Bitmap.Config.ALPHA_8);
            if (yPlane.getPixelStride() == 1 && yPlane.getRowStride() == image.getWidth()) {
                grayscale.copyPixelsFromBuffer(yBuffer);
            } else {
                ByteBuffer grayscaleBuffer = ByteBuffer.allocate(image.getWidth() * image.getHeight());
                if (yPlane.getPixelStride() == 1) {
                    for (int y = 0; y < image.getHeight(); y++) {
                        yBuffer.position(y * yPlane.getRowStride());
                        yBuffer.get(grayscaleBuffer.array(), y * image.getWidth(), image.getWidth());
                    }
                } else {
                    for (int y = 0; y < image.getHeight(); y++) {
                        for (int x = 0; x < image.getWidth(); x++) {
                            grayscaleBuffer.put(yBuffer.get(y * yPlane.getRowStride() + x * yPlane.getPixelStride()));
                        }
                    }
                }
                yBuffer.rewind();
                grayscaleBuffer.rewind();
                grayscale.copyPixelsFromBuffer(grayscaleBuffer);
            }
            for (CameraImageListener listener : externalCameraImageListener) {
                listener.onCameraImage(grayscale, windowFill, triggered, fps);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Populate preferences
        PreferenceManager.setDefaultValues(this, R.xml.root_preferences, true);
        // Create notification for foreground service
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 0);
        notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("Camera Service")
                .setContentText("working…")
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build();
        // Create wake lock
        PowerManager powerManager = getSystemService(PowerManager.class);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_ID);
        // Create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL_ID,
                    "Camera Service", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Notifications from camera service");
            notificationManager.createNotificationChannel(channel);
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        cameraSettings = new SettingsActivity.CameraSettings(sharedPreferences);
    }

    private void startCamera() {
        if (ActivityCompat.checkSelfPermission(this,
                Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            fatal("no camera permission");
        }
        CameraManager manager = getSystemService(CameraManager.class);
        String cameraId = null;
        try {
            for (String id : manager.getCameraIdList()) {
                CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_BACK) {
                    cameraId = id;
                    Size[] outputSizes = characteristics.get(CameraCharacteristics
                            .SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(CAMERA_IMAGE_FORMAT);
                    if (outputSizes == null) {
                        fatal("image format not supported by camera");
                        throw new AssertionError("unreachable"); // fixes compiler warning
                    }
                    for (Size size : outputSizes) {
                        int resolutionDiff = Math.abs(PREFERRED_CAMERA_IMAGE_RESOLUTION -
                                size.getWidth() * size.getHeight());
                        if (cameraImageSize == null ||
                                resolutionDiff < Math.abs(PREFERRED_CAMERA_IMAGE_RESOLUTION -
                                        cameraImageSize.getWidth() * cameraImageSize.getHeight())) {
                            cameraImageSize = size;
                        }
                    }
                    if (cameraImageSize == null) {
                        fatal("no image size found");
                    }
                    activeCameraCharacteristics = characteristics;
                    break;
                }
            }
            if (cameraId == null) {
                fatal("no camera found");
            }
            manager.openCamera(cameraId, cameraDeviceStateCallback, null);
        } catch (CameraAccessException e) {
            fatal("failed to open camera", e);
        }
    }

    private void fatal(@NonNull String msg, @Nullable Throwable tr) {
        Log.e(TAG, msg, tr);
        System.exit(1);
    }

    private void fatal(@NonNull String msg) {
        fatal(msg, null);
    }

    public interface CameraImageListener extends EventListener {
        void onCameraImage(@Nullable Bitmap image, int windowFill, boolean triggered, float fps);
    }

    public class CameraBinder extends Binder {
        public void registerCameraImageListener(CameraImageListener listener) {
            externalCameraImageListener.add(listener);
        }

        public void removeCameraImageListener(CameraImageListener listener) {
            externalCameraImageListener.remove(listener);
        }

        public void requestDump() {
            dumpRequested = true;
        }
    }
}
