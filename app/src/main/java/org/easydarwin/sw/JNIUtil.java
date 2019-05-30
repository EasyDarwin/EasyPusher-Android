package org.easydarwin.sw;

/**
 */
public class JNIUtil {

    static {
        System.loadLibrary("Utils");
    }


    /**
     * 旋转1个字节为单位的矩阵
     *
     * @param data   要旋转的矩阵
     * @param offset 偏移量
     * @param width  宽度
     * @param height 高度
     * @param degree 旋转度数
     */
    public static void rotateMatrix(byte[] data, int offset, int width, int height, int degree) {
        callMethod("RotateByteMatrix", null, data, offset, width, height, degree);
    }

    /**
     * 旋转2个字节为单位的矩阵
     *
     * @param data   要旋转的矩阵
     * @param offset 偏移量
     * @param width  宽度
     * @param height 高度
     * @param degree 旋转度数
     */
    public static void rotateShortMatrix(byte[] data, int offset, int width, int height, int degree) {
        callMethod("RotateShortMatrix", null, data, offset, width, height, degree);
    }

    private static native void callMethod(String methodName, Object[] returnValue, Object... params);




    /**
     * 0 NULL,
     * 1 yuv_to_yvu,
     * 2 yuv_to_yuvuv,
     * 3 yuv_to_yvuvu,
     * 4 yuvuv_to_yuv,
     * 5 yuvuv_to_yvu,
     * 6 yuvuv_to_yvuvu,
     *
     * @param data
     * @param width
     * @param height
     * @param mode
     */
    public static native void yuvConvert(byte[] data, int width, int height, int mode);

    /**
     * @param yuv
     * @param argb
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void Android420ToARGB(byte[] yuv, byte[] argb, int width, int height, int mode);

    /**
     * @param yuv
     * @param argb
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void Android420ToABGR(byte[] yuv, byte[] argb, int width, int height, int mode);


    public static native void ARGBToRGB24(byte[] argb, byte[] rgb, int width, int height);

    /**
     * Convert camera sample to I420 with cropping, rotation and vertical flip.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param cropX      "crop_x" and "crop_y" are starting position for cropping.
     *                   To center, crop_x = (src_width - dst_width) / 2
     *                   crop_y = (src_height - dst_height) / 2
     * @param cropY      "crop_x" and "crop_y" are starting position for cropping.
     *                   To center, crop_x = (src_width - dst_width) / 2
     *                   crop_y = (src_height - dst_height) / 2
     * @param cropWidth
     * @param cropHeight
     * @param rotation   "rotation" can be 0, 90, 180 or 270.
     * @param mode       0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void ConvertToI420
    (byte[] src, byte[] dst, int width, int height, int cropX, int cropY,
     int cropWidth, int cropHeight, int rotation, int mode);


    /**
     * Convert camera sample to I420 with cropping, rotation and vertical flip.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param mode   0:420,1:YV12,2:NV21,3:NV12
     */
    public static native void ConvertFromI420
    (byte[] src, byte[] dst, int width, int height, int mode);

    /**
     * I420压缩.
     *
     * @param src
     * @param dst
     * @param width
     * @param height
     * @param dstWidth
     * @param dstHeight
     * @param mode      0:Point sample; Fastest.<p>1:Filter horizontally only.<p>2:Faster than box, but lower quality scaling down.<p>3:Highest quality.
     */
    public static native void I420Scale
    (byte[] src, byte[] dst, int width, int height, int dstWidth, int dstHeight, int mode);

}
