package com.example.myapplication;

import android.graphics.SurfaceTexture;
import android.view.TextureView;

/**
 * Created by apple on 2017/9/11.
 */

public abstract class SurfaceTextureListenerWrapper implements TextureView.SurfaceTextureListener{

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {

        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

    }
}
