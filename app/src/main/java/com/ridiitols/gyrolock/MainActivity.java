package com.ridiitols.gyrolock;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.util.Size;
import android.util.Log;
import android.os.Environment;
import android.provider.MediaStore;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.CameraCaptureSession;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.ColorDrawable;
import android.widget.SeekBar;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraInfo;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.camera2.interop.Camera2CameraControl;
import androidx.camera.camera2.interop.Camera2CameraInfo;
import androidx.camera.camera2.interop.Camera2Interop;
import androidx.camera.camera2.interop.CaptureRequestOptions;
import androidx.camera.core.MeteringPoint;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.AspectRatioStrategy;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.core.ConcurrentCamera;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;


import java.io.File;
import java.io.FileWriter;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity implements SensorEventListener {
    private static final int REQ = 41;
    private PreviewView previewView;
    private LiveFilterPreviewView filteredPreview;
    private ImageAnalysis imageAnalysis;
    private TextView status;
    private Button recordButton;
    private VideoCapture<Recorder> videoCapture;
    private ImageCapture imageCapture;
    private boolean motionPhotoPending = false;
    private boolean motionHdrForCapture = false;
    private boolean motionCoolForCapture = false;
    private boolean motionRatio916ForCapture = false;
    private File motionStillFile = null;
    private long shutterTimestampNs = 0L;
    private long captureRequestTimestampNs = 0L;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Camera camera;
    // v3.0.30 experiment: concurrent rear-camera session (main + independent ultrawide).
    private Camera concurrentMainCamera, concurrentUltraCamera;
    private Preview concurrentUltraPreview;
    private VideoCapture<Recorder> concurrentUltraVideoCapture;
    private ImageCapture concurrentUltraImageCapture;
    private PreviewView concurrentUltraPreviewView;
    private boolean concurrentRearCamerasActive = false;
    private String concurrentDiagnostic = "Belum diuji";
    private String concurrentMainCameraId = null;
    private ProcessCameraProvider cameraProvider;
    private Preview previewUseCase;
    private VideoCapture<Recorder> mainVideoCaptureForConcurrent;
    private ImageCapture mainImageCaptureForConcurrent;
    private String independentUltraWideCameraId = null;
    private volatile String lastActivePhysicalCameraId = null;
    private static final String CAMERA_DIAG_TAG = "GyroLockOptical";
    private boolean digitalMode = true;
    private boolean lensModeSwitchPending = false;
    private boolean pendingDigitalMode = true;
    // OPTICAL mode may select the independently exposed ultrawide camera when the logical
    // back camera cannot zoom below 1.0x. This switch is only allowed before a Motion Photo.
    private boolean opticalUltraWideActive = false;
    private boolean opticalLensSwitchPending = false;
    private boolean pendingOpticalUltraWide = false;
    private float pendingOpticalZoom = 1.0f;
    // If the user taps a physical lens while a Motion Photo is being captured/encoded,
    // queue the request and apply it only after the still+clip transaction is complete.
    private boolean opticalSwitchQueuedAfterMotionPhoto = false;
    private Button lensModeButton;
    private Button zoom05Button, zoom1Button, zoom2Button, zoom4Button, flashButton, hdrButton, filterButton, ratioButton, timerButton;
    private int captureTimerSeconds = 0;
    private boolean motionTimerCapture = false;
    private boolean countdownTorchOn = false;
    private long countdownEndsAtMs = 0L;
    private final Runnable countdownTick = new Runnable() {
        @Override public void run() {
            if (countdownEndsAtMs <= 0L) return;
            long remaining = Math.max(0L, countdownEndsAtMs - SystemClock.elapsedRealtime());
            if (remaining <= 0L) {
                countdownEndsAtMs = 0L;
                setCountdownTorch(false);
                if (timerButton != null) timerButton.setText("◷ " + (captureTimerSeconds == 0 ? "OFF" : captureTimerSeconds + "s"));
                takeMotionPhoto();
                return;
            }
            long remainingSeconds = (remaining + 999L) / 1000L;
            if (timerButton != null) timerButton.setText("" + remainingSeconds);
            long elapsed = captureTimerSeconds * 1000L - remaining;
            long interval;
            if (captureTimerSeconds == 3 || remaining <= 3000L) interval = 250L;
            else interval = (elapsed % 1000L < 140L) ? 140L : 860L;
            boolean shouldLight = captureTimerSeconds == 3 || remaining <= 3000L
                    ? ((SystemClock.elapsedRealtime() / 250L) % 2L == 0L)
                    : (elapsed % 1000L < 140L);
            setCountdownTorch(shouldLight);
            mainHandler.postDelayed(this, Math.max(40L, interval));
        }
    };
    private android.animation.ValueAnimator zoomAnimator;
    private Button exposureLockButton;
    private SeekBar exposureSeekBar;
    private TextView exposureLabel;
    private FrameLayout previewContainer;
    private FocusRingView focusRing;
    private Camera2CameraControl camera2Control;
    private boolean hdrSceneSupported = false;
    private boolean hdrEnabled = false;
    private boolean dramaticCoolEnabled = false;
    private String motionPhotoStamp = null;
    private long recordingStopRequestedNs = 0L;
    private int minExposureIndex = 0, maxExposureIndex = 0, currentExposureIndex = 0;
    private boolean exposureLocked = false;
    private boolean updatingExposureUi = false;
    // Still-photo flash mode; CameraX fires the LED once, synchronized to the still exposure.
    private boolean photoFlashEnabled = false;
    // Remember the last touch-to-focus point for shutter-time AF.
    private float lastFocusX = -1f, lastFocusY = -1f;
    private boolean focusCaptureFinished = false;
    // Latest lens focus distance reported by Camera2 (diopters). 0 = infinity; larger = closer.
    private volatile float lastReportedFocusDistance = -1f;
    private long recordingStartedNs = 0L;
    private float selectedZoom = 1.0f;
    private boolean motionRatio916 = false;
    private Recording recording;
    // v3.0.31 experiment: keep the ultrawide video stream rolling at the same time as main.
    private Recording concurrentUltraRecording;
    private File concurrentUltraRawFile;
    private long concurrentUltraRecordingStartedNs = 0L;
    private boolean concurrentUltraFinalizeSeen = false;
    private boolean concurrentUltraRecordingFailed = false;
    private boolean motionCaptureUltraWide = false;
    // Entries are {elapsedRealtimeNanos, 1 if ultrawide is selected else 0}.
    private final List<long[]> concurrentLensSwitches = new ArrayList<>();
    private ExecutorService cameraExecutor;
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    // Quaternion state from the single selected GeoMagnetic Rotation Vector sensor.
    private final double[] previousRotationQuaternion = new double[4]; // x, y, z, w
    private boolean hasPreviousRotationQuaternion = false;
    private long previousRotationTimestampNs = 0L;
    private double[] latestMotionRates = new double[3];
    private final List<GyroSample> gyroSamples = new ArrayList<>();
    private long gyroStartNs = 0L;
    private long gyroEndNs = 0L; // monotonic anchor near the actual encoded clip end
    private long lastSavedNs = 0L;
    private boolean collectingGyro = false;
    // Live gyro preview state. This transforms only the preview surface; the saved
    // recording is stabilized from the same gyro stream after recording stops.
    private long liveLastTimestampNs = 0L;
    private double liveRateX = 0, liveRateY = 0, liveRateZ = 0;
    private double liveAngleX = 0, liveAngleY = 0, liveAngleZ = 0;
    private double liveSmoothX = 0, liveSmoothY = 0, liveSmoothZ = 0;
    private double liveAnchorX = 0, liveAnchorY = 0, liveAnchorZ = 0;
    private boolean liveAnglesInitialized = false;
    private File sessionDir;
    private File pendingRawFile;
    private File pendingCsvFile;
    // Experimental optical-switch history: retain the previous lens buffer so the final
    // Motion Photo can include frames recorded before a physical lens change.
    private final List<File> opticalLensSegments = new ArrayList<>();

    private final class FocusRingView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float cx, cy;
        private boolean visible = false;
        private final float density;
        FocusRingView(android.content.Context context) {
            super(context);
            density = getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2.0f * density);
            paint.setColor(Color.YELLOW);
        }
        void showAt(float x, float y) {
            cx = x; cy = y; visible = true; invalidate();
            removeCallbacks(hideRing);
            postDelayed(hideRing, 1800L);
        }
        private final Runnable hideRing = () -> { visible = false; invalidate(); };
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!visible) return;
            float r = 36.0f * density;
            canvas.drawCircle(cx, cy, r, paint);
            canvas.drawLine(cx - 8*density, cy, cx + 8*density, cy, paint);
            canvas.drawLine(cx, cy - 8*density, cx, cy + 8*density, paint);
        }
    }

    private final class LiveFilterPreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Object lock = new Object();
        private Bitmap pending;
        private long lastFrameNs = 0L;
        private int[] work = new int[0];

        LiveFilterPreviewView(android.content.Context context) {
            super(context);
            setWillNotDraw(false);
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
        }

        /**
         * Preview filter only. HDR is deliberately NOT previewed: HDR is a saved-photo
         * processing step and becomes visible in the resulting Motion Photo/JPEG in Gallery.
         * Dramatic Cool, however, is a live viewfinder effect and must be visible immediately.
         */
        void submit(ImageProxy image, boolean cool) {
            long now = SystemClock.elapsedRealtimeNanos();
            if (now - lastFrameNs < 55_000_000L) { image.close(); return; }
            lastFrameNs = now;
            try {
                ImageProxy.PlaneProxy pp = image.getPlanes()[0];
                java.nio.ByteBuffer buf = pp.getBuffer();
                int w = image.getWidth(), h = image.getHeight();
                if (w <= 0 || h <= 0 || (long) w * h > 2_000_000L) { image.close(); return; }
                int rowStride = pp.getRowStride();
                int pixelStride = pp.getPixelStride();
                if (pixelStride < 4) { image.close(); return; }
                int need = w * h;
                if (work.length < need) work = new int[need];
                for (int y = 0; y < h; y++) {
                    int rowBase = y * rowStride;
                    for (int x = 0; x < w; x++) {
                        int off = rowBase + x * pixelStride;
                        if (off + 3 >= buf.limit()) {
                            work[y * w + x] = Color.BLACK;
                            continue;
                        }
                        // CameraX OUTPUT_IMAGE_FORMAT_RGBA_8888 = R,G,B,A.
                        double r = (buf.get(off) & 0xff) / 255.0;
                        double g = (buf.get(off + 1) & 0xff) / 255.0;
                        double b = (buf.get(off + 2) & 0xff) / 255.0;
                        int a = buf.get(off + 3) & 0xff;

                        if (cool) {
                            double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                            // Match the saved-photo Dramatic Cool grade below.
                            double shadow = coolSmooth(0.72, 0.10, l);
                            double contrast = 1.055;
                            double mid = 0.50;
                            r = clamp(mid + (r - mid) * contrast, 0, 1);
                            g = clamp(mid + (g - mid) * contrast, 0, 1);
                            b = clamp(mid + (b - mid) * contrast, 0, 1);
                            double coolStrength = 0.030 + 0.032 * shadow;
                            r = clamp(r - coolStrength * 0.60, 0, 1);
                            g = clamp(g + coolStrength * 0.06, 0, 1);
                            b = clamp(b + coolStrength, 0, 1);
                            double gray = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                            double sat = 1.105;
                            r = clamp(gray + (r - gray) * sat, 0, 1);
                            g = clamp(gray + (g - gray) * sat, 0, 1);
                            b = clamp(gray + (b - gray) * sat, 0, 1);
                        }
                        work[y * w + x] = (a << 24)
                                | ((int) (r * 255.0 + 0.5) << 16)
                                | ((int) (g * 255.0 + 0.5) << 8)
                                | (int) (b * 255.0 + 0.5);
                    }
                }
                Bitmap bmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmap.setPixels(work, 0, w, 0, 0, w, h);
                int rot = image.getImageInfo().getRotationDegrees();
                if (rot != 0) {
                    Matrix m = new Matrix(); m.postRotate(rot);
                    Bitmap rotated = Bitmap.createBitmap(bmap, 0, 0, bmap.getWidth(), bmap.getHeight(), m, true);
                    if (rotated != bmap) bmap.recycle();
                    bmap = rotated;
                }
                synchronized (lock) {
                    Bitmap old = pending;
                    pending = bmap;
                    if (old != null && !old.isRecycled()) old.recycle();
                }
                postInvalidateOnAnimation();
            } catch (Throwable ignored) {
                // Never allow the live filter to stop CameraX analysis.
            } finally {
                image.close();
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap b;
            synchronized (lock) { b = pending; pending = null; }
            if (b != null) {
                Bitmap old = (Bitmap) getTag();
                setTag(b);
                if (old != null && old != b && !old.isRecycled()) old.recycle();
            }
            Bitmap current = (Bitmap) getTag();
            if (current == null || current.isRecycled()) return;
            float sx = getWidth() / (float) current.getWidth();
            float sy = getHeight() / (float) current.getHeight();
            float scale = Math.max(sx, sy);
            float dw = current.getWidth() * scale, dh = current.getHeight() * scale;
            float left = (getWidth() - dw) * 0.5f, top = (getHeight() - dh) * 0.5f;
            canvas.drawBitmap(current, null, new android.graphics.RectF(left, top, left + dw, top + dh), paint);
        }

        private double coolSmooth(double e0, double e1, double x) {
            double t = clamp((x - e0) / (e1 - e0), 0, 1);
            return t * t * (3 - 2 * t);
        }
        private double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    static class GyroSample {
        // Sensor timestamps use Android's monotonic clock, which lets us align samples
        // to the recording start rather than relying on callback arrival time.
        final long timestampNs;
        final double x, y, z;
        GyroSample(long timestampNs, double x, double y, double z) {
            this.timestampNs = timestampNs; this.x = x; this.y = y; this.z = z;
        }
        double secondsFrom(long startNs) { return (timestampNs - startNs) / 1_000_000_000.0; }
    }

    @Override protected void onPause() {
        if (countdownEndsAtMs > 0L) { countdownEndsAtMs = 0L; mainHandler.removeCallbacks(countdownTick); setCountdownTorch(false); }
        super.onPause();
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraExecutor = Executors.newSingleThreadExecutor();
        sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        // Samsung A16 stabilization uses exactly ONE motion source: the
        // GeoMagnetic Rotation Vector. Do not mix its fused orientation with
        // accelerometer, magnetometer, Orientation Sensor, or another vector.
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10151D);

        // Top quick-control row: continuous torch (not a one-shot photo flash).
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topRow.setPadding(8, 2, 8, 2);
        flashButton = new Button(this);
        flashButton.setText("⚡̸ FLASH");
        flashButton.setOnClickListener(v -> togglePhotoFlash());
        topRow.addView(flashButton, new LinearLayout.LayoutParams(0, -2, 1));
        hdrButton = new Button(this);
        hdrButton.setText("HDR̸");
        hdrButton.setOnClickListener(v -> toggleHdr());
        topRow.addView(hdrButton, new LinearLayout.LayoutParams(0, -2, 1));
        filterButton = new Button(this);
        filterButton.setText("◉̸ FILTER");
        filterButton.setOnClickListener(v -> toggleDramaticCool());
        topRow.addView(filterButton, new LinearLayout.LayoutParams(0, -2, 1));
        lensModeButton = new Button(this);
        lensModeButton.setText("DIGITAL");
        lensModeButton.setOnClickListener(v -> toggleLensMode());
        topRow.addView(lensModeButton, new LinearLayout.LayoutParams(0, -2, 1));
        timerButton = new Button(this);
        timerButton.setText("◷ OFF");
        timerButton.setOnClickListener(v -> showTimerMenu());
        topRow.addView(timerButton, new LinearLayout.LayoutParams(0, -2, 1));
        styleGlassButton(flashButton); styleGlassButton(hdrButton); styleGlassButton(filterButton);
        styleGlassButton(lensModeButton); styleGlassButton(timerButton);
        root.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

        previewContainer = new FrameLayout(this);
        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewContainer.addView(previewView, new FrameLayout.LayoutParams(-1, -1));
        filteredPreview = new LiveFilterPreviewView(this);
        filteredPreview.setVisibility(View.GONE);
        filteredPreview.setBackground(new ColorDrawable(Color.BLACK));
        previewContainer.addView(filteredPreview, new FrameLayout.LayoutParams(-1, -1));
        focusRing = new FocusRingView(this);
        focusRing.setClickable(false);
        focusRing.setFocusable(false);
        previewContainer.addView(focusRing, new FrameLayout.LayoutParams(-1, -1));
        root.addView(previewContainer, new LinearLayout.LayoutParams(-1, 0, 1));
        // Tap anywhere in preview: show a focus ring and request AF/AE metering.
        previewView.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                focusRing.showAt(event.getX(), event.getY());
                focusAt(event.getX(), event.getY());
            }
            return true;
        });
        filteredPreview.setOnTouchListener((view, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                focusRing.showAt(event.getX(), event.getY());
                focusAt(event.getX(), event.getY());
            }
            return true;
        });

        LinearLayout zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        zoomRow.setPadding(8, 6, 8, 4);
        zoom05Button = new Button(this);
        zoom1Button = new Button(this);
        zoom2Button = new Button(this);
        zoom4Button = new Button(this);
        zoom05Button.setText("0,5");
        zoom1Button.setText("1");
        zoom2Button.setText("2");
        zoom4Button.setVisibility(View.GONE);
        // Camera-style direct zoom stops: one tap immediately applies the selected ratio.
        zoom05Button.setOnClickListener(v -> setZoom(0.5f));
        zoom1Button.setOnClickListener(v -> setZoom(1.0f));
        zoom2Button.setOnClickListener(v -> setZoom(2.0f));
        styleZoomButton(zoom05Button); styleZoomButton(zoom1Button); styleZoomButton(zoom2Button);
        LinearLayout.LayoutParams z05 = new LinearLayout.LayoutParams(dp(42), dp(42));
        LinearLayout.LayoutParams z1 = new LinearLayout.LayoutParams(dp(42), dp(42));
        LinearLayout.LayoutParams z2 = new LinearLayout.LayoutParams(dp(42), dp(42));
        z05.setMargins(dp(10), 0, dp(10), 0); z1.setMargins(dp(10), 0, dp(10), 0);
        z2.setMargins(dp(10), 0, dp(10), 0);
        zoomRow.addView(zoom05Button, z05); zoomRow.addView(zoom1Button, z1); zoomRow.addView(zoom2Button, z2);
        root.addView(zoomRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ratioRow = new LinearLayout(this);
        ratioRow.setOrientation(LinearLayout.HORIZONTAL);
        ratioRow.setGravity(Gravity.CENTER);
        ratioRow.setPadding(8, 0, 8, 4);
        ratioButton = new Button(this);
        ratioButton.setText("3:4");
        ratioButton.setOnClickListener(v -> toggleMotionRatio());
        styleGlassButton(ratioButton);
        ratioRow.addView(ratioButton, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(ratioRow, new LinearLayout.LayoutParams(-1, -2));

        // Exposure compensation controls. The AE-lock button freezes the camera's
        // auto-exposure state after the user has chosen the desired brightness.
        LinearLayout exposureRow = new LinearLayout(this);
        exposureRow.setOrientation(LinearLayout.HORIZONTAL);
        exposureRow.setGravity(Gravity.CENTER_VERTICAL);
        exposureRow.setPadding(12, 0, 12, 0);
        exposureLabel = new TextView(this);
        exposureLabel.setTextColor(Color.WHITE);
        exposureLabel.setTextSize(12);
        exposureLabel.setText("EV 0.0");
        exposureRow.addView(exposureLabel, new LinearLayout.LayoutParams(-2, -2));
        exposureSeekBar = new SeekBar(this);
        exposureSeekBar.setMax(100);
        exposureSeekBar.setProgress(50);
        exposureRow.addView(exposureSeekBar, new LinearLayout.LayoutParams(0, -2, 1));
        exposureSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser || updatingExposureUi || camera == null || exposureLocked) return;
                int range = maxExposureIndex - minExposureIndex;
                int index = minExposureIndex + Math.round(range * (progress / 100.0f));
                applyExposureIndex(index);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        exposureLockButton = new Button(this);
        exposureLockButton.setText("AE LOCK");
        exposureLockButton.setOnClickListener(v -> toggleExposureLock());
        styleGlassButton(exposureLockButton);
        exposureRow.addView(exposureLockButton, new LinearLayout.LayoutParams(-2, -2));
        root.addView(exposureRow, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextColor(0xFFFFFFFF);
        status.setTextSize(15);
        status.setGravity(Gravity.CENTER);
        status.setPadding(16, 14, 16, 14);
        status.setText("Kamera • Siap merekam");
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        // iPhone-inspired centered shutter: circular outer ring with a clear capture action.
        LinearLayout shutterRow = new LinearLayout(this);
        shutterRow.setGravity(Gravity.CENTER);
        shutterRow.setPadding(8, 8, 8, 16);
        recordButton = new Button(this);
        recordButton.setText("●");
        recordButton.setTextSize(30);
        recordButton.setTextColor(0xFF202020);
        recordButton.setContentDescription("Ambil Motion Photo");
        android.graphics.drawable.GradientDrawable shutterBg = new android.graphics.drawable.GradientDrawable();
        shutterBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        shutterBg.setColor(0xFFF7F7F7);
        shutterBg.setStroke(5, 0xFF777777);
        recordButton.setBackground(shutterBg);
        recordButton.setOnClickListener(v -> startCaptureCountdown());
        int shutterSize = (int) (76 * getResources().getDisplayMetrics().density + 0.5f);
        LinearLayout.LayoutParams shutterParams = new LinearLayout.LayoutParams(shutterSize, shutterSize);
        shutterRow.addView(recordButton, shutterParams);
        root.addView(shutterRow, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);

        if (hasPermissions()) startCamera();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, REQ);
    }

    private boolean hasPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCamera() {
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                ProcessCameraProvider provider = ProcessCameraProvider.getInstance(this).get();
                cameraProvider = provider;
                independentUltraWideCameraId = findIndependentUltraWideCameraId(provider);
                Preview.Builder previewBuilder = new Preview.Builder();
                Camera2Interop.Extender<Preview> camera2PreviewExtender =
                        new Camera2Interop.Extender<>(previewBuilder);
                camera2PreviewExtender.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession session,
                            android.hardware.camera2.CaptureRequest request, TotalCaptureResult result) {
                        Float d = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                        if (d != null && Float.isFinite(d)) lastReportedFocusDistance = d;
                        // Diagnostic only: when the OEM exposes a logical multi-camera,
                        // Android 10+ reports which physical sensor currently backs it.
                        if (Build.VERSION.SDK_INT >= 29) {
                            try {
                                String active = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                                if (active != null && !active.equals(lastActivePhysicalCameraId)) {
                                    lastActivePhysicalCameraId = active;
                                    Log.i(CAMERA_DIAG_TAG, "Logical camera active physical ID=" + active);
                                }
                            } catch (Exception ignored) { }
                        }
                    }
                });
                Preview preview = previewBuilder.build();
                previewUseCase = preview;
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                // Prefer an SD rolling buffer so the camera has more stream bandwidth available for the high-resolution still; the final Motion Photo video is re-encoded to the selected portrait ratio.
                Recorder recorder = new Recorder.Builder()
                        .setQualitySelector(QualitySelector.fromOrderedList(
                                Arrays.asList(Quality.SD, Quality.HD),
                                FallbackStrategy.lowerQualityThan(Quality.HD)))
                        .build();
                videoCapture = VideoCapture.withOutput(recorder);
                // GCam-style capture experiment: prioritize the highest 4:3 JPEG size exposed by
                // this camera, rather than only requesting 4000x3000. The actual size
                // still depends on the sensor and the concurrent Preview + VideoCapture
                // + ImageCapture stream combination supported by the device.
                ResolutionSelector stillResolution = new ResolutionSelector.Builder()
                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        // Prefer full-resolution JPEG sizes (>=8 MP) when CameraX exposes
                        // them for this use-case. Returning only these candidates prevents a
                        // small 1080p stream from silently winning over the sensor JPEG stream.
                        // If the concurrent video+still session cannot bind, the catch below
                        // retries with CameraX's unrestricted highest-available selector.
                        .setResolutionFilter((supportedSizes, rotationDegrees) -> {
                            List<Size> highRes = new ArrayList<>();
                            for (Size size : supportedSizes) {
                                long pixels = (long) size.getWidth() * (long) size.getHeight();
                                double ratio = size.getWidth() / (double) Math.max(1, size.getHeight());
                                if (pixels >= 8_000_000L && ratio >= 1.25 && ratio <= 1.50) highRes.add(size);
                            }
                            highRes.sort((a, b) -> Long.compare(
                                    (long)b.getWidth() * b.getHeight(), (long)a.getWidth() * a.getHeight()));
                            return highRes.isEmpty() ? supportedSizes : highRes;
                        })
                        .build();
                ImageCapture.Builder stillBuilder = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                        .setResolutionSelector(stillResolution);
                // Observe the actual lens position reported by the camera. This lets the
                // post-shutter pulse return to the focus value that was really in use,
                // instead of guessing it from the tapped focus point.
                Camera2Interop.Extender<ImageCapture> camera2ImageExtender =
                        new Camera2Interop.Extender<>(stillBuilder);
                camera2ImageExtender.setSessionCaptureCallback(new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureCompleted(CameraCaptureSession session,
                            android.hardware.camera2.CaptureRequest request, TotalCaptureResult result) {
                        Float d = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
                        if (d != null && Float.isFinite(d)) lastReportedFocusDistance = d;
                    }
                });
                // Detect HDR scene support on the rear camera. Apply it dynamically through
                // Camera2CameraControl so it can be disabled whenever flash is enabled.
                hdrSceneSupported = false;
                try {
                    for (androidx.camera.core.CameraInfo info : provider.getAvailableCameraInfos()) {
                        Integer facing = Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        int[] scenes = Camera2CameraInfo.from(info).getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
                        if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK && scenes != null) {
                            for (int scene : scenes) if (scene == CaptureRequest.CONTROL_SCENE_MODE_HDR) hdrSceneSupported = true;
                        }
                    }
                } catch (Exception ignored) { hdrSceneSupported = false; }
                hdrEnabled = false;
                imageCapture = stillBuilder.build();
                // 3.0.10: remove the per-frame RGBA filter analyzer. It caused heavy
                // preview stutter when COOL was enabled. COOL is now post-capture only;
                // the native CameraX preview stays smooth.
                imageAnalysis = null;
                provider.unbindAll();
                boolean concurrentBound = false;
                // Experimental path: ask CameraX to bind the rear main and independent
                // ultrawide cameras in ONE concurrent session. This avoids stop/unbind/rebind
                // during a lens tap. CameraX/device combinations may reject this use-case set.
                try {
                    concurrentBound = tryBindConcurrentRearCameras(provider, preview, videoCapture, imageCapture);
                } catch (Exception concurrentError) {
                    Log.w(CAMERA_DIAG_TAG, "Concurrent rear camera bind failed; falling back to single camera", concurrentError);
                    concurrentBound = false;
                }
                if (!concurrentBound) {
                    String physicalProbe = probeLogicalPhysicalRearCameras();
                    concurrentDiagnostic = concurrentDiagnostic + " | " + physicalProbe;
                    Log.e(CAMERA_DIAG_TAG, "DIAGNOSTIC SUMMARY: " + concurrentDiagnostic);
                    Toast.makeText(this, "SIMULTAN GAGAL: " + concurrentDiagnostic, Toast.LENGTH_LONG).show();
                    if (status != null) status.setText("SIMULTAN OFF • " + concurrentDiagnostic);
                    provider.unbindAll();
                    try {
                        camera = provider.bindToLifecycle(this, getSelectedCameraSelector(), preview, videoCapture, imageCapture);
                    } catch (Exception lensBindError) {
                    // Some vendors expose a separate camera ID but do not support the full
                    // Preview + VideoCapture + high-resolution ImageCapture combination on it.
                    independentUltraWideCameraId = null;
                    opticalUltraWideActive = false;
                    digitalMode = false;
                    provider.unbindAll();
                    try {
                        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, videoCapture, imageCapture);
                    } catch (Exception highResolutionCombinationError) {
                        // Explicit high-resolution candidates can be unsupported while video
                        // is active. Fall back safely, retaining the largest size CameraX can
                        // bind in this stream combination instead of failing camera startup.
                        provider.unbindAll();
                        ResolutionSelector fallbackStillResolution = new ResolutionSelector.Builder()
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                                .build();
                        imageCapture = new ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                                .setResolutionSelector(fallbackStillResolution)
                                .build();
                        camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, videoCapture, imageCapture);
                    }
                    Toast.makeText(this, "Kamera memakai konfigurasi yang kompatibel; resolusi foto aktual akan ditampilkan saat memotret.", Toast.LENGTH_LONG).show();
                    }
                }
                try {
                    camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                } catch (Exception ignored) { camera2Control = null; }
                setupExposureControls();
                applyCameraCaptureOptions();
                boolean hasFlash = camera.getCameraInfo().hasFlashUnit();
                // Keep the control tappable in DIGITAL mode: if the separately bound
                // ultra-wide has no LED capability, the handler can switch to OPTICAL.
                flashButton.setEnabled(hasFlash || digitalMode);
                flashButton.setText(hasFlash ? "⚡̸ FLASH"
                        : digitalMode ? "⚡ FLASH → OPTICAL" : "⚡ FLASH TIDAK ADA");
                photoFlashEnabled = false;
                hdrButton.setEnabled(true);
                hdrButton.setText(hdrEnabled ? "HDR ✓" : "HDR̸");
                filterButton.setEnabled(true);
                filterButton.setText("◉̸ FILTER");
                updateLiveFilterVisibility();
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_OFF);
                Sensor primaryMotionSensor = rotationSensor;
                if (primaryMotionSensor != null) {
                    sensorManager.registerListener(this, primaryMotionSensor, SensorManager.SENSOR_DELAY_FASTEST);
                }
                boolean hasMotionSource = primaryMotionSensor != null;
                if (hasMotionSource) {
                    previewView.setScaleX(1.07f);
                    previewView.setScaleY(1.07f);
                }
                String motionSource = hasMotionSource ? "GeoMagnetic Rotation Vector (single sensor)" : "GeoMagnetic Rotation Vector tidak tersedia";
                status.setText("Motion Photo siap • HDR software + Dramatic Cool tersedia • stabilisasi: " + motionSource);
                lensModeButton.setText(digitalMode ? "DIGITAL" : "OPTICAL");
                if (digitalMode) {
                    androidx.camera.core.ZoomState initialZoom = camera.getCameraInfo().getZoomState().getValue();
                    float initialRatio = independentUltraWideCameraId != null ? 0.5f
                            : (initialZoom == null ? 1.0f : initialZoom.getMinZoomRatio());
                    setZoom(initialRatio);
                }
                // Start a rolling low-resolution video buffer immediately so motion before
                // the shutter is available. The finalized clip is trimmed to a short window.
                mainHandler.postDelayed(() -> { if (recording == null && imageCapture != null) startRollingVideo(); }, 400);
            } catch (Exception e) {
                status.setText("Kamera gagal dibuka: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * Experimental dual-rear-camera bind. Both cameras get a Preview + VideoCapture +
     * ImageCapture in the same CameraX concurrent session. A second PreviewView is kept
     * attached but hidden until the user selects the ultrawide. If the device does not
     * expose a concurrent main/ultrawide pair, or rejects these stream combinations, the
     * caller uses the existing single-camera fallback.
     */
    /**
     * Probe whether the device exposes a logical rear camera containing physical IDs.
     * This does not pretend to enable concurrency: it tells us whether a Camera2
     * physical-output session is worth implementing for this handset.
     */
    private String probeLogicalPhysicalRearCameras() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.i(CAMERA_DIAG_TAG, "Logical multi-camera physical-ID probe requires Android 9+");
            return "logical-camera probe perlu Android 9+";
        }
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
            if (manager == null) return "CameraManager tidak tersedia";
            List<String> logicalRear = new ArrayList<>();
            List<String> rearSummary = new ArrayList<>();
            for (String id : manager.getCameraIdList()) {
                try {
                    android.hardware.camera2.CameraCharacteristics ch = manager.getCameraCharacteristics(id);
                    Integer facing = ch.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                    if (facing == null || facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue;
                    int[] caps = ch.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
                    boolean logical = false;
                    if (caps != null) for (int cap : caps) {
                        if (cap == android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                            logical = true; break;
                        }
                    }
                    Set<String> physical = ch.getPhysicalCameraIds();
                    String summary = "id=" + id + ",logical=" + logical + ",physical=" +
                            (physical == null ? "[]" : physical.toString());
                    rearSummary.add(summary);
                    Log.i(CAMERA_DIAG_TAG, "Rear camera capability: " + summary);
                    if (logical && physical != null && !physical.isEmpty()) logicalRear.add(id + "->" + physical);
                } catch (Exception cameraError) {
                    Log.w(CAMERA_DIAG_TAG, "Unable to inspect rear camera id=" + id, cameraError);
                }
            }
            String result;
            if (!logicalRear.isEmpty()) {
                result = "LOGICAL_PHYSICAL_FOUND=" + logicalRear;
                Log.i(CAMERA_DIAG_TAG, "Logical rear camera physical IDs found: " + logicalRear +
                        ". Physical output switching still requires a dedicated Camera2 session implementation.");
            } else {
                result = "LOGICAL_PHYSICAL_NOT_EXPOSED;rear=" + rearSummary;
                Log.w(CAMERA_DIAG_TAG, "No logical rear camera with physical IDs exposed by Camera2.");
            }
            return result.length() > 180 ? result.substring(0, 180) : result;
        } catch (Exception e) {
            Log.w(CAMERA_DIAG_TAG, "Logical/physical camera probe failed", e);
            return "logical-camera probe error=" + e.getClass().getSimpleName();
        }
    }

    private boolean tryBindConcurrentRearCameras(ProcessCameraProvider provider,
            Preview mainPreview, VideoCapture<Recorder> mainVideo, ImageCapture mainStill) {
        if (Build.VERSION.SDK_INT < 30 || independentUltraWideCameraId == null) {
            concurrentDiagnostic = Build.VERSION.SDK_INT < 30
                    ? "Perlu Android 11+ (API 30)" : "ID kamera ultrawide terpisah tidak ditemukan";
            Log.w(CAMERA_DIAG_TAG, "Concurrent bind skipped: " + concurrentDiagnostic);
            return false;
        }

        // Gather every rear camera ID other than the selected ultrawide. Some vendors
        // expose several logical/physical rear IDs; trying only the first one can reject
        // an otherwise supported pair. Prefer IDs explicitly advertised as concurrent.
        List<String> rearIds = new ArrayList<>();
        Map<String, Float> focalById = new HashMap<>();
        Set<Set<String>> advertisedPairs = new HashSet<>();
        try {
            android.hardware.camera2.CameraManager cm = (android.hardware.camera2.CameraManager)
                    getSystemService(android.content.Context.CAMERA_SERVICE);
            if (cm != null) advertisedPairs = cm.getConcurrentCameraIds();
        } catch (Exception e) {
            Log.w(CAMERA_DIAG_TAG, "Unable to query concurrent camera IDs", e);
        }
        final Set<Set<String>> concurrentPairs = advertisedPairs;
        float ultraFocal = Float.MAX_VALUE;
        for (CameraInfo info : provider.getAvailableCameraInfos()) {
            try {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                String id = c2.getCameraId();
                Integer facing = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                float[] fs = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (id == null || facing == null || facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) continue;
                float focal = Float.MAX_VALUE;
                if (fs != null) for (float f : fs) if (f > 0f && f < focal) focal = f;
                if (id.equals(independentUltraWideCameraId)) {
                    ultraFocal = focal;
                } else if (!rearIds.contains(id)) {
                    rearIds.add(id);
                    focalById.put(id, focal);
                }
            } catch (Exception ignored) { }
        }
        if (rearIds.isEmpty()) {
            concurrentDiagnostic = "Tidak ada ID kamera belakang kedua";
            Log.w(CAMERA_DIAG_TAG, "Concurrent bind skipped: " + concurrentDiagnostic);
            return false;
        }
        concurrentDiagnostic = "Ditemukan " + rearIds.size() + " kandidat; pasangan resmi=" + concurrentPairs.size();
        final float targetMainFocal = ultraFocal == Float.MAX_VALUE ? 0f : Math.max(ultraFocal * 2f, ultraFocal + 1f);
        rearIds.sort((a, b) -> {
            boolean aAdvertised = false, bAdvertised = false;
            for (Set<String> pair : concurrentPairs) {
                if (pair.contains(a) && pair.contains(independentUltraWideCameraId)) aAdvertised = true;
                if (pair.contains(b) && pair.contains(independentUltraWideCameraId)) bAdvertised = true;
            }
            if (aAdvertised != bAdvertised) return aAdvertised ? -1 : 1;
            float af = focalById.containsKey(a) ? focalById.get(a) : Float.MAX_VALUE;
            float bf = focalById.containsKey(b) ? focalById.get(b) : Float.MAX_VALUE;
            float as = af == Float.MAX_VALUE ? Float.MAX_VALUE : Math.abs(af - targetMainFocal);
            float bs = bf == Float.MAX_VALUE ? Float.MAX_VALUE : Math.abs(bf - targetMainFocal);
            return Float.compare(as, bs);
        });

        concurrentUltraPreviewView = new PreviewView(this);
        concurrentUltraPreviewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        concurrentUltraPreviewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        // Keep the second surface alive from the beginning; alpha hides it without
        // toggling the surface lifecycle during lens changes.
        concurrentUltraPreviewView.setVisibility(View.VISIBLE);
        concurrentUltraPreviewView.setAlpha(0f);
        if (previewContainer != null) {
            previewContainer.addView(concurrentUltraPreviewView,
                    new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        }
        concurrentUltraPreview = new Preview.Builder().build();
        concurrentUltraPreview.setSurfaceProvider(concurrentUltraPreviewView.getSurfaceProvider());
        Recorder ultraRecorder = new Recorder.Builder()
                .setQualitySelector(QualitySelector.fromOrderedList(
                        Arrays.asList(Quality.SD, Quality.HD), FallbackStrategy.lowerQualityThan(Quality.HD)))
                .build();
        concurrentUltraVideoCapture = VideoCapture.withOutput(ultraRecorder);
        concurrentUltraImageCapture = new ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build();

        UseCaseGroup mainGroup = new UseCaseGroup.Builder()
                .addUseCase(mainPreview).addUseCase(mainVideo).addUseCase(mainStill).build();
        UseCaseGroup ultraGroup = new UseCaseGroup.Builder()
                .addUseCase(concurrentUltraPreview).addUseCase(concurrentUltraVideoCapture)
                .addUseCase(concurrentUltraImageCapture).build();
        final String ultraId = independentUltraWideCameraId;

        for (String candidateMainId : rearIds) {
            final String mainId = candidateMainId;
            CameraSelector mainSelector = new CameraSelector.Builder().addCameraFilter(infos -> {
                List<CameraInfo> filtered = new ArrayList<>();
                for (CameraInfo info : infos) {
                    try { if (mainId.equals(Camera2CameraInfo.from(info).getCameraId())) filtered.add(info); }
                    catch (Exception ignored) { }
                }
                return filtered;
            }).build();
            CameraSelector ultraSelector = new CameraSelector.Builder().addCameraFilter(infos -> {
                List<CameraInfo> filtered = new ArrayList<>();
                for (CameraInfo info : infos) {
                    try { if (ultraId.equals(Camera2CameraInfo.from(info).getCameraId())) filtered.add(info); }
                    catch (Exception ignored) { }
                }
                return filtered;
            }).build();
            Log.i(CAMERA_DIAG_TAG, "Trying concurrent rear pair main=" + mainId + ", ultraWide=" + ultraId
                    + ", advertised=" + concurrentPairs.stream().anyMatch(pair -> pair.contains(mainId) && pair.contains(ultraId)));
            try {
                provider.unbindAll();
                List<ConcurrentCamera.SingleCameraConfig> configs = Arrays.asList(
                        new ConcurrentCamera.SingleCameraConfig(mainSelector, mainGroup, this),
                        new ConcurrentCamera.SingleCameraConfig(ultraSelector, ultraGroup, this));
                ConcurrentCamera session = provider.bindToLifecycle(configs);
                List<Camera> cameras = session.getCameras();
                if (cameras == null || cameras.size() < 2) {
                    provider.unbindAll();
                    Log.w(CAMERA_DIAG_TAG, "Concurrent pair returned fewer than two Camera objects: " + mainId);
                    continue;
                }
                concurrentMainCamera = cameras.get(0);
                concurrentUltraCamera = cameras.get(1);
                mainVideoCaptureForConcurrent = mainVideo;
                mainImageCaptureForConcurrent = mainStill;
                camera = concurrentMainCamera;
                previewUseCase = mainPreview;
                videoCapture = mainVideo;
                imageCapture = mainStill;
                concurrentMainCameraId = mainId;
                concurrentRearCamerasActive = true;
                opticalUltraWideActive = false;
                Log.i(CAMERA_DIAG_TAG, "CONCURRENT rear cameras ACTIVE: main=" + mainId + ", ultrawide=" + ultraId);
                if (status != null) status.setText("Kamera simultan aktif • buffer 1× + 0,5×");
                return true;
            } catch (Exception e) {
                concurrentDiagnostic = "Ditolak pair " + mainId + "+" + ultraId + ": "
                        + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " - " + e.getMessage());
                if (concurrentDiagnostic.length() > 150) concurrentDiagnostic = concurrentDiagnostic.substring(0, 150);
                Log.w(CAMERA_DIAG_TAG, "Concurrent rear pair rejected; trying next rear ID. " + concurrentDiagnostic, e);
                try { provider.unbindAll(); } catch (Exception ignored) { }
            }
        }

        if (previewContainer != null && concurrentUltraPreviewView != null) previewContainer.removeView(concurrentUltraPreviewView);
        concurrentUltraPreviewView = null; concurrentUltraPreview = null;
        concurrentUltraVideoCapture = null; concurrentUltraImageCapture = null;
        concurrentMainCamera = null; concurrentUltraCamera = null; concurrentRearCamerasActive = false;
        if (concurrentPairs.isEmpty()) {
            concurrentDiagnostic = "Android tidak mengiklankan pasangan kamera belakang simultan; semua percobaan bind gagal";
        } else {
            concurrentDiagnostic = "Semua pasangan ditolak CameraX. Kandidat=" + rearIds.size()
                    + ", pasangan resmi=" + concurrentPairs.size() + ". Terakhir: " + concurrentDiagnostic;
        }
        Log.e(CAMERA_DIAG_TAG, "No rear camera pair accepted concurrent binding; " + concurrentDiagnostic);
        return false;
    }

    private int dp(float value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private void styleGlassButton(Button button) {
        button.setAllCaps(false); button.setTextSize(12); button.setTextColor(Color.WHITE);
        button.setPadding(dp(10), dp(7), dp(10), dp(7));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE); bg.setCornerRadius(dp(18));
        bg.setColor(0x553A4658); bg.setStroke(dp(1), 0x66FFFFFF);
        button.setBackground(bg); button.setElevation(dp(2));
    }

    private void styleZoomButton(Button button) {
        button.setAllCaps(false); button.setTextSize(14); button.setTextColor(Color.WHITE);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL); bg.setColor(0x553A4658);
        bg.setStroke(dp(1), 0x88FFFFFF); button.setBackground(bg);
        button.setElevation(dp(3)); button.setPadding(0, 0, 0, 0);
    }

    private void showZoomMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] labels = {"0,5×", "1×", "2×", "3×", "5×"};
        float[] values = {0.5f, 1f, 2f, 3f, 5f};
        for (int i = 0; i < labels.length; i++) {
            final float zoom = values[i]; menu.getMenu().add(labels[i]).setOnMenuItemClickListener(item -> { setZoom(zoom); return true; });
        }
        menu.show();
    }

    private void showTimerMenu() {
        PopupMenu menu = new PopupMenu(this, timerButton);
        menu.getMenu().add("Timer mati").setOnMenuItemClickListener(i -> { captureTimerSeconds = 0; timerButton.setText("◷ OFF"); return true; });
        menu.getMenu().add("3 detik").setOnMenuItemClickListener(i -> { captureTimerSeconds = 3; timerButton.setText("◷ 3s"); return true; });
        menu.getMenu().add("10 detik").setOnMenuItemClickListener(i -> { captureTimerSeconds = 10; timerButton.setText("◷ 10s"); return true; });
        menu.show();
    }

    private void startCaptureCountdown() {
        if (captureTimerSeconds <= 0) { takeMotionPhoto(); return; }
        if (countdownEndsAtMs > 0L) return;
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
            Toast.makeText(this, "Timer aktif; flash tidak tersedia di kamera ini", Toast.LENGTH_SHORT).show();
        }
        countdownEndsAtMs = SystemClock.elapsedRealtime() + captureTimerSeconds * 1000L;
        countdownTorchOn = false;
        mainHandler.removeCallbacks(countdownTick);
        countdownTick.run();
    }

    private void setCountdownTorch(boolean enabled) {
        if (countdownTorchOn == enabled) return;
        countdownTorchOn = enabled;
        if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
            try {
                camera.getCameraControl().enableTorch(enabled);
                if (enabled) setAssistTorchLowStrengthIfSupported();
            } catch (Exception ignored) { }
        }
    }

    private void takeMotionPhoto() {
        if (imageCapture == null || recording == null) {
            Toast.makeText(this, "Kamera sedang disiapkan, coba lagi sebentar", Toast.LENGTH_SHORT).show();
            return;
        }
        long bufferedMs = recordingStartedNs == 0L ? 0L : (SystemClock.elapsedRealtimeNanos() - recordingStartedNs) / 1_000_000L;
        if (bufferedMs < 1600L) {
            Toast.makeText(this, "Tunggu sebentar agar video sebelum shutter tersedia", Toast.LENGTH_SHORT).show();
            return;
        }
        if (motionPhotoPending) return;
        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists()) sessionDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        motionPhotoStamp = stamp;
        motionTimerCapture = captureTimerSeconds > 0;
        recordingStopRequestedNs = 0L;
        motionStillFile = new File(sessionDir, "motion_still_" + stamp + ".jpg");
        motionPhotoPending = true;
        motionCaptureUltraWide = concurrentRearCamerasActive && opticalUltraWideActive;
        motionHdrForCapture = hdrEnabled;
        motionCoolForCapture = dramaticCoolEnabled;
        motionRatio916ForCapture = motionRatio916;
        focusCaptureFinished = false;
        recordButton.setEnabled(false);
        recordButton.setText("MENYIAPKAN FOTO...");
        status.setText(photoFlashEnabled
                ? "Mengambil foto langsung • flash hanya sesuai pilihan pengguna..."
                : "Mengambil foto langsung • fokus berkelanjutan tetap aktif...");
        // Do not start a new AF/AE metering cycle or turn on the LED before capture.
        // The old pre-capture AE lock + assist torch caused a visible dark-to-bright
        // exposure jump in the rolling Motion Photo video and added shutter latency.
        // CameraX continuous AF/AE remains active; send the still request immediately.
        captureMotionStill();
    }

    private void toggleMotionRatio() {
        // The rolling buffer is always recording, so recording != null must NOT block
        // this button. The selected ratio is consumed later by the final encoder.
        if (motionPhotoPending) {
            Toast.makeText(this, "Tunggu Motion Photo selesai dulu", Toast.LENGTH_SHORT).show();
            return;
        }
        motionRatio916 = !motionRatio916;
        if (ratioButton != null) ratioButton.setText(motionRatio916 ? "9:16" : "3:4");
        if (previewView != null) {
            float scale = motionRatio916 ? 1.015f : 1.07f;
            previewView.setScaleX(scale);
            previewView.setScaleY(scale);
        }
        if (status != null) status.setText(motionRatio916
                ? "Mode 9:16 aktif • video Motion Photo 720×1280"
                : "Mode 3:4 aktif • video Motion Photo 720×960");
    }

    /**
     * Real post-shutter lens pulse: keep the focus used for the still, jump to the
     * closest focus position, then jump back to the exact reported focus position.
     * Total visible pulse is intentionally about 0.30 s.
     */
    private void triggerPostShutterFocusPulse() {
        if (camera2Control == null) return;
        final float originalFocus = lastReportedFocusDistance;
        if (!Float.isFinite(originalFocus) || originalFocus < 0f) return;
        try {
            Camera2CameraInfo info = Camera2CameraInfo.from(camera.getCameraInfo());
            Float minFocusDistance = info.getCameraCharacteristic(
                    android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (minFocusDistance == null || minFocusDistance <= 0f) return;
            final float closest = minFocusDistance;
            // If we are already near the closest end, there is no useful pulse to show.
            if (closest - originalFocus < 0.08f) return;

            // Let the still capture request enter the camera queue first. This is the
            // critical difference from the previous version: the pulse cannot steal the
            // shutter moment by changing LENS_FOCUS_DISTANCE at the same instant.
            mainHandler.postDelayed(() -> {
                if (camera2Control == null) return;
                try {
                    CaptureRequestOptions.Builder jump = new CaptureRequestOptions.Builder();
                    jump.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF);
                    jump.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, closest);
                    camera2Control.setCaptureRequestOptions(jump.build());
                } catch (Exception ignored) { }
            }, 70L);

            // Return to the exact focus value that the camera reported before the shutter.
            mainHandler.postDelayed(() -> {
                if (camera2Control == null) return;
                try {
                    CaptureRequestOptions.Builder back = new CaptureRequestOptions.Builder();
                    back.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_OFF);
                    back.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, originalFocus);
                    camera2Control.setCaptureRequestOptions(back.build());
                } catch (Exception ignored) { }
            }, 190L);

            // Release manual control at ~300 ms, but only after the original focus has
            // already been commanded again.
            mainHandler.postDelayed(() -> {
                if (camera2Control == null) return;
                try {
                    CaptureRequestOptions.Builder release = new CaptureRequestOptions.Builder();
                    release.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    camera2Control.setCaptureRequestOptions(release.build());
                } catch (Exception ignored) { }
            }, 300L);
        } catch (Exception ignored) { }
    }

    private void prepareFocusThenCapture() {
        if (!motionPhotoPending) return;
        Runnable beginFocus = () -> {
            if (!motionPhotoPending || focusCaptureFinished) return;
            if (camera == null || previewView == null || previewView.getWidth() <= 0 || previewView.getHeight() <= 0) {
                finishFocusBeforeCapture();
                return;
            }
            try {
                // Reuse the last user-selected focus point; use center if none was tapped.
                // A bounded wait prevents the shutter from hanging if the camera AF is slow.
                float x = lastFocusX >= 0f ? lastFocusX : previewView.getWidth() * 0.5f;
                float y = lastFocusY >= 0f ? lastFocusY : previewView.getHeight() * 0.5f;
                MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
                FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                        FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                        .setAutoCancelDuration(2, TimeUnit.SECONDS)
                        .build();
                com.google.common.util.concurrent.ListenableFuture<androidx.camera.core.FocusMeteringResult> future =
                        camera.getCameraControl().startFocusAndMetering(action);
                // Capture as soon as AF completes, or at the timeout if it is slow.
                mainHandler.postDelayed(this::finishFocusBeforeCapture, photoFlashEnabled ? 1200L : 400L);
                future.addListener(() -> mainHandler.post(this::finishFocusBeforeCapture),
                        ContextCompat.getMainExecutor(this));
            } catch (Exception ignored) {
                mainHandler.postDelayed(this::finishFocusBeforeCapture, 120L);
            }
        };
        beginFocus.run();
    }

    private void finishFocusBeforeCapture() {
        if (!motionPhotoPending || focusCaptureFinished) return;
        focusCaptureFinished = true;
        if (photoFlashEnabled && camera != null && camera.getCameraInfo().hasFlashUnit()) {
            // Request the still-flash mode before releasing the assist torch. Wait for
            // CameraX's torch-off future to complete; a fixed short delay alone can race
            // with the torch pipeline and make the LED look continuously lit.
            try {
                imageCapture.setFlashMode(ImageCapture.FLASH_MODE_ON);
                com.google.common.util.concurrent.ListenableFuture<Void> torchOff =
                        camera.getCameraControl().enableTorch(false);
                torchOff.addListener(() -> mainHandler.postDelayed(this::captureMotionStill, 100L),
                        ContextCompat.getMainExecutor(this));
            } catch (Exception ignored) {
                mainHandler.postDelayed(this::captureMotionStill, 180L);
            }
        } else {
            captureMotionStill();
        }
    }

    /**
     * Use a lower focus-assist torch level on Android 13+ devices that expose
     * adjustable LED strength. The final still flash remains CameraX-controlled
     * and uses the camera's normal full flash output.
     */
    private void setAssistTorchLowStrengthIfSupported() {
        if (android.os.Build.VERSION.SDK_INT < 33 || camera == null) return;
        try {
            String cameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
            if (manager == null || cameraId == null) return;
            android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Boolean flashAvailable = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE);
            Integer maxStrength = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            if (Boolean.TRUE.equals(flashAvailable) && maxStrength != null && maxStrength > 1) {
                // Invoke reflectively so the source also compiles against SDK stubs that do not
                // expose this API. On Android 13+ the method is present on supported devices.
                java.lang.reflect.Method setStrength = manager.getClass().getMethod(
                        "setTorchStrengthLevel", String.class, int.class);
                setStrength.invoke(manager, cameraId, 1);
            }
        } catch (Exception ignored) {
            // Most devices do not expose adjustable torch strength; retain the default.
        }
    }

    private void setTorchStrengthMaximumIfSupported() {
        if (android.os.Build.VERSION.SDK_INT < 33 || camera == null) return;
        try {
            String cameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId();
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
            if (manager == null || cameraId == null) return;
            android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer maxStrength = characteristics.get(
                    android.hardware.camera2.CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL);
            if (maxStrength != null && maxStrength > 0) {
                java.lang.reflect.Method setStrength = manager.getClass().getMethod(
                        "setTorchStrengthLevel", String.class, int.class);
                setStrength.invoke(manager, cameraId, maxStrength);
            }
        } catch (Exception ignored) { }
    }

    private void captureMotionStill() {
        if (imageCapture == null || !motionPhotoPending || motionStillFile == null) return;
        // This timestamp is the actual shutter/capture request moment, not a later HDR or
        // Motion Photo processing timestamp. The focus pulse is armed only after CameraX
        // reports that the still image has actually arrived.
        imageCapture.setFlashMode(photoFlashEnabled && camera.getCameraInfo().hasFlashUnit()
                ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        captureRequestTimestampNs = SystemClock.elapsedRealtimeNanos();
        shutterTimestampNs = captureRequestTimestampNs;
        // Never pulse/raise the torch as part of the shutter path.
        recordButton.setText("MENANGKAP FOTO...");
        motionCaptureUltraWide = concurrentRearCamerasActive && opticalUltraWideActive;
        imageCapture.takePicture(ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
            @Override public void onCaptureSuccess(@NonNull ImageProxy image) {
                // Prefer camera sensor timestamp over callback-arrival time so delivery
                // latency cannot shift the still-frame marker toward the clip start.
                long imageTimestampNs = image.getImageInfo().getTimestamp();
                long callbackNowNs = SystemClock.elapsedRealtimeNanos();
                if (imageTimestampNs > 0L && imageTimestampNs <= callbackNowNs
                        && callbackNowNs - imageTimestampNs < 2_000_000_000L) {
                    shutterTimestampNs = imageTimestampNs;
                } else {
                    shutterTimestampNs = captureRequestTimestampNs;
                }
                int capturedWidth = image.getWidth();
                int capturedHeight = image.getHeight();
                try {
                    ImageProxy.PlaneProxy[] planes = image.getPlanes();
                    if (planes == null || planes.length == 0) throw new IOException("JPEG tidak tersedia");
                    java.nio.ByteBuffer buf = planes[0].getBuffer();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    try (java.io.FileOutputStream out = new java.io.FileOutputStream(motionStillFile)) {
                        out.write(jpeg);
                    }
                    // CameraX gives the display rotation separately when using the in-memory
                    // callback. Store it in EXIF so the later 9:16 crop and Gallery display are
                    // based on the correct orientation.
                    try {
                        androidx.exifinterface.media.ExifInterface exif =
                                new androidx.exifinterface.media.ExifInterface(motionStillFile.getAbsolutePath());
                        int degrees = image.getImageInfo().getRotationDegrees();
                        int exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL;
                        if (degrees == 90) exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90;
                        else if (degrees == 180) exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180;
                        else if (degrees == 270) exifOrientation = androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270;
                        exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, String.valueOf(exifOrientation));
                        exif.saveAttributes();
                    } catch (Exception ignored) { }
                } catch (Exception e) {
                    image.close();
                    failMotionPhoto("Gagal menyimpan frame shutter: " + e.getMessage());
                    return;
                } finally {
                    try { image.close(); } catch (Exception ignored) { }
                }

                // This is the exact point after the sensor/camera has produced the still.
                // Start the physical lens pulse NOW, rather than while ImageCapture is still
                // exposing the shutter frame.
                triggerPostShutterFocusPulse();

                double capturedMp = (capturedWidth * (double) capturedHeight) / 1_000_000.0;
                if (status != null) status.setText(String.format(Locale.US,
                        "Foto utama mentah • %.1f MP (%d×%d)%s • merekam " + (motionTimerCapture ? "0,3" : "1,5") + " detik sesudah shutter...",
                        capturedMp, capturedWidth, capturedHeight,
                        capturedMp < 8.0 ? " • resolusi dibatasi kombinasi kamera aktif" : ""));
                // Normal capture keeps the still centered in a ~3 s Motion Photo.
                // Timer capture intentionally keeps only ~300 ms after the shutter;
                // the final 2 s window therefore places the still near 1.70 s / 2.00 s.
                long postShutterWindowNs = motionTimerCapture ? 300_000_000L : 1_500_000_000L;
                long remainingNs = shutterTimestampNs + postShutterWindowNs - SystemClock.elapsedRealtimeNanos();
                long stopDelayMs = Math.max(0L, remainingNs / 1_000_000L);
                mainHandler.postDelayed(() -> {
                    if (!motionPhotoPending) return;
                    if (recording != null) {
                        recordButton.setText("MENYIAPKAN MOTION PHOTO...");
                        // Finalize the ultrawide first, then stop the main stream from its
                        // Finalize callback so the timeline builder can see both complete MP4s.
                        if (concurrentRearCamerasActive && concurrentUltraRecording != null) {
                            try {
                                concurrentUltraRecording.stop();
                                mainHandler.postDelayed(() -> {
                                    if (motionPhotoPending && recording != null && !concurrentUltraFinalizeSeen) {
                                        recordingStopRequestedNs = SystemClock.elapsedRealtimeNanos();
                                        try { recording.stop(); }
                                        catch (Exception e) { failMotionPhoto("Gagal menghentikan buffer utama: " + e.getMessage()); }
                                    }
                                }, 350L);
                            } catch (Exception e) {
                                Log.w(CAMERA_DIAG_TAG, "Could not stop ultrawide buffer", e);
                                recordingStopRequestedNs = SystemClock.elapsedRealtimeNanos();
                                try { recording.stop(); }
                                catch (Exception stopError) { failMotionPhoto("Gagal menghentikan buffer video: " + stopError.getMessage()); }
                            }
                        } else {
                            recordingStopRequestedNs = SystemClock.elapsedRealtimeNanos();
                            try { recording.stop(); }
                            catch (Exception e) { failMotionPhoto("Gagal menghentikan buffer video: " + e.getMessage()); }
                        }
                    } else failMotionPhoto("Buffer video tidak aktif.");
                }, stopDelayMs);
            }

            @Override public void onError(@NonNull ImageCaptureException exception) {
                failMotionPhoto("Pengambilan foto gagal: " + exception.getMessage());
            }
        });
    }

    private void cropStillTo916(File jpeg) throws IOException {
        if (jpeg == null || !jpeg.isFile()) return;
        androidx.exifinterface.media.ExifInterface exif = new androidx.exifinterface.media.ExifInterface(jpeg.getAbsolutePath());
        int orientation = exif.getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL);
        Bitmap src = BitmapFactory.decodeFile(jpeg.getAbsolutePath());
        if (src == null) throw new IOException("JPEG tidak dapat didekode");
        Matrix rotate = new Matrix();
        if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90) rotate.postRotate(90);
        else if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180) rotate.postRotate(180);
        else if (orientation == androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270) rotate.postRotate(270);
        Bitmap oriented = (rotate.isIdentity()) ? src : Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), rotate, true);
        if (oriented != src) src.recycle();
        int w = oriented.getWidth(), h = oriented.getHeight();
        double target = 9.0 / 16.0;
        int cropW = (int)Math.round(h * target);
        cropW = Math.min(cropW, w);
        int left = Math.max(0, (w - cropW) / 2);
        Bitmap cropped = Bitmap.createBitmap(oriented, left, 0, cropW, h);
        if (cropped != oriented) oriented.recycle();
        try (FileOutputStreamCompat out = new FileOutputStreamCompat(jpeg)) {
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 100, out.stream)) throw new IOException("Gagal menulis crop 9:16");
        } finally { cropped.recycle(); }
        androidx.exifinterface.media.ExifInterface outExif = new androidx.exifinterface.media.ExifInterface(jpeg.getAbsolutePath());
        outExif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                String.valueOf(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL));
        outExif.saveAttributes();
    }

    private static final class FileOutputStreamCompat implements AutoCloseable {
        final java.io.FileOutputStream stream;
        FileOutputStreamCompat(File file) throws IOException { stream = new java.io.FileOutputStream(file); }
        @Override public void close() throws IOException { stream.close(); }
    }

    private void startRollingVideo() {
        if (videoCapture == null || recording != null || motionPhotoPending) return;
        if (sessionDir == null) sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists()) sessionDir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        gyroEndNs = 0L;
        lastSavedNs = 0L;
        collectingGyro = true;
        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);
        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    gyroStartNs = SystemClock.elapsedRealtimeNanos();
                    recordingStartedNs = gyroStartNs;
                    if (concurrentRearCamerasActive) {
                        synchronized (concurrentLensSwitches) {
                            concurrentLensSwitches.clear();
                            concurrentLensSwitches.add(new long[]{recordingStartedNs, opticalUltraWideActive ? 1L : 0L});
                        }
                    }
                    if (concurrentRearCamerasActive && concurrentUltraVideoCapture != null && concurrentUltraRecording == null) {
                        startConcurrentUltraBuffer();
                    }
                    recordButton.setEnabled(false);
                    recordButton.setText("MENYIAPKAN BUFFER...");
                    mainHandler.postDelayed(() -> {
                        if (!motionPhotoPending && recording != null) {
                            recordButton.setEnabled(true);
                            recordButton.setText("AMBIL MOTION PHOTO");
                            status.setText("Buffer video aktif • sudah tersedia 1,5 detik sebelum shutter");
                        }
                    }, 1650L);
                    status.setText("Buffer Motion Photo aktif • tekan shutter kapan saja");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    // The stop request is a closer anchor to the encoded clip end than
                    // the later Finalize callback (which can arrive tens of ms afterward).
                    gyroEndNs = (motionPhotoPending && recordingStopRequestedNs > 0L)
                            ? recordingStopRequestedNs : SystemClock.elapsedRealtimeNanos();
                    collectingGyro = false;
                    if (opticalLensSwitchPending) {
                        opticalLensSwitchPending = false;
                        try {
                            // Preserve the outgoing lens's recent buffer instead of deleting it.
                            // The next lens starts a new segment; at shutter time the segments
                            // are stitched in chronological order and trimmed to the final window.
                            File segment = new File(sessionDir, "lens_segment_" + System.currentTimeMillis() + ".mp4");
                            if (rawFile.exists() && rawFile.length() > 0) {
                                trimToLastThreeSeconds(rawFile, segment);
                                opticalLensSegments.add(segment);
                            }
                        } catch (Exception segmentError) {
                            Log.w(CAMERA_DIAG_TAG, "Could not preserve outgoing lens segment", segmentError);
                        } finally {
                            if (rawFile.exists()) rawFile.delete();
                        }
                        mainHandler.post(MainActivity.this::finishOpticalPhysicalLensSwitch);
                        return;
                    }
                    if (lensModeSwitchPending) {
                        lensModeSwitchPending = false;
                        if (rawFile.exists()) rawFile.delete();
                        mainHandler.postDelayed(MainActivity.this::finishLensModeSwitch, 120L);
                        return;
                    }
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        if (motionPhotoPending) failMotionPhoto("Buffer video gagal (" + fin.getError() + ")");
                        else status.setText("Buffer video gagal; mencoba mulai ulang...");
                        if (!motionPhotoPending) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                        return;
                    }
                    try {
                        File trimmed = new File(sessionDir, rawFile.getName().replace("raw_", "motion_raw_"));
                        boolean dualTimelineBuilt = false;
                        if (concurrentRearCamerasActive && motionPhotoPending && concurrentUltraFinalizeSeen
                                && !concurrentUltraRecordingFailed && concurrentUltraRawFile != null
                                && concurrentUltraRawFile.exists() && concurrentUltraRawFile.length() > 0) {
                            long clipEndNs = recordingStopRequestedNs > 0L ? recordingStopRequestedNs : SystemClock.elapsedRealtimeNanos();
                            long clipWindowNs = motionTimerCapture ? 2_000_000_000L : 3_000_000_000L;
                            long clipStartNs = Math.max(recordingStartedNs, clipEndNs - clipWindowNs);
                            try {
                                dualTimelineBuilt = stitchConcurrentLensTimeline(rawFile, concurrentUltraRawFile,
                                        trimmed, clipStartNs, clipEndNs);
                            } catch (Exception dualError) {
                                Log.w(CAMERA_DIAG_TAG, "Concurrent lens timeline stitch failed; using selected camera buffer", dualError);
                                dualTimelineBuilt = false;
                            }
                        }
                        if (dualTimelineBuilt) {
                            Log.i(CAMERA_DIAG_TAG, "Motion Photo built from concurrent main/ultrawide timeline");
                        } else if (!opticalLensSegments.isEmpty()) {
                            File currentSegment = new File(sessionDir, "lens_segment_current_" + System.currentTimeMillis() + ".mp4");
                            trimToLastThreeSeconds(rawFile, currentSegment);
                            List<File> segments = new ArrayList<>(opticalLensSegments);
                            segments.add(currentSegment);
                            File stitched = new File(sessionDir, "lens_stitched_" + System.currentTimeMillis() + ".mp4");
                            try {
                                concatenateVideoSegments(segments, stitched);
                                trimToMotionWindow(stitched, trimmed);
                            } catch (Exception stitchError) {
                                // Compatibility fallback: preserve a valid Motion Photo from
                                // the currently selected lens if cross-camera stitching fails.
                                Log.w(CAMERA_DIAG_TAG, "Lens-segment stitch failed; using selected-lens buffer", stitchError);
                                if (trimmed.exists()) trimmed.delete();
                                trimToMotionWindow(rawFile, trimmed);
                            } finally {
                                for (File segment : segments) if (segment.exists()) segment.delete();
                                if (stitched.exists()) stitched.delete();
                                opticalLensSegments.clear();
                            }
                        } else if (!dualTimelineBuilt) {
                            File fallbackSource = (motionCaptureUltraWide && concurrentUltraFinalizeSeen
                                    && !concurrentUltraRecordingFailed && concurrentUltraRawFile != null
                                    && concurrentUltraRawFile.exists() && concurrentUltraRawFile.length() > 0)
                                    ? concurrentUltraRawFile : rawFile;
                            trimToMotionWindow(fallbackSource, trimmed);
                        }
                        // Do not guess that the clip is exactly 3 seconds long. Anchor the
                        // gyro clock to the actual trimmed video's duration and monotonic end.
                        long clipDurationNs = getVideoDurationNs(trimmed);
                        if (gyroEndNs > 0L && clipDurationNs > 0L) gyroStartNs = Math.max(0L, gyroEndNs - clipDurationNs);
                        File csv = new File(sessionDir, trimmed.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = trimmed;
                        pendingCsvFile = csv;
                        if (motionPhotoPending) {
                            status.setText("Video pendek siap • memproses foto utama lalu menstabilkan...");
                            processMotionStillThenStabilize(trimmed, csv);
                        } else {
                            status.setText("Buffer selesai; mulai ulang...");
                            mainHandler.postDelayed(MainActivity.this::startRollingVideo, 500);
                        }
                    } catch (Exception e) {
                        if (motionPhotoPending) failMotionPhoto("Persiapan video gagal: " + e.getMessage());
                        else { status.setText("Buffer dipulihkan; mencoba ulang..."); mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700); }
                    }
                }
            });
        } catch (Exception e) {
            recording = null; collectingGyro = false;
            if (motionPhotoPending) failMotionPhoto("Tidak dapat memulai buffer: " + e.getMessage());
            else status.setText("Buffer kamera gagal dimulai: " + e.getMessage());
        }
    }

    /** Start the second camera's rolling buffer without stopping the main camera stream. */
    private void startConcurrentUltraBuffer() {
        if (!concurrentRearCamerasActive || concurrentUltraVideoCapture == null || concurrentUltraRecording != null || motionPhotoPending) return;
        if (sessionDir == null) sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists()) sessionDir.mkdirs();
        concurrentUltraRawFile = new File(sessionDir, "raw_ultrawide_" + System.currentTimeMillis() + ".mp4");
        concurrentUltraFinalizeSeen = false;
        concurrentUltraRecordingFailed = false;
        try {
            PendingRecording pending = concurrentUltraVideoCapture.getOutput().prepareRecording(this,
                    new FileOutputOptions.Builder(concurrentUltraRawFile).build());
            concurrentUltraRecording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    concurrentUltraRecordingStartedNs = SystemClock.elapsedRealtimeNanos();
                    Log.i(CAMERA_DIAG_TAG, "Ultrawide concurrent rolling buffer started");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
                    concurrentUltraFinalizeSeen = true;
                    concurrentUltraRecording = null;
                    concurrentUltraRecordingFailed = fin.hasError() || concurrentUltraRawFile == null
                            || !concurrentUltraRawFile.exists() || concurrentUltraRawFile.length() == 0;
                    Log.i(CAMERA_DIAG_TAG, "Ultrawide buffer finalized; error=" + fin.getError()
                            + ", bytes=" + (concurrentUltraRawFile != null && concurrentUltraRawFile.exists() ? concurrentUltraRawFile.length() : 0));
                    if (motionPhotoPending && recording != null && recordingStopRequestedNs == 0L) {
                        recordingStopRequestedNs = SystemClock.elapsedRealtimeNanos();
                        try { recording.stop(); }
                        catch (Exception stopError) { mainHandler.post(() -> failMotionPhoto("Gagal menghentikan buffer utama: " + stopError.getMessage())); }
                    }
                }
            });
        } catch (Exception e) {
            concurrentUltraRecording = null;
            concurrentUltraRecordingFailed = true;
            Log.w(CAMERA_DIAG_TAG, "Could not start ultrawide concurrent buffer", e);
        }
    }

    /**
     * Process the saved still only after the rolling clip has finished. This keeps HDR
     * and Cool work from delaying the post-shutter half of the Motion Photo.
     */
    private void processMotionStillThenStabilize(File trimmed, File csv) {
        final boolean useHdr = motionHdrForCapture;
        final boolean useCool = motionCoolForCapture;
        final boolean use916 = motionRatio916ForCapture;
        cameraExecutor.execute(() -> {
            boolean processed = false;
            try {
                if (motionStillFile != null && motionStillFile.isFile()) {
                    if (use916) cropStillTo916(motionStillFile);
                    if (useHdr || useCool) processed = SoftHdrProcessor.apply(motionStillFile, useHdr, useCool);
                }
            } catch (Exception ignored) { }
            final boolean processedPhoto = processed;
            mainHandler.post(() -> {
                if (!motionPhotoPending) return;
                if (useHdr || useCool) {
                    status.setText(processedPhoto
                            ? (useHdr && useCool ? "HDR + Cool foto selesai • memproses video pendek..."
                                : useHdr ? "HDR foto selesai • memproses video pendek..."
                                : "Cool foto selesai • menerapkan Cool ke video pendek...")
                            : "Foto asli dipertahankan • menstabilkan video pendek...");
                }
                stabilize(trimmed, csv, useCool, use916);
            });
        });
    }

    /** Apply a lens request queued during Motion Photo processing without corrupting its still/clip. */
    private void applyQueuedOpticalSwitchIfReady() {
        if (!opticalSwitchQueuedAfterMotionPhoto || motionPhotoPending) return;
        opticalSwitchQueuedAfterMotionPhoto = false;
        final boolean targetUltraWide = pendingOpticalUltraWide;
        final float targetZoom = pendingOpticalZoom;
        mainHandler.postDelayed(() -> {
            if (motionPhotoPending) {
                opticalSwitchQueuedAfterMotionPhoto = true;
                return;
            }
            pendingOpticalUltraWide = targetUltraWide;
            pendingOpticalZoom = targetZoom;
            requestOpticalPhysicalLens(targetUltraWide, targetZoom);
        }, 180L);
    }

    private void failMotionPhoto(String message) {
        try { if (camera != null) camera.getCameraControl().enableTorch(false); } catch (Exception ignored) { }
        // Never discard the high-resolution still if the video pipeline fails.
        if (motionStillFile != null && motionStillFile.exists()) {
            try { publishPhoto(motionStillFile); message += " • foto utama tetap disimpan ke galeri"; }
            catch (Exception ignored) { }
        }
        motionPhotoPending = false;
        motionStillFile = null;
        recordButton.setEnabled(true);
        recordButton.setText("AMBIL MOTION PHOTO");
        status.setText(message + " • video dapat diuji ulang");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (opticalSwitchQueuedAfterMotionPhoto) applyQueuedOpticalSwitchIfReady();
        else if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
    }

    /**
     * Stitch a shutter-centred clip from two simultaneously running camera buffers.
     * The lens selection history is translated into intervals on a shared monotonic clock.
     * This is a best-effort sample-copy path: camera streams must expose compatible H.264
     * format/size. If they do not, caller falls back to the selected camera's valid clip.
     */
    private boolean stitchConcurrentLensTimeline(File mainFile, File ultraFile, File output,
            long clipStartNs, long clipEndNs) throws IOException {
        if (mainFile == null || ultraFile == null || !mainFile.isFile() || !ultraFile.isFile()
                || mainFile.length() == 0 || ultraFile.length() == 0 || clipEndNs <= clipStartNs) return false;
        long mainStartNs = recordingStartedNs;
        long ultraStartNs = concurrentUltraRecordingStartedNs;
        if (mainStartNs <= 0L || ultraStartNs <= 0L) return false;
        List<long[]> history = new ArrayList<>();
        synchronized (concurrentLensSwitches) { history.addAll(concurrentLensSwitches); }
        if (history.isEmpty()) history.add(new long[]{mainStartNs, motionCaptureUltraWide ? 1L : 0L});
        history.sort((a,b) -> Long.compare(a[0], b[0]));
        long currentLens = history.get(0)[1];
        for (long[] e : history) if (e[0] <= clipStartNs) currentLens = e[1]; else break;
        List<long[]> intervals = new ArrayList<>();
        long cursor = clipStartNs;
        for (long[] e : history) {
            if (e[0] <= clipStartNs || e[0] >= clipEndNs) continue;
            if (e[0] > cursor) intervals.add(new long[]{cursor, e[0], currentLens});
            cursor = e[0]; currentLens = e[1];
        }
        if (cursor < clipEndNs) intervals.add(new long[]{cursor, clipEndNs, currentLens});
        if (intervals.isEmpty()) intervals.add(new long[]{clipStartNs, clipEndNs, currentLens});

        android.media.MediaMuxer muxer = null;
        android.media.MediaFormat reference = null;
        int outputTrack = -1;
        boolean started = false;
        try {
            if (output.exists()) output.delete();
            muxer = new android.media.MediaMuxer(output.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (long[] interval : intervals) {
                long segStartNs = interval[0], segEndNs = interval[1], wide = interval[2];
                File src = wide == 1L ? ultraFile : mainFile;
                long sourceStartNs = wide == 1L ? ultraStartNs : mainStartNs;
                long fromUs = Math.max(0L, (segStartNs - sourceStartNs) / 1000L);
                long toUs = Math.max(fromUs + 1L, (segEndNs - sourceStartNs) / 1000L);
                long outputBaseUs = (segStartNs - clipStartNs) / 1000L;
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                try {
                    ex.setDataSource(src.getAbsolutePath());
                    int videoTrack = -1;
                    android.media.MediaFormat fmt = null;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        android.media.MediaFormat f = ex.getTrackFormat(i);
                        String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) { videoTrack = i; fmt = f; break; }
                    }
                    if (videoTrack < 0 || fmt == null) throw new IOException("Tidak ada track video di " + src.getName());
                    if (reference == null) {
                        reference = fmt;
                        outputTrack = muxer.addTrack(reference);
                        if (fmt.containsKey("rotation-degrees")) {
                            int rot = fmt.getInteger("rotation-degrees");
                            if (rot == 90 || rot == 180 || rot == 270) muxer.setOrientationHint(rot);
                        }
                        muxer.start(); started = true;
                    } else {
                        String m1 = reference.getString(android.media.MediaFormat.KEY_MIME);
                        String m2 = fmt.getString(android.media.MediaFormat.KEY_MIME);
                        int w1 = reference.containsKey(android.media.MediaFormat.KEY_WIDTH) ? reference.getInteger(android.media.MediaFormat.KEY_WIDTH) : -1;
                        int h1 = reference.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? reference.getInteger(android.media.MediaFormat.KEY_HEIGHT) : -1;
                        int w2 = fmt.containsKey(android.media.MediaFormat.KEY_WIDTH) ? fmt.getInteger(android.media.MediaFormat.KEY_WIDTH) : -2;
                        int h2 = fmt.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? fmt.getInteger(android.media.MediaFormat.KEY_HEIGHT) : -2;
                        if (!java.util.Objects.equals(m1, m2) || w1 != w2 || h1 != h2)
                            throw new IOException("Format stream 1x/0.5x berbeda; perlu transcode bersama");
                    }
                    ex.selectTrack(videoTrack);
                    ex.seekTo(fromUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                    while (true) {
                        long pts = ex.getSampleTime();
                        if (pts < 0L || pts >= toUs) break;
                        if (pts >= fromUs) {
                            buffer.clear();
                            int size = ex.readSampleData(buffer, 0);
                            if (size < 0) break;
                            info.offset = 0; info.size = size;
                            info.presentationTimeUs = Math.max(0L, outputBaseUs + pts - fromUs);
                            info.flags = ex.getSampleFlags();
                            muxer.writeSampleData(outputTrack, buffer, info);
                        }
                        if (!ex.advance()) break;
                    }
                } finally { ex.release(); }
            }
            return started;
        } catch (Exception e) {
            if (output.exists()) output.delete();
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Penggabungan buffer kamera gagal: " + e.getMessage(), e);
        } finally {
            if (muxer != null) {
                try { if (started) muxer.stop(); } catch (Exception ignored) { }
                try { muxer.release(); } catch (Exception ignored) { }
            }
        }
    }

    /**
     * Best-effort stitching for sequential physical-lens buffers. This does not claim
     * frame-perfect seamlessness: it copies encoded video samples and requires compatible
     * video formats. If the two cameras expose different encoder formats, the caller falls
     * back to the normal single-segment path rather than publishing a corrupt MP4.
     */
    private void concatenateVideoSegments(List<File> segments, File output) throws IOException {
        if (segments == null || segments.isEmpty()) throw new IOException("Tidak ada segmen lensa");
        android.media.MediaMuxer muxer = null;
        long timeOffsetUs = 0L;
        int outputTrack = -1;
        android.media.MediaFormat referenceFormat = null;
        try {
            muxer = new android.media.MediaMuxer(output.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            boolean started = false;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(4 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (File segment : segments) {
                if (segment == null || !segment.exists() || segment.length() == 0) continue;
                android.media.MediaExtractor ex = new android.media.MediaExtractor();
                try {
                    ex.setDataSource(segment.getAbsolutePath());
                    int videoTrack = -1;
                    android.media.MediaFormat videoFormat = null;
                    long durationUs = 0L;
                    for (int i = 0; i < ex.getTrackCount(); i++) {
                        android.media.MediaFormat f = ex.getTrackFormat(i);
                        String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) {
                            videoTrack = i;
                            videoFormat = f;
                            if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) durationUs = f.getLong(android.media.MediaFormat.KEY_DURATION);
                            break;
                        }
                    }
                    if (videoTrack < 0 || videoFormat == null) continue;
                    if (!started) {
                        referenceFormat = videoFormat;
                        outputTrack = muxer.addTrack(referenceFormat);
                        String rotation = videoFormat.containsKey("rotation-degrees") ? String.valueOf(videoFormat.getInteger("rotation-degrees")) : null;
                        if (rotation != null) {
                            try { int d = Integer.parseInt(rotation); if (d == 90 || d == 180 || d == 270) muxer.setOrientationHint(d); } catch (Exception ignored) { }
                        }
                        muxer.start();
                        started = true;
                    } else {
                        String refMime = referenceFormat.getString(android.media.MediaFormat.KEY_MIME);
                        String mime = videoFormat.getString(android.media.MediaFormat.KEY_MIME);
                        int rw = referenceFormat.containsKey(android.media.MediaFormat.KEY_WIDTH) ? referenceFormat.getInteger(android.media.MediaFormat.KEY_WIDTH) : -1;
                        int w = videoFormat.containsKey(android.media.MediaFormat.KEY_WIDTH) ? videoFormat.getInteger(android.media.MediaFormat.KEY_WIDTH) : -1;
                        int rh = referenceFormat.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? referenceFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT) : -1;
                        int h = videoFormat.containsKey(android.media.MediaFormat.KEY_HEIGHT) ? videoFormat.getInteger(android.media.MediaFormat.KEY_HEIGHT) : -1;
                        if (!java.util.Objects.equals(refMime, mime) || rw != w || rh != h) throw new IOException("Format video antar-lensa berbeda; stitch belum aman");
                    }
                    ex.selectTrack(videoTrack);
                    long lastPts = 0L;
                    while (true) {
                        buffer.clear();
                        info.offset = 0;
                        info.size = ex.readSampleData(buffer, 0);
                        if (info.size < 0) break;
                        long pts = ex.getSampleTime();
                        if (pts < 0) break;
                        info.presentationTimeUs = timeOffsetUs + pts;
                        info.flags = ex.getSampleFlags();
                        muxer.writeSampleData(outputTrack, buffer, info);
                        lastPts = Math.max(lastPts, pts);
                        if (!ex.advance()) break;
                    }
                    timeOffsetUs += Math.max(durationUs, lastPts + 33333L);
                } finally { try { ex.release(); } catch (Exception ignored) { } }
            }
            if (!started) throw new IOException("Tidak ada track video yang dapat digabung");
            muxer.stop();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Gagal menggabungkan pergantian lensa", e);
        } finally {
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) { }
        }
        if (!output.exists() || output.length() < 1024) throw new IOException("Hasil gabungan terlalu kecil");
    }

    private void trimToMotionWindow(File input, File output) throws IOException {
        long windowUs = motionTimerCapture ? 2_000_000L : 3_000_000L;
        trimToLastWindow(input, output, windowUs);
    }

    private void trimToLastWindow(File input, File output, long windowUs) throws IOException {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            long durationUs = 0L;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                if (f.containsKey(android.media.MediaFormat.KEY_DURATION))
                    durationUs = Math.max(durationUs, f.getLong(android.media.MediaFormat.KEY_DURATION));
            }
            long startUs = Math.max(0L, durationUs - Math.max(500_000L, windowUs));
            muxer = new android.media.MediaMuxer(output.getAbsolutePath(),
                    android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int rotation = 0;
            android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
            try {
                retriever.setDataSource(input.getAbsolutePath());
                String value = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (value != null) rotation = Integer.parseInt(value);
            } catch (Exception ignored) { }
            finally { try { retriever.release(); } catch (Exception ignored) { } }
            rotation = ((rotation % 360) + 360) % 360;
            if (rotation == 90 || rotation == 180 || rotation == 270) muxer.setOrientationHint(rotation);
            int[] map = new int[extractor.getTrackCount()];
            java.util.Arrays.fill(map, -1);
            for (int i = 0; i < map.length; i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) map[i] = muxer.addTrack(f);
            }
            muxer.start();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (int i = 0; i < map.length; i++) {
                if (map[i] < 0) continue;
                extractor.selectTrack(i);
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    info.offset = 0; info.size = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) break;
                    long pts = extractor.getSampleTime();
                    if (pts < 0L) break;
                    if (pts >= startUs) {
                        info.presentationTimeUs = Math.max(0L, pts - startUs);
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(map[i], buffer, info);
                    }
                    if (!extractor.advance()) break;
                }
                extractor.unselectTrack(i);
            }
        } finally {
            try { if (muxer != null) muxer.stop(); } catch (Exception ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Exception ignored) { }
            try { extractor.release(); } catch (Exception ignored) { }
        }
        if (!output.exists() || output.length() < 1024L)
            throw new IOException("Pemotongan Motion Photo gagal menghasilkan video");
    }

    private void trimToLastThreeSeconds(File input, File output) throws IOException {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            extractor.setDataSource(input.getAbsolutePath());
            long durationUs = 0L;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                if (f.containsKey(android.media.MediaFormat.KEY_DURATION)) durationUs = Math.max(durationUs, f.getLong(android.media.MediaFormat.KEY_DURATION));
            }
            long startUs = Math.max(0L, durationUs - 3_000_000L);
            muxer = new android.media.MediaMuxer(output.getAbsolutePath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // MediaMuxer does not copy source orientation metadata automatically. Without
            // this hint a portrait clip may be displayed sideways after trimming.
            int sourceRotation = 0;
            android.media.MediaMetadataRetriever rotationRetriever = new android.media.MediaMetadataRetriever();
            try {
                rotationRetriever.setDataSource(input.getAbsolutePath());
                String rotationValue = rotationRetriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotationValue != null) sourceRotation = Integer.parseInt(rotationValue);
            } catch (Exception ignored) { }
            finally { try { rotationRetriever.release(); } catch (Exception ignored) { } }
            sourceRotation = ((sourceRotation % 360) + 360) % 360;
            if (sourceRotation == 90 || sourceRotation == 180 || sourceRotation == 270) muxer.setOrientationHint(sourceRotation);
            int[] map = new int[extractor.getTrackCount()];
            java.util.Arrays.fill(map, -1);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                android.media.MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) map[i] = muxer.addTrack(f);
            }
            muxer.start();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(2 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (int i = 0; i < map.length; i++) {
                if (map[i] < 0) continue;
                extractor.selectTrack(i);
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                while (true) {
                    info.offset = 0;
                    info.size = extractor.readSampleData(buffer, 0);
                    if (info.size < 0) break;
                    long pts = extractor.getSampleTime();
                    if (pts < 0) break;
                    if (pts >= startUs) {
                        info.presentationTimeUs = Math.max(0L, pts - startUs);
                        info.flags = extractor.getSampleFlags();
                        muxer.writeSampleData(map[i], buffer, info);
                    }
                    if (!extractor.advance()) break;
                }
                extractor.unselectTrack(i);
            }
            muxer.stop();
        } catch (Exception e) {
            throw new IOException("Gagal memotong buffer video", e);
        } finally {
            try { extractor.release(); } catch (Exception ignored) {}
            if (muxer != null) try { muxer.release(); } catch (Exception ignored) {}
        }
        if (!output.exists() || output.length() < 1024) throw new IOException("Klip Motion Photo terlalu kecil");
    }

    private void toggleRecording() {
        // A second tap after recording has finished starts processing the saved video.
        if (recording == null && pendingRawFile != null && pendingCsvFile != null) {
            File raw = pendingRawFile;
            File csv = pendingCsvFile;
            recordButton.setEnabled(false);
            recordButton.setText("MEMPROSES...");
            stabilize(raw, csv);
            return;
        }

        if (recording != null) {
            recordButton.setEnabled(false);
            recordButton.setText("MENYIMPAN...");
            status.setText("Menghentikan rekaman dan menyimpan video asli...");
            try {
                recording.stop();
            } catch (Exception e) {
                recordButton.setEnabled(true);
                recordButton.setText("BERHENTI & STABILKAN");
                status.setText("Gagal menghentikan rekaman: " + e.getMessage());
            }
            return;
        }

        if (videoCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }

        sessionDir = new File(getExternalFilesDir(null), "GyroLock");
        if (!sessionDir.exists() && !sessionDir.mkdirs()) {
            status.setText("Tidak bisa membuat folder penyimpanan.");
            return;
        }

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File rawFile = new File(sessionDir, "raw_" + stamp + ".mp4");
        gyroSamples.clear();
        // Set a provisional start immediately. The absolute sensor timestamps are kept,
        // and VideoRecordEvent.Start refines this to the actual CameraX recording start.
        gyroStartNs = SystemClock.elapsedRealtimeNanos();
        gyroEndNs = 0L;
        lastSavedNs = 0L;
        collectingGyro = true;

        FileOutputOptions output = new FileOutputOptions.Builder(rawFile).build();
        PendingRecording pending = videoCapture.getOutput().prepareRecording(this, output);

        try {
            recording = pending.start(ContextCompat.getMainExecutor(this), event -> {
                if (event instanceof VideoRecordEvent.Start) {
                    gyroStartNs = SystemClock.elapsedRealtimeNanos();
                    recordButton.setEnabled(true);
                    recordButton.setText("BERHENTI & STABILKAN");
                    status.setText("Merekam • pratinjau gyro aktif; hasil final diproses setelah rekam.");
                } else if (event instanceof VideoRecordEvent.Finalize) {
                    recording = null;
                    gyroEndNs = SystemClock.elapsedRealtimeNanos();
                    collectingGyro = false;
                    if (opticalLensSwitchPending) {
                        opticalLensSwitchPending = false;
                        if (rawFile.exists()) rawFile.delete();
                        mainHandler.post(MainActivity.this::finishOpticalPhysicalLensSwitch);
                        return;
                    }
                    if (lensModeSwitchPending) {
                        lensModeSwitchPending = false;
                        if (rawFile.exists()) rawFile.delete();
                        mainHandler.postDelayed(MainActivity.this::finishLensModeSwitch, 120L);
                        return;
                    }
                    VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;

                    if (fin.hasError() || !rawFile.exists() || rawFile.length() == 0) {
                        status.setText("Rekaman gagal disimpan (kode " + fin.getError() + ").");
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                        gyroStartNs = 0L;
                        return;
                    }

                    try {
                        File csv = new File(sessionDir, rawFile.getName().replace(".mp4", "_gyro.csv"));
                        writeGyroCsv(csv);
                        pendingRawFile = rawFile;
                        pendingCsvFile = csv;
                        status.setText("Video asli tersimpan. Tekan STABILKAN VIDEO untuk memprosesnya.");
                        recordButton.setEnabled(true);
                        recordButton.setText("STABILKAN VIDEO");
                        // Keep gyroStartNs until the stabilization button is pressed; it is
                        // needed to align absolute sensor timestamps to this video.
                    } catch (Exception e) {
                        status.setText("Video asli tersimpan, tetapi data gyro gagal: " + e.getMessage());
                        recordButton.setEnabled(true);
                        recordButton.setText("MULAI REKAM");
                    }
                }
            });
        } catch (Exception e) {
            recording = null;
            collectingGyro = false;
            recordButton.setEnabled(true);
            recordButton.setText("MULAI REKAM");
            status.setText("Gagal memulai rekaman: " + e.getMessage());
        }
    }

    private void togglePhotoFlash() {
        if (camera == null || imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        // A separately bound ultra-wide camera often reports no flash even though the
        // phone's main rear camera has a physical LED. In that case switch to OPTICAL
        // before enabling flash, rather than leaving the flash control permanently blocked.
        if (!camera.getCameraInfo().hasFlashUnit()) {
            boolean rearFlashAvailable = false;
            try {
                if (cameraProvider != null) {
                    for (androidx.camera.core.CameraInfo info : cameraProvider.getAvailableCameraInfos()) {
                        Integer facing = Camera2CameraInfo.from(info).getCameraCharacteristic(
                                android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        if (facing != null && facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                                && info.hasFlashUnit()) { rearFlashAvailable = true; break; }
                    }
                }
            } catch (Exception ignored) { }
            if (digitalMode && rearFlashAvailable) {
                photoFlashEnabled = true;
                pendingDigitalMode = false;
                Toast.makeText(this, "Flash memakai kamera OPTICAL karena lensa DIGITAL tidak mengekspos flash", Toast.LENGTH_LONG).show();
                if (recording != null) {
                    lensModeSwitchPending = true;
                    try { recording.stop(); }
                    catch (Exception e) { lensModeSwitchPending = false; photoFlashEnabled = false; }
                } else {
                    finishLensModeSwitch();
                }
                return;
            }
            Toast.makeText(this, "Flash fisik tidak tersedia pada kamera yang aktif", Toast.LENGTH_SHORT).show();
            return;
        }
        photoFlashEnabled = !photoFlashEnabled;
        imageCapture.setFlashMode(photoFlashEnabled ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
        if (hdrButton != null) hdrButton.setEnabled(true);
        if (filterButton != null) filterButton.setEnabled(true);
        applyCameraCaptureOptions();
        if (flashButton != null) flashButton.setText(photoFlashEnabled ? "⚡ FLASH" : "⚡̸ FLASH");
        if (hdrButton != null) hdrButton.setText(hdrEnabled ? "HDR ✓" : "HDR̸");
        if (filterButton != null) filterButton.setText(dramaticCoolEnabled ? "◉ FILTER" : "◉̸ FILTER");
        if (status != null) status.setText(photoFlashEnabled
                ? "Flash aktif • bantuan cahaya saat fokus, lalu kilatan utama saat foto"
                : (hdrEnabled
                    ? (dramaticCoolEnabled ? "Flash mati • HDR + Dramatic Cool aktif" : "Flash mati • HDR software aktif")
                    : (dramaticCoolEnabled ? "Flash mati • Dramatic Cool aktif" : "Flash mati • pemrosesan foto nonaktif")));
    }

    private void toggleHdr() {
        hdrEnabled = !hdrEnabled;
        // Software HDR is independent of the camera HAL, so it remains available even
        // when the hardware scene-mode HDR request is unsupported or flash is enabled.
        applyCameraCaptureOptions();
        if (hdrButton != null) hdrButton.setText(hdrEnabled ? "HDR ✓" : "HDR̸");
        updateLiveFilterVisibility();
        if (status != null) status.setText(hdrEnabled
                ? (dramaticCoolEnabled ? "HDR ON + Dramatic Cool ON • HDR setelah foto" : "HDR ON • terlihat setelah foto disimpan")
                : (dramaticCoolEnabled ? "HDR OFF + Dramatic Cool ON • diterapkan setelah foto" : "HDR OFF • foto asli"));
    }

    private void toggleDramaticCool() {
        dramaticCoolEnabled = !dramaticCoolEnabled;
        if (filterButton != null) filterButton.setText(dramaticCoolEnabled ? "◉ FILTER" : "◉̸ FILTER");
        updateLiveFilterVisibility();
        if (status != null) status.setText(dramaticCoolEnabled
                ? (hdrEnabled ? "Dramatic Cool ON • diterapkan setelah foto • HDR setelah foto" : "Dramatic Cool ON • diterapkan setelah foto")
                : (hdrEnabled ? "Dramatic Cool OFF • HDR setelah foto" : "Filter OFF • foto asli"));
    }

    private void updateLiveFilterVisibility() {
        // 3.0.10: no live filter overlay. Keep the native CameraX preview untouched
        // so enabling COOL cannot introduce frame drops. COOL is applied to the captured
        // JPEG in SoftHdrProcessor, so the filter still works on the saved photo.
        if (filteredPreview != null) {
            filteredPreview.setVisibility(View.GONE);
            filteredPreview.setEnabled(false);
        }
        if (previewView != null) previewView.setVisibility(View.VISIBLE);
        if (imageAnalysis != null) imageAnalysis.clearAnalyzer();
    }

    /** Requests the camera HAL's HDR scene mode when supported. This is not
     * computational multi-frame HDR and the device may ignore the request. */
    private void applyCameraCaptureOptions() {
        if (camera2Control == null) return;
        try {
            CaptureRequestOptions.Builder builder = new CaptureRequestOptions.Builder();
            // Keep the actual shutter capture in the camera's normal scene mode.
            // Software HDR is applied to the saved still only after the full motion clip
            // has been collected; enabling hardware HDR here can alter/delay the shutter.
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_DISABLED);
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, exposureLocked);
            camera2Control.setCaptureRequestOptions(builder.build());
        } catch (Exception ignored) { }
    }

    private void setupExposureControls() {
        if (camera == null || exposureSeekBar == null) return;
        try {
            android.util.Range<Integer> range = camera.getCameraInfo().getExposureState().getExposureCompensationRange();
            minExposureIndex = range.getLower();
            maxExposureIndex = range.getUpper();
            currentExposureIndex = camera.getCameraInfo().getExposureState().getExposureCompensationIndex();
            boolean supported = maxExposureIndex > minExposureIndex;
            exposureSeekBar.setEnabled(supported);
            exposureLockButton.setEnabled(supported);
            if (!supported) {
                exposureLabel.setText("EV tidak didukung");
                return;
            }
            updateExposureUi(currentExposureIndex);
        } catch (Exception e) {
            exposureSeekBar.setEnabled(false);
            exposureLockButton.setEnabled(false);
            exposureLabel.setText("EV tidak tersedia");
        }
    }

    private void applyExposureIndex(int index) {
        if (camera == null) return;
        int safe = Math.max(minExposureIndex, Math.min(maxExposureIndex, index));
        currentExposureIndex = safe;
        try {
            camera.getCameraControl().setExposureCompensationIndex(safe).addListener(() -> runOnUiThread(() -> {
                if (exposureLabel != null) updateExposureUi(safe);
            }), ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            if (status != null) status.setText("Pengaturan exposure tidak didukung kamera ini");
        }
    }

    private void updateExposureUi(int index) {
        if (camera == null || exposureSeekBar == null || exposureLabel == null) return;
        double step = camera.getCameraInfo().getExposureState().getExposureCompensationStep().doubleValue();
        double ev = index * step;
        exposureLabel.setText(String.format(Locale.US, "EV %+.1f", ev));
        int range = maxExposureIndex - minExposureIndex;
        int progress = range == 0 ? 50 : Math.round((index - minExposureIndex) * 100.0f / range);
        updatingExposureUi = true;
        exposureSeekBar.setProgress(Math.max(0, Math.min(100, progress)));
        updatingExposureUi = false;
    }

    private void toggleExposureLock() {
        if (camera == null || camera2Control == null) {
            Toast.makeText(this, "AE Lock tidak didukung kamera ini", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean requested = !exposureLocked;
        try {
            exposureLocked = requested;
            applyCameraCaptureOptions();
            runOnUiThread(() -> {
                if (exposureLockButton != null) {
                    exposureLockButton.setText(exposureLocked ? "AE LOCK ON" : "AE LOCK");
                    exposureLockButton.setSelected(exposureLocked);
                }
                if (exposureSeekBar != null) exposureSeekBar.setEnabled(!exposureLocked && maxExposureIndex > minExposureIndex);
                if (status != null) status.setText(exposureLocked
                        ? "Exposure terkunci • ketuk AE LOCK ON untuk membuka"
                        : "Exposure otomatis aktif kembali");
            });
        } catch (Exception e) {
            Toast.makeText(this, "Gagal mengunci exposure pada kamera ini", Toast.LENGTH_SHORT).show();
        }
    }

    private void focusAt(float x, float y) {
        if (camera == null || previewView == null) return;
        lastFocusX = x;
        lastFocusY = y;
        try {
            MeteringPoint point = previewView.getMeteringPointFactory().createPoint(x, y);
            FocusMeteringAction action = new FocusMeteringAction.Builder(point,
                    FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build();
            camera.getCameraControl().startFocusAndMetering(action).addListener(() -> {
                runOnUiThread(() -> {
                    if (status != null && recording == null)
                        status.setText("Fokus disentuh • FHD jika didukung • zoom " + selectedZoom + "×");
                });
            }, ContextCompat.getMainExecutor(this));
        } catch (Exception e) {
            Toast.makeText(this, "Fokus sentuh tidak didukung di titik ini", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Find an independently exposed rear camera that looks like an ultra-wide lens.
     * A logical multi-camera that exposes several focal lengths as one camera is not
     * considered independent: CameraX cannot promise a "digital only" crop on it.
     */
    private String findIndependentUltraWideCameraId(ProcessCameraProvider provider) {
        try {
            String bestId = null;
            String longestId = null;
            float shortest = Float.MAX_VALUE;
            float longest = 0f;
            List<String> ids = new ArrayList<>();
            List<Float> focalValues = new ArrayList<>();
            for (androidx.camera.core.CameraInfo info : provider.getAvailableCameraInfos()) {
                Camera2CameraInfo c2 = Camera2CameraInfo.from(info);
                Integer facing = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                float[] focal = c2.getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
                if (facing == null || facing != android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                        || focal == null || focal.length == 0) continue;
                // Some vendors expose more than one focal-length entry for one CameraInfo.
                // Use the shortest advertised focal length instead of rejecting that camera;
                // otherwise a real, separately exposed ultra-wide can be missed entirely.
                float cameraShortest = Float.MAX_VALUE;
                for (float f : focal) if (f > 0f && f < cameraShortest) cameraShortest = f;
                if (cameraShortest == Float.MAX_VALUE) continue;
                String id = c2.getCameraId();
                ids.add(id);
                focalValues.add(cameraShortest);
                if (cameraShortest < shortest) { shortest = cameraShortest; bestId = id; }
                if (cameraShortest > longest) { longest = cameraShortest; longestId = id; }
            }
            // Require a meaningfully shorter separate rear camera, not just a single
            // ordinary rear lens that we cannot confidently call ultra-wide.
            if (ids.size() >= 2 && bestId != null && shortest < longest * 0.78f) {
                Log.i(CAMERA_DIAG_TAG, "Separate rear ultra-wide candidate id=" + bestId
                        + ", shortestFocal=" + shortest + ", longestFocal=" + longest
                        + ", exposedRearIds=" + ids);
                logOpticalConcurrentCapability(bestId, longestId);
                return bestId;
            }
            Log.i(CAMERA_DIAG_TAG, "No separately exposed ultra-wide confirmed. Rear camera IDs=" + ids
                    + ", focalLengths=" + focalValues);
        } catch (Exception ignored) {}
        return null;
    }

    /** Diagnostic probe for Android's advertised main/ultra-wide concurrent pair. */
    private void logOpticalConcurrentCapability(String ultraWideId, String mainId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || ultraWideId == null || mainId == null) {
            Log.i(CAMERA_DIAG_TAG, "Concurrent optical probe unavailable on this API or camera pair.");
            return;
        }
        try {
            android.hardware.camera2.CameraManager manager =
                    (android.hardware.camera2.CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
            boolean pairSupported = false;
            for (Set<String> pair : manager.getConcurrentCameraIds()) {
                if (pair.contains(ultraWideId) && pair.contains(mainId)) {
                    pairSupported = true;
                    break;
                }
            }
            Log.i(CAMERA_DIAG_TAG, "Concurrent main/ultra-wide pair advertised=" + pairSupported
                    + ", main=" + mainId + ", ultraWide=" + ultraWideId);
            if (pairSupported) {
                Log.i(CAMERA_DIAG_TAG, "Concurrent pair is advertised by Camera2; v3.0.31 will attempt the concurrent CameraX stream graph and log any rejected use-case combination.");
            } else {
                Log.i(CAMERA_DIAG_TAG, "Concurrent pair unavailable; safe fallback uses a single-camera session.");
            }
        } catch (Exception e) {
            Log.w(CAMERA_DIAG_TAG, "Concurrent camera capability probe failed: " + e.getMessage());
        }
    }

    private void beginOpticalSwitchTransition() {
        if (previewView == null) return;
        previewView.animate().cancel();
        previewView.setAlpha(1.0f);
        previewView.setScaleX(1.0f);
        previewView.setScaleY(1.0f);
        // A subtle 90 ms push-in followed by a settle on the newly selected feed makes
        // the physical FOV change feel less abrupt without ever compositing two feeds.
        previewView.animate().scaleX(1.025f).scaleY(1.025f).setDuration(90L).start();
    }

    private void finishOpticalSwitchTransition() {
        if (previewView == null) return;
        previewView.animate().cancel();
        previewView.animate().scaleX(1.0f).scaleY(1.0f).alpha(1.0f).setDuration(110L).start();
    }

    private CameraSelector getSelectedCameraSelector() {
        if ((digitalMode || opticalUltraWideActive) && independentUltraWideCameraId != null) {
            final String wantedId = independentUltraWideCameraId;
            return new CameraSelector.Builder().addCameraFilter(cameraInfos -> {
                List<androidx.camera.core.CameraInfo> filtered = new ArrayList<>();
                for (androidx.camera.core.CameraInfo info : cameraInfos) {
                    try {
                        if (wantedId.equals(Camera2CameraInfo.from(info).getCameraId())) filtered.add(info);
                    } catch (Exception ignored) {}
                }
                return filtered;
            }).build();
        }
        return CameraSelector.DEFAULT_BACK_CAMERA;
    }

    private void toggleLensMode() {
        if (cameraProvider == null || previewUseCase == null || videoCapture == null || imageCapture == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        if (motionPhotoPending) {
            Toast.makeText(this, "Tunggu Motion Photo selesai sebelum mengganti mode lensa", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingDigitalMode = !digitalMode;
        if (pendingDigitalMode && independentUltraWideCameraId == null) {
            Toast.makeText(this,
                    "HP tidak mengekspos ultrawide sebagai kamera terpisah. Mode DIGITAL tidak bisa menjamin lensa tetap sama pada perangkat ini.",
                    Toast.LENGTH_LONG).show();
        }
        if (recording != null) {
            // CameraX cannot rebind a use case while the rolling Motion Photo recording
            // is active. Finish that short buffer first, then switch and restart it.
            lensModeSwitchPending = true;
            status.setText("Mengganti mode lensa… menyiapkan ulang buffer Motion Photo");
            try { recording.stop(); }
            catch (Exception e) {
                lensModeSwitchPending = false;
                Toast.makeText(this, "Gagal menghentikan buffer kamera", Toast.LENGTH_LONG).show();
            }
        } else {
            finishLensModeSwitch();
        }
    }

    private void finishLensModeSwitch() {
        if (cameraProvider == null || previewUseCase == null || videoCapture == null || imageCapture == null) return;
        try {
            digitalMode = pendingDigitalMode;
            opticalUltraWideActive = false;
            if (zoomAnimator != null) zoomAnimator.cancel();
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, getSelectedCameraSelector(),
                    previewUseCase, videoCapture, imageCapture);
            camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            setupExposureControls();
            applyCameraCaptureOptions();
            boolean activeCameraHasFlash = camera.getCameraInfo().hasFlashUnit();
            if (!activeCameraHasFlash) photoFlashEnabled = false;
            imageCapture.setFlashMode(photoFlashEnabled
                    ? ImageCapture.FLASH_MODE_ON : ImageCapture.FLASH_MODE_OFF);
            if (flashButton != null) {
                flashButton.setEnabled(activeCameraHasFlash || (digitalMode && independentUltraWideCameraId != null));
                flashButton.setText(!activeCameraHasFlash ? "⚡ FLASH TIDAK ADA"
                        : photoFlashEnabled ? "⚡ FLASH" : "⚡̸ FLASH");
            }
            lensModeButton.setText(digitalMode ? "DIGITAL" : "OPTICAL");
            setZoom(digitalMode ? 0.5f : 1.0f);
            if (status != null) status.setText(digitalMode
                    ? "DIGITAL • ultrawide terpisah jika tersedia"
                    : "OPTICAL • kamera belakang multilensa jika didukung");
            mainHandler.postDelayed(() -> {
                if (!motionPhotoPending && recording == null) startRollingVideo();
            }, 300L);
        } catch (Exception e) {
            digitalMode = false;
            try {
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase, videoCapture, imageCapture);
                camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                setupExposureControls();
                applyCameraCaptureOptions();
            } catch (Exception ignored) {}
            if (lensModeButton != null) lensModeButton.setText("OPTICAL");
            Toast.makeText(this, "Mode lensa tidak didukung penuh; kembali ke kamera belakang utama.", Toast.LENGTH_LONG).show();
            mainHandler.postDelayed(() -> {
                if (!motionPhotoPending && recording == null) startRollingVideo();
            }, 700L);
        }
    }

    private void requestOpticalPhysicalLens(boolean useUltraWide, float requestedZoom) {
        if (independentUltraWideCameraId == null) {
            Toast.makeText(this, "Kamera ultrawide fisik tidak diekspos oleh perangkat ini.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingOpticalUltraWide = useUltraWide;
        pendingOpticalZoom = requestedZoom;
        // With two rear cameras already bound concurrently, change only the visible preview
        // and selected still source. This is allowed during Motion Photo; neither stream stops.
        if (concurrentRearCamerasActive) {
            finishOpticalPhysicalLensSwitch();
            return;
        }
        if (motionPhotoPending) {
            opticalSwitchQueuedAfterMotionPhoto = true;
            Toast.makeText(this, "Kamera simultan tidak aktif; perpindahan ditunda sampai Motion Photo selesai", Toast.LENGTH_LONG).show();
            return;
        }
        beginOpticalSwitchTransition();
        if (recording != null) {
            opticalLensSwitchPending = true;
            if (status != null) status.setText("OPTICAL • mengganti lensa fisik… menyiapkan ulang buffer singkat");
            try { recording.stop(); }
            catch (Exception e) {
                opticalLensSwitchPending = false;
                Toast.makeText(this, "Tidak dapat menyiapkan pergantian lensa", Toast.LENGTH_LONG).show();
            }
        } else {
            finishOpticalPhysicalLensSwitch();
        }
    }

    private VideoCapture<Recorder> videoCaptureForMain() {
        return mainVideoCaptureForConcurrent != null ? mainVideoCaptureForConcurrent : videoCapture;
    }
    private ImageCapture imageCaptureForMain() {
        return mainImageCaptureForConcurrent != null ? mainImageCaptureForConcurrent : imageCapture;
    }

    private void finishOpticalPhysicalLensSwitch() {
        if (cameraProvider == null || previewUseCase == null || videoCapture == null || imageCapture == null) return;
        if (concurrentRearCamerasActive && concurrentMainCamera != null && concurrentUltraCamera != null) {
            boolean oldWide = opticalUltraWideActive;
            if (oldWide != pendingOpticalUltraWide) {
                long now = SystemClock.elapsedRealtimeNanos();
                synchronized (concurrentLensSwitches) {
                    concurrentLensSwitches.add(new long[]{now, pendingOpticalUltraWide ? 1L : 0L});
                }
            }
            opticalUltraWideActive = pendingOpticalUltraWide;
            boolean wide = opticalUltraWideActive;
            if (previewView != null) { previewView.setVisibility(View.VISIBLE); previewView.setAlpha(wide ? 0f : 1f); }
            if (concurrentUltraPreviewView != null) {
                concurrentUltraPreviewView.setVisibility(View.VISIBLE);
                concurrentUltraPreviewView.setAlpha(wide ? 1f : 0f);
            }
            camera = wide ? concurrentUltraCamera : concurrentMainCamera;
            // Keep the rolling recorder field anchored to the main camera. The ultrawide
            // recorder is managed independently, so switching preview/still source must not
            // cause startRollingVideo() to start a second recording on the same Recorder.
            videoCapture = videoCaptureForMain();
            imageCapture = wide ? concurrentUltraImageCapture : imageCaptureForMain();
            try { camera2Control = Camera2CameraControl.from(camera.getCameraControl()); } catch (Exception ignored) { camera2Control = null; }
            selectedZoom = pendingOpticalZoom;
            if (status != null) status.setText(wide ? "CONCURRENT • ultrawide 0,5× dipilih" : "CONCURRENT • kamera utama 1× dipilih");
            finishOpticalSwitchTransition();
            return;
        }
        try {
            opticalUltraWideActive = pendingOpticalUltraWide;
            cameraProvider.unbindAll();
            camera = cameraProvider.bindToLifecycle(this, getSelectedCameraSelector(),
                    previewUseCase, videoCapture, imageCapture);
            camera2Control = Camera2CameraControl.from(camera.getCameraControl());
            setupExposureControls();
            applyCameraCaptureOptions();
            float actualZoom = opticalUltraWideActive ? Math.max(1.0f, pendingOpticalZoom * 2.0f) : Math.max(1.0f, pendingOpticalZoom);
            camera.getCameraControl().setZoomRatio(actualZoom);
            selectedZoom = pendingOpticalZoom;
            String activeCameraId = "unknown";
            try { activeCameraId = Camera2CameraInfo.from(camera.getCameraInfo()).getCameraId(); }
            catch (Exception ignored) { }
            Log.i(CAMERA_DIAG_TAG, "OPTICAL physical rebind complete. ultraWide=" + opticalUltraWideActive
                    + ", selectedCameraId=" + activeCameraId + ", logicalActivePhysicalId=" + lastActivePhysicalCameraId);
            if (status != null) status.setText(opticalUltraWideActive
                    ? "OPTICAL • ultrawide fisik aktif (camera ID " + activeCameraId + ")"
                    : "OPTICAL • kamera utama aktif (camera ID " + activeCameraId + ")");
        } catch (Exception e) {
            opticalUltraWideActive = false;
            try {
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase, videoCapture, imageCapture);
                camera2Control = Camera2CameraControl.from(camera.getCameraControl());
                setupExposureControls();
                applyCameraCaptureOptions();
            } catch (Exception ignored) { }
            Toast.makeText(this, "Perpindahan lensa tidak didukung pada kombinasi kamera ini.", Toast.LENGTH_LONG).show();
        }
        finishOpticalSwitchTransition();
        mainHandler.postDelayed(() -> {
            if (!motionPhotoPending && recording == null) startRollingVideo();
        }, 100L);
    }

    private void setZoom(float requested) {
        if (camera == null) {
            Toast.makeText(this, "Kamera belum siap", Toast.LENGTH_SHORT).show();
            return;
        }
        // In concurrent mode, crossing the 0.5x/1x stop swaps only the displayed camera
        // and selected still source. Neither camera session is unbound or reopened.
        if (concurrentRearCamerasActive && ((requested < 0.75f) != opticalUltraWideActive)) {
            pendingOpticalUltraWide = requested < 0.75f;
            pendingOpticalZoom = requested;
            finishOpticalPhysicalLensSwitch();
            return;
        }
        // OPTICAL mode uses a real separately exposed ultrawide at the 0.5x stop when
        // the logical back camera cannot provide sub-1x zoom. Outside an active Motion
        // Photo, rebind the lens and restart the rolling prebuffer; never cut a live clip.
        if (!digitalMode && independentUltraWideCameraId != null) {
            androidx.camera.core.ZoomState currentState = camera.getCameraInfo().getZoomState().getValue();
            float currentMin = currentState == null ? 1.0f : currentState.getMinZoomRatio();
            boolean needsPhysicalWide = requested < 0.75f && currentMin >= 0.95f && !opticalUltraWideActive;
            boolean needsMainLens = requested >= 0.75f && opticalUltraWideActive;
            if (needsPhysicalWide || needsMainLens) {
                requestOpticalPhysicalLens(needsPhysicalWide, requested);
                return;
            }
        }
        try {
            androidx.camera.core.ZoomState state = camera.getCameraInfo().getZoomState().getValue();
            float min = state == null ? 1.0f : state.getMinZoomRatio();
            float max = state == null ? Math.max(1.0f, requested) : state.getMaxZoomRatio();
            // When DIGITAL has an independently exposed ultra-wide camera, 1.0 camera
            // zoom corresponds to the user's 0.5x field-of-view label. Zooming then stays
            // on that selected camera instead of asking the logical multi-camera to switch.
            boolean usingSeparateWide = independentUltraWideCameraId != null && (digitalMode || opticalUltraWideActive);
            float cameraRequested = usingSeparateWide ? requested * 2.0f : requested;
            float target = Math.max(min, Math.min(cameraRequested, max));
            float current = state == null ? (usingSeparateWide ? selectedZoom * 2.0f : selectedZoom) : state.getZoomRatio();
            if (zoomAnimator != null) zoomAnimator.cancel();
            // DIGITAL 1x <-> 0.5x on the same ultrawide module uses a ~480 ms eased
            // transition with a small end-pulse inspired by the supplied reference video.
            // The pulse is applied to the camera zoom itself, so it is captured in video.
            final boolean digitalWideStep = digitalMode && independentUltraWideCameraId != null
                    && ((selectedZoom >= 0.90f && requested <= 0.60f)
                    || (selectedZoom <= 0.60f && requested >= 0.90f));
            final float fromRatio = current;
            final float toRatio = target;
            final float pulse = digitalWideStep ? Math.min(0.075f, Math.max(0.035f, target * 0.055f)) : 0.0f;
            zoomAnimator = android.animation.ValueAnimator.ofFloat(0.0f, 1.0f);
            zoomAnimator.setDuration(digitalWideStep ? 480L : Math.max(380L, Math.min(800L, (long)(Math.abs(target-current) * 220L))));
            android.view.animation.AccelerateDecelerateInterpolator easing =
                    new android.view.animation.AccelerateDecelerateInterpolator();
            zoomAnimator.addUpdateListener(anim -> {
                if (camera == null) return;
                float p = (Float) anim.getAnimatedValue();
                float eased = easing.getInterpolation(p);
                float ratio = fromRatio + (toRatio - fromRatio) * eased;
                if (pulse > 0.0f && p > 0.68f && p < 1.0f) {
                    float phase = (p - 0.68f) / 0.32f;
                    ratio += pulse * (float) Math.sin(Math.PI * phase);
                }
                ratio = Math.max(min, Math.min(max, ratio));
                camera.getCameraControl().setZoomRatio(ratio);
            });
            zoomAnimator.start();
            selectedZoom = usingSeparateWide ? target / 2.0f : target;
            float displayedTarget = selectedZoom;
            if (status != null && recording == null) {
                String suffix = Math.abs(target - cameraRequested) > 0.02f ? " (rentang zoom dibatasi perangkat)" : "";
                status.setText((digitalMode ? "DIGITAL " : "OPTICAL ") + displayedTarget + "×" + suffix + " • ketuk layar untuk fokus");
            }
            if (Math.abs(target - cameraRequested) > 0.02f) Toast.makeText(this,
                    "Rentang zoom perangkat membatasi pilihan ini", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Zoom gagal: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        // Ignore every source except the one explicitly selected for this build.
        if (rotationSensor == null || event.sensor.getType() != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR) return;
        long now = event.timestamp;
        if (event.values == null || event.values.length < 3) return;

        // Android rotation-vector values are quaternion x/y/z and optionally w.
        // Reconstruct w for 3-value implementations, normalize, then differentiate
        // the relative quaternion. This avoids Euler-angle wrap and gimbal coupling.
        double qx = event.values[0];
        double qy = event.values[1];
        double qz = event.values[2];
        double qw = event.values.length >= 4 ? event.values[3]
                : Math.sqrt(Math.max(0.0, 1.0 - qx*qx - qy*qy - qz*qz));
        double qNorm = Math.sqrt(qx*qx + qy*qy + qz*qz + qw*qw);
        if (!Double.isFinite(qNorm) || qNorm < 1.0e-8) return;
        qx /= qNorm; qy /= qNorm; qz /= qNorm; qw /= qNorm;

        if (!hasPreviousRotationQuaternion || previousRotationTimestampNs == 0L || now <= previousRotationTimestampNs) {
            previousRotationQuaternion[0] = qx; previousRotationQuaternion[1] = qy;
            previousRotationQuaternion[2] = qz; previousRotationQuaternion[3] = qw;
            hasPreviousRotationQuaternion = true;
            previousRotationTimestampNs = now;
            return;
        }

        // q and -q represent the same orientation; choose the shortest arc.
        double dot = previousRotationQuaternion[0]*qx + previousRotationQuaternion[1]*qy
                + previousRotationQuaternion[2]*qz + previousRotationQuaternion[3]*qw;
        if (dot < 0.0) { qx = -qx; qy = -qy; qz = -qz; qw = -qw; }

        // delta = inverse(previous) * current; its axis is expressed in the
        // device-local frame and its angle gives the interval angular velocity.
        double px = previousRotationQuaternion[0], py = previousRotationQuaternion[1];
        double pz = previousRotationQuaternion[2], pw = previousRotationQuaternion[3];
        double dx = pw*qx - px*qw - py*qz + pz*qy;
        double dy = pw*qy + px*qz - py*qw - pz*qx;
        double dz = pw*qz - px*qy + py*qx - pz*qw;
        double dw = pw*qw + px*qx + py*qy + pz*qz;
        previousRotationQuaternion[0] = qx; previousRotationQuaternion[1] = qy;
        previousRotationQuaternion[2] = qz; previousRotationQuaternion[3] = qw;
        double dt = (now - previousRotationTimestampNs) / 1_000_000_000.0;
        previousRotationTimestampNs = now;
        if (dt <= 0.0001 || dt > 0.20) return;

        // Normalize delta and convert to axis-angle rate. Clamp impossible spikes
        // from magnetic heading corrections instead of integrating them as motion.
        double dNorm = Math.sqrt(dx*dx + dy*dy + dz*dz + dw*dw);
        if (!Double.isFinite(dNorm) || dNorm < 1.0e-8) return;
        dx /= dNorm; dy /= dNorm; dz /= dNorm; dw /= dNorm;
        if (dw < 0.0) { dx = -dx; dy = -dy; dz = -dz; dw = -dw; }
        double vectorNorm = Math.sqrt(dx*dx + dy*dy + dz*dz);
        double angle = 2.0 * Math.atan2(vectorNorm, Math.max(0.0, dw));
        double scale = vectorNorm > 1.0e-8 ? angle / (vectorNorm * dt) : 0.0;
        double rateX = clamp(dx * scale, -4.0, 4.0);
        double rateY = clamp(dy * scale, -4.0, 4.0);
        double rateZ = clamp(dz * scale, -4.0, 4.0);

        // Live preview and saved clip consume this exact same single-sensor stream.
        if (previewView != null && previewView.getWidth() > 0) {
            if (liveLastTimestampNs != 0L) {
                double previewDt = Math.max(0.0, Math.min(0.05, (now - liveLastTimestampNs) / 1_000_000_000.0));
                double rx = deadBand(rateX, 0.032);
                double ry = deadBand(rateY, 0.032);
                double rz = deadBand(rateZ, 0.032);
                double rateAlpha = previewDt / (0.065 + previewDt);
                liveRateX += rateAlpha * (rx - liveRateX);
                liveRateY += rateAlpha * (ry - liveRateY);
                liveRateZ += rateAlpha * (rz - liveRateZ);
                liveAngleX += liveRateX * previewDt;
                liveAngleY += liveRateY * previewDt;
                liveAngleZ += liveRateZ * previewDt;
                if (!liveAnglesInitialized) {
                    liveSmoothX = liveAngleX; liveSmoothY = liveAngleY; liveSmoothZ = liveAngleZ;
                    liveAnchorX = liveAngleX; liveAnchorY = liveAngleY; liveAnchorZ = liveAngleZ;
                    liveAnglesInitialized = true;
                }
                final double maxTilt = 0.18;
                final double maxRoll = 0.10;
                double dxLive = liveAngleX - liveAnchorX;
                double dyLive = liveAngleY - liveAnchorY;
                double dzLive = liveAngleZ - liveAnchorZ;
                double followAlpha = Math.min(1.0, previewDt * 0.24);
                if (Math.abs(dxLive) > maxTilt) liveAnchorX += (dxLive - Math.copySign(maxTilt, dxLive)) * followAlpha;
                if (Math.abs(dyLive) > maxTilt) liveAnchorY += (dyLive - Math.copySign(maxTilt, dyLive)) * followAlpha;
                if (Math.abs(dzLive) > maxRoll) liveAnchorZ += (dzLive - Math.copySign(maxRoll, dzLive)) * followAlpha;
                dxLive = clamp(liveAngleX - liveAnchorX, -maxTilt, maxTilt);
                dyLive = clamp(liveAngleY - liveAnchorY, -maxTilt, maxTilt);
                dzLive = clamp(liveAngleZ - liveAnchorZ, -maxRoll, maxRoll);
                previewView.setPivotX(previewView.getWidth() * 0.5f);
                previewView.setPivotY(previewView.getHeight() * 0.5f);
                previewView.setRotation((float) (-Math.toDegrees(dzLive)));
                previewView.setTranslationX((float) (dyLive * previewView.getWidth() * 0.48));
                previewView.setTranslationY((float) (-dxLive * previewView.getHeight() * 0.48));
            }
            liveLastTimestampNs = now;
        }

        if (!collectingGyro || gyroStartNs == 0L) return;
        if (lastSavedNs != 0L && now - lastSavedNs < 10_000_000L) return;
        lastSavedNs = now;
        synchronized (gyroSamples) { gyroSamples.add(new GyroSample(now, rateX, rateY, rateZ)); }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void writeGyroCsv(File csv) throws IOException {
        try (FileWriter w = new FileWriter(csv)) {
            w.write("t,gyro_x_rad_s,gyro_y_rad_s,gyro_z_rad_s\n");
            synchronized (gyroSamples) {
                for (GyroSample s : gyroSamples) {
                    double t = s.secondsFrom(gyroStartNs);
                    if (t >= 0) w.write(String.format(Locale.US, "%.6f,%.7f,%.7f,%.7f\n", t, s.x, s.y, s.z));
                }
            }
        }
    }

    // Gyro-based stabilization: integrate gyro XYZ, smooth the camera path, and
    // sample a 240-point correction curve. Native Vulkan applies the curve per decoded
    // frame and MediaCodec encodes the stabilized result; no FFmpeg path is used.
    private void stabilize(File input, File csv) {
        stabilize(input, csv, dramaticCoolEnabled, motionRatio916);
    }

    private void stabilize(File input, File csv, boolean coolVideo, boolean portrait916) {
        status.setText("Menyiapkan stabilisasi Vulkan 240 titik... video asli tetap aman.");
        recordButton.setEnabled(false);
        new Thread(() -> {
            File output = null;
            File silent = null;
            try {
                List<GyroSample> all;
                synchronized (gyroSamples) { all = new ArrayList<>(gyroSamples); }
                long clipDurationNs = getVideoDurationNs(input);
                long endNs = gyroEndNs;
                if (clipDurationNs <= 0L) throw new IOException("Durasi klip tidak dapat dibaca; video asli tetap aman.");
                if (endNs <= 0L) throw new IOException("Timestamp akhir rekaman gyro tidak tersedia; video asli tetap aman.");
                // CameraX's trimmed MP4 timeline starts at PTS 0. Align that timeline to
                // the sensor's monotonic clock using the actual clip duration and end time;
                // a fixed 'now - 3 seconds' offset causes the wrong gyro motion to be applied.
                long clipStartNs = endNs - clipDurationNs;
                List<GyroSample> samples = new ArrayList<>();
                for (GyroSample sample : all) {
                    long relativeNs = sample.timestampNs - clipStartNs;
                    if (relativeNs >= -30_000_000L && relativeNs <= clipDurationNs + 30_000_000L) {
                        samples.add(new GyroSample(Math.max(0L, relativeNs), sample.x, sample.y, sample.z));
                    }
                }
                if (samples.size() < 8) throw new IOException("Data gyro tidak mencakup waktu klip; video asli tetap aman.");

                int n = samples.size();
                double[] t = new double[n], ax = new double[n], ay = new double[n], az = new double[n];
                for (int i = 0; i < n; i++) t[i] = samples.get(i).timestampNs / 1_000_000_000.0;
                double[] bias = estimateInitialGyroBias(samples, t, 0.75);
                double fx = 0, fy = 0, fz = 0;
                double prevFx = 0, prevFy = 0, prevFz = 0;
                // Conservative EIS input gate. Gyro samples contain high-frequency
                // electronic noise even when the phone is physically still. RMS alone
                // can be dominated by a few spikes, so classify a stationary take using
                // both the median and 90th-percentile residual angular speed.
                final double deadBandThreshold = 0.045; // rad/s; reject low-level fused-vector noise more firmly
                List<Double> residualMagnitudes = new ArrayList<>();
                double residualEnergy = 0.0;
                for (GyroSample sample : samples) {
                    double rx = sample.x - bias[0], ry = sample.y - bias[1], rz = sample.z - bias[2];
                    double magnitude = Math.sqrt(rx * rx + ry * ry + rz * rz);
                    residualMagnitudes.add(magnitude);
                    residualEnergy += magnitude * magnitude;
                }
                Collections.sort(residualMagnitudes);
                double residualRms = Math.sqrt(residualEnergy / Math.max(1, samples.size()));
                double medianRate = residualMagnitudes.get(residualMagnitudes.size() / 2);
                double p90Rate = residualMagnitudes.get(Math.min(residualMagnitudes.size() - 1,
                        (int) Math.floor((residualMagnitudes.size() - 1) * 0.90)));
                // If the typical rate is tiny and even the 90th percentile is modest,
                // trust the camera image more than sensor noise: emit a zero correction
                // curve rather than creating visible left-right movement from noise.
                // Sensor noise can vary with phone pose. A few outliers while upright
                // should not force the whole clip into an aggressive correction path.
                boolean stationaryClip = medianRate < 0.035 && p90Rate < 0.120 && residualRms < 0.075;
                for (int i = 1; i < n; i++) {
                    double dt = Math.max(0, Math.min(0.05, t[i] - t[i - 1]));
                    GyroSample cur = samples.get(i);
                    double rx = deadBand(cur.x - bias[0], deadBandThreshold);
                    double ry = deadBand(cur.y - bias[1], deadBandThreshold);
                    double rz = deadBand(cur.z - bias[2], deadBandThreshold);
                    // Reject low-energy samples independently, so a brief sensor spike
                    // cannot start an accumulated random-walk correction.
                    if (stationaryClip || Math.sqrt(rx * rx + ry * ry + rz * rz) < 0.060) {
                        rx = 0.0; ry = 0.0; rz = 0.0;
                    }
                    double alpha = dt <= 0 ? 0 : dt / (0.050 + dt);
                    prevFx = fx; prevFy = fy; prevFz = fz;
                    fx += alpha * (rx - fx);
                    fy += alpha * (ry - fy);
                    fz += alpha * (rz - fz);
                    ax[i] = ax[i - 1] + 0.5 * (fx + prevFx) * dt;
                    ay[i] = ay[i - 1] + 0.5 * (fy + prevFy) * dt;
                    az[i] = az[i - 1] + 0.5 * (fz + prevFz) * dt;
                }

                double[] cx = new double[n], cy = new double[n], cz = new double[n];
                if (!stationaryClip) {
                    // EIS reference path: retain a gently smoothed camera trajectory and
                    // correct only the difference between measured and reference paths.
                    // Use a lower gain than the prior build to avoid visible overshoot,
                    // left-right pumping, and jelly when the motion estimate is imperfect.
                    double[] sx = smoothTrajectory(ax, t, 0.85);
                    double[] sy = smoothTrajectory(ay, t, 0.85);
                    double[] sz = smoothTrajectory(az, t, 0.85);
                    final double correctionGain = residualRms > 0.22 ? 0.68 : 0.58;
                    for (int i = 0; i < n; i++) {
                        cx[i] = clamp((ax[i] - sx[i]) * correctionGain, -0.16, 0.16);
                        cy[i] = clamp((ay[i] - sy[i]) * correctionGain, -0.16, 0.16);
                        cz[i] = clamp((az[i] - sz[i]) * correctionGain, -0.09, 0.09);
                    }
                    // Smooth the correction itself more gently to prevent oscillatory
                    // correction from being written into the output. This deliberately
                    // favors natural residual movement over an aggressive counter-motion.
                    cx = smoothTrajectory(cx, t, 0.38);
                    cy = smoothTrajectory(cy, t, 0.38);
                    cz = smoothTrajectory(cz, t, 0.38);
                }

                final int points = 240;
                float[] pointTimes = new float[points];
                float[] pointX = new float[points], pointY = new float[points], pointZ = new float[points];
                double duration = Math.max(0.001, clipDurationNs / 1_000_000_000.0);
                for (int k = 0; k < points; k++) {
                    double target = duration * k / (points - 1.0);
                    pointTimes[k] = (float) target;
                    pointX[k] = (float) interpolateSeries(t, cx, target);
                    pointY[k] = (float) interpolateSeries(t, cy, target);
                    pointZ[k] = (float) interpolateSeries(t, cz, target);
                }

                output = new File(sessionDir, input.getName().replace("raw_", "stabilized_").replace(".mp4", "_GPU.mp4"));
                silent = new File(sessionDir, input.getName().replace("raw_", "gpu_silent_").replace(".mp4", ".mp4"));
                if (output.exists()) output.delete();
                if (silent.exists()) silent.delete();
                final File outputFinal = output;
                final File silentFinal = silent;
                runOnUiThread(() -> status.setText("Stabilisasi Vulkan + MediaCodec 240 titik berjalan..."));
                GyroVulkanStabilizer.stabilize(this, input, silentFinal, outputFinal,
                        pointTimes, pointX, pointY, pointZ, portrait916, coolVideo,
                        message -> runOnUiThread(() -> status.setText(message)));

                runOnUiThread(() -> {
                    try {
                        if (motionPhotoPending && motionStillFile != null && motionStillFile.exists()) {
                            String motionStamp = motionPhotoStamp != null ? motionPhotoStamp : new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            File motionJpeg = new File(sessionDir, "GyroLock_" + motionStamp + "MP.jpg");
                            long markerUs = calculateMotionMarkerUs(outputFinal);
                            MotionPhotoWriter.write(motionStillFile, outputFinal, motionJpeg, markerUs);
                            publishPhoto(motionJpeg);
                            // Single-file Motion Photo: the MP4 is embedded in the JPEG by MotionPhotoWriter.
                            // Do not publish a separate MP4 companion.
                            motionPhotoPending = false;
                            motionStillFile = null;
                            motionPhotoStamp = null;
                            status.setText("Motion Photo tersimpan di DCIM/Camera");
                            Toast.makeText(this, "Motion Photo tersimpan di Kamera", Toast.LENGTH_LONG).show();
                        } else {
                            publishVideo(outputFinal);
                            status.setText("Stabilisasi selesai! Video tersimpan di Movies/Camera.");
                        }
                        pendingRawFile = null;
                        pendingCsvFile = null;
                        recordButton.setText("AMBIL MOTION PHOTO");
                    } catch (Exception publishError) {
                        status.setText("Stabilisasi selesai, tetapi gagal menyalin hasil ke Galeri: " + publishError.getMessage());
                        recordButton.setText("COBA SIMPAN HASIL LAGI");
                    }
                    recordButton.setEnabled(true);
                    if (opticalSwitchQueuedAfterMotionPhoto && !motionPhotoPending) applyQueuedOpticalSwitchIfReady();
                    else if (!motionPhotoPending && recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 600);
                });
            } catch (Throwable e) {
                final String message = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage());
                runOnUiThread(() -> {
                    if (motionPhotoPending && motionStillFile != null && motionStillFile.exists() && input.exists()) {
                        try {
                            String motionStamp = motionPhotoStamp != null ? motionPhotoStamp : new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                            File fallbackMotion = new File(sessionDir, "GyroLock_" + motionStamp + "MP.jpg");
                            long markerUs = calculateMotionMarkerUs(input);
                            MotionPhotoWriter.write(motionStillFile, input, fallbackMotion, markerUs);
                            publishPhoto(fallbackMotion);
                            // Fallback also saves one combined Motion Photo only; no separate MP4 is published.
                            motionPhotoPending = false;
                            motionStillFile = null;
                            motionPhotoStamp = null;
                            status.setText("Motion Photo tersimpan; stabilisasi gyro tidak tersedia, video asli dipakai.");
                            Toast.makeText(this, "Motion Photo tersimpan tanpa stabilisasi", Toast.LENGTH_LONG).show();
                            recordButton.setEnabled(true);
                            recordButton.setText("AMBIL MOTION PHOTO");
                            if (opticalSwitchQueuedAfterMotionPhoto) applyQueuedOpticalSwitchIfReady();
                            else if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                            return;
                        } catch (Exception saveError) {
                            failMotionPhoto("Stabilisasi gagal dan video gerak tidak dapat digabung: " + message);
                            return;
                        }
                    }
                    status.setText("Stabilisasi GPU gagal; video asli tetap aman. " + message);
                    recordButton.setEnabled(true);
                    recordButton.setText("AMBIL MOTION PHOTO");
                    if (recording == null) mainHandler.postDelayed(MainActivity.this::startRollingVideo, 700);
                });
            }
        }, "GyroLock-GPU-Stabilizer").start();
    }

    /** Returns the actual encoded video duration in monotonic nanoseconds. */
    private long getVideoDurationNs(File clip) {
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(clip.getAbsolutePath());
            String durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs != null) return Math.max(0L, Long.parseLong(durationMs)) * 1_000_000L;
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        return 0L;
    }

    /** Align the XMP still-frame marker to the shutter request inside the trimmed clip. */
    private long calculateMotionMarkerUs(File clip) {
        long clipDurationUs = 3_000_000L;
        android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever();
        try {
            retriever.setDataSource(clip.getAbsolutePath());
            String durationMs = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationMs != null) clipDurationUs = Math.max(0L, Long.parseLong(durationMs) * 1000L);
        } catch (Exception ignored) {
        } finally {
            try { retriever.release(); } catch (Exception ignored) { }
        }
        long afterShutterUs = recordingStopRequestedNs > shutterTimestampNs && shutterTimestampNs > 0L
                ? (recordingStopRequestedNs - shutterTimestampNs) / 1000L
                : 1_500_000L;
        return Math.max(0L, Math.min(clipDurationUs, clipDurationUs - afterShutterUs));
    }

    private double interpolateSeries(double[] times, double[] values, double target) {
        if (target <= times[0]) return values[0];
        int last = times.length - 1;
        if (target >= times[last]) return values[last];
        int lo = 0, hi = last;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= target) lo = mid; else hi = mid;
        }
        double span = times[hi] - times[lo];
        if (span <= 0) return values[lo];
        double f = (target - times[lo]) / span;
        return values[lo] + (values[hi] - values[lo]) * f;
    }

    private double deadBand(double value, double threshold) {
        // Soft dead-band avoids turning sensor noise around zero into visible jitter.
        double magnitude = Math.abs(value) - threshold;
        return magnitude <= 0.0 ? 0.0 : Math.copySign(magnitude, value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double[] estimateInitialGyroBias(List<GyroSample> samples, double[] times, double windowSeconds) {
        // Median is robust against a few hand movements while the recording starts.
        // Mean bias estimation can mistake an initial pan for sensor bias and then
        // make an otherwise stationary clip drift or vibrate during stabilization.
        List<Double> xs = new ArrayList<>(), ys = new ArrayList<>(), zs = new ArrayList<>();
        for (int i = 0; i < samples.size() && times[i] <= windowSeconds; i++) {
            GyroSample sample = samples.get(i);
            xs.add(sample.x); ys.add(sample.y); zs.add(sample.z);
        }
        if (xs.isEmpty()) return new double[]{0, 0, 0};
        return new double[]{median(xs), median(ys), median(zs)};
    }

    private double median(List<Double> values) {
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? (sorted.get(middle - 1) + sorted.get(middle)) * 0.5
                : sorted.get(middle);
    }

    private double[] smoothTrajectory(double[] values, double[] times, double windowSeconds) {
        // Sliding-window mean using a prefix sum: O(n), not O(n * windowSamples).
        // The wider camera-path window suppresses walking-footstep oscillation;
        // the short correction window removes abrupt reversal without over-blurring motion.
        int n = values.length;
        double[] prefix = new double[n + 1];
        for (int i = 0; i < n; i++) prefix[i + 1] = prefix[i] + values[i];
        double[] result = new double[n];
        int left = 0, right = 0;
        double half = Math.max(0.0, windowSeconds * 0.5);
        for (int i = 0; i < n; i++) {
            while (left < i && times[i] - times[left] > half) left++;
            if (right < i) right = i;
            while (right + 1 < n && times[right + 1] - times[i] <= half) right++;
            int count = right - left + 1;
            result[i] = count > 0 ? (prefix[right + 1] - prefix[left]) / count : values[i];
        }
        return result;
    }

    private List<Integer> pickIndices(int n, int maxPoints) {
        List<Integer> picked = new ArrayList<>();
        int count = Math.min(maxPoints, n);
        for (int k = 0; k < count; k++) {
            int index = count == 1 ? 0 : (int)Math.round(k * (n - 1.0) / (count - 1.0));
            if (picked.isEmpty() || picked.get(picked.size() - 1) != index) picked.add(index);
        }
        return picked;
    }

    private String buildExpression(double[] times, double[] values, List<Integer> idx) {
        if (idx == null || idx.isEmpty()) return "0";
        int last = idx.get(idx.size() - 1);
        String expression = String.format(Locale.US, "%.7f", values[last]);
        for (int k = idx.size() - 2; k >= 0; k--) {
            int ia = idx.get(k), ib = idx.get(k + 1);
            double ta = times[ia], tb = times[ib], va = values[ia], vb = values[ib];
            double dt = tb - ta;
            if (dt <= 0.0001) continue;
            String interpolation = String.format(Locale.US, "(%.7f+(%.7f)*(t-%.6f)/%.7f)", va, vb - va, ta, dt);
            expression = String.format(Locale.US, "if(lt(t\\,%.6f)\\,%s\\,%s)", tb, interpolation, expression);
        }
        return expression;
    }

    /**
     * Publishes the processed MP4 into the public Movies/GyroLock folder so it
     * appears in common file managers and gallery apps. The working/original
     * files stay in the app's private external-files directory.
     */
    private Uri publishPhoto(File source) throws IOException {
        ContentResolver resolver = getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, source.getName());
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.DATE_TAKEN, source.lastModified());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Camera");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IOException("Galeri menolak file Motion Photo");
        boolean success = false;
        try (FileInputStream in = new FileInputStream(source);
             OutputStream out = resolver.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Tidak dapat membuka output galeri");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            out.flush();
            success = true;
        } finally {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (success) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                    resolver.update(uri, ready, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            } else if (!success) {
                resolver.delete(uri, null, null);
            }
        }
        return uri;
    }

    private Uri publishVideo(File source) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, source.getName());
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Camera");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) throw new IOException("Gagal membuat file video di Movies/Camera");
            boolean success = false;
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = resolver.openOutputStream(uri, "w")) {
                if (out == null) throw new IOException("Tidak bisa menulis file video");
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Video.Media.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return uri;
        } else {
            File movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File folder = new File(movies, "Camera");
            if (!folder.exists() && !folder.mkdirs()) throw new IOException("Gagal membuat folder Movies/GyroLock");
            File dest = new File(folder, source.getName());
            try (FileInputStream in = new FileInputStream(source);
                 OutputStream out = new java.io.FileOutputStream(dest)) {
                byte[] buffer = new byte[1024 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
                out.flush();
            }
            return Uri.fromFile(dest);
        }
    }

    private String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ && hasPermissions()) startCamera();
        else if (requestCode == REQ) status.setText("Izin kamera dan mikrofon diperlukan.");
    }

    @Override protected void onDestroy() {
        try { if (recording != null) recording.stop(); } catch (Exception ignored) { }
        super.onDestroy();
        collectingGyro = false;
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraExecutor != null) cameraExecutor.shutdown();
    }
}
