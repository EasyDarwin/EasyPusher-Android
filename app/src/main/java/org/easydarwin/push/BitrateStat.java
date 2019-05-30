package org.easydarwin.push;

import android.os.SystemClock;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class BitrateStat {


    static Map<String, LinkedList<long[]>> sMap = new HashMap<>();


    public static int stat(String tag, long size){
        LinkedList<long[]> list = sMap.get(tag);
        if (list == null) {
            list = new LinkedList();
            sMap.put(tag, list);
        }



        long []tail = null;
        try {
            tail = list.getLast();
        }catch (Throwable ex){

        }
        long total = tail != null ? tail[2]:0;
        long []head = null;
        try{
            head = list.getFirst();
        }catch (Exception ex){

        }
        long [] arr = new long[]{
                SystemClock.elapsedRealtime(),size, size + total};
        list.offer(arr);
        while (list.size()>30)
        {
            long[] poll = list.poll();
            arr[2] -= poll[1];
        }
        if (head != null){
            int bitrate = (int) ((size + total) * 1000 / (SystemClock.elapsedRealtime() - head[0]));
            return bitrate;
        }
        return 0;
    }
}
