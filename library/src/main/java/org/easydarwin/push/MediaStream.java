package org.easydarwin.push;

import android.app.Activity;
import android.app.Application;
import android.arch.lifecycle.AndroidViewModel;
import android.arch.lifecycle.Lifecycle;
import android.arch.lifecycle.LifecycleObserver;
import android.arch.lifecycle.LifecycleOwner;
import android.arch.lifecycle.LiveData;
import android.arch.lifecycle.Observer;
import android.arch.lifecycle.OnLifecycleEvent;
import android.arch.lifecycle.ViewModel;
import android.arch.lifecycle.ViewModelProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodec;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.preference.PreferenceManager;
import android.support.annotation.MainThread;
import android.support.v4.app.ActivityCompat;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import org.easydarwin.audio.AudioStream;
import org.easydarwin.easypusher.BuildConfig;
import org.easydarwin.easypusher.EasyApplication;
import org.easydarwin.easypusher.R;
import org.easydarwin.easyrtmp.push.EasyRTMP;
import org.easydarwin.hw.EncoderDebugger;
import org.easydarwin.hw.NV21Convertor;
import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.sw.TxtOverlay;
import org.easydarwin.util.Util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;


public class MediaStream extends AndroidViewModel implements LifecycleObserver {
    private static final boolean VERBOSE = BuildConfig.DEBUG;
    private static final int SWITCH_CAMERA = 11;
    private final boolean enanleVideo;
    private Lifecycle lifecycle;
    Pusher mEasyPusher;
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
    private final Application mApplicationContext;
    private boolean mSWCodec;
    private VideoConsumer mVC;
    private TxtOverlay overlay;
    private EasyMuxer mMuxer;
    private final HandlerThread mCameraThread;
    private final Handler mCameraHandler;
    private EncoderDebugger debugger;
    private int previewFormat;
    private boolean shouldStartPreview;
    private boolean cameraOpened;
    private ServiceConnection conn;
    private PushScreenService pushScreenService;

