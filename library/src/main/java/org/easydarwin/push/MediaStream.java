package org.easydarwin.push;

import android.app.Activity;
import android.app.Application;
import android.app.Service;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.annotation.MainThread;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;

import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.UVCCamera;

import org.easydarwin.audio.AudioStream;
import org.easydarwin.easypusher.BuildConfig;
import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.sw.TxtOverlay;
import org.easydarwin.util.Util;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static android.graphics.ImageFormat.NV21;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar;


public class MediaStream extends Service implements LifecycleObserver {


    public static final String EXTRA_ENABLE_AUDIO = "extra-enable-audio";
    private MediaBinder binder = new MediaBinder();
    private boolean mIsRecording;
    private boolean cameraPushing;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public class MediaBinder extends Binder {
        public MediaStream getService(){
            return MediaStream.this;
        }
    }

    static class MediaStreamPublisher implements Publisher<MediaStream>, LifecycleObserver{

        private final LifecycleOwner lifecyclerOwner;
        private  ServiceConnection conn;
        private final WeakReference<Context> context;

        private MediaStreamPublisher(Context context, LifecycleOwner owner) {
            this.context = new WeakReference(context);
            this.lifecyclerOwner = owner;
        }

        @Override
        public void subscribe(final Subscriber<? super MediaStream> s) {
            conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (service instanceof MediaBinder) {
                        MediaStream stream = ((MediaBinder) service).getService();
                        stream.lifecycle = lifecyclerOwner.getLifecycle();
                        lifecyclerOwner.getLifecycle().addObserver(MediaStreamPublisher.this);
                        lifecyclerOwner.getLifecycle().addObserver(stream);
                        s.onNext(stream);
                        s.onComplete();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {

                }
            };
            Context c = context.get();
            if (c == null) return;
            Intent serv = new Intent(c, MediaStream.class);
            if (!c.bindService(serv, conn, 0)){
                s.onError(new IllegalStateException("bindService error!"));
                s.onComplete();
            }
        }

        @OnLifecycleEvent(value = Lifecycle.Event.ON_DESTROY)
        void destory(){
            Context c = context.get();
            if (c == null) return;
            c.unbindService(conn);
        }
    }

    public static Publisher<MediaStream> getBindedMediaStream(final Context context, LifecycleOwner owner){
        final MediaStreamPublisher publisher = new MediaStreamPublisher(context, owner);
        return publisher;
    }


    private static final boolean VERBOSE = BuildConfig.DEBUG;
    private static final int SWITCH_CAMERA = 11;
    private boolean enanleVideo = true;
    private Lifecycle lifecycle;
    private final Pusher mEasyPusher = new EasyPusher();
    static final String TAG = "EasyPusher";
    int width = 640, height = 480;
    int framerate, bitrate;
    int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
    MediaCodec mMediaCodec;
    WeakReference<SurfaceTexture> mSurfaceHolderRef;
    Camera mCamera;
    AudioStream audioStream;
    private boolean isCameraBack = true;
    private int mDgree;
    private Application mApplicationContext;
    private boolean mSWCodec;
    private VideoConsumer mVC;
    private TxtOverlay overlay;
    private EasyMuxer mMuxer;
    private HandlerThread mCameraThread;
    private Handler mCameraHandler;
    private boolean cameraOpened;
    static ServiceConnection conn;
    static PushScreenService pushScreenService;
    private UVCCamera uvcCamera;
    private int mTargetCameraId;
    private Subscriber<? super Object> mSwitchCameraSubscriber;
    private Throwable uvcError;


    public static class CodecInfo {
        public String mName = "";
        public int mColorFormat = 0;
        boolean hevcEncode = false;
        public String mime = "";
    }
    public CodecInfo info = new CodecInfo();


