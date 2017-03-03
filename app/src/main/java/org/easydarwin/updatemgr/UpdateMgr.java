/*
	Copyright (c) 2012-2017 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/
package org.easydarwin.updatemgr;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;

import org.easydarwin.easypusher.R;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class UpdateMgr {
    private static final String TAG = "UpdateMgr";
    private Context mContext;
    private String mApkUrl;
    private Handler mHandler;
    private Runnable mShowDlg = new Runnable() {
        @Override
        public void run() {
            showUpdateDialog();
        }
    };

    public UpdateMgr(Context context){
        this.mContext = context;
        mHandler = new Handler();
    }

    /**
     * 检测当前APP是否需要升级
     */
    public void checkUpdate(){
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url="http://www.easydarwin.org/versions/easypusher/version.txt";
                    OkHttpClient client = new OkHttpClient();
                    Request request = new Request.Builder()
                            .url(url)
                            .build();
                    Response response = client.newCall(request).execute();
                    if (response.isSuccessful()) {
                        String string = response.body().string();
                        JSONObject obj = new JSONObject(string);

                        String versionCode = obj.optString("versionCode");
                        String versionName= obj.optString("versionName");
                        String versionUrl= obj.optString("url");

                        if(TextUtils.isEmpty(versionUrl)){
                            return;
                        }
                        PackageManager packageManager=mContext.getPackageManager();
                        try {
                            PackageInfo packageInfo=packageManager.getPackageInfo(mContext.getPackageName(),0);
                            int localVersionCode=packageInfo.versionCode;
                            int remoteVersionCode= Integer.valueOf(versionCode);
                            Log.d(TAG, "kim localVersionCode="+localVersionCode+", remoteVersionCode="+remoteVersionCode);
                            if(localVersionCode<remoteVersionCode){
                                mApkUrl = versionUrl;
                                mHandler.post(mShowDlg);
                            }
                        } catch (PackageManager.NameNotFoundException e) {
                            Log.e(TAG,"Get PackageInfo error !");
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    /**
     * 提示升级对话框
     */
    private void showUpdateDialog(){
        final String apkUrl=mApkUrl;
        Log.d(TAG, "kim showUpdateDialog. apkUrl="+apkUrl);
        new AlertDialog.Builder(mContext)
                .setMessage("EasyPusher可以升级到更高的版本，是否升级")
                .setTitle("升级提示")
                .setIcon(R.drawable.easy_logo)
                .setPositiveButton("升级", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DownloadManager downloadManager = (DownloadManager) mContext.getSystemService(Context.DOWNLOAD_SERVICE);
                        Uri uri = Uri.parse(apkUrl);
                        DownloadManager.Request request = new DownloadManager.Request(uri);
                        // 设置允许使用的网络类型，这里是移动网络和wifi都可以
                        request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE | DownloadManager.Request.NETWORK_WIFI);
                        // 禁止发出通知，既后台下载，如果要使用这一句必须声明一个权限：android.permission.DOWNLOAD_WITHOUT_NOTIFICATION
                        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
                        downloadManager.enqueue(request);
                        dialog.dismiss();
                    }
                }).setNegativeButton("取消", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
            }
        }).create().show();
    }
}
