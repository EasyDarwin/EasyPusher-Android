package org.easydarwin.bus;

/**
 * 推流中的fps、bps
 *
 * Created by apple on 2017/5/14.
 */
public class StreamStat {

    public final int framePerSecond, bytesPerSecond;

    public StreamStat(int framePerSecond, int bytesPerSecond) {
        this.framePerSecond = framePerSecond;
        this.bytesPerSecond = bytesPerSecond;
    }
}
