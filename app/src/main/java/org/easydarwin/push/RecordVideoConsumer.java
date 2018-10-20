package org.easydarwin.push;

import android.content.Context;
import android.preference.PreferenceManager;

import org.easydarwin.muxer.EasyMuxer;
import org.easydarwin.sw.TxtOverlay;

import java.text.SimpleDateFormat;
import java.util.Date;

public class RecordVideoConsumer implements VideoConsumerWrapper {

    HWConsumer consumer;
    private TxtOverlay overlay;
    private final Context context;

    public RecordVideoConsumer(Context context, EasyMuxer muxer) {
        this.context = context;
        consumer = new HWConsumer(context, null);
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
        if (PreferenceManager.getDefaultSharedPreferences(context).getBoolean("key_enable_video_overlay", false)) {
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