    public void startStream(String ip, String port, String id, InitCallback callback) {
        mEasyPusher.initPush( mApplicationContext, callback);
        PushingState.sCodec = mSWCodec ? "x264":(info.hevcEncode ? "hevc":"avc");
        mEasyPusher.setMediaInfo(!mSWCodec && info.hevcEncode ? Pusher.Codec.EASY_SDK_VIDEO_CODEC_H265:Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
        mEasyPusher.start(ip, port, String.format("%s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);
    }

    public void pushScreen(final int resultCode, final Intent data, final String ip, final String port, final String id) {
        if (resultCode != Activity.RESULT_OK) {
            pushingScreenLiveData.postValue(new PushingState("", -3003, "用户取消", true));
            pushingScreenLiveData.postValue(new PushingState("", 0, "未开始", true));
            return;
        }
        stopStream();
        if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(port) || TextUtils.isEmpty(id)) {
            pushingScreenLiveData.postValue(new PushingState("", -3002, "参数异常", true));
            return;
        }


        if (pushScreenService != null) {
            stopPushScreen();

        }else{
            Intent intent = new Intent(mApplicationContext, PushScreenService.class);

            conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    PushScreenService.MyBinder binder = (PushScreenService.MyBinder) service;
                    pushScreenService = binder.getService();
                    pushScreenService.startVirtualDisplay(resultCode, data, ip, port, id, pushingScreenLiveData);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    pushScreenService = null;
                    pushingScreenLiveData.postValue(new PushingState("", 0, "未开始", true));
                }
            };
            mApplicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        }
    }

    public void stopPushScreen() {
        stopPushScreen(mApplicationContext);
    }

    static void stopPushScreen(Application app) {
        if (pushScreenService != null) {
            app.unbindService(conn);
            pushScreenService = null;
            conn = null;
        }
        pushingScreenLiveData.postValue(new PushingState("", 0, "未开始", true));
    }


    public static class PushingState {
        public final int state;
        public final String msg;
        public final String url;
        public final boolean screenPushing;
        static String sCodec ="avc";
        public String videoCodec = sCodec;

        public PushingState(int state, String msg) {
            this.state = state;
            this.msg = msg;
            screenPushing = false;
            url = "";
        }

        public PushingState(String url, int state, String msg, boolean screenPushing) {
            this.url = url;
            this.state = state;
            this.msg = msg;
            this.screenPushing = screenPushing;
        }
    }


    public static class CameraPreviewResolutionLiveData extends LiveData<int[]> {

        @Override
        protected void postValue(int[] value) {
            super.postValue(value);
        }

    }

    public static class PushingStateLiveData extends LiveData<PushingState> {
        @Override
        protected void postValue(PushingState value) {
            super.postValue(value);
        }

    }

    public static class PushingScreenLiveData extends LiveData<PushingState> {

        @Override
        protected void postValue(PushingState value) {
            super.postValue(value);
        }
    }

    private static final CameraPreviewResolutionLiveData cameraPreviewResolution = new CameraPreviewResolutionLiveData();
    private static final PushingStateLiveData pushingStateLiveData = new PushingStateLiveData();
    static final PushingScreenLiveData pushingScreenLiveData = new PushingScreenLiveData();

