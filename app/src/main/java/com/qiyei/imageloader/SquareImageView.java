package com.qiyei.imageloader;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageView;

/**
 * Created by daner on 2016/7/6.
 * 1273482124@qq.com
 */
public class SquareImageView extends ImageView {

    public SquareImageView(Context context){
        super(context);
    }

    public SquareImageView(Context context , AttributeSet attrs){
        super(context,attrs);
    }

    public SquareImageView(Context context ,AttributeSet attrs , int style){
        super(context,attrs,style);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        //方正的ImageView
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
