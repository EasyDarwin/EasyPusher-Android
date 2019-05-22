package org.easydarwin.easypusher;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.preference.PreferenceManager;

import com.squareup.otto.Bus;
import com.squareup.otto.Subscribe;
import com.squareup.otto.ThreadEnforcer;
import com.tencent.bugly.crashreport.CrashReport;

import org.easydarwin.bus.StartRecord;
import org.easydarwin.bus.StopRecord;
import org.easydarwin.util.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class EasyApplication extends Application {

    public static final String CHANNEL_CAMERA = "camera";

    private static EasyApplication mApplication;
    public static int activeDays = 9999;

    public static final Bus BUS = new Bus(ThreadEnforcer.ANY);
    public long mRecordingBegin;
    public boolean mRecording;

    public static EasyApplication getEasyApplication() {
        return mApplication;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        mApplication = this;

        if (!BuildConfig.DEBUG) {
            CrashReport.initCrashReport(getApplicationContext(), "678a09e90e", false);
        }

        File youyuan = getFileStreamPath("SIMYOU.ttf");
        if (!youyuan.exists()) {
            AssetManager am = getAssets();

            try {
                InputStream is = am.open("zk/SIMYOU.ttf");
                FileOutputStream os = openFileOutput("SIMYOU.ttf", MODE_PRIVATE);
                byte[] buffer = new byte[1024];
                int len;

                while ((len = is.read(buffer)) != -1) {
                    os.write(buffer, 0, len);
                }

                os.close();
                is.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        BUS.register(this);

        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.camera);

            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel channel = new NotificationChannel(CHANNEL_CAMERA, name, importance);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Subscribe
    public void onStartRecord(StartRecord sr) {
        // 开始录像的通知，记下当前时间
        mRecording = true;
        mRecordingBegin = System.currentTimeMillis();
    }

    @Subscribe
    public void onStopRecord(StopRecord sr){
        // 停止录像的通知，更新状态
        mRecording = false;
        mRecordingBegin = 0;
    }
}
