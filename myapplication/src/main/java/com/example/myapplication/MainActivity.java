package com.example.myapplication;

import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.view.TextureView;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import org.easydarwin.push.MediaStream;

import io.reactivex.Single;
import io.reactivex.functions.Consumer;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1000;
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    public static final String HOST = "cloud.easydarwin.org";
    private MediaStream mediaStream;

    private Single<MediaStream> getMediaStream() {
        Single<MediaStream> single = RxHelper.single(MediaStream.getBindedMediaStream(this, this), mediaStream);
        if (mediaStream == null) {
            return single.doOnSuccess(new Consumer<MediaStream>() {
                @Override
                public void accept(MediaStream ms) throws Exception {
                    mediaStream = ms;
                }
            });
        } else {
            return single;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CheckBox hevc_enable = findViewById(R.id.enable_265);
        hevc_enable.setChecked(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("try_265_encode", false));
        hevc_enable.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                PreferenceManager.getDefaultSharedPreferences(MainActivity.this).edit().putBoolean("try_265_encode", isChecked).apply();
            }
        });

        // 启动服务...
        Intent intent = new Intent(this, MediaStream.class);
        startService(intent);

        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(final MediaStream ms) throws Exception {
                ms.observeCameraPreviewResolution(MainActivity.this, new Observer<int[]>() {
                    @Override
                    public void onChanged(@Nullable int[] size) {
                        Toast.makeText(MainActivity.this, "当前摄像头分辨率为:" + size[0] + "*" + size[1], Toast.LENGTH_SHORT).show();
                    }
                });
                final TextView pushingStateText = findViewById(R.id.pushing_state);
                final TextView pushingBtn = findViewById(R.id.pushing);
                ms.observePushingState(MainActivity.this, new Observer<MediaStream.PushingState>() {

                    @Override
                    public void onChanged(@Nullable MediaStream.PushingState pushingState) {
                        if (pushingState.screenPushing) {
                            pushingStateText.setText("屏幕推送");

                            // 更改屏幕推送按钮状态.

                            TextView tview = findViewById(R.id.pushing_desktop);
                            if (ms.isScreenPushing()) {
                                tview.setText("取消推送");
                            } else {
                                tview.setText("推送屏幕");
                            }
                            findViewById(R.id.pushing_desktop).setEnabled(true);
                        } else {
                            pushingStateText.setText("推送");
                            if (ms.isCameraPushing()) {
                                pushingBtn.setText("停止");
                            } else {
                                pushingBtn.setText("推送");
                            }
                        }

                        pushingStateText.append(":\t" + pushingState.msg);
                        if (pushingState.state > 0) {
                            pushingStateText.append(pushingState.url);
                            pushingStateText.append("\n");
                            if ("avc".equals(pushingState.videoCodec)) {
                                pushingStateText.append("视频编码方式：" + "H264硬编码");
                            }else if ("hevc".equals(pushingState.videoCodec)) {
                                pushingStateText.append("视频编码方式："  + "H265硬编码");
                            }else if ("x264".equals(pushingState.videoCodec)) {
                                pushingStateText.append("视频编码方式："  + "x264");
                            }
                        }

                    }
                });
                TextureView textureView = findViewById(R.id.texture_view);
                if (textureView.isAvailable()) {
                    ms.setSurfaceTexture(textureView.getSurfaceTexture());
                } else {
                    textureView.setSurfaceTextureListener(new SurfaceTextureListenerWrapper() {
                        @Override
                        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
                            ms.setSurfaceTexture(surfaceTexture);
                        }

                        @Override
                        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                            ms.setSurfaceTexture(null);
                            return true;
                        }
                    });
                }

                if (ActivityCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
                }
            }
        }, new Consumer<Throwable>() {
            @Override
            public void accept(Throwable throwable) throws Exception {
                Toast.makeText(MainActivity.this, "创建服务出错!", Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void onPushing(View view) {
        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(MediaStream mediaStream) throws Exception {
                MediaStream.PushingState state = mediaStream.getPushingState();
                if (state != null && state.state > 0) { // 终止推送和预览
                    mediaStream.stopStream();
                    mediaStream.closeCameraPreview();
                } else {                                // 启动预览和推送.
                    mediaStream.openCameraPreview();
                    String id = PreferenceManager.getDefaultSharedPreferences(MainActivity.this).getString("caemra-id", null);
                    if (id == null) {
                        double v = Math.random() * 1000;
                        id = "c_" + (int) v;
                        PreferenceManager.getDefaultSharedPreferences(MainActivity.this).edit().putString("caemra-id", id).apply();
                    }
                    mediaStream.startStream(HOST, "554", id);
                }
            }
        });
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                    getMediaStream().subscribe(new Consumer<MediaStream>() {
                        @Override
                        public void accept(MediaStream mediaStream) throws Exception {
                            mediaStream.notifyPermissionGranted();
                        }
                    });
                } else {
                    finish();
                }
                break;
            }
        }
    }

    // 推送屏幕.
    public void onPushScreen(final View view) {
        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(MediaStream mediaStream) {
                if (mediaStream.isScreenPushing()) { // 正在推送，那取消推送。
                    // 取消推送。
                    mediaStream.stopPushScreen();
                } else {    // 没在推送，那启动推送。
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {     // lollipop 以前版本不支持。
                        return;
                    }
                    MediaProjectionManager mMpMngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
                    startActivityForResult(mMpMngr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
                    // 防止点多次.
                    view.setEnabled(false);
                }
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, final int resultCode, final Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            getMediaStream().subscribe(new Consumer<MediaStream>() {
                @Override
                public void accept(MediaStream mediaStream) {
                    mediaStream.pushScreen(resultCode, data, HOST, "554", "screen111");
                }
            });
        }
    }

    public void onSwitchCamera(View view) {
        getMediaStream().subscribe(new Consumer<MediaStream>() {
            @Override
            public void accept(MediaStream mediaStream) throws Exception {
                mediaStream.switchCamera();
            }
        });
    }

    public void onUVCCamera(View view) {
        Intent intent = new Intent(this, UVCActivity.class);
        startActivity(intent);
    }
}
