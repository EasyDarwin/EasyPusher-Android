package org.easydarwin.push;

import org.easydarwin.audio.AudioStream;


/**
 * Created by apple on 2017/5/13.
 */
public interface MuxerModule {
    void inject(HWConsumer consumer);
    void inject(AudioStream MedisStream);
}

