/*
	Copyright (c) 2013-2016 EasyDarwin.ORG.  All rights reserved.
	Github: https://github.com/EasyDarwin
	WEChat: EasyDarwin
	Website: http://www.easydarwin.org
*/

package org.easydarwin.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.easydarwin.config.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 类Util的实现描述：//TODO 类实现描述
 *
 * @author HELONG 2016/3/8 17:42
 */
public class Util {

    /**
     * 获取摄像头支持的分辨率
     * @param context
     * @return
     */
    public static List<String> getSupportResolution(Context context){
        List<String> resolutions=new ArrayList<>();
        SharedPreferences sharedPreferences=context.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE);
        String r=sharedPreferences.getString(Config.K_RESOLUTION, "");
        if(!TextUtils.isEmpty(r)){
            String[] arr=r.split(";");
            if(arr.length>0){
                resolutions= Arrays.asList(arr);
            }
        }

        return resolutions;
    }

    /**
     * 保存支持分辨率
     * @param context
     * @param value
     */
    public static void saveSupportResolution(Context context, String value){
        SharedPreferences sharedPreferences=context.getSharedPreferences(Config.PREF_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putString(Config.K_RESOLUTION, value).commit();
    }


}