    BlockingQueue<byte[]> bufferQueue = new ArrayBlockingQueue<byte[]>(10);
    BlockingQueue<byte[]> cache = new ArrayBlockingQueue<byte[]>(100);
    final Runnable dequeueRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                byte[] data = bufferQueue.poll(10, TimeUnit.MICROSECONDS);
                if (data != null) {
                    onPreviewFrame2(data, uvcCamera);
                    cache.offer(data);
                }
                if (uvcCamera == null) return;
                mCameraHandler.post(this);
            } catch (InterruptedException ex) {
                ex.printStackTrace();
            }
        }
    };
    final Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {

        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            onPreviewFrame2(data, camera);
        }

    };
    final IFrameCallback uvcFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(ByteBuffer frame) {
            if (uvcCamera == null) return;
            Thread.currentThread().setName("UVCCamera");
            frame.clear();
            byte[] data = cache.poll();
            if (data == null) {
                data = new byte[frame.capacity()];
            }
            frame.get(data);
//            bufferQueue.offer(data);
//
//            mCameraHandler.post(dequeueRunnable);

            onPreviewFrame2(data, uvcCamera);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        mApplicationContext = getApplication();
        File youyuan = getFileStreamPath("SIMYOU.ttf");
        if (!youyuan.exists()){
            AssetManager am = getAssets();
            try {
                InputStream is = am.open("zk/SIMYOU.ttf");
                FileOutputStream os = openFileOutput("SIMYOU.ttf", MODE_PRIVATE);
                byte[] buffer = new byte[1024];
                int len = 0;
                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }
                os.close();
                is.close();

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        mSurfaceHolderRef = new WeakReference(null);
        mCameraThread = new HandlerThread("CAMERA") {
            public void run() {
                try {
                    super.run();
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    if (pushScreenService != null) {
                        // 推送屏幕在关闭后不停止.
//            mApplicationContext.unbindService(conn);
//            pushScreenService = null;
                    } else {
                        stopStream();
                    }
                    destroyCamera();
                }
            }
        };
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
//        return super.onStartCommand(intent, flags, startId);

        intent = new Intent(mApplicationContext, UVCCameraService.class);
        mApplicationContext.startService(intent);

        return START_NOT_STICKY;
    }

    public void onPreviewFrame2(byte[] data, Object camera) {
        if (camera instanceof Camera) {
            if (mDgree == 0) {
                Camera.CameraInfo camInfo = new Camera.CameraInfo();
                Camera.getCameraInfo(mCameraId, camInfo);
                int cameraRotationOffset = camInfo.orientation;

                if (cameraRotationOffset % 180 != 0) {
                    yuvRotate(data, 1, width, height, cameraRotationOffset);
                }
                save2file(data, String.format("/sdcard/yuv_%d_%d.yuv", height, width));
            }
            if (PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key_enable_video_overlay", true)) {
                String txt;// = String.format("drawtext=fontfile=" + mApplicationContext.getFileStreamPath("SIMYOU.ttf") + ": text='%s%s':x=(w-text_w)/2:y=H-60 :fontcolor=white :box=1:boxcolor=0x00000000@0.3", "EasyPusher", new SimpleDateFormat("yyyy-MM-ddHHmmss").format(new Date()));
                txt = "EasyPusher " + new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
                overlay.overlay(data, txt);
            }
            mVC.onVideo(data, NV21);
            mCamera.addCallbackBuffer(data);
        } else {
            if (PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key_enable_video_overlay", true)) {
                String txt;// = String.format("drawtext=fontfile=" + mApplicationContext.getFileStreamPath("SIMYOU.ttf") + ": text='%s%s':x=(w-text_w)/2:y=H-60 :fontcolor=white :box=1:boxcolor=0x00000000@0.3", "EasyPusher", new SimpleDateFormat("yyyy-MM-ddHHmmss").format(new Date()));
                txt = "EasyPusher " + new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
                overlay.overlay(data, txt);
            }
            mVC.onVideo(data, NV21);
        }
    }


    @MainThread
    public void startStream(final String ip, final String port, final String id) {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    startStream(ip,port, id);
                }
            });
            return;
        }
        stopStream();
        stopPushScreen();
        cameraPushing = true;
        InitCallback callback = new InitCallback() {
            @Override
            public void onCallback(int code) {
                String msg = "";
                String url = String.format("rtsp://%s:%s/%s.sdp", ip,port,id);
                switch (code) {
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_INVALID_KEY:
                        msg = ("无效Key");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_SUCCESS:
                        msg = ("未开始");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTING:
                        msg = ("连接中");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECTED:
                        msg = ("连接成功");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_FAILED:
                        msg = ("连接失败");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_CONNECT_ABORT:
                        msg = ("连接异常中断");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_PUSHING:
                        msg = ("推流中");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_PUSH_STATE_DISCONNECTED:
                        msg = ("断开连接");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PLATFORM_ERR:
                        msg = ("平台不匹配");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_COMPANY_ID_LEN_ERR:
                        msg = ("授权使用商不匹配");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PROCESS_NAME_LEN_ERR:
                        msg = ("进程名称长度不匹配");
                        break;
                }
                pushingStateLiveData.postValue(new PushingState(url, code, msg, false));
            }
        };

