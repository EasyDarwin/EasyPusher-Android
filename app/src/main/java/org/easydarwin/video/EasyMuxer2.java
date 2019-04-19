package org.easydarwin.video;

import java.lang.annotation.Native;

/**
 * Created by John on 2017/1/10.
 */

public class EasyMuxer2 {

    static {
        System.loadLibrary("proffmpeg");
        System.loadLibrary("VideoCodecer");
    }
    public static final int AVMEDIA_TYPE_VIDEO  = 0;
    public static final int AVMEDIA_TYPE_AUDIO  = 1;


    public static final int VIDEO_TYPE_H264 = 0;
    public static final int VIDEO_TYPE_H265 = 1;
    @Native
    private long ctx;

    public native int create(String path, int videoType, int width, int height, byte[] extra, int sample, int channel);

    public native int writeFrame(int streamType, byte[] frame, int offset, int length, long timeStampMillis);

    public native void close();
}
