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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 类Util的实现描述
 *
 * @author HELONG 2016/3/8 17:42
 */
public class Util {

    private static final String PREF_NAME = "easy_pref";
    private static final String K_RESOLUTION = "k_resolution";

    /**
     * 获取摄像头支持的分辨率
     *
     * @param context
     * @return
     */
    public static List<String> getSupportResolution(Context context) {
        List<String> resolutions = new ArrayList<>();
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String r = sharedPreferences.getString(K_RESOLUTION, "");

        if (!TextUtils.isEmpty(r)) {
            String[] arr = r.split(";");

            if (arr.length > 0) {
                resolutions = Arrays.asList(arr);
            }
        }

        return resolutions;
    }

    /**
     * 保存支持分辨率
     *
     * @param context
     * @param value
     */
    public static void saveSupportResolution(Context context,String value) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sharedPreferences.edit().putString(K_RESOLUTION, value).commit();
    }
}