    public void pushScreen(final int resultCode, final Intent data, final String ip, final String port ,final String id) {
        pushingScreenLiveData.postValue(new PushingState(0,"未开始", true));
        if (resultCode == Activity.RESULT_OK){

            if (TextUtils.isEmpty(ip) || TextUtils.isEmpty(port)||TextUtils.isEmpty(id))
            {
                pushingScreenLiveData.postValue(new PushingState(-3002,"参数异常", true));
                return;
            }
            Intent intent = new Intent(mApplicationContext, PushScreenService.class);

            conn = new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    PushScreenService.MyBinder binder = (PushScreenService.MyBinder) service;
                    pushScreenService = binder.getService();
                    pushScreenService.startVirtualDisplay(resultCode, data, pushingScreenLiveData, ip, port, id);
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    pushScreenService = null;
                }
            };
            mApplicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE);
        }else{
            pushingScreenLiveData.postValue(new PushingState(-3003,"用户取消", true));
        }
    }


    public static class PushingState {
        public final int state;
        public final String msg;
        public final boolean screenPushing;

        public PushingState(int state, String msg) {
            this.state = state;
            this.msg = msg;
            screenPushing = false;
        }

        public PushingState(int state, String msg, boolean screenPushing){
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
        private PushingState mOldValue;

        @Override
        protected void postValue(PushingState value) {
            if (mOldValue != null && mOldValue.state == value.state) return;
            mOldValue = value;
            super.postValue(value);
        }

    }

    public class PushingScreenLiveData extends LiveData<PushingState>{
        private PushingState mOldValue;

        @Override
        protected void postValue(PushingState value) {
            if (mOldValue != null && mOldValue.state == value.state) return;
            if (value.state < 0)
                if (pushScreenService != null) {
                    mApplicationContext.unbindService(conn);
                    pushScreenService  = null;
                }
            mOldValue = value;
            super.postValue(value);
        }
    }

    public static class StreamingStateLiveData extends LiveData<Boolean> {

        @Override
        protected void postValue(Boolean value) {
            super.postValue(value);
        }

    }

    private final CameraPreviewResolutionLiveData cameraPreviewResolution;
    private final PushingStateLiveData pushingStateLiveData;
    private final StreamingStateLiveData streamingStateLiveData;
    private final PushingScreenLiveData pushingScreenLiveData;

    public MediaStream(Application context) {
        this(context, null, true);
    }

    public MediaStream(Application context, SurfaceTexture texture, boolean enableVideo) {
        super(context);
        cameraPreviewResolution = new CameraPreviewResolutionLiveData();
        pushingStateLiveData = new PushingStateLiveData();
        streamingStateLiveData = new StreamingStateLiveData();
        pushingScreenLiveData = new PushingScreenLiveData();
        mApplicationContext = context;
        mSurfaceHolderRef = new WeakReference(texture);
        if (EasyApplication.isRTMP())
            mEasyPusher = new EasyRTMP();
        else mEasyPusher = new EasyPusher();
        mCameraThread = new HandlerThread("CAMERA") {
            public void run() {
                try {
                    super.run();
                } finally {
                    stopStream();
                    destroyCamera();
                }
            }
        };
        mCameraThread.start();
        mCameraHandler = new Handler(mCameraThread.getLooper()) {
            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);
                if (msg.what == SWITCH_CAMERA) {
                    switchCameraTask.run();
                }
            }
        };
        this.enanleVideo = enableVideo;

        if (enableVideo)
            previewCallback = new Camera.PreviewCallback() {

                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if (mDgree == 0) {
                        Camera.CameraInfo camInfo = new Camera.CameraInfo();
                        Camera.getCameraInfo(mCameraId, camInfo);
                        int cameraRotationOffset = camInfo.orientation;

                        if (cameraRotationOffset % 180 != 0) {
                            if (previewFormat == ImageFormat.YV12) {
                                yuvRotate(data, 0, width, height, cameraRotationOffset);
                            } else {
                                yuvRotate(data, 1, width, height, cameraRotationOffset);
                            }
                        }
                        save2file(data, String.format("/sdcard/yuv_%d_%d.yuv", height, width));
                    }
                    if (PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key_enable_video_overlay", false)) {
                        String txt = String.format("drawtext=fontfile=" + mApplicationContext.getFileStreamPath("SIMYOU.ttf") + ": text='%s%s':x=(w-text_w)/2:y=H-60 :fontcolor=white :box=1:boxcolor=0x00000000@0.3", "EasyPusher", new SimpleDateFormat("yyyy-MM-ddHHmmss").format(new Date()));
                        txt = "EasyPusher " + new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
                        overlay.overlay(data, txt);
                    }
                    mVC.onVideo(data, previewFormat);
                    mCamera.addCallbackBuffer(data);
                }

            };
    }

    public void setLifecycle(Lifecycle lifecycle){
        this.lifecycle = lifecycle;
        lifecycle.addObserver(this);
    }

    @MainThread
    public void startStream(String ip, String port, String id) {
        InitCallback callback = new InitCallback() {
            @Override
            public void onCallback(int code) {
                String msg = "";
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
                        msg = ("断授权使用商不匹配");
                        break;
                    case EasyPusher.OnInitPusherCallback.CODE.EASY_ACTIVATE_PROCESS_NAME_LEN_ERR:
                        msg = ("进程名称长度不匹配");
                        break;
                }
                pushingStateLiveData.postValue(new PushingState(code, msg));
            }
        };
        mEasyPusher.initPush(ip, port, String.format("%s.sdp", id), mApplicationContext, callback);
        streamingStateLiveData.postValue(true);
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

    @MainThread
    public void observeStreamingState(LifecycleOwner owner, Observer<Boolean> observer) {
        streamingStateLiveData.observe(owner, observer);
    }

    public PushingState getPushingState() {
        return pushingStateLiveData.getValue();
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
        closeCameraPreview();
        if (lifecycle != null) lifecycle.removeObserver(this);
    }

    @MainThread
    public void openCameraPreview(){
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
    public void closeCameraPreview(){
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

    protected void createCamera() {

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
        if (mCamera != null) return;
        try {
            mSWCodec = PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getBoolean("key-sw-codec", false);
            mCamera = Camera.open(mCameraId);


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

            debugger = EncoderDebugger.debug(mApplicationContext, width, height);

            previewFormat = mSWCodec ? ImageFormat.YV12 : debugger.getNV21Convertor().getPlanar() ? ImageFormat.YV12 : ImageFormat.NV21;
            parameters.setPreviewFormat(previewFormat);
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
            int displayRotation;
            displayRotation = (cameraRotationOffset - mDgree + 360) % 360;
            mCamera.setDisplayOrientation(displayRotation);

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

    public synchronized void startRecord() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    startRecord();
                }
            });
            return;
        }
        long millis = PreferenceManager.getDefaultSharedPreferences(mApplicationContext).getInt("record_interval", 300000);
        mMuxer = new EasyMuxer(new File(recordPath, new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date())).toString(), millis);
        if (mVC == null || audioStream == null) {
            throw new IllegalStateException("you need to start preview before startRecord!");
        }
        mVC.setMuxer(mMuxer);
        audioStream.setMuxer(mMuxer);
    }


    public synchronized void stopRecord() {
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
        if (mCamera != null) {
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

            boolean rotate = false;
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

            overlay = new TxtOverlay(mApplicationContext);
            try {
                if (mSWCodec) {
                    mVC = new SWConsumer(mApplicationContext, mEasyPusher);
                } else {
                    mVC = new HWConsumer(mApplicationContext, mEasyPusher);
                }
                if (!rotate) {
                    mVC.onVideoStart(previewSize.width, previewSize.height);
                    overlay.init(previewSize.width, previewSize.height, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
                } else {
                    mVC.onVideoStart(previewSize.height, previewSize.width);
                    overlay.init(previewSize.height, previewSize.width, mApplicationContext.getFileStreamPath("SIMYOU.ttf").getPath());
                }
            } catch (IOException ex) {
                ex.printStackTrace();
            } catch (IllegalArgumentException ex) {
                ex.printStackTrace();
            }
        }
        audioStream = new AudioStream(mEasyPusher);
        audioStream.startRecord();
    }


    Camera.PreviewCallback previewCallback;


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

    public Camera getCamera() {
        return mCamera;
    }


    /**
     * 切换前后摄像头
     */
    public void switchCamera() {
        if (mCameraHandler.hasMessages(SWITCH_CAMERA)) return;
        mCameraHandler.sendEmptyMessage(SWITCH_CAMERA);
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
            if (!enanleVideo) return;
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            cameraCount = Camera.getNumberOfCameras();//得到摄像头的个数
            for (int i = 0; i < cameraCount; i++) {
                Camera.getCameraInfo(i, cameraInfo);//得到每一个摄像头的信息
                stopPreview();
                destroyCamera();
                if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    //现在是后置，变更为前置
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {//代表摄像头的方位，CAMERA_FACING_FRONT前置      CAMERA_FACING_BACK后置
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
                        createCamera();
                        startPreview();
                        break;
                    }
                } else {
                    //现在是前置， 变更为后置
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {//代表摄像头的方位，CAMERA_FACING_FRONT前置      CAMERA_FACING_BACK后置
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_FRONT;
                        createCamera();
                        startPreview();
                        break;
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

    public boolean isStreaming() {
        return streamingStateLiveData.getValue();
    }


    public void stopStream() {
        mEasyPusher.stop();
        streamingStateLiveData.postValue(false);
        pushingStateLiveData.postValue(new PushingState(0,"未开始"));
    }

    @MainThread
    public void setSurfaceTexture(SurfaceTexture texture) {
        if (texture == null) {
            stopPreview();
            mSurfaceHolderRef = null;
        }else {
            mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
            stopPreview();
            if (cameraOpened) openCameraPreview();
        }
    }

    @MainThread
    public void notifyPermissionGranted(){
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

    public boolean isRecording() {
        return mMuxer != null;
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        stopStream();
        stopPreview();
        destroyCamera();
        release();

        if (pushScreenService != null) {
            mApplicationContext.unbindService(conn);
            pushScreenService  = null;
        }
    }
}
