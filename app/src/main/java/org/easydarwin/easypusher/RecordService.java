package org.easydarwin.easypusher;

import android.annotation.TargetApi;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.TextView;

import com.squareup.otto.Subscribe;
import com.tencent.bugly.crashreport.CrashReport;

import org.easydarwin.encode.AudioStream;
import org.easydarwin.push.EasyPusher;
import org.easydarwin.push.MediaStream;
import org.easydarwin.push.Pusher;
import org.easydarwin.util.Config;
import org.easydarwin.util.SPUtil;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import static android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME;
import static org.easydarwin.push.MediaStream.listEncoders;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class RecordService extends Service {

    private static final String TAG = "RecordService";

    // 生成悬浮窗
    private WindowManager wm;
    private WindowManager.LayoutParams param;
    private View mLayout;//要引用的布局文件.
    private GestureDetector mGD;

    // 录屏
    private MediaProjectionManager mMpmngr;
    private MediaProjection mMpj;
    private VirtualDisplay mVirtualDisplay;

    private int windowWidth;
    private int windowHeight;
    private int screenDensity;

    private Surface mSurface;
    private MediaCodec mMediaCodec;

    final AudioStream audioStream = AudioStream.getInstance(EasyApplication.getEasyApplication());

    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    public static Pusher mEasyPusher;
    private Thread mPushThread;
    private byte[] mPpsSps;
    private WindowManager mWindowManager;


    private final boolean useImageReader = false;
    private ImageReader mImageReader;
    private MediaStream.CodecInfo ci;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 初始化MediaProjectionManager
        mMpmngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);

        createEnvironment();

        mWindowManager = (WindowManager) getApplicationContext().getSystemService(Context.WINDOW_SERVICE);

        // 在Android 6.0后，Android需要动态获取权限，若没有权限，则不启用该service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                return;
            }
        }

        try {
            configureMedia();
            startPush();
            showView();
        } catch (IOException e) {
            e.printStackTrace();
            CrashReport.postCatchedException(e);
        }

        EasyApplication.BUS.register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        EasyApplication.BUS.unregister(this);

        hideView();
        stopPush();
        release();

        if (mMpj != null) {
            mMpj.stop();
        }

        super.onDestroy();
    }

    /**
     * 生成悬浮窗
     * */
    private void createEnvironment() {
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowWidth = wm.getDefaultDisplay().getWidth();
        windowHeight = wm.getDefaultDisplay().getHeight();

        DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(displayMetrics);

        screenDensity = displayMetrics.densityDpi;

        // 1倍屏幕大小,0.75倍屏幕大小,0.5倍屏幕大小,0.3倍屏幕大小,0.25倍屏幕大小,0.2倍屏幕大小
        int defaultIdx = SPUtil.getScreenPushingResIndex(this);

        switch (defaultIdx) {
            case 0:
                break;
            case 1:
                windowWidth *= 0.75;
                windowHeight *= 0.75;
                break;
            case 2:
                windowWidth *= 0.5;
                windowHeight *= 0.5;
                break;
            case 3:
                windowWidth *= 0.3;
                windowHeight *= 0.3;
                break;
            case 4:
                windowWidth *= 0.25;
                windowHeight *= 0.25;
                break;
            case 5:
                windowWidth *= 0.2;
                windowHeight *= 0.2;
                break;
        }

        windowWidth /= 16;
        windowWidth *= 16;

        windowHeight /= 16;
        windowHeight *= 16;
    }

    private void configureMedia() throws IOException {
//        ArrayList<MediaStream.CodecInfo> infos = listEncoders("video/avc");
//        ci = infos.get(0);

        mMediaCodec = MediaCodec.createByCodecName(ci.mName);
        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", windowWidth, windowHeight);
//        int bitrate = (int) (windowWidth * windowHeight * 25 * 2 * 0.05f);
//        if (windowWidth >= 1920 || windowHeight >= 1920) bitrate *= 0.3;
//        else if (windowWidth >= 1280 || windowHeight >= 1280) bitrate *= 0.4;
//        else if (windowWidth >= 720 || windowHeight >= 720) bitrate *= 0.6;

        int bitrate = 72 * 1000 + SPUtil.getBitrateKbps(this);

        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 15);

        if (useImageReader) {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar);
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, ci.mColorFormat);
        } else {
            mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            mediaFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 20000000);
        }

        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);

        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        if (useImageReader) {
            mImageReader = ImageReader.newInstance(windowWidth, windowHeight, PixelFormat.RGBA_8888, 2);
            mSurface = mImageReader.getSurface();
        } else {
            mSurface = mMediaCodec.createInputSurface();
        }

        mMediaCodec.start();
    }

    private void startPush() {
        if (mPushThread != null)
            return;

        mPushThread = new Thread("RecordService") {
            @Override
            public void run() {
                mEasyPusher = new EasyPusher();
                String ip = Config.getIp(RecordService.this);
                String port = Config.getPort(RecordService.this);
                String id = Config.getId(RecordService.this);
                mEasyPusher.initPush( getApplicationContext(), null);
                mEasyPusher.setMediaInfo(Pusher.Codec.EASY_SDK_VIDEO_CODEC_H264, 25, Pusher.Codec.EASY_SDK_AUDIO_CODEC_AAC, 1, 8000, 16);
                mEasyPusher.start(ip,port,String.format("%s_s.sdp", id), Pusher.TransType.EASY_RTP_OVER_TCP);

                try {
                    audioStream.addPusher(mEasyPusher);

                    byte[] argbFrame = new byte[windowWidth * windowHeight * 4];
                    byte[] frameBuffer = new byte[windowWidth * windowHeight * 3 / 2];
                    long presentationTimeUs = 0;
                    long lastKeyFrameUS = 0, lastRequestKeyFrameUS = 0;

                    while (mPushThread != null) {
                        if (lastKeyFrameUS > 0 && SystemClock.elapsedRealtimeNanos() / 1000 - lastKeyFrameUS >= 3000000) {  // 3s no key frame.
                            if (SystemClock.elapsedRealtimeNanos() / 1000 - lastRequestKeyFrameUS >= 3000000) {
                                Bundle p = new Bundle();
                                p.putInt(PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                                mMediaCodec.setParameters(p);

                                Log.i(TAG, "request key frame");

                                lastRequestKeyFrameUS = SystemClock.elapsedRealtimeNanos() / 1000;
                            }
                        }

                        if (mImageReader == null) {
                            int index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 10000);
                            Log.i(TAG, "dequeue output buffer index=" + index);

                            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {//请求超时

                            } else if (index >= 0) {//有效输出
                                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(index);
                                byte[] outData = new byte[mBufferInfo.size];
                                outputBuffer.get(outData);

//                                String data0 = String.format("%x %x %x %x %x %x %x %x %x %x ", outData[0], outData[1], outData[2], outData[3], outData[4], outData[5], outData[6], outData[7], outData[8], outData[9]);
//                                Log.e("out_data", data0);

                                // 记录pps和sps
                                int type = outData[4] & 0x07;
                                if (type == 7 || type == 8) {
                                    mPpsSps = outData;
                                } else if (type == 5) {
                                    lastKeyFrameUS = SystemClock.elapsedRealtimeNanos() / 1000;

                                    // 在关键帧前面加上pps和sps数据
                                    if (mPpsSps != null) {
                                        byte[] iframeData = new byte[mPpsSps.length + outData.length];
                                        System.arraycopy(mPpsSps, 0, iframeData, 0, mPpsSps.length);
                                        System.arraycopy(outData, 0, iframeData, mPpsSps.length, outData.length);
                                        outData = iframeData;
                                    }
                                }

                                mEasyPusher.push(outData, mBufferInfo.presentationTimeUs / 1000, 1);
                                mMediaCodec.releaseOutputBuffer(index, false);
                                Thread.sleep(40);   // 不至于动的时候帧率太高。
                            }
                        } else {
                            Image image = mImageReader.acquireLatestImage();

                            if (image != null) {
                                ByteBuffer byteBuffer = image.getPlanes()[0].getBuffer();
                                byteBuffer.get(argbFrame);
                                save2file(argbFrame, "/sdcard/argb.argb");

//                            int w = image.getWidth();
//                            int h = image.getHeight();
//                                JNIUtil.argb2yuv(argbFrame, frameBuffer, windowWidth, windowHeight, ci.mColorFormat == 19 ? 1 : 3);
//                            JNIUtil.yuvConvert(frameBuffer, windowWidth, windowHeight, ci.mColorFormat == 19?1:5);

//                            if (codecInfo.mInColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
//                                JniLibYuv.yuvConvert(frameBuffer, nv21Buffer, windowWidth, windowHeight, JniLibYuv.I420_TO_YV21);
//                            } else if (codecInfo.mInColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar) {
//                                JniLibYuv.yuvConvert(frameBuffer, nv21Buffer, windowWidth, windowHeight, JniLibYuv.I420_TO_YV21);
//                            } else if (codecInfo.mInColorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
//                                ;
//                            }

                                image.close();
                            } else {
//                            if (lastFrameTime != 0 && systemTimeNow -lastFrameTime > 1000/mFrameRate*2) {
//                                lastFrameTime = systemTimeNow;
//                                inputBuffers = mMediaCodec.getInputBuffers();
//                                int bufferIndex = mMediaCodec.dequeueInputBuffer(5000);
//                                if (bufferIndex >= 0) {
//                                    inputBuffers[bufferIndex].clear();
//
//                                    int min = inputBuffers[bufferIndex].capacity() < frameBuffer.length
//                                            ? inputBuffers[bufferIndex].capacity() : frameBuffer.length;
//                                    inputBuffers[bufferIndex].put(nv21Buffer, 0, min);
//
//                                    mMediaCodec.queueInputBuffer(bufferIndex, 0, inputBuffers[bufferIndex].position(),
//                                            System.currentTimeMillis() * 1000, 0);
//                                }
//                            }
                            }

                            if (SystemClock.elapsedRealtimeNanos() / 1000 - presentationTimeUs >= 30000) {
                                int bufferIndex = mMediaCodec.dequeueInputBuffer(0);

                                if (bufferIndex > -1) {
                                    ByteBuffer inputBuffer = mMediaCodec.getInputBuffer(bufferIndex);
                                    inputBuffer.clear();
                                    inputBuffer.put(frameBuffer);

                                    Log.i(TAG, "视频帧间隔:" + (SystemClock.elapsedRealtimeNanos() / 1000 - presentationTimeUs) / 1000);

                                    presentationTimeUs = SystemClock.elapsedRealtimeNanos() / 1000;
                                    mMediaCodec.queueInputBuffer(bufferIndex, 0, frameBuffer.length, presentationTimeUs, 0);
                                } else {
                                    Log.w(TAG, "视频帧间隔已足够大.但是依然没有可用的编码输入队列");
                                }
                            }

                            int index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 0);
//                        Log.i(TAG, "dequeue output buffer index=" + index);

                            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {//请求超时
                                Thread.sleep(1);
                            } else if (index >= 0) {//有效输出
                                ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(index);
                                byte[] outData = new byte[mBufferInfo.size];
                                outputBuffer.get(outData);

                                // 记录pps和sps
                                int type = outData[4] & 0x07;
                                if (type == 7 || type == 8) {
                                    mPpsSps = outData;
                                    Log.i(TAG, "sps pps frame");
                                } else if (type == 5) {
                                    lastKeyFrameUS = SystemClock.elapsedRealtimeNanos() / 1000;
                                    Log.i(TAG, "key frame");

                                    //在关键帧前面加上pps和sps数据
                                    if (mPpsSps != null) {
                                        byte[] iframeData = new byte[mPpsSps.length + outData.length];
                                        System.arraycopy(mPpsSps, 0, iframeData, 0, mPpsSps.length);
                                        System.arraycopy(outData, 0, iframeData, mPpsSps.length, outData.length);
                                        outData = iframeData;
                                    }
                                }

                                mEasyPusher.push(outData, mBufferInfo.presentationTimeUs / 1000, 1);
                                mMediaCodec.releaseOutputBuffer(index, false);
                            }
                        }
                    }
                } catch (Throwable e) {
                    e.printStackTrace();
                } finally {
                    audioStream.removePusher(mEasyPusher);
                }
            }
        };

        mPushThread.start();
        startVirtualDisplay();
    }

    /**
     * 显示悬浮窗
     * */
    private void showView() {
        if (mLayout != null)
            return;

        mLayout = LayoutInflater.from(this).inflate(R.layout.float_btn, null);

        param = new WindowManager.LayoutParams();

        //设置type.系统提示型窗口，一般都在应用程序窗口之上.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            param.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            param.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        //设置效果为背景透明
        param.format = PixelFormat.RGBA_8888;

        //设置flags.不可聚焦及不可使用按钮对悬浮窗进行操控.
        param.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        param.flags = param.flags | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

        param.alpha = 1.0f;

        //设置窗口初始停靠位置.
        param.gravity = Gravity.LEFT | Gravity.TOP;
//        param.x = getResources().getDisplayMetrics().widthPixels - param.width - getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        param.x = getResources().getDimensionPixelSize(R.dimen.activity_horizontal_margin);
        param.y = getResources().getDimensionPixelSize(R.dimen.float_btn_height);

        /*
         * 设置悬浮窗口长宽数据。注意，这里的width和height均使用px而非dp.(如果你想完全对应布局设置，需要先获取到机器的dpi,px与dp的换算为px = dp * (dpi / 160))
         */
        param.width = getResources().getDimensionPixelSize(R.dimen.float_btn_width);
        param.height = getResources().getDimensionPixelSize(R.dimen.float_btn_height);

        // 添加mLayout
        mWindowManager.addView(mLayout, param);

        mGD = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            public boolean mScroll;

            int x;
            int y;

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                Intent intent = new Intent(RecordService.this, SplashActivity.class);
                intent.putExtra("screen-pushing", true);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                startActivity(intent);

                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (!mScroll && Math.sqrt((e1.getX() - e2.getX()) * (e1.getX() - e2.getX()) + (e1.getY() - e2.getY()) * (e1.getY() - e2.getY())) > ViewConfiguration.get(RecordService.this).getScaledTouchSlop()) {
                    mScroll = true;
                }

                if (!mScroll) {
                    return false;
                } else {
                    WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                    p.x = (int) (x + e2.getRawX() - e1.getRawX());
                    p.y = (int) (y + e2.getRawY() - e1.getRawY());
                    mWindowManager.updateViewLayout(mLayout, p);

                    return true;
                }
            }

            @Override
            public boolean onDown(MotionEvent e) {
                if (mLayout == null)
                    return true;

                mScroll = false;

                WindowManager.LayoutParams p = (WindowManager.LayoutParams) mLayout.getLayoutParams();
                x = p.x;
                y = p.y;

                return super.onDown(e);
            }
        });

        mLayout.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                return mGD.onTouchEvent(event);
            }
        });

        final TextView textView = mLayout.findViewById(R.id.text);
        textView.post(new Runnable() {
            @Override
            public void run() {
                textView.setText("" + SystemClock.elapsedRealtimeNanos());
                textView.postDelayed(this, 50);
            }
        });
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startVirtualDisplay() {
        if (mMpj == null) {
            mMpj = mMpmngr.getMediaProjection(StreamActivity.mResultCode, StreamActivity.mResultIntent);
            StreamActivity.mResultCode = 0;
            StreamActivity.mResultIntent = null;
        }

        if (mMpj == null)
            return;

        // 通过MediaProjection对象的createVirtualDisplay方法，拿到VirtureDisplay对象，拿这个对象的时候，需要把Surface对象传进去。
        mVirtualDisplay = mMpj.createVirtualDisplay(
                "record_screen",
                windowWidth,
                windowHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR | DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                mSurface,
                null,
                null);
    }

    private void save2file(byte[] data, String path) {
        if (true)
            return;

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

    /*
     * 销毁WindowManager
     * */
    private void hideView() {
        if (mLayout == null)
            return;

        mWindowManager.removeView(mLayout);
        mLayout = null;
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopPush() {
        Thread t = mPushThread;

        if (t != null) {
            mPushThread = null;

            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        if (mEasyPusher != null)
            mEasyPusher.stop();

        mEasyPusher = null;
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void release() {
        Log.i(TAG, " release() ");

        if (mSurface != null) {
            mSurface.release();
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }

        if (mImageReader != null) {
            mImageReader.close();
            mImageReader = null;
        }
    }
//
//    @Subscribe
//    public void onPushCallback(final PushCallback cb) {
//        if (mLayout == null)
//            return;
//
//        mLayout.post(new Runnable() {
//            @Override
//            public void run() {
//                switch (cb.code) {
//                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_FAILED:
//                    case EasyRTMP.OnInitPusherCallback.CODE.EASY_RTMP_STATE_CONNECT_ABORT:
//                        if (mLayout == null)
//                            return;
//
//                        mLayout.findViewById(R.id.action_push_error).setVisibility(View.VISIBLE);
//                        break;
//                    default:
//                        if (mLayout == null)
//                            return;
//                        mLayout.findViewById(R.id.action_push_error).setVisibility(View.GONE);
//                }
//            }
//        });
//    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void encodeToVideoTrack(int index) {
        ByteBuffer encodedData = mMediaCodec.getOutputBuffer(index);

        if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {//是编码需要的特定数据，不是媒体数据
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG");
            mBufferInfo.size = 0;
        }

        if (mBufferInfo.size == 0) {
            Log.d(TAG, "info.size == 0, drop it.");
            encodedData = null;
        } else {
            Log.d(TAG, "got buffer, info: size=" + mBufferInfo.size
                    + ", presentationTimeUs=" + mBufferInfo.presentationTimeUs
                    + ", offset=" + mBufferInfo.offset);
        }

        if (encodedData != null) {
            encodedData.position(mBufferInfo.offset);
            encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

//            mMuxer.writeSampleData(mVideoTrackIndex, encodedData, mBufferInfo);//写入
            Log.i(TAG, "sent " + mBufferInfo.size + " bytes to muxer...");
        }
    }
}
