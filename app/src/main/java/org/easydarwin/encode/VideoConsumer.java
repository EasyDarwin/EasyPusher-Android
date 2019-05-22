package org.easydarwin.encode;

import org.easydarwin.muxer.EasyMuxer;

/**
 * Created by apple on 2017/5/13.
 */
public interface VideoConsumer {
    void onVideoStart(int width, int height) ;

    int onVideo(byte[] data, int format);

    void onVideoStop();

    void setMuxer(EasyMuxer muxer);
}
