package org.easydarwin.push;

import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.Process;
import android.util.Log;

import org.easydarwin.bus.SupportResolution;
import org.easydarwin.easypusher.BackgroundCameraService;
import org.easydarwin.encode.AudioStream;
import org.easydarwin.encode.ClippableVideoConsumer;
import org.easydarwin.encode.HWConsumer;
import org.easydarwin.encode.SWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.muxer.RecordVideoConsumer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.util.SPUtil;
import org.easydarwin.util.Util;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
import static android.media.MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar;
import static org.easydarwin.easypusher.EasyApplication.BUS;

/**
 * 摄像头实时数据采集，并调用相关编码器
 * */
public class MediaStream {

    public static CodecInfo info = new CodecInfo();

    private static final String TAG = "MediaStream";
    private static final int SWITCH_CAMERA = 11;

    int width = 1280, height = 720;

    private final boolean enableVideo;
    boolean isPushStream = false;// 是否要推送数据

    private boolean isCameraBack = true;
    private int displayRotationDegree;

    private Context mApplicationContext;
    WeakReference<SurfaceTexture> mSurfaceHolderRef;

    private boolean mSWCodec, mHevc;

    Camera mCamera;
    int mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;

    private VideoConsumer mVC, mRecordVC;
    AudioStream audioStream;
    private EasyMuxer mMuxer;
    Pusher mEasyPusher;

    private final HandlerThread mCameraThread;
    private final Handler mCameraHandler;

    private String recordPath = Environment.getExternalStorageDirectory().getPath();

    private byte[] i420_buffer;
    private int frameWidth;
    private int frameHeight;

    private Camera.CameraInfo camInfo;
    private Camera.Parameters parameters;

    Camera.PreviewCallback previewCallback;

