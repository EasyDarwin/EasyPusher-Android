package org.easydarwin.encode;

import android.content.Context;

import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.JNIUtil;
import org.easydarwin.sw.TxtOverlay;
import org.easydarwin.util.SPUtil;

import java.text.SimpleDateFormat;
import java.util.Date;

public class ClippableVideoConsumer implements VideoConsumerWrapper {

    private final VideoConsumer consumer;

    private final int width;
    private final int height;

    private final Context context;
    private TxtOverlay overlay;

    private int originalWidth,originalHeight;
    private byte[] i420_buffer2;

    /**
     *
     * @param context   context
     * @param consumer  the consumer which will consume the clipped video.
     * @param width     clipped video width
     * @param height    clipped video height
     */
    public ClippableVideoConsumer(Context context, VideoConsumer consumer, int width, int height) {
        this.consumer = consumer;
        this.width = width;
        this.height = height;
        this.context = context;

        i420_buffer2 = new byte[width*height * 3 / 2];
    }

    @Override
    public void onVideoStart(int width, int height) {
        originalHeight = height;
        originalWidth = width;

        consumer.onVideoStart(this.width,this.height);

        overlay = new TxtOverlay(context);
        overlay.init(width, height, context.getFileStreamPath("SIMYOU.ttf").getPath());
    }

    @Override
    public int onVideo(byte[] data, int format) {
        JNIUtil.I420Scale(data, i420_buffer2, originalWidth, originalHeight, width, height,0);

        if (SPUtil.getEnableVideoOverlay(context)) {
            String txt = String.format("drawtext=fontfile=" + context.getFileStreamPath("SIMYOU.ttf") + ": text='%s%s':x=(w-text_w)/2:y=H-60 :fontcolor=white :box=1:boxcolor=0x00000000@0.3", "EasyPusher", new SimpleDateFormat("yyyy-MM-ddHHmmss").format(new Date()));
            txt =  new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
            overlay.overlay(i420_buffer2, txt);
        }

        return consumer.onVideo(i420_buffer2, format);
    }

    @Override
    public void onVideoStop() {
        consumer.onVideoStop();

        if (overlay != null) {
            overlay.release();
            overlay = null;
        }
    }

    @Override
    public void setMuxer(EasyMuxer muxer) {
        consumer.setMuxer(muxer);
    }
}
