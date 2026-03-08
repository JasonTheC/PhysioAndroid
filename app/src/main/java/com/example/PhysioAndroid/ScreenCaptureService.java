package com.example.PhysioAndroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.ContextThemeWrapper;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.util.Pair;

import com.example.physioandroid.R;
import com.google.android.gms.nearby.messages.MessageListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;


public class ScreenCaptureService extends Service {

    public static String chosenProbe;
    public static String orientation;
    private String[] poses;
    public List<byte[]> poseImagesToSend = new ArrayList<>();
    public  List<Long> timeList = new ArrayList<>();
    public List<Float> pitchList = new ArrayList<>();
    public List<JSONObject> poseJSONsToSend = new ArrayList<>();
    private int poseInt = 0;
    public static String bodyPart= "None";
    public static String patientName = "";
    public static String studyNotes = "";
    public int subBodyPart = 4;
    public boolean fanFlag = false;
    public static boolean closeApp = false;
    private static final String TAG = "ScreenCaptureService";
    private static final String RESULT_CODE = "RESULT_CODE";
    private static final String DATA = "DATA";
    private static final String ACTION = "ACTION";
    private static final String START = "START";
    private static final String STOP = "STOP";
    private static final String SCREENCAP_NAME = "screencap";
    private ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private static final int BUFFER_SIZE = 1920 * 1080 * 4; // Adjust based on your max resolution and pixel format
    private final ByteBuffer mReusableBuffer = ByteBuffer.allocate(BUFFER_SIZE);

    private static int IMAGES_PRODUCED;

    private MediaProjection mMediaProjection;
    private String mStoreDir;
    private ImageReader mImageReader;
    private Handler mHandler;
    private Display mDisplay;
    private VirtualDisplay mVirtualDisplay;
    private int mDensity;
    private int mWidth;
    private int mHeight;
    private int mRotation;
    private OrientationChangeCallback mOrientationChangeCallback;
    static long startFan;
    private boolean stopFan;

    static final String CHANNEL_ID = "Overlay_notification_channel";
    private static final int LayoutParamFlags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;

    private LayoutInflater inflater;
    private View UIView;
    private WindowManager windowManager;
    private WindowManager.LayoutParams UIparams;
    private WindowManager.LayoutParams appParams;
    private WindowManager.LayoutParams studyIDParams;
    private TextView studyIDTextView;
    public SocketThread socketThread;

    // Per-sweep storage state (mirrors ScanActivity directory structure)
    private File storageRoot;
    private String studyUUID;
    private String createdDate;
    private String createdTime;
    private File rawDir;
    private String imagePrefix;
    private int imageIndex;

    // WitMotion BLE IMU (matches ScanActivity)
    private static final String IMU_MAC_ADDRESS = "D0:77:17:74:E6:B2";
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;
    private volatile boolean imuConnected = false;
    private volatile float imuRoll = 0.0f;
    private volatile float imuPitch = 0.0f;
    private volatile float imuYaw = 0.0f;
    private final List<float[]> capturedImuData = new ArrayList<>();