//        mEasyPusher.initPush(ip, port, String.format("%s.sdp", id), mApplicationContext, callback);
        startStream(ip,port,id, callback);
    }


    @MainThread
    public void observeCameraPreviewResolution(LifecycleOwner owner, Observer<int[]> observer) {
        cameraPreviewResolution.observe(owner, observer);
    }

    @MainThread
    public void observePushingState(LifecycleOwner owner, Observer<PushingState> observer) {
        pushingStateLiveData.observe(owner, observer);
        pushingScreenLiveData.observe(owner, observer);
    }

    public PushingState getPushingState() {
        return pushingStateLiveData.getValue();
    }


    public boolean isScreenPushing(){
        return pushScreenService != null;
    }


    public boolean isCameraPushing(){
        return cameraPushing;
    }

    public PushingState getScreenPushingState() {
        return pushingScreenLiveData.getValue();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void start() {
        if (cameraOpened)
            if (cameraCanOpenNow()) {
                createCamera();
                startPreview();
            }
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void stop() {
        if (cameraOpened)
            stopPreview();
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    public void destory() {
        if (false)
        closeCameraPreview();
        if (lifecycle != null) lifecycle.removeObserver(this);
    }

    @MainThread
    public void openCameraPreview() {
        cameraOpened = true;
        if (cameraCanOpenNow()) {
            createCamera();
            startPreview();
        }
    }

    private boolean cameraCanOpenNow() {
        if (lifecycle.getCurrentState().isAtLeast(Lifecycle.State.CREATED)) {
            if (ActivityCompat.checkSelfPermission(getApplication(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(getApplication(), android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                // connect if not connected
                if (mSurfaceHolderRef != null && mSurfaceHolderRef.get() != null) {
                    return true;
                }
            }
        }
        return false;
    }

    @MainThread
    public void closeCameraPreview() {
        cameraOpened = false;
        stopPreview();
        destroyCamera();
    }

    public static int[] determineMaximumSupportedFramerate(Camera.Parameters parameters) {
        int[] maxFps = new int[]{0, 0};
        List<int[]> supportedFpsRanges = parameters.getSupportedPreviewFpsRange();
        for (Iterator<int[]> it = supportedFpsRanges.iterator(); it.hasNext(); ) {
            int[] interval = it.next();
            if (interval[1] > maxFps[1] || (interval[0] > maxFps[0] && interval[1] == maxFps[1])) {
                maxFps = interval;
            }
        }
        return maxFps;
    }


    public static void initEncoder(Context context, CodecInfo info){
        info.hevcEncode = false;
        boolean try265Encode = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("try_265_encode", false);
        ArrayList<CodecInfo> infos = listEncoders(try265Encode ?MediaFormat.MIMETYPE_VIDEO_HEVC:MediaFormat.MIMETYPE_VIDEO_AVC);
        if (infos.isEmpty()) {
            if (try265Encode){
                infos = listEncoders(MediaFormat.MIMETYPE_VIDEO_AVC);
            }
        }else{
            if (try265Encode) info.hevcEncode = true;
        }
        if (!infos.isEmpty()) {
            CodecInfo ci = infos.get(0);
            info.mName = ci.mName;
            info.mColorFormat = ci.mColorFormat;
            info.mime = ci.mime;
        }else{
            info.mName = "";
            info.mColorFormat = 0;
        }
    }

    protected void createCamera() {

        mSWCodec = PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key-sw-codec", false);
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                    createCamera();
                }
            });
            return;
        }
        if (!enanleVideo) {
            return;
        }

        if (!mSWCodec) {
            initEncoder(mApplicationContext, info);
            if (TextUtils.isEmpty(info.mName) && info.mColorFormat == 0) {
                mSWCodec = true;
            }
        }

        if (mCameraId == 2) {
            UVCCamera value = UVCCameraService.liveData.getValue();
            if (value != null) {
                // uvc camera.
                uvcCamera = value;
                value.setPreviewSize(width, height,1, 30, UVCCamera.PIXEL_FORMAT_YUV420SP,1.0f);
                return;
//            value.startPreview();
            }else{
                Log.i(TAG, "NO UVCCamera");
                uvcError = new Exception("no uvccamera connected!");
                return;
            }
//            mCameraId = 0;
        }

        if (mCamera != null) return;
        if (!enanleVideo) {
            return;
        }
        try {
            mCamera = Camera.open(mCameraId);
            mCamera.setErrorCallback(new Camera.ErrorCallback() {
                @Override
                public void onError(int i, Camera camera) {
                    throw new IllegalStateException("Camera Error:" + i);
                }
            });
            Log.i(TAG, "open Camera");

            Camera.Parameters parameters = mCamera.getParameters();
            int[] max = determineMaximumSupportedFramerate(parameters);
            Camera.CameraInfo camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, camInfo);
            int cameraRotationOffset = camInfo.orientation;
            if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT)
                cameraRotationOffset += 180;
            int rotate = (360 + cameraRotationOffset - mDgree) % 360;
            parameters.setRotation(rotate);
            parameters.setRecordingHint(true);

//            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();
            parameters.setPreviewSize(width, height);
//            parameters.setPreviewFpsRange(max[0], max[1]);
            parameters.setPreviewFrameRate(20);

//            int maxExposureCompensation = parameters.getMaxExposureCompensation();
//            parameters.setExposureCompensation(3);
//
//            if(parameters.isAutoExposureLockSupported()) {
//                parameters.setAutoExposureLock(false);
//            }

//            parameters.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
//            parameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
//            parameters.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
//            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
//            mCamera.setFaceDetectionListener(new );

//            if (parameters.isAutoWhiteBalanceLockSupported()){
//                parameters.setAutoExposureLock(false);
//            }

            mCamera.setParameters(parameters);
            Log.i(TAG, "setParameters");
            int displayRotation;
            displayRotation = (cameraRotationOffset - mDgree + 360) % 360;
            mCamera.setDisplayOrientation(displayRotation);

            Log.i(TAG, "setDisplayOrientation");
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String stack = sw.toString();
            destroyCamera();
            e.printStackTrace();
        }
    }

    private void save2file(byte[] data, String path) {
        if (true) return;
        try {
            FileOutputStream fos = new FileOutputStream(path, true);
            fos.write(data);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // 根据Unicode编码完美的判断中文汉字和符号
    private static boolean isChinese(char c) {
        Character.UnicodeBlock ub = Character.UnicodeBlock.of(c);
        if (ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS || ub == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A || ub == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || ub == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION || ub == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
                || ub == Character.UnicodeBlock.GENERAL_PUNCTUATION) {
            return true;
        }
        return false;
    }

    private int getTxtPixelLength(String txt, boolean zoomed) {
        int length = 0;
        int fontWidth = zoomed ? 16 : 8;
        for (int i = 0; i < txt.length(); i++) {
            length += isChinese(txt.charAt(i)) ? fontWidth * 2 : fontWidth;
        }
        return length;
    }

    public synchronized boolean isRecording(){
        return mIsRecording;
    }

    public synchronized void startRecord(final String path, final long maxDurationMillis) {
        mIsRecording = true;
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRecord(path, maxDurationMillis);
                }
            });
            return;
        }
        try {
            mMuxer = new EasyMuxer(path, maxDurationMillis);
            if (mVC == null || audioStream == null) {
                throw new IllegalStateException("you need to start preview before startRecord!");
            }
            mVC.setMuxer(mMuxer);
            audioStream.setMuxer(mMuxer);
        } catch (Exception e) {
            e.printStackTrace();
            mIsRecording = false;
        }
    }


    public synchronized void stopRecord() {
        mIsRecording = false;
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopRecord();
                }
            });
            return;
        }
        if (mVC == null || audioStream == null) {
//            nothing
        } else {
            mVC.setMuxer(null);
            audioStream.setMuxer(null);
        }
        if (mMuxer != null) mMuxer.release();
        mMuxer = null;
    }

    /**
     * 开启预览
     */
    protected synchronized void startPreview() {

        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    createCamera();
                    startPreview();
                }
            });
            return;
        }
        boolean rotate = false;
        UVCCamera value = uvcCamera;
        if (value != null) {
            SurfaceTexture holder = mSurfaceHolderRef.get();
            if (holder != null) {
                value.setPreviewTexture(holder);
            }
            try {
                value.setFrameCallback(uvcFrameCallback, UVCCamera.PIXEL_FORMAT_YUV420SP/*UVCCamera.PIXEL_FORMAT_NV21*/);
                value.startPreview();
                cameraPreviewResolution.postValue(new int[]{width, height});
            }catch (Throwable e){
                uvcError = e;
            }

        }else if (mCamera != null) {
            int previewFormat = mCamera.getParameters().getPreviewFormat();
            Camera.Size previewSize = mCamera.getParameters().getPreviewSize();
            int size = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(previewFormat) / 8;
            width = previewSize.width;
            height = previewSize.height;
            mCamera.addCallbackBuffer(new byte[size]);
            mCamera.addCallbackBuffer(new byte[size]);
            mCamera.setPreviewCallbackWithBuffer(previewCallback);


            if (Util.getSupportResolution(mApplicationContext).size() == 0) {
                StringBuilder stringBuilder = new StringBuilder();
                List<Camera.Size> supportedPreviewSizes = mCamera.getParameters().getSupportedPreviewSizes();
                for (Camera.Size str : supportedPreviewSizes) {
                    stringBuilder.append(str.width + "x" + str.height).append(";");
                }
                Util.saveSupportResolution(mApplicationContext, stringBuilder.toString());
            }
            cameraPreviewResolution.postValue(new int[]{width, height});
            try {
                SurfaceTexture holder = mSurfaceHolderRef.get();
                if (holder != null) {
                    mCamera.setPreviewTexture(holder);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }


            mCamera.startPreview();
            try {
                mCamera.autoFocus(null);
            } catch (Exception e) {
                //忽略异常
                Log.i(TAG, "auto foucus fail");
            }


            if (mDgree == 0) {
                Camera.CameraInfo camInfo = new Camera.CameraInfo();
                Camera.getCameraInfo(mCameraId, camInfo);
                int cameraRotationOffset = camInfo.orientation;
                if (cameraRotationOffset == 90) {
                    rotate = true;
                } else if (cameraRotationOffset == 270) {
                    rotate = true;
                }
            }


        }
        overlay = new TxtOverlay(mApplicationContext);
        try {
            if (mSWCodec) {
                mVC = new SWConsumer(mApplicationContext, mEasyPusher);
            } else {
                mVC = new HWConsumer(mApplicationContext, mEasyPusher, info);
            }
            if (!rotate) {
                mVC.onVideoStart(width, height);
                overlay.init(width, height, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
            } else {
                mVC.onVideoStart(height, width);
                overlay.init(height, width, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } catch (IllegalArgumentException ex) {
            ex.printStackTrace();
        }
        audioStream = new AudioStream(mEasyPusher);
        audioStream.startRecord();
    }


    /**
     * 旋转YUV格式数据
     *
     * @param src    YUV数据
     * @param format 0，420P；1，420SP
     * @param width  宽度
     * @param height 高度
     * @param degree 旋转度数
     */
    private static void yuvRotate(byte[] src, int format, int width, int height, int degree) {
        int offset = 0;
        if (format == 0) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += (width * height);
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
            offset += width * height / 4;
            JNIUtil.rotateMatrix(src, offset, width / 2, height / 2, degree);
        } else if (format == 1) {
            JNIUtil.rotateMatrix(src, offset, width, height, degree);
            offset += width * height;
            JNIUtil.rotateShortMatrix(src, offset, width / 2, height / 2, degree);
        }
    }

    /**
     * 停止预览
     */
    protected synchronized void stopPreview() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopPreview();
                }
            });
            return;
        }
        UVCCamera value = uvcCamera;
        if (value != null) {
            value.stopPreview();
        }
        mCameraHandler.removeCallbacks(dequeueRunnable);
        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);
        }
        if (audioStream != null) {
            audioStream.stop();
            audioStream = null;
        }
        if (mVC != null)
            mVC.onVideoStop();
        if (overlay != null)
            overlay.release();

        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
        destroyCamera();
    }

    public Publisher<Camera> getCamera() {
        return new Publisher<Camera>() {
            @Override
            public void subscribe(final Subscriber<? super Camera> s) {
                if (mCameraHandler == null) s.onError(new IllegalStateException());
                if (!mCameraHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        s.onNext(mCamera);
                        s.onComplete();
                    }
                })) {
                    s.onError(new IllegalStateException());
                }
            }
        };
    }

    /**
     *
     * @param cameraId      0表示后置,1表示前置,2表示uvc摄像头,-1表示默认切换(比如前后置来回切换.).在非-1的情况下,如果没有ID对应的摄像头,则也会作默认切换.
     */
    public Publisher<Object> switchCamera(final int cameraId) {
        Publisher pub = new Publisher<Object>() {
            @Override
            public void subscribe(Subscriber<? super Object> s) {
                mSwitchCameraSubscriber = s;
                mTargetCameraId = cameraId;
                mCameraHandler.removeCallbacks(switchCameraTask);
                mCameraHandler.post(switchCameraTask);
            }
        };
        return pub;
    }

    public void switchCamera(){
        switchCamera(-1).subscribe(new Subscriber<Object>() {
            @Override
            public void onSubscribe(Subscription s) {

            }

            @Override
            public void onNext(Object o) {

            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }

            @Override
            public void onComplete() {

            }
        });
    }

    private Runnable switchCameraTask = new Runnable() {
        @Override
        public void run() {
            int cameraCount = 0;
            if (isCameraBack) {
                isCameraBack = false;
            } else {
                isCameraBack = true;
            }
            try {
                if (!enanleVideo) return;
                if (mTargetCameraId != -1 && mCameraId == mTargetCameraId) {
                    if (uvcCamera != null || mCamera != null) {
                        return;
                    }
                }
                if (mTargetCameraId == -1) {
                    if (mCameraId == Camera.CameraInfo.CAMERA_FACING_BACK) {
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
                    } else if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                        // 尝试切换到外置摄像头...
                        mCameraId = 2;
                    } else {
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
                    }
                } else {
                    mCameraId = mTargetCameraId;
                }
                uvcError = null;
                stopPreview();
                destroyCamera();
                createCamera();
                startPreview();
            }finally {
                if (uvcCamera != null){
                    if (mSwitchCameraSubscriber != null){
                        mSwitchCameraSubscriber.onNext(uvcCamera);
                    }
                }else if (mCamera != null){
                    if (mSwitchCameraSubscriber != null){
                        mSwitchCameraSubscriber.onNext(mCamera);
                    }
                }else {
                    if (mSwitchCameraSubscriber != null){
                        if (uvcError != null){
                            mSwitchCameraSubscriber.onError(uvcError);
                        }else {
                            mSwitchCameraSubscriber.onError(new IOException("could not create camera of id:" + mCameraId));
                        }
                    }else{
//                        uvcCamera = new UVCCamera();
//                        mSwitchCameraSubscriber.onNext(uvcCamera);
//                        if (uvcFrameCallback != null){
//                            new Thread(new Runnable() {
//                                @Override
//                                public void run() {
//                                    long begin = SystemClock.elapsedRealtime();
//                                    ByteBuffer bf = ByteBuffer.allocate(640*480*3/2);
//                                    while (SystemClock.elapsedRealtime() -begin <= 30*1000){
//                                        uvcFrameCallback.onFrame(bf);
//                                        try {
//                                            Thread.sleep(33);
//                                        } catch (InterruptedException e) {
//                                            e.printStackTrace();
//                                        }
//                                    }
//                                }
//                            }).start();
//                        }
                    }
                }
            }

        }
    };

    private String recordPath = Environment.getExternalStorageDirectory().getPath();

    public void setRecordPath(String recordPath) {
        this.recordPath = recordPath;
    }

    /**
     * 销毁Camera
     */
    protected synchronized void destroyCamera() {

        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    destroyCamera();
                }
            });
            return;
        }

        UVCCamera value = uvcCamera;
        if (value != null) {
//            value.destroy();
            uvcCamera = null;
        }
        if (mCamera != null) {
            mCamera.stopPreview();
            try {
                mCamera.release();
            } catch (Exception e) {
            }
            mCamera = null;
        }
        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    public void stopStream() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    stopStream();
                }
            });
            return;
        }
        mEasyPusher.stop();
        pushingStateLiveData.postValue(new PushingState(0, "未开始"));
