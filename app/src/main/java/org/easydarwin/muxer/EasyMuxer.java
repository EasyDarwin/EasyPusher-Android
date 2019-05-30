package org.easydarwin.muxer;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import org.easydarwin.bus.StartRecord;
import org.easydarwin.bus.StopRecord;
import org.easydarwin.easypusher.BuildConfig;
import org.easydarwin.easypusher.EasyApplication;
import org.easydarwin.video.EasyMuxer2;

import java.nio.ByteBuffer;

/**
 * Created by John on 2017/1/10.
 */

public class EasyMuxer {

    private static final boolean VERBOSE = BuildConfig.DEBUG;
    private static final String TAG = EasyMuxer.class.getSimpleName();
    private final String mFilePath;
    private EasyMuxer2 mMuxer;
    private final long durationMillis;
    private int index = 0;
    private long mBeginMillis;
    private MediaFormat mVideoFormat;
    private MediaFormat mAudioFormat;

    public EasyMuxer(String path, long durationMillis) {
        mFilePath = path;
        this.durationMillis = durationMillis;
        mMuxer = new EasyMuxer2();
    }

    public synchronized void addTrack(MediaFormat format, boolean isVideo) {
        // now that we have the Magic Goodies, start the muxer
        if (mVideoFormat != null && mAudioFormat != null)
            throw new RuntimeException("already add all tracks");

        if (isVideo) {
            mVideoFormat = format;
            if (mAudioFormat != null) {
                startMuxer();
                EasyApplication.BUS.post(new StartRecord());
            }
        } else {
            mAudioFormat = format;
            if (mVideoFormat != null) {
                startMuxer();
                EasyApplication.BUS.post(new StartRecord());
            }
        }
    }

    private void startMuxer() {
        mBeginMillis = SystemClock.elapsedRealtime();
        String mp4Path = mFilePath;
        if (mp4Path.toLowerCase().endsWith(".mp4")){
            mp4Path = mp4Path.substring(0, mp4Path.length()-".mp4".length());
        }
        mp4Path += "-"+ index++;
        mp4Path += ".mp4";
        ByteBuffer sps = mVideoFormat.getByteBuffer("csd-0");
        ByteBuffer pps = mVideoFormat.getByteBuffer("csd-1");
        sps.clear();
        pps.clear();
        int sps_size = 0, pps_size = 0;
        if (sps != null) {
            sps_size = sps.remaining();
        } else {
            sps = ByteBuffer.allocate(0);
        }
        if (pps != null) {
            pps_size = pps.remaining();
        } else {
            pps = ByteBuffer.allocate(0);
        }
        byte []extra = new byte[sps_size + pps_size];
        sps.get(extra, 0, sps_size);
        pps.get(extra, sps_size, pps_size);
        sps.clear();
        pps.clear();
        int r = mMuxer.create(mp4Path, 0, mVideoFormat.getInteger(MediaFormat.KEY_WIDTH), mVideoFormat.getInteger(MediaFormat.KEY_HEIGHT), extra,
                mAudioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), mAudioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
        if (r != 0) throw new IllegalStateException("muxer create error:" + r);
    }

    public synchronized void pumpStream(ByteBuffer outputBuffer, MediaCodec.BufferInfo bufferInfo) {
        if (mAudioFormat == null || mAudioFormat == null) {
            Log.i(TAG, String.format("pumpStream video but muxer is not start.ignore.."));
            return;
        }
        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.  Ignore it.
        } else if (bufferInfo.size != 0) {
            // adjust the ByteBuffer values to match BufferInfo (not needed?)
            outputBuffer.position(bufferInfo.offset);
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
            byte[] buffer = new byte[bufferInfo.size];
            outputBuffer.get(buffer);
            outputBuffer.position(bufferInfo.offset);
            outputBuffer.limit(bufferInfo.offset + bufferInfo.size);
            int r = mMuxer.writeFrame(EasyMuxer2.AVMEDIA_TYPE_VIDEO,buffer,0, bufferInfo.size, bufferInfo.presentationTimeUs/1000);
            if (r != 0){
                Log.w(TAG,"WriteFrame return error:"+r);
            }
        }

        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE)
                Log.i(TAG, "BUFFER_FLAG_END_OF_STREAM received");
        }

        if (SystemClock.elapsedRealtime() - mBeginMillis >= durationMillis) {
            if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == 0) {
                return;
            }
            Log.w(TAG,"Changing file");
            mMuxer.close();
            mMuxer = new EasyMuxer2();
            startMuxer();
            pumpStream(outputBuffer, bufferInfo);
        }
    }

    public synchronized void pumpPCMStream(byte[] buffer, long timeStampMillis){
        int r = mMuxer.writeFrame(EasyMuxer2.AVMEDIA_TYPE_AUDIO,buffer,0, buffer.length, timeStampMillis);
        if (r != 0){
            Log.w(TAG,"Write audio Frame return error:"+r);
        }
    }
    public synchronized void release() {
        if (mMuxer != null) {
            mMuxer.close();
            mMuxer = null;
            EasyApplication.BUS.post(new StopRecord());
        }
    }
}
