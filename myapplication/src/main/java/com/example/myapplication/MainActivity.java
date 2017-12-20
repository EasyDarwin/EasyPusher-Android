package com.example.myapplication;

import android.arch.lifecycle.Observer;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.os.Bundle;
import android.arch.lifecycle.ViewModelProviders;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.easydarwin.push.MediaStream;
import org.easydarwin.util.AbstractSubscriber;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.List;

import io.reactivex.Single;
import io.reactivex.SingleEmitter;
import io.reactivex.SingleOnSubscribe;
import io.reactivex.SingleSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.functions.Consumer;
import io.reactivex.functions.Function;
import io.reactivex.subjects.PublishSubject;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1000;
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private MediaStream mediaStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 使用软编码.
        PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean("key-sw-codec", true).apply();

        mediaStream = ViewModelProviders.of(this).get(MediaStream.class);
        mediaStream.setLifecycle(getLifecycle());


        mediaStream.observeCameraPreviewResolution(this, new Observer<int[]>() {
            @Override
            public void onChanged(@Nullable int[] size) {
                Toast.makeText(MainActivity.this, "当前摄像头分辨率为:" + size[0] + "*" + size[1], Toast.LENGTH_SHORT).show();
            }
        });
        final TextView pushingStateText = findViewById(R.id.pushing_state);
        final TextView pushingBtn = findViewById(R.id.pushing);
        mediaStream.observePushingState(this, new Observer<MediaStream.PushingState>() {

            @Override
            public void onChanged(@Nullable MediaStream.PushingState pushingState) {
                if (pushingState.screenPushing) {
                    pushingStateText.setText("屏幕推送");

                    // 更改屏幕推送按钮状态.

                    TextView tview = findViewById(R.id.pushing_desktop);
                    if (pushingState.state > 0) {
                        tview.setText("取消推送");
                    } else {
                        tview.setText("推送屏幕");
                    }
                    findViewById(R.id.pushing_desktop).setEnabled(true);
                } else {
                    pushingStateText.setText("推送");

                    if (pushingState.state > 0) {
                        pushingBtn.setText("停止");
                    } else {
                        pushingBtn.setText("推送");
                    }

                }
                pushingStateText.append(":\t" + pushingState.msg);
                if (pushingState.state > 0) {
                    pushingStateText.append(pushingState.screenPushing ? String.format("rtsp://cloud.easydarwin.org:554/screen.sdp") : String.format("rtsp://cloud.easydarwin.org:554/ttt.sdp"));
                }


            }
        });
        TextureView textureView = findViewById(R.id.texture_view);
        textureView.setSurfaceTextureListener(new SurfaceTextureListenerWrapper() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                mediaStream.setSurfaceTexture(surfaceTexture);
            }
        });


        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
        }
    }

    public void onPushing(View view) {
        MediaStream.PushingState state = mediaStream.getPushingState();
        if (state != null && state.state > 0) {
            mediaStream.stopStream();
            mediaStream.closeCameraPreview();
        } else {
            mediaStream.openCameraPreview();
            mediaStream.startStream("cloud.easydarwin.org", "554", "test123456");
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    mediaStream.notifyPermissionGranted();
                } else {
                    finish();
                }
                break;
            }
        }
    }

    // 推送屏幕.
    public void onPushScreen(View view) {

        MediaStream.PushingState state = mediaStream.getScreenPushingState();
        if (state != null && state.state > 0) {
            // 取消推送。
            mediaStream.stopPushScreen();
        } else {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return;
            }
            MediaProjectionManager mMpMngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
            startActivityForResult(mMpMngr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
            // 防止点多次.
            view.setEnabled(false);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            mediaStream.pushScreen(resultCode, data, "cloud.easydarwin.org", "554", "screen");
        }
    }

    public void onSwitchCamera(View view) {
        mediaStream.switchCamera();
    }

    public void onChangeCameraParam(View view) {

        final Looper[]looperHolder = new Looper[1];
        final Camera[] cameraHolder = new Camera[1];

        final PublishSubject<Camera> subj = PublishSubject.create();
        mediaStream.getCamera().subscribe(new AbstractSubscriber<Camera>() {

            @Override
            public void onNext(Camera camera) { // 摄像头线程回调...
                subj.onNext(camera);
                cameraHolder[0] = camera;
                looperHolder[0] = Looper.myLooper();
            }

            @Override
            public void onError(Throwable t) {
                subj.onError(t);
            }
        });
        subj.firstOrError()
        .observeOn(AndroidSchedulers.mainThread())
        .flatMap(new Function<Camera, SingleSource<String>>() {
            @Override
            public SingleSource<String> apply(Camera camera) throws Exception {

                final PublishSubject<String> subj = PublishSubject.create();
                final Camera.Parameters parameters = camera.getParameters();
                List<String> supportedSceneModes = parameters.getSupportedSceneModes();
                final String []arr = new String[supportedSceneModes.size()];
                supportedSceneModes.toArray(arr);
                new AlertDialog.Builder(MainActivity.this).setTitle("切换模式").setItems(arr, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        subj.onNext(arr[which]);
                    }
                }).show();

                return subj.firstOrError().subscribeOn(AndroidSchedulers.from(looperHolder[0]));
            }
        })
        .subscribe(new Consumer<String>() {
            @Override
            public void accept(String s) throws Exception {

                final Camera.Parameters parameters = cameraHolder[0].getParameters();
                parameters.setSceneMode(s);

                cameraHolder[0].setParameters(parameters);
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                throwable.printStackTrace();
            }
        });

    }

    public void onUVCCamera(View view) {
        mediaStream.switchCamera(2).subscribe(new AbstractSubscriber<Object>(){
            @Override
            public void onNext(Object o) {
                super.onNext(o);
                mediaStream.startStream("cloud.easydarwin.org", "554", "ttt");
            }

            @Override
            public void onError(final Throwable t) {
                super.onError(t);
                t.printStackTrace();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(MainActivity.this,"UVC摄像头启动失败.." + t.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
}
