package org.easydarwin.push;

import android.content.Context;

/**
 * Created by john on 2017/5/6.
 */

public interface Pusher {

    public static class Codec {
        /* 视频编码 */
        public static final int EASY_SDK_VIDEO_CODEC_H264 = 0x1C;
        public static final int EASY_SDK_VIDEO_CODEC_H265 = 0x48323635;

        /* 音频编码 */
        public static final int EASY_SDK_AUDIO_CODEC_AAC = 0x15002;
        public static final int EASY_SDK_AUDIO_CODEC_G711U = 0x10006;
        public static final int EASY_SDK_AUDIO_CODEC_G711A = 0x10007;
        public static final int EASY_SDK_AUDIO_CODEC_G726 = 0x1100B;
    }

    public static class TransType {
        public static final int EASY_RTP_OVER_TCP = 1;   //TCP推送
        public static final int EASY_RTP_OVER_UDP = 2;   //UDP推送
    }

    public void stop() ;

    public  void initPush(final Context context, final InitCallback callback);
    public  void initPush(final String url, final Context context, final InitCallback callback, int pts);
    public  void initPush(final String url, final Context context, final InitCallback callback);

    public void setMediaInfo(int videoCodec, int videoFPS, int audioCodec, int audioChannel, int audioSamplerate, int audioBitPerSample);
    public void start(String serverIP, String serverPort, String streamName, int transType);

    public  void push(byte[] data, int offset, int length, long timestamp, int type);

    public  void push(byte[] data, long timestamp, int type);
}
