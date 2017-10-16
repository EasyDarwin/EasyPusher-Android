package com.example.myapplication;

import android.arch.lifecycle.LifecycleActivity;
import android.arch.lifecycle.Observer;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.support.annotation.Nullable;
import android.os.Bundle;
import android.arch.lifecycle.ViewModelProviders;
import android.support.v4.app.ActivityCompat;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import org.easydarwin.push.MediaStream;

public class MainActivity extends LifecycleActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 1000;
    private static final int REQUEST_MEDIA_PROJECTION = 1001;
    private MediaStream mediaStream;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mediaStream = ViewModelProviders.of(this).get(MediaStream.class);
        mediaStream.setLifecycle(getLifecycle());
        mediaStream.openCameraPreview();

        mediaStream.observeCameraPreviewResolution(this, new Observer<int[]>() {
            @Override
            public void onChanged(@Nullable int[] size) {
                Toast.makeText(MainActivity.this,"当前摄像头分辨率为:" + size[0] + "*" + size[1], Toast.LENGTH_SHORT).show();
            }
        });
        final TextView pushingStateText = findViewById(R.id.pushing_state);
        final TextView pushingBtn = findViewById(R.id.pushing);
        mediaStream.observePushingState(this, new Observer<MediaStream.PushingState>(){

            @Override
            public void onChanged(@Nullable MediaStream.PushingState pushingState) {
                if (pushingState.screenPushing) {
                    pushingStateText.setText("屏幕推送");

                    // 更改屏幕推送按钮状态.

                    TextView tview = findViewById(R.id.pushing_desktop);
                    if (pushingState.state > 0){
                        tview.setText("取消推送");
                    }else {
                        tview.setText("推送屏幕");
                    }
                    findViewById(R.id.pushing_desktop).setEnabled(true);
                }else{
                    pushingStateText.setText("摄像头推送");
                }
                pushingStateText.append(":\t" + pushingState.msg);
                if (pushingState.state > 0) {
                    pushingStateText.append(pushingState.screenPushing ? String.format("rtsp://cloud.easydarwin.org:554/screen123456.sdp"):String.format("rtsp://cloud.easydarwin.org:554/test123456.sdp"));
                }


            }
        });
        mediaStream.observeStreamingState(this, new Observer<Boolean>(){

            @Override
            public void onChanged(@Nullable Boolean aBoolean) {
                pushingBtn.setText(aBoolean ? "停止推送":"开始推送");
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
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA,android.Manifest.permission.RECORD_AUDIO}, REQUEST_CAMERA_PERMISSION);
        }
    }

    public void onPushing(View view) {
        MediaStream.PushingState state = mediaStream.getPushingState();
        if (state != null && state.state > 0){
            mediaStream.stopStream();
        }else {
            mediaStream.startStream("cloud.easydarwin.org", "554", "test123456");
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case REQUEST_CAMERA_PERMISSION: {
                if (grantResults.length > 1
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED&& grantResults[1] == PackageManager.PERMISSION_GRANTED) {
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        MediaProjectionManager mMpMngr = (MediaProjectionManager) getApplicationContext().getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mMpMngr.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION);
        // 防止点多次.
        view.setEnabled(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_MEDIA_PROJECTION){
            mediaStream.pushScreen(resultCode, data, "cloud.easydarwin.org", "554", "screen123456");
        }
    }
}