//        pushingScreenLiveData.postValue(new PushingState("", 0, "未开始", true));

//        if (pushScreenService != null) {
//            mApplicationContext.unbindService(conn);
//            pushScreenService = null;
//        }

        cameraPushing = false;
    }

    @MainThread
    public void setSurfaceTexture(final SurfaceTexture texture) {
        if (texture == null) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (uvcCamera != null){
                        uvcCamera.setPreviewDisplay((Surface) null);
                    }else {
                        stopPreview();
                    }
                }
            });

            mSurfaceHolderRef = null;
        } else {
            mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (uvcCamera != null){
                        uvcCamera.setPreviewDisplay(new Surface(texture));
                    }else {
                        stopPreview();
                        if (cameraOpened) openCameraPreview();
                    }
                }
            });

        }
    }

    @MainThread
    public void notifyPermissionGranted() {
        if (cameraOpened) openCameraPreview();
    }

    protected void release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mCameraThread.quitSafely();
        } else {
            if (!mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    mCameraThread.quit();
                }
            })) {
                mCameraThread.quit();
            }
        }
        try {
            mCameraThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void onDestroy() {
        super.onDestroy();

        if (pushScreenService != null) {
            // 推送屏幕在关闭后不停止.
//            mApplicationContext.unbindService(conn);
//            pushScreenService = null;
        } else {
            stopStream();
        }
        stopPreview();
        destroyCamera();
        release();

        Intent intent = new Intent(mApplicationContext, UVCCameraService.class);
        mApplicationContext.stopService(intent);
    }



    public static ArrayList<CodecInfo> listEncoders(String mime) {
        // 可能有多个编码库，都获取一下。。。
        ArrayList<CodecInfo> codecInfos = new ArrayList<CodecInfo>();
        int numCodecs = MediaCodecList.getCodecCount();
        // int colorFormat = 0;
        // String name = null;
        for (int i1 = 0; i1 < numCodecs; i1++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i1);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            if (codecMatch(mime, codecInfo)) {
                String name = codecInfo.getName();
                int colorFormat = getColorFormat(codecInfo, mime);
                if (colorFormat != 0) {
                    CodecInfo ci = new CodecInfo();
                    ci.mName = name;
                    ci.mColorFormat = colorFormat;
                    ci.mime = mime;
                    codecInfos.add(ci);
                }
            }
        }
        return codecInfos;
    }

    public static boolean codecMatch(String mimeType, MediaCodecInfo codecInfo) {
        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }

    public static int getColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int[] cf = new int[capabilities.colorFormats.length];
        System.arraycopy(capabilities.colorFormats, 0, cf, 0, cf.length);
        List<Integer> sets = new ArrayList<>();
        for (int i = 0; i < cf.length; i++) {
            sets.add(cf[i]);
        }
        if (sets.contains(COLOR_FormatYUV420SemiPlanar)) {
            return COLOR_FormatYUV420SemiPlanar;
        } else if (sets.contains(COLOR_FormatYUV420Planar)) {
            return COLOR_FormatYUV420Planar;
        } else if (sets.contains(COLOR_FormatYUV420PackedPlanar)) {
            return COLOR_FormatYUV420PackedPlanar;
        } else if (sets.contains(COLOR_TI_FormatYUV420PackedSemiPlanar)) {
            return COLOR_TI_FormatYUV420PackedSemiPlanar;
        }
        return 0;
    }
}
