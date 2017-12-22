package org.easydarwin.push;

import android.annotation.TargetApi;
import android.app.Application;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

import org.easydarwin.easypusher.EasyApplication;
import org.easydarwin.easypusher.R;
import org.easydarwin.easyrtmp.push.EasyRTMP;

import java.io.IOException;
import java.nio.ByteBuffer;

import static android.app.PendingIntent.FLAG_CANCEL_CURRENT;


public class PushScreenService extends Service {

    private static final String TAG = "RService";
    public static final String ACTION_CLOSE_PUSHING_SCREEN = "ACTION_CLOSE_PUSHING_SCREEN";
    private String mVideoPath;
    private MediaProjectionManager mMpmngr;
    private MediaProjection mMpj;
    private VirtualDisplay mVirtualDisplay;
    private int windowWidth;
    private int windowHeight;
    private int screenDensity;

    private Surface mSurface;
    private MediaCodec mMediaCodec;

    private WindowManager wm;



    private MediaCodec.BufferInfo mBufferInfo = new MediaCodec.BufferInfo();

    private Thread mPushThread;
    private byte[] mPpsSps;
    private BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Application app = (Application) context.getApplicationContext();
            MediaStream.stopPushScreen(app);
        }
    };


    public class MyBinder extends Binder
    {
        public PushScreenService getService(){
            return PushScreenService.this;
        }
    }

    MyBinder binder = new MyBinder();
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mMpmngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
        createEnvironment();

        registerReceiver(mReceiver,new IntentFilter(ACTION_CLOSE_PUSHING_SCREEN));
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void configureMedia() throws IOException {

        MediaFormat mediaFormat = MediaFormat.createVideoFormat("video/avc", windowWidth, windowHeight);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 1200000);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, 1);
        mediaFormat.setInteger(MediaFormat.KEY_CAPTURE_RATE, 25);
        mediaFormat.setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1000000);
        mMediaCodec = MediaCodec.createEncoderByType("video/avc");
        mMediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mSurface = mMediaCodec.createInputSurface();
        mMediaCodec.start();
    }

    private void createEnvironment() {
        mVideoPath = Environment.getExternalStorageDirectory().getPath() + "/";
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        windowWidth = wm.getDefaultDisplay().getWidth();
        windowHeight = wm.getDefaultDisplay().getHeight();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(displayMetrics);
        screenDensity = displayMetrics.densityDpi;

        while (windowWidth > 480){
            windowWidth /= 2;
            windowHeight /=2;
        }

        windowWidth /= 16;
        windowWidth *= 16;


        windowHeight /= 16;
        windowHeight *= 16;
    }

    private void startPush(final Pusher pusher, final MediaStream.PushingScreenLiveData liveData) {
//        liveData.postValue(new MediaStream.PushingState(0, "未开始", true));
        mPushThread = new Thread(){
            @TargetApi(Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void run() {

                startForeground(111, new Notification.Builder(PushScreenService.this).setContentTitle(getString(R.string.screen_pushing))
                        .setSmallIcon(R.drawable.ic_pusher_screen_pushing)
                        .addAction(new Notification.Action(R.drawable.ic_close_pushing_screen, "关闭",
                                PendingIntent.getBroadcast(getApplicationContext(), 10000, new Intent(ACTION_CLOSE_PUSHING_SCREEN), FLAG_CANCEL_CURRENT))).build());
                while (mPushThread != null) {
                    int index = mMediaCodec.dequeueOutputBuffer(mBufferInfo, 10000);
                    Log.d(TAG, "dequeue output buffer index=" + index);

                    if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {//请求超时
                        try {
                            // wait 10ms
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                        }
                    } else if (index >= 0) {//有效输出

                        ByteBuffer outputBuffer = mMediaCodec.getOutputBuffer(index);


                        byte[] outData = new byte[mBufferInfo.size];
                        outputBuffer.get(outData);

//                        String data0 = String.format("%x %x %x %x %x %x %x %x %x %x ", outData[0], outData[1], outData[2], outData[3], outData[4], outData[5], outData[6], outData[7], outData[8], outData[9]);
//                        Log.e("out_data", data0);

                        //记录pps和sps
                        int type = outData[4] & 0x07;
                        if (type == 7 || type == 8) {
                            mPpsSps = outData;
                        } else if (type == 5) {
                            //在关键帧前面加上pps和sps数据
                            if (mPpsSps != null) {
                                byte[] iframeData = new byte[mPpsSps.length + outData.length];
                                System.arraycopy(mPpsSps, 0, iframeData, 0, mPpsSps.length);
                                System.arraycopy(outData, 0, iframeData, mPpsSps.length, outData.length);
                                outData = iframeData;
                            }
                        }

                        pusher.push(outData, mBufferInfo.presentationTimeUs/1000, 1);


                        mMediaCodec.releaseOutputBuffer(index, false);
                    }

                }
                stopForeground(true);
            }
        };
        mPushThread.start();
    }


    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void stopPush(){
        Thread t = mPushThread;
        if (t != null){
            mPushThread = null;
            try {
                t.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    void startVirtualDisplay(int resultCode, Intent resultData, MediaStream.PushingScreenLiveData liveData,Pusher pusher) {

        try {
            configureMedia();
        } catch (IOException e) {
            e.printStackTrace();
            liveData.postValue(new MediaStream.PushingState("",-1, "编码器初始化错误", true));
            return;
        }

        if (mMpj == null) {
            mMpj = mMpmngr.getMediaProjection(resultCode, resultData);
        }
        if (mMpj == null) {
            liveData.postValue(new MediaStream.PushingState("",-1, "未知错误", true));
            return;
        }
        mVirtualDisplay = mMpj.createVirtualDisplay("record_screen", windowWidth, windowHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR|DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC|DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION, mSurface, null, null);

        startPush(pusher, liveData);
    }


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



    @TargetApi(Build.VERSION_CODES.KITKAT)
    private void release() {

        Log.i(TAG, " release() ");
        if (mMediaCodec != null) {
            mMediaCodec.stop();
            mMediaCodec.release();
            mMediaCodec = null;
        }
        if (mSurface != null){
            mSurface.release();
        }
        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPush();
        release();
        if (mMpj != null) {
            mMpj.stop();
        }
        unregisterReceiver(mReceiver);
    }
}
