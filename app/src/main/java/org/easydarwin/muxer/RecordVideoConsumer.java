package org.easydarwin.muxer;

import android.content.Context;

import org.easydarwin.encode.SWConsumer;
import org.easydarwin.encode.VideoConsumer;
import org.easydarwin.encode.VideoConsumerWrapper;
import org.easydarwin.encode.HWConsumer;
import org.easydarwin.sw.TxtOverlay;
import org.easydarwin.util.SPUtil;

import java.text.SimpleDateFormat;
import java.util.Date;

public class RecordVideoConsumer implements VideoConsumerWrapper {

    VideoConsumer consumer;

    private TxtOverlay overlay;
    private final Context context;

    public RecordVideoConsumer(Context context, String mime, boolean swCodec, EasyMuxer muxer) {
        this.context = context;

        consumer = swCodec ? new SWConsumer(context, null) : new HWConsumer(context, mime, null);
        consumer.setMuxer(muxer);
    }

    @Override
    public void onVideoStart(int width, int height) {
        consumer.onVideoStart(width, height);
        overlay = new TxtOverlay(context);
        overlay.init(width, height, context.getFileStreamPath("SIMYOU.ttf").getPath());
    }

    @Override
    public int onVideo(byte[] data, int format) {
        if (SPUtil.getEnableVideoOverlay(context)) {
            String txt = new SimpleDateFormat("yy-MM-dd HH:mm:ss SSS").format(new Date());
            overlay.overlay(data, txt);
        }

        return consumer.onVideo(data, format);
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

    }
}
