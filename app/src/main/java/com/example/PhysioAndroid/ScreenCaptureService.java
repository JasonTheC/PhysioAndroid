package com.example.PhysioAndroid;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
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

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


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
            poseImagesToSend.add(byteArray);
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
            if(stopFan){
                fanFlag = false;  // Stop capturing new images immediately
                try {
                    String currentOrientation = orientation;
                    JSONObject dataToSend = new JSONObject();
                    dataToSend.put("startFan", startFan);
                    dataToSend.put("imageType","voxel");
                    dataToSend.put("target", bodyPart);
                    dataToSend.put("pt_number", pt_number);
                    dataToSend.put("orientation", orientation);
                    dataToSend.put("timeList", timeList.toString());
                    dataToSend.put("pitchList", "[]");
                    Log.e("to send", "data to send = " + dataToSend);
                    message = socketThread.sendList((List<byte[]>) poseImagesToSend, dataToSend);
                    setMessage(message);
                    poseImagesToSend.clear();
                    timeList.clear();
                    pitchList.clear();
                    IMAGES_PRODUCED=0;
                    stopFan = false;  // Reset stopFan after sending
                    if (currentOrientation.equals("Transverse")){
                        orientation = "Sagittal";
                        
                    } else if (currentOrientation.equals("Sagittal")) {
                        // Generate new studyID after sagittal sweep
                        pt_number = UUID.randomUUID().toString();
                        updateStudyIDOverlay(pt_number);
                        // Reset orientation for next study
                        orientation = "Transverse";
                    }
                }catch (Exception e){
                    Log.e("return","Exceptiong = " + e);
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

        //For screen grab
        socketThread = new SocketThread();
        socketThread.start();

     


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
    }
    public void StartFan(View view) {
        ((TextView) UIView.findViewById(R.id.textView)).setText("Fan Through");
        stopFan = false;
        fanFlag = true;
        if (mOrientationChangeCallback != null) {
            mOrientationChangeCallback.disable();
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
}

class SocketThread extends Thread {
    byte[] data;
    boolean curSending = false;
    public static Handler socketHandler;
    public static final int SERVER_PORT = 8888;

    private PrintWriter mBufferOut;
    private BufferedReader mBufferIn;
    public Socket socket = null;
    private boolean running = false;
    private int target = 1;
    public int subBodyPart = 2;

    @Override
    public void run() {
        super.run();
        URL url = null;
        try {
            url = new URL("http://www.carriertech.uk");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        try {
            
            InetAddress serverAddr = InetAddress.getByName(url.getHost());
            socket = new Socket(serverAddr, SERVER_PORT);
            //Log.e("socketthread","the socket is = " + socket);
            mBufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));

        } catch (IOException e) {
            Log.e("socketthread","failed = " + e);
        }

    }
    public String sendData(String msg, byte[] byteArray) throws InterruptedException, IOException {
        String mServerMessage = "";
        //Log.e("messagesend","in thread and cursending is "+curSending);
        if (!curSending) {
            curSending = true;
            byte[] messageByte = new byte[20];

            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());
            try {
                dos.write(byteArray);
                dos.writeChars("ENDOFIMAGE");
                dos.writeUTF(msg);
                dos.writeChars("ENDOFFILE");
                int bytesRead = dis.read(messageByte);
                mServerMessage+= new String(messageByte, 0, bytesRead);

            } catch (IOException e) {
                Log.e("messagesend", String.valueOf(e));
                e.printStackTrace();
            }

        curSending = false;
        }
        return mServerMessage;
    }

    public String sendList(List<byte[]> poseImagesToSend, JSONObject poseJSONsToSend) throws IOException {
        String message = "";
        Socket socket = null;
        DataOutputStream dos = null;
        DataInputStream dis = null;
        BufferedReader mBufferIn = null;
        URL url = new URL("http://carriertech.uk");
        try {
            InetAddress serverAddr = InetAddress.getByName(url.getHost());
            Log.e("sendList", "Resolved IP: " + serverAddr.getHostAddress());
            Log.e("sendList", "Starting to send data to server");
            socket = new Socket(serverAddr, SERVER_PORT);
            //Log.e("socketthread","the socket is = " + socket);
            mBufferIn = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String mServerMessage = "";
            dos = new DataOutputStream(socket.getOutputStream());
            dis = new DataInputStream(socket.getInputStream());
            byte[] messageByte = new byte[10];
            int c = 0;
            for (byte[] image : poseImagesToSend) {
                c++;
                dos.write(image);
                dos.writeBytes("ENDOFIMAGE");
                Log.i("socket", "sent image " + c);
            }
            String DTS = poseJSONsToSend.toString();
            dos.writeUTF(DTS);
            dos.writeBytes("ENDOFFILE");
            message = "All data has been sent";
            Log.e("sendList", "Data sent, waiting for response");
            int bytesRead = dis.read(messageByte);
            mServerMessage += new String(messageByte, 0, bytesRead);
            Log.e("sendList", "Server response: " + mServerMessage);
        }catch (IOException e) {
            Log.e("sendList", "Error sending data: " + e.getMessage());
            e.printStackTrace();
            message = "There was an error sending the data";
        }finally {
            // Close everything in the finally block
            try {
                if (dos != null) dos.close();
                if (dis != null) dis.close();
                if (mBufferIn != null) mBufferIn.close();
                if (socket != null) socket.close();
            } catch (IOException e) {
                Log.e("return", "Error closing resources: " + e.getMessage());
            }
        }
        return message;
    }
}