    /**
     * 切换摄像头的线程
     * */
    private Runnable switchCameraTask = new Runnable() {
        @Override
        public void run() {
            int cameraCount;

            if (isCameraBack) {
                isCameraBack = false;
            } else {
                isCameraBack = true;
            }

            if (!enableVideo)
                return;

            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            cameraCount = Camera.getNumberOfCameras();  // 得到摄像头的个数

            for (int i = 0; i < cameraCount; i++) {
                Camera.getCameraInfo(i, cameraInfo);    // 得到每一个摄像头的信息
                stopPreview();
                destroyCamera();

                if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    // 现在是后置，变更为前置
                    if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {//代表摄像头的方位，CAMERA_FACING_FRONT前置      CAMERA_FACING_BACK后置
                        mCameraId = Camera.CameraInfo.CAMERA_FACING_BACK;
                        createCamera();
                        startPreview();
                        break;
                    }
                } else {
                    // 现在是前置， 变更为后置
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

    /**
     * 初始化MediaStream
     * */
    public MediaStream(Context context, SurfaceTexture texture, boolean enableVideo) {
        mApplicationContext = context;
        audioStream = AudioStream.getInstance(mApplicationContext);
        mSurfaceHolderRef = new WeakReference(texture);

        mCameraThread = new HandlerThread("CAMERA") {
            public void run() {
                try {
                    super.run();
                } catch (Throwable e) {
                    e.printStackTrace();

                    Intent intent = new Intent(mApplicationContext, BackgroundCameraService.class);
                    mApplicationContext.stopService(intent);
                } finally {
                    stopStream();
                    stopPreview();
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

        this.enableVideo = enableVideo;

        if (enableVideo)
            previewCallback = (data, camera) -> {
                if (data == null)
                    return;

                int result;

                if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    result = (camInfo.orientation + displayRotationDegree) % 360;
                } else {  // back-facing
                    result = (camInfo.orientation - displayRotationDegree + 360) % 360;
                }

                if (i420_buffer == null || i420_buffer.length != data.length) {
                    i420_buffer = new byte[data.length];
                }

                JNIUtil.ConvertToI420(data, i420_buffer, width, height, 0, 0, width, height, result % 360, 2);
                System.arraycopy(i420_buffer, 0, data, 0, data.length);

                if (mRecordVC != null) {
                    mRecordVC.onVideo(i420_buffer, 0);
                }

                mVC.onVideo(data, 0);
                mCamera.addCallbackBuffer(data);
            };
    }

    /**
     * 初始化摄像头
     * */
    public void createCamera() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
                createCamera();
            });

            return;
        }

        mHevc = SPUtil.getHevcCodec(mApplicationContext);
        mEasyPusher = new EasyPusher();

        if (!enableVideo) {
            return;
        }

        try {
            mSWCodec = SPUtil.getswCodec(mApplicationContext);
            mCamera = Camera.open(mCameraId);
            mCamera.setErrorCallback((i, camera) -> {
                throw new IllegalStateException("Camera Error:" + i);
            });

            Log.i(TAG, "open Camera");

            parameters = mCamera.getParameters();

            if (Util.getSupportResolution(mApplicationContext).size() == 0) {
                StringBuilder stringBuilder = new StringBuilder();
                List<Camera.Size> supportedPreviewSizes = parameters.getSupportedPreviewSizes();

                for (Camera.Size str : supportedPreviewSizes) {
                    stringBuilder.append(str.width + "x" + str.height).append(";");
                }

                Util.saveSupportResolution(mApplicationContext, stringBuilder.toString());
            }

            BUS.post(new SupportResolution());

            camInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(mCameraId, camInfo);
            int cameraRotationOffset = camInfo.orientation;

            if (mCameraId == Camera.CameraInfo.CAMERA_FACING_FRONT)
                cameraRotationOffset += 180;

            int rotate = (360 + cameraRotationOffset - displayRotationDegree) % 360;
            parameters.setRotation(rotate);
//            parameters.setRecordingHint(true);

            ArrayList<CodecInfo> infos = listEncoders(mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC);

            if (!infos.isEmpty()) {
                CodecInfo ci = infos.get(0);
                info.mName = ci.mName;
                info.mColorFormat = ci.mColorFormat;
            } else {
                mSWCodec = true;
            }

//            List<Camera.Size> sizes = parameters.getSupportedPreviewSizes();

            parameters.setPreviewSize(width, height);
            int[] ints = determineMaximumSupportedFramerate(parameters);
            parameters.setPreviewFpsRange(ints[0], ints[1]);

            List<String> supportedFocusModes = parameters.getSupportedFocusModes();

            if (supportedFocusModes == null)
                supportedFocusModes = new ArrayList<>();

            if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            }

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
            displayRotation = (cameraRotationOffset - displayRotationDegree + 360) % 360;
            mCamera.setDisplayOrientation(displayRotation);

            Log.i(TAG, "setDisplayOrientation");
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);

//            String stack = sw.toString();
            destroyCamera();
            e.printStackTrace();
        }
    }

    /**
     * 销毁Camera
     */
    public synchronized void destroyCamera() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> destroyCamera());
            return;
        }

        if (mCamera != null) {
            mCamera.stopPreview();

            try {
                mCamera.release();
            } catch (Exception e) {
                e.printStackTrace();
            }

            Log.i(TAG, "release Camera");

            mCamera = null;
        }

        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * 回收线程
     * */
    public void release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mCameraThread.quitSafely();
        } else {
            if (!mCameraHandler.post(() -> mCameraThread.quit())) {
                mCameraThread.quit();
            }
        }

        try {
            mCameraThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * 开启预览
     */
    public synchronized void startPreview() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> startPreview());
            return;
        }

        if (mCamera != null) {
            int previewFormat = parameters.getPreviewFormat();

            Camera.Size previewSize = parameters.getPreviewSize();
            int size = previewSize.width * previewSize.height * ImageFormat.getBitsPerPixel(previewFormat) / 8;

            width = previewSize.width;
            height = previewSize.height;

            mCamera.addCallbackBuffer(new byte[size]);
            mCamera.addCallbackBuffer(new byte[size]);
            mCamera.setPreviewCallbackWithBuffer(previewCallback);

            Log.i(TAG, "setPreviewCallbackWithBuffer");

            try {
                // TextureView的
                SurfaceTexture holder = mSurfaceHolderRef.get();

                if (holder != null) {
                    mCamera.setPreviewTexture(holder);
                    Log.i(TAG, "setPreviewTexture");
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            mCamera.startPreview();

            boolean frameRotate;
            int result;

            if (camInfo.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                result = (camInfo.orientation + displayRotationDegree) % 360;
            } else {  // back-facing
                result = (camInfo.orientation - displayRotationDegree + 360) % 360;
            }

            frameRotate = result % 180 != 0;

            frameWidth = frameRotate ? height : width;
            frameHeight = frameRotate ? width : height;

            if (mSWCodec) {
                mVC = new ClippableVideoConsumer(mApplicationContext, new SWConsumer(mApplicationContext, mEasyPusher), frameWidth, frameHeight);
            } else {
                mVC = new ClippableVideoConsumer(mApplicationContext, new HWConsumer(mApplicationContext, mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC, mEasyPusher), frameWidth, frameHeight);
            }

            mVC.onVideoStart(frameWidth, frameHeight);
        }

        audioStream.addPusher(mEasyPusher);
    }

    /**
     * 停止预览
     */
    public synchronized void stopPreview() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> stopPreview());
            return;
        }

        if (mCamera != null) {
            mCamera.stopPreview();
            mCamera.setPreviewCallbackWithBuffer(null);

            Log.i(TAG, "StopPreview");
        }

        if (audioStream != null) {
            audioStream.removePusher(mEasyPusher);
            audioStream.setMuxer(null);

            Log.i(TAG, "Stop AudioStream");
        }

        if (mVC != null) {
            mVC.onVideoStop();

            Log.i(TAG, "Stop VC");
        }

        if (mRecordVC != null) {
            mRecordVC.onVideoStop();
        }

        if (mMuxer != null) {
            mMuxer.release();
            mMuxer = null;
        }
    }

    /**
     * 开始推流
     * */
    public void startStream(String ip, String port, String id, InitCallback callback) {
        mEasyPusher.initPush(mApplicationContext, callback);
        mEasyPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
        mEasyPusher.start(ip, port, String.format("%s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);
        isPushStream = true;
    }

    /**
     * 停止推流
     * */
    public void stopStream() {
        mEasyPusher.stop();
        isPushStream = false;
    }

    /**
     * 录像
     * */
    public synchronized void startRecord() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> startRecord());
            return;
        }

        boolean rotate = false;

        if (mCamera == null) {
            return;
        }

        // 默认录像时间300000毫秒
        mMuxer = new EasyMuxer(new File(recordPath, new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss").format(new Date())).toString(), 300000);

        mRecordVC = new RecordVideoConsumer(mApplicationContext,
                mHevc ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC,
                mSWCodec,
                mMuxer);
        mRecordVC.onVideoStart(frameWidth, frameHeight);

        if (audioStream != null) {
            audioStream.setMuxer(mMuxer);
        }
    }

    /**
     * 停止录像
     * */
    public synchronized void stopRecord() {
        if (Thread.currentThread() != mCameraThread) {
            mCameraHandler.post(() -> stopRecord());
            return;
        }

        if (mRecordVC == null || audioStream == null) {
//            nothing
        } else {
            audioStream.setMuxer(null);
            mRecordVC.onVideoStop();
            mRecordVC = null;
        }

        if (mMuxer != null)
            mMuxer.release();

        mMuxer = null;
    }

    /**
     * 更新分辨率
     */
    public void updateResolution(final int w, final int h) {
        if (mCamera == null)
            return;

        stopPreview();
        destroyCamera();

        mCameraHandler.post(() -> {
            width = w;
            height = h;
        });

        createCamera();
        startPreview();
    }

    /**
     * 切换前后摄像头
     */
    public void switchCamera() {
        if (mCameraHandler.hasMessages(SWITCH_CAMERA))
            return;

        mCameraHandler.sendEmptyMessage(SWITCH_CAMERA);
    }

    public static class CodecInfo {
        public String mName;
        public int mColorFormat;
    }

    public static ArrayList<CodecInfo> listEncoders(String mime) {
        // 可能有多个编码库，都获取一下
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
                    codecInfos.add(ci);
                }
            }
        }

        return codecInfos;
    }

    /* ============================== private method ============================== */

    private static boolean codecMatch(String mimeType, MediaCodecInfo codecInfo) {
        String[] types = codecInfo.getSupportedTypes();

        for (String type : types) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }

        return false;
    }

    private static int getColorFormat(MediaCodecInfo codecInfo, String mimeType) {
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

    private static int[] determineMaximumSupportedFramerate(Camera.Parameters parameters) {
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

    /* ============================== get/set ============================== */

    public void setRecordPath(String recordPath) {
        this.recordPath = recordPath;
    }

    public boolean isRecording() {
        return mMuxer != null;
    }

    public void setSurfaceTexture(SurfaceTexture texture) {
        mSurfaceHolderRef = new WeakReference<SurfaceTexture>(texture);
    }

    public boolean isStreaming() {
        return isPushStream;
    }

    public Camera getCamera() {
        return mCamera;
    }

    public int getDisplayRotationDegree() {
        return displayRotationDegree;
    }

    public void setDisplayRotationDegree(int degree) {
        displayRotationDegree = degree;
    }
}