    private static long lastImageTime = 0;
    public static String pt_number = UUID.randomUUID().toString();
    private long endTime;
    private long lastImage;
    private long acquriedImage;
    private String message = "";
    private MessageListener messageListener;
    public interface MessageListener {
        void onMessageChanged(String newMessage);
    }

    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }
    private void setMessage(String newMessage) {
        this.message = newMessage;
        if (messageListener != null) {
            messageListener.onMessageChanged(newMessage);
        }
        updateUIText(newMessage);
    }
    public static Intent getStartIntent(Context context, int resultCode, Intent data) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, START);
        intent.putExtra(RESULT_CODE, resultCode);
        intent.putExtra(DATA, data);
        return intent;
    }

    public static Intent getStopIntent(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, STOP);
        return intent;
    }

    private static final String RETRY_IMU = "RETRY_IMU";

    /** Intent to trigger a BLE IMU scan retry after runtime permissions are granted. */
    public static Intent getRetryImuIntent(Context context) {
        Intent intent = new Intent(context, ScreenCaptureService.class);
        intent.putExtra(ACTION, RETRY_IMU);
        return intent;
    }

    private static boolean isStartCommand(Intent intent) {
        return intent.hasExtra(RESULT_CODE) && intent.hasExtra(DATA)
                && intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), START);
    }

    private static boolean isStopCommand(Intent intent) {
        return intent.hasExtra(ACTION) && Objects.equals(intent.getStringExtra(ACTION), STOP);
    }

    private static int getVirtualDisplayFlags() {
        return DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
    }

    private void processImageAsync(final Image image) {
        try {
            // Copy image data to our reusable buffer
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int remaining = buffer.remaining();
            if (remaining <= mReusableBuffer.capacity()) {
                mReusableBuffer.clear();
                mReusableBuffer.put(buffer);
                mReusableBuffer.flip();
                byte[] data = new byte[remaining];
                mReusableBuffer.get(data);
    
                // Submit task to executor
                mExecutor.execute(new ImageProcessingTask(data, stopFan, pixelStride, rowStride));
            } else {
                Log.e(TAG, "Buffer overflow: " + remaining + " > " + mReusableBuffer.capacity());
            }
        } finally {
            image.close(); // Release the image immediately
        }
    }

    private class ImageProcessingTask implements Runnable {
        private final byte[] mImageData;
        private final boolean mStopFan;
        private final int pixelStride;
        private final int rowStride;

    ImageProcessingTask(byte[] data, boolean stopFan, int pixelStride, int rowStride) {
        mImageData = data;
        mStopFan = stopFan; 
        this.pixelStride = pixelStride;
        this.rowStride = rowStride;
    }
        @Override
        public void run() {
            if (!fanFlag) return;  // Prevent processing if fan is stopped
            // Snapshot IMU at frame capture time for consistent filename + JSON entry
            final float frameRoll  = imuRoll;
            final float framePitch = imuPitch;
            final float frameYaw   = imuYaw;
            Bitmap  bitmap = null;
            long startTime = System.nanoTime();
            long duration = (endTime - startTime) / 1000000;
            //Log.d("Timing", "Last image in" + duration + " ms");
            Log.d(TAG, "StudyID is " + pt_number + " at orientation " + orientation);
            
            int rowPadding = this.rowStride - this.pixelStride * mWidth;
            bitmap = Bitmap.createBitmap(mWidth + rowPadding / this.pixelStride, mHeight, Bitmap.Config.ARGB_8888);
            ByteBuffer buffer = ByteBuffer.wrap(mImageData);
            bitmap.copyPixelsFromBuffer(buffer);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, byteArrayOutputStream);
            byte[] byteArray = byteArrayOutputStream.toByteArray();
            bitmap.recycle();
            try {
                byteArrayOutputStream.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            endTime = System.nanoTime();
            duration = (endTime - startTime) / 1000000;  // Convert to milliseconds
            //Log.d("Timing", "Got image in" + duration + " ms");
            // Save frame to disk (mirrors ScanActivity raw/ approach)
            if (rawDir != null) {
                // Filename encodes index + IMU angles: {prefix}_{index:04d}_r{roll}_p{pitch}_y{yaw}.jpg
                String frameFilename = imagePrefix + "_" + String.format(Locale.US,
                        "%04d_p%.4f_r%.4f_y%.4f",
                        imageIndex,
                        framePitch,
                        frameRoll,
                        frameYaw) + ".jpg";
                imageIndex++;
                try {
                    FileOutputStream fos = new FileOutputStream(new File(rawDir, frameFilename));
                    fos.write(byteArray);
                    fos.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to save frame: " + e.getMessage());
                }
            }
            endTime = System.nanoTime();
            duration = (endTime - startTime) / 1000000;  // Convert to milliseconds
            //Log.d("Timing", "stored image in" + duration + " ms");
            IMAGES_PRODUCED++;
            long time = System.currentTimeMillis()-startFan;
            long diff = time - lastImageTime;
            lastImageTime = time;
            //Log.d("imageTiming", "Image " + IMAGES_PRODUCED + " at " + time + "ms, diff " + diff + "ms");
            
            //Log.i("fan data", " Image number " + IMAGES_PRODUCED + " at " + time + "with orientation " + orientation);
            timeList.add(time);
            // Record IMU reading for this frame (use same snapshot as filename)
            synchronized (capturedImuData) {
                capturedImuData.add(new float[]{time, frameRoll, framePitch, frameYaw});
            }
            if (mStopFan) {
                fanFlag = false;  // Stop capturing new images immediately
                try {
                    final String currentOrientation = orientation;
                    final String orientationKey = currentOrientation.equalsIgnoreCase("Transverse") ? "transverse" : "sagital";

                    // Save IMU data as imu_data.json alongside the images
                    List<float[]> imuSnapshot;
                    synchronized (capturedImuData) {
                        imuSnapshot = new ArrayList<>(capturedImuData);
                        capturedImuData.clear();
                    }
                    if (rawDir != null && !imuSnapshot.isEmpty()) {
                        try {
                            org.json.JSONArray imuArray = new org.json.JSONArray();
                            for (float[] entry : imuSnapshot) {
                                JSONObject imuEntry = new JSONObject();
                                imuEntry.put("time", (long) entry[0]);
                                imuEntry.put("roll",  entry[1]);
                                imuEntry.put("pitch", entry[2]);
                                imuEntry.put("yaw",   entry[3]);
                                imuArray.put(imuEntry);
                            }
                            File imuFile = new File(rawDir, "imu_data.json");
                            FileOutputStream imuFos = new FileOutputStream(imuFile);
                            imuFos.write(imuArray.toString().getBytes());
                            imuFos.close();
                            Log.d(TAG, "Saved IMU data: " + imuSnapshot.size() + " readings");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to save IMU data: " + e.getMessage());
                        }
                    }

                    // Save study-info.json with patient name and notes
                    if (rawDir != null) {
                        try {
                            JSONObject studyInfo = new JSONObject();
                            studyInfo.put("patientName", patientName);
                            studyInfo.put("studyNotes", studyNotes);
                            studyInfo.put("bodyPart", bodyPart);
                            studyInfo.put("orientation", orientationKey);
                            studyInfo.put("studyUUID", studyUUID);
                            studyInfo.put("createdDate", createdDate);
                            studyInfo.put("createdTime", createdTime);
                            File studyInfoFile = new File(rawDir, "study-info.json");
                            FileOutputStream siFos = new FileOutputStream(studyInfoFile);
                            siFos.write(studyInfo.toString(2).getBytes());
                            siFos.close();
                            Log.d(TAG, "Saved study-info.json");
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to save study-info.json: " + e.getMessage());
                        }
                    }

                    // Collect saved image files from disk (sorted by name)
                    ArrayList<File> imageFiles = new ArrayList<>();
                    if (rawDir != null && rawDir.exists()) {
                        File[] files = rawDir.listFiles((dir, name) -> name.endsWith(".jpg"));
                        if (files != null) {
                            Arrays.sort(files, (f1, f2) -> f1.getName().compareTo(f2.getName()));
                            for (File f : files) imageFiles.add(f);
                        }
                    }

                    // Build metadata JSON matching ScanActivity / SocketThread.java WebSocket protocol
                    JSONObject metadata = new JSONObject();
                    metadata.put("type", "scan_data");
                    metadata.put("patientEmail", pt_number);
                    metadata.put("patientName", patientName.isEmpty() ? pt_number : patientName);
                    metadata.put("studyNotes", studyNotes);
                    metadata.put("patientGender", "unknown");
                    metadata.put("patientDOB", "unknown");
                    metadata.put("studyUUID", studyUUID);
                    metadata.put("organ", "bladder_prostate");
                    metadata.put("orientation", orientationKey);
                    metadata.put("createdDate", createdDate);
                    metadata.put("createdTime", createdTime);
                    metadata.put("imageCount", imageFiles.size());
                    Log.d(TAG, "Sending " + imageFiles.size() + " images for " + orientationKey);

                    new SocketThread().sendScanData(metadata, imageFiles,
                            () -> {
                                setMessage("Upload complete (" + currentOrientation + ")");
                                Log.d(TAG, "Upload complete for " + currentOrientation);
                            },
                            (error) -> {
                                setMessage("Upload failed: " + error);
                                Log.e(TAG, "Upload error: " + error);
                            }
                    );

                    poseImagesToSend.clear();
                    timeList.clear();
                    pitchList.clear();
                    IMAGES_PRODUCED = 0;
                    stopFan = false;  // Reset after sending

                    if (currentOrientation.equals("Transverse")) {
                        orientation = "Sagittal";
                    } else if (currentOrientation.equals("Sagittal")) {
                        // End of study: fresh UUID for next patient
                        studyUUID = null;
                        pt_number = UUID.randomUUID().toString();
                        updateStudyIDOverlay(pt_number);
                        orientation = "Transverse";
                    }
                } catch (Exception e) {
                    Log.e("return", "Exception = " + e);
                }
            }
        } 
    }
    private class ImageAvailableListener implements ImageReader.OnImageAvailableListener {
        @Override
        public void onImageAvailable(ImageReader reader) {
            //long startImageAvailable = System.nanoTime();
            //long duration = (acquriedImage - startImageAvailable) / 1000000;
            //Log.d("Timing", "Acquired  image in" + duration + " ms");
            Image image = mImageReader.acquireLatestImage();
            //acquriedImage = System.nanoTime();
            if (fanFlag && image!=null){
                processImageAsync(image);

            } else if (image != null) {
                image.close();
            }

        }
    }

    private class OrientationChangeCallback extends OrientationEventListener {

        OrientationChangeCallback(Context context) {
            super(context);
        }

        @Override
        public void onOrientationChanged(int orientation) {
            final int rotation = mDisplay.getRotation();
            if (rotation != mRotation) {
                mRotation = rotation;
                try {
                    // clean up
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);

                    // re-create virtual display depending on device width / height
                    createVirtualDisplay();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private class MediaProjectionStopCallback extends MediaProjection.Callback {
        @Override
        public void onStop() {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mVirtualDisplay != null) mVirtualDisplay.release();
                    if (mImageReader != null) mImageReader.setOnImageAvailableListener(null, null);
                    if (mOrientationChangeCallback != null) mOrientationChangeCallback.disable();
                    mMediaProjection.unregisterCallback(MediaProjectionStopCallback.this);
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    public void closeApp(View view){
        System.exit(0);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        orientation = "Transverse";
//For Overlay
        int LAYOUT_FLAG;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            LAYOUT_FLAG = WindowManager.LayoutParams.TYPE_PHONE;
        }
        windowManager = (WindowManager) this.getSystemService(WINDOW_SERVICE);
        appParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.TRANSPARENT);
        appParams.height = 500;
        appParams.gravity = Gravity.BOTTOM;

        UIparams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.OPAQUE);
    // Inflate the UI with the app theme so AppCompat widgets can resolve their theme
    ContextThemeWrapper wrapper = new ContextThemeWrapper(this, R.style.Theme_AndroidDemov1);
    inflater = LayoutInflater.from(wrapper);
    UIparams.gravity = Gravity.BOTTOM | Gravity.START;
    UIparams.x = 20;  // Distance from the left edge (20px margin)
    UIparams.y = 100;  // Distance from the bottom edge (100px margin to avoid navigation bar)
        UIView = inflater.inflate(R.layout.ui, null);
        windowManager.addView(UIView, UIparams);

        // Create studyID overlay at the top
        studyIDParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                LayoutParamFlags,
                PixelFormat.TRANSPARENT);
        studyIDParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        studyIDParams.y = 20;
        studyIDTextView = new TextView(this);
        studyIDTextView.setText(pt_number);
        studyIDTextView.setTextColor(Color.WHITE);
        studyIDTextView.setTextSize(18);
        studyIDTextView.setBackgroundColor(Color.parseColor("#80000000")); // Semi-transparent black
        windowManager.addView(studyIDTextView, studyIDParams);

        // Programmatically wire click listeners to avoid reflection-based android:onClick issues
        View closeBtn = UIView.findViewById(com.example.physioandroid.R.id.Closebutton);
        if (closeBtn != null) {
            closeBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    closeApp(v);
                }
            });
        }
        View toggleBtn = UIView.findViewById(com.example.physioandroid.R.id.toggleButton);
        if (toggleBtn != null) {
            toggleBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    toggleFan(v);
                }
            });
        }

        mDisplay = windowManager.getDefaultDisplay();


        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, getString(R.string.overlay_notification), NotificationManager.IMPORTANCE_HIGH);
            notificationChannel.setSound(null, null);
            notificationManager.createNotificationChannel(notificationChannel);
            Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID);
            builder.setContentTitle(getString(R.string.overlay)).setContentText(getString(R.string.overlay_notification)).setSmallIcon(R.drawable.ic_launcher_background);
            startForeground(1, builder.build());
        }

        // Per-sweep storage root (mirrors ScanActivity directory structure)
        File extDir = getExternalFilesDir(null);
        if (extDir != null) {
            storageRoot = new File(extDir, "storage");
            if (!storageRoot.exists()) storageRoot.mkdirs();
        }

        // Start BLE scan for WitMotion IMU (BLE + location permissions assumed granted by caller)
        startIMUScan();

        // create store dir
        File externalFilesDir = getExternalFilesDir(null);
        if (externalFilesDir != null) {
            mStoreDir = externalFilesDir.getAbsolutePath() + "/screenshots/";
            File storeDirectory = new File(mStoreDir);
            if (!storeDirectory.exists()) {
                boolean success = storeDirectory.mkdirs();
                if (!success) {
                    Log.e(TAG, "failed to create file storage directory.");
                    stopSelf();
                }
            }
        } else {
            Log.e(TAG, "failed to create file storage directory, getExternalFilesDir is null.");
            stopSelf();
        }

        new Thread() {
            @Override
            public void run() {
                Looper.prepare();
                mHandler = new Handler();
                Looper.loop();
            }
        }.start();

    };




    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && RETRY_IMU.equals(intent.getStringExtra(ACTION))) {
            // (Re-)attempt BLE scan now that permissions may have been granted
            if (!imuConnected) startIMUScan();
            return START_NOT_STICKY;
        }
        if (isStartCommand(intent)) {
            // create notification
            Pair<Integer, Notification> notification = NotificationUtils.getNotification(this);
            startForeground(notification.first, notification.second);



            // start projection
            int resultCode = intent.getIntExtra(RESULT_CODE, Activity.RESULT_CANCELED);
            Intent data = intent.getParcelableExtra(DATA);
            startProjection(resultCode, data);
        } else if (isStopCommand(intent)) {
            stopProjection();
            stopSelf();
        } else {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private void startProjection(int resultCode, Intent data) {
        MediaProjectionManager mpManager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mMediaProjection == null) {
            mMediaProjection = mpManager.getMediaProjection(resultCode, data);
            if (mMediaProjection != null) {
                // display metrics
                mDensity = Resources.getSystem().getDisplayMetrics().densityDpi;
                WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
                mDisplay = windowManager.getDefaultDisplay();

                // create virtual display depending on device width / height
                createVirtualDisplay();

                // register orientation change callback
                mOrientationChangeCallback = new OrientationChangeCallback(this);
                if (mOrientationChangeCallback.canDetectOrientation()) {
                    mOrientationChangeCallback.enable();
                }

                // register media projection stop callback
                mMediaProjection.registerCallback(new MediaProjectionStopCallback(), mHandler);
            }
        }
    }

    private void stopProjection() {
        if (mHandler != null) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (mMediaProjection != null) {
                        mMediaProjection.stop();
                    }
                }
            });
        }
    }

    @SuppressLint("WrongConstant")
    private void createVirtualDisplay() {
        // get width and height
        mWidth = Resources.getSystem().getDisplayMetrics().widthPixels/2;
        mHeight = Resources.getSystem().getDisplayMetrics().heightPixels/2;

        // start capture reader
        mImageReader = ImageReader.newInstance(mWidth, mHeight, PixelFormat.RGBA_8888, 20);
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(SCREENCAP_NAME, mWidth, mHeight,
                mDensity, getVirtualDisplayFlags(), mImageReader.getSurface(), null, mHandler);
        mImageReader.setOnImageAvailableListener(new ImageAvailableListener(), mHandler);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        windowManager.removeView(studyIDTextView);
        // Disconnect BLE IMU
        if (bluetoothGatt != null) {
            bluetoothGatt.disconnect();
            bluetoothGatt.close();
            bluetoothGatt = null;
        }
    }
    public void StartFan(View view) {
        ((TextView) UIView.findViewById(R.id.textView)).setText("Fan Through");
        stopFan = false;
        fanFlag = true;
        if (mOrientationChangeCallback != null) {
            mOrientationChangeCallback.disable();
        }

        // Init study UUID + timestamps on the first sweep of a new study
        if (studyUUID == null) {
            studyUUID = UUID.randomUUID().toString();
            createdDate = new SimpleDateFormat("dd_MM_yyyy", Locale.US).format(new Date());
            createdTime = new SimpleDateFormat("HH_mm_ss", Locale.US).format(new Date());
        }
        imagePrefix = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        imageIndex = 0;
        IMAGES_PRODUCED = 0;
        synchronized (capturedImuData) { capturedImuData.clear(); }

        // Build raw save directory: storage/{pt_number}/{date_time}/{studyUUID}/{orientation}/raw/
        String orientationFolder = orientation.equalsIgnoreCase("Transverse") ? "transverse" : "sagital";
        if (storageRoot != null) {
            String dateTime = createdDate + "_" + createdTime;
            rawDir = new File(storageRoot,
                    pt_number + "/" + dateTime + "/" + studyUUID + "/" + orientationFolder + "/raw");
            if (!rawDir.exists()) rawDir.mkdirs();
        }
    }

    public void StopFan(View view){
        stopFan = true;
        ((TextView) UIView.findViewById(R.id.textView)).setText("Sending data");
        if (mOrientationChangeCallback != null) {
            mOrientationChangeCallback.enable();
        }
        if (orientation.equals("Sagittal")){
            ((TextView) UIView.findViewById(R.id.textView)).setText("All Done, sending data");
        }
    }

    /**
     * Toggle handler wired from layout: switches between StartFan and StopFan.
     * Must be public for android:onClick lookup when inflating with a Service context.
     */
    public void toggleFan(View view) {
        // Find the toggle button in the inflated UI and update its label appropriately
        android.widget.Button btn = (android.widget.Button) UIView.findViewById(com.example.physioandroid.R.id.toggleButton);
        if (!fanFlag) {
            StartFan(view);
            if (btn != null) btn.setText("Stop");
        } else {
            StopFan(view);
            if (btn != null) btn.setText("Start");
        }
    }

    private void updateUIText(final String message) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                TextView textView = UIView.findViewById(R.id.textView);
                if (textView != null) {
                    textView.setText(message);
                }
            }
        });
    }

    private void updateStudyIDOverlay(final String newID) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (studyIDTextView != null) {
                    studyIDTextView.setText(newID);
                }
            }
        });
    }

    // ==================== WitMotion BLE IMU ====================

    @SuppressLint("MissingPermission")
    private void startIMUScan() {
        try {
            BluetoothManager btManager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
            if (btManager == null) return;
            bluetoothAdapter = btManager.getAdapter();
            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                Log.w(TAG, "Bluetooth not available or disabled - IMU will not connect");
                return;
            }

            // Runtime permission checks required for Android 12+ (API 31+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) != android.content.pm.PackageManager.PERMISSION_GRANTED
                        || checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "BLUETOOTH_SCAN / BLUETOOTH_CONNECT not granted at runtime - IMU scan skipped. Grant permissions from the Activity first.");
                    return;
                }
            } else {
                if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.w(TAG, "ACCESS_FINE_LOCATION not granted - IMU scan skipped.");
                    return;
                }
            }

            bluetoothAdapter.getBluetoothLeScanner().startScan(imuScanCallback);
            Log.d(TAG, "Started BLE scan for WitMotion IMU");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BLE scan: " + e.getMessage());
        }
    }

    private final ScanCallback imuScanCallback = new ScanCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice device = result.getDevice();
            Log.d(TAG, "BLE scan result: " + device.getAddress() + " (target: " + IMU_MAC_ADDRESS + ")");
            if (!imuConnected && IMU_MAC_ADDRESS.equals(device.getAddress())) {
                Log.d(TAG, "IMU_FOUND: Target WitMotion device found, stopping scan and connecting...");
                imuConnected = true;
                bluetoothAdapter.getBluetoothLeScanner().stopScan(imuScanCallback);
                bluetoothGatt = device.connectGatt(ScreenCaptureService.this, false, imuGattCallback);
                Log.d(TAG, "IMU_CONNECTING: connectGatt() called for " + IMU_MAC_ADDRESS);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.e(TAG, "IMU_SCAN_FAILED: BLE scan failed with error code " + errorCode);
        }
    };

    private final BluetoothGattCallback imuGattCallback = new BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            Log.d(TAG, "IMU_STATE_CHANGE: status=" + status + " newState=" + newState
                    + " (CONNECTED=" + BluetoothProfile.STATE_CONNECTED
                    + ", DISCONNECTED=" + BluetoothProfile.STATE_DISCONNECTED + ")");
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "IMU_CONNECTED: Successfully connected to WitMotion IMU. Discovering services...");
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                imuConnected = false;
                Log.w(TAG, "IMU_DISCONNECTED: status=" + status);
            } else {
                Log.w(TAG, "IMU_STATE_CHANGE: unhandled status=" + status + " newState=" + newState);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "IMU_SERVICES_DISCOVERED: status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Log all discovered services to help diagnose wrong UUIDs
                for (android.bluetooth.BluetoothGattService svc : gatt.getServices()) {
                    Log.d(TAG, "IMU_SERVICE: " + svc.getUuid());
                }
                try {
                    android.bluetooth.BluetoothGattService service =
                        gatt.getService(UUID.fromString("0000ffe5-0000-1000-8000-00805f9a34fb"));
                    if (service == null) {
                        Log.e(TAG, "IMU_ERROR: Service ffe5 not found! Check UUID or device model.");
                        return;
                    }
                    BluetoothGattCharacteristic characteristic =
                        service.getCharacteristic(UUID.fromString("0000ffe4-0000-1000-8000-00805f9a34fb"));
                    if (characteristic == null) {
                        Log.e(TAG, "IMU_ERROR: Characteristic ffe4 not found!");
                        return;
                    }
                    Log.d(TAG, "IMU_CHAR_FOUND: ffe4 properties=" + characteristic.getProperties());
                    gatt.setCharacteristicNotification(characteristic, true);
                    BluetoothGattDescriptor desc = characteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
                    if (desc == null) {
                        Log.e(TAG, "IMU_ERROR: CCCD descriptor (2902) not found on ffe4!");
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        int result = gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        Log.d(TAG, "IMU_DESCRIPTOR_WRITE (API33+): result=" + result);
                    } else {
                        desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                        boolean result = gatt.writeDescriptor(desc);
                        Log.d(TAG, "IMU_DESCRIPTOR_WRITE (legacy): result=" + result);
                    }
                    Log.d(TAG, "IMU_NOTIFICATIONS_REQUESTED: waiting for descriptor write callback");
                } catch (Exception e) {
                    Log.e(TAG, "IMU_ERROR: Failed to enable IMU notifications: " + e.getMessage(), e);
                }
            } else {
                Log.e(TAG, "IMU_ERROR: onServicesDiscovered failed with status=" + status);
            }
        }

        @SuppressLint("MissingPermission")
        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "IMU_DESCRIPTOR_WRITTEN: status=" + status
                    + " desc=" + descriptor.getUuid());
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "IMU_READY: Notifications enabled successfully. Waiting for data...");
            } else {
                Log.e(TAG, "IMU_ERROR: Descriptor write failed with status=" + status);
            }
        }

        // Android < 13 callback
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            parseImuData(characteristic.getValue());
        }

        // Android 13+ (API 33) callback — the deprecated overload above is no longer called
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, byte[] value) {
            parseImuData(value);
        }

        private void parseImuData(byte[] data) {
            if (data == null || data.length < 18) {
                Log.w(TAG, "IMU_DATA: packet too short or null, length=" + (data == null ? "null" : data.length));
                return;
            }
            int i = 2;
            imuRoll  = (float) (((data[i + 11]) << 8) | ((data[i + 10]) & 255)) / 32768 * 180;
            imuPitch = (float) (((data[i + 13]) << 8) | ((data[i + 12]) & 255)) / 32768 * 180;
            imuYaw   = (float) (((data[i + 15]) << 8) | ((data[i + 14]) & 255)) / 32768 * 180;
            Log.d(TAG, "IMU_DATA: roll=" + imuRoll + " pitch=" + imuPitch + " yaw=" + imuYaw
                    + " (raw len=" + data.length + ")");
        }
    };
}

