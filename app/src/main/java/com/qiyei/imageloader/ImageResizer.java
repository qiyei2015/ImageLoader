package com.qiyei.imageloader;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.FileDescriptor;

/**
 * Created by daner on 2016/6/18.
 * 1273482124@qq.com
 * 图片压缩
 */
public class ImageResizer {

    public ImageResizer(){

    }

    /**
     * 从资源中加载压缩的图片
     * @param res
     * @param resId
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap decodeSampleBitmapFromResources(Resources res , int resId , int reqWidth , int reqHeight){

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;//只加载基本信息，不把图片加载进内存
        BitmapFactory.decodeResource(res,resId,options);
        options.inSampleSize = calculateInsampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds = false;

        return BitmapFactory.decodeResource(res,resId,options);
    }

    /**
     * 从文件描述符中加载压缩的图片
     * @param fd
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap decodeSampleBitmapFromFileDescriptor(FileDescriptor fd , int reqWidth , int reqHeight){
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFileDescriptor(fd,null,options);
        options.inSampleSize = calculateInsampleSize(options,reqWidth,reqHeight);
        options.inJustDecodeBounds = false;

        return BitmapFactory.decodeFileDescriptor(fd,null,options);

    }

    /**
     * 返回缩放系数，一般是1 2 4 8 等
     * @param options
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private int calculateInsampleSize(BitmapFactory.Options options , int reqWidth , int reqHeight){

        int originWidth = options.outWidth;
        int originHeight = options.outHeight;

        int inSampleSize = 1;

        int halfWidth = originWidth / 2;
        int halfHeight = originHeight / 2;

        while ((halfHeight / inSampleSize > reqWidth) && (halfWidth / inSampleSize > reqHeight)){
            inSampleSize *= 2;
        }

        return inSampleSize;
    }


}