/**
 * WebSocket client for sending scan data to PACS server.
 * Protocol mirrors SocketThread.java (com.carriertech.healson).
 */
class SocketThread {
    private static final String TAG = "SocketThread";
    private static final String WEBSOCKET_URL = "ws://carriertech.uk:7556";

    private WebSocketClient webSocketClient;
    private ArrayList<File> imageFiles;
    private JSONObject metadata;
    private int sentImages = 0;

    public interface ProgressCallback {
        void onProgressUpdate(int current, int total);
    }

    public interface CompletionCallback {
        void onComplete();
    }

    public interface ErrorCallback {
        void onError(String error);
    }

    private ProgressCallback progressCallback;
    private CompletionCallback completionCallback;
    private ErrorCallback errorCallback;

    /** Send scan data without progress tracking. */
    public void sendScanData(JSONObject metadata, ArrayList<File> imageFiles,
                             CompletionCallback onComplete, ErrorCallback onError) {
        sendScanData(metadata, imageFiles, null, onComplete, onError);
    }

    /** Send scan data with optional progress tracking. */
    public void sendScanData(JSONObject metadata, ArrayList<File> imageFiles,
                             ProgressCallback onProgress,
                             CompletionCallback onComplete, ErrorCallback onError) {
        this.progressCallback   = onProgress;
        this.completionCallback = onComplete;
        this.errorCallback      = onError;
        this.metadata           = metadata;
        this.imageFiles         = imageFiles;
        this.sentImages         = 0;

        try {
            URI serverUri = new URI(WEBSOCKET_URL);
            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    android.util.Log.d(TAG, "WebSocket connected to PACS server");
                    try {
                        send(metadata.toString());
                        android.util.Log.d(TAG, "Sent metadata");
                        sendNextImage();
                    } catch (Exception e) {
                        android.util.Log.e(TAG, "Error sending metadata", e);
                        if (errorCallback != null)
                            errorCallback.onError("Failed to send metadata: " + e.getMessage());
                    }
                }

                @Override
                public void onMessage(String message) {
                    android.util.Log.d(TAG, "Server: " + message);
                    try {
                        org.json.JSONObject response = new org.json.JSONObject(message);
                        if ("image_received".equals(response.optString("status"))) {
                            sendNextImage();
                        }
                    } catch (org.json.JSONException e) {
                        android.util.Log.e(TAG, "Error parsing response", e);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    android.util.Log.d(TAG, "WebSocket closed - code: " + code + ", reason: " + reason);
                    if (sentImages < imageFiles.size() && errorCallback != null)
                        errorCallback.onError("Connection closed after " + sentImages + " images");
                }

                @Override
                public void onError(Exception ex) {
                    android.util.Log.e(TAG, "WebSocket error", ex);
                    if (errorCallback != null)
                        errorCallback.onError("Connection error: " + ex.getMessage());
                }
            };
            webSocketClient.connect();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Error creating WebSocket connection", e);
            if (errorCallback != null)
                errorCallback.onError("Failed to connect: " + e.getMessage());
        }
    }

    private void sendNextImage() {
        if (sentImages >= imageFiles.size()) {
            // All images sent - signal completion
            try {
                org.json.JSONObject complete = new org.json.JSONObject();
                complete.put("type", "complete");
                webSocketClient.send(complete.toString());
                android.util.Log.d(TAG, "All images sent. Closing connection.");
                if (progressCallback != null)
                    progressCallback.onProgressUpdate(sentImages, imageFiles.size());
                if (completionCallback != null)
                    completionCallback.onComplete();
                webSocketClient.close();
            } catch (org.json.JSONException e) {
                android.util.Log.e(TAG, "Error sending completion signal", e);
                if (errorCallback != null)
                    errorCallback.onError("Failed to send completion signal: " + e.getMessage());
            }
            return;
        }

        File imageFile = imageFiles.get(sentImages);
        try {
            if (!imageFile.exists()) throw new Exception("File does not exist");
            if (!imageFile.canRead()) throw new Exception("File cannot be read");

            // Prefix with "processed_" if coming from processed/ dir (routes correctly server-side)
            String filenameToSend = imageFile.getName();
            String parentName = imageFile.getParentFile() != null
                    ? imageFile.getParentFile().getName().toLowerCase() : "";
            if (parentName.contains("processed") && !filenameToSend.startsWith("processed_"))
                filenameToSend = "processed_" + filenameToSend;

            android.util.Log.d(TAG, "Sending image " + (sentImages + 1) + "/" + imageFiles.size()
                    + ": " + filenameToSend + " (" + imageFile.length() + " bytes)");

            webSocketClient.send(filenameToSend);

            java.io.FileInputStream fis = new java.io.FileInputStream(imageFile);
            byte[] imageData = new byte[(int) imageFile.length()];
            int bytesRead = fis.read(imageData);
            fis.close();

            if (bytesRead != imageFile.length())
                throw new Exception("Failed to read entire file");

            webSocketClient.send(imageData);
            sentImages++;

            android.util.Log.d(TAG, "Sent image " + sentImages + "/" + imageFiles.size());
            if (progressCallback != null)
                progressCallback.onProgressUpdate(sentImages, imageFiles.size());

        } catch (Exception e) {
            android.util.Log.e(TAG, "Error sending image: " + imageFile.getAbsolutePath(), e);
            if (errorCallback != null)
                errorCallback.onError("Failed to send " + imageFile.getName() + ": " + e.getMessage());
            sentImages++;
            sendNextImage();
        }
    }
}
