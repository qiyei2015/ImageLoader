package com.qiyei.imageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by daner on 2016/6/18.
 * 1273482124@qq.com
 */
public class ImageLoader {

    private static final String TAG = "ImageLoader";

    public static final int MESSAGE_POST_RESULT = 1;

    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    private static final long KEEP_ALIVE = 10L;

    private static final int TAG_KEY_URI = R.id.image;

    private static final long DISK_CACHE_SIZE = 50 * 1024 * 1024;//50M
    private static final int IO_BUFFER_SIZE = 8 * 1024; //8K
    private static final int DISK_CACHE_INDEX = 0;

    private Context mContext;

    //memory cache
    private LruCache<String,Bitmap> mMemoryCache;
    // diskcache
    private DiskLruCache mDiskLruCache;
    private ImageResizer mImageResizer = new ImageResizer();


    private boolean mIsDiskLruCacheCreated = false;

    private static final ThreadFactory sThreadFactory = new ThreadFactory() {

        //线程计数
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {

            return new Thread(r,TAG+"#"+mCount.getAndIncrement());
        }
    };

    //线程执行
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(CORE_POOL_SIZE
            ,MAXIMUM_POOL_SIZE,KEEP_ALIVE, TimeUnit.SECONDS,new LinkedBlockingDeque<Runnable>(),sThreadFactory);

    private Handler mMainHandler = new Handler(Looper.getMainLooper()){

        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;
            imageView.setImageBitmap(result.bitmap);
            String uri = (String) imageView.getTag();
            if (uri.equals(result.uri)){
                imageView.setImageBitmap(result.bitmap);
            } else {
                Log.w(TAG,"set image bitmap,but url has changed,ignored !");
            }
        }
    };

    /**
     * 静态内部类
     */
    private static class LoaderResult{
        public ImageView imageView;
        public String uri;
        public Bitmap bitmap;

        public LoaderResult(ImageView imageView , String uri,Bitmap bitmap){
            this.imageView = imageView;
            this.uri = uri;
            this.bitmap = bitmap;
        }
    }

    /**
     * 构造函数，私有化
     * @param context
     */
    private ImageLoader(Context context){
        mContext = context.getApplicationContext();
        int maxMemory = (int) Runtime.getRuntime().maxMemory() / 1024;
        int cacheSize = maxMemory / 8;

        mMemoryCache = new LruCache<String,Bitmap>(cacheSize){

            @Override
            protected int sizeOf(String key, Bitmap value) {
                //返回的KB
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        File diskCacheDir = getDiskCacheDir(mContext,"bitmap");
        if (!diskCacheDir.exists()){
            diskCacheDir.mkdir();
        }

        //判断磁盘缓存是否磁盘空间足够
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE){
            try {
                //目录，app版本号，大小等
                mDiskLruCache = DiskLruCache.open(diskCacheDir,1,1,DISK_CACHE_SIZE);
                mIsDiskLruCacheCreated = true;

            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 创建一个ImageLoader实例
     * @param context
     * @return
     */
    public static ImageLoader build(Context context){
        return new ImageLoader(context);
    }

    /**
     *  将url的图片绑定到imageView上显示，这个是异步加载
     */
    public void bindBitmap(String url , ImageView imageView){
        bindBitmap(url,imageView,0,0);
    }

    /**
     * 将url的图片绑定到imageView上显示，这个是异步加载
     * @param url
     * @param imageView
     * @param reqWidth
     * @param reqHeight
     */
    public void bindBitmap(final String url , final ImageView imageView , final int reqWidth , final int reqHeight){
        imageView.setTag(TAG_KEY_URI,url);

        //从内存中加载
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if (bitmap != null){
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap taskBitmap = loadBitmap(url,reqWidth,reqHeight);
                if (taskBitmap != null){
                    LoaderResult loaderResult = new LoaderResult(imageView,url,taskBitmap);
                    //获取对应的消息体，然后发送到目标handler
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT,loaderResult).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }


    /**
     * 同步加载图片接口
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    public Bitmap loadBitmap(String url , int reqWidth , int reqHeight){
        //先从内存中获取图片
        Bitmap bitmap = loadBitmapFromMemCache(url);
        if (bitmap != null){
            Log.d(TAG,"loadBitmapFromMemCache,url:" + url);
            return bitmap;
        }

        try {
            bitmap = loadBitmapFromDiskCache(url,reqWidth,reqHeight);
            if (bitmap != null){
                Log.d(TAG,"loadBitmapFromDiskCache,url:" + url);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(url,reqWidth,reqHeight);
            Log.d(TAG,"loadBitmapFromHttp,url:" + url);
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (bitmap == null && !mIsDiskLruCacheCreated){
            Log.w(TAG,"encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(url);
        }

        return bitmap;

    }

    /**
     * 从内存加载图片
     * @param url 获取的url
     * @return 返回的bitmap
     */
    private Bitmap loadBitmapFromMemCache(String url){
        final String key = hashKeyFromUrl(url);
        Bitmap bitmap = getBitmapFromMemCahce(key);
        return bitmap;
    }

    /**
     * 从DiskCache中加载图片
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     */
    private Bitmap loadBitmapFromDiskCache(String url,int reqWidth,int reqHeight) throws IOException{
        //不能再主线程执行IO请求任务
        if (Looper.myLooper() == Looper.getMainLooper()){
            Log.w(TAG,"load bitmap from UI thread, it`s not recommend !");
        }

        if (mDiskLruCache == null){
            return null;
        }

        Bitmap bitmap = null;
        String key = hashKeyFromUrl(url);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null){
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            //文件描述符
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer.decodeSampleBitmapFromFileDescriptor(fileDescriptor,reqWidth,reqHeight);
            if (bitmap != null){
                //添加到内存缓存
                addBitmapToMemoryCache(key,bitmap);
            }
        }
        return bitmap;
    }

    /**
     * 从Http上下载url的图片
     * @param url
     * @param reqWidth
     * @param reqHeight
     * @return
     * @throws IOException
     */
    private Bitmap loadBitmapFromHttp(String url,int reqWidth,int reqHeight) throws IOException{
        //不能再主线程执行网络请求任务
        if (Looper.myLooper() == Looper.getMainLooper()){
            throw new RuntimeException("can not visit network from UI Thread.");
        }

        //检查mDiskCache是否初始化成功
        if (mDiskLruCache == null){
            return null;
        }

        String key = hashKeyFromUrl(url);
        DiskLruCache.Editor editor = mDiskLruCache.edit(key);
        if (editor != null){
            OutputStream outputStream = editor.newOutputStream(DISK_CACHE_INDEX);
            //如果下载成功就提交事务,否则取消事务
            if (downloadUrlToStream(url,outputStream)){
                editor.commit();
            } else {
                editor.abort();
            }
            //刷新缓存
            mDiskLruCache.flush();
        }
        return loadBitmapFromDiskCache(url,reqWidth,reqHeight);
    }

    /**
     * 将url的图片下载到OutputStream流中
     * @param urlString
     * @param out
     * @return
     */
    private boolean downloadUrlToStream(String urlString,OutputStream out){

        HttpURLConnection httpURLConnection = null;
        BufferedOutputStream outputStream = null;
        BufferedInputStream inputStream = null;

        try {
            URL url = new URL(urlString);
            httpURLConnection = (HttpURLConnection) url.openConnection();
            inputStream = new BufferedInputStream(httpURLConnection.getInputStream(),IO_BUFFER_SIZE);
            outputStream = new BufferedOutputStream(out,IO_BUFFER_SIZE);

            int b;
            while ((b = inputStream.read())!= -1){
                outputStream.write(b);
            }
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG,"download bitmap failed.");
        } finally {
            //关闭链接
            if (httpURLConnection != null){
                httpURLConnection.disconnect();
            }
            //关闭IO链接
            try {
                if (outputStream != null){
                    outputStream.close();
                }

                if (inputStream != null){
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    /**
     * 从网络下载bitmap
     * @param urlString
     * @return
     */
    private Bitmap downloadBitmapFromUrl(String urlString){
        Bitmap bitmap = null;
        HttpURLConnection httpURLConnection = null;
        BufferedInputStream inputStream = null;

        try {
            URL url = new URL(urlString);
            httpURLConnection = (HttpURLConnection) url.openConnection();
            inputStream = new BufferedInputStream(httpURLConnection.getInputStream(),IO_BUFFER_SIZE);
            bitmap = BitmapFactory.decodeStream(inputStream);
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG,"download bitmap from url failed.");
        } finally {
            if (httpURLConnection != null){
               httpURLConnection.disconnect();
            }
            try {
                if (inputStream != null){
                    inputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return bitmap;
    }

    /**
     * 根据对应的url获取对应的key
     * @param url
     * @return
     */
    private String hashKeyFromUrl(String url){
        String cacheKey;

        //计算url的散列值。
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将byte数组转换为十六进制字符串
     * @param bytes
     * @return
     */
    private String bytesToHexString(byte[] bytes){
        StringBuilder builder = new StringBuilder();

        for (int i = 0 ; i < bytes.length ; i++){
            String hex = Integer.toHexString(0xFF & bytes[i]);

            if (bytes.length == 1){
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    /**
     * 获取DiskCache缓存目录
     * @param context
     * @param key
     * @return
     */
    private File getDiskCacheDir(Context context ,String key){
        //检查是否包含媒体存储
        boolean externalStroageAvailable = Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED);

        String cachePath;

        if (externalStroageAvailable){
            //使用外部存储
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        //返回路径加文件分隔符 + key
        return new File(cachePath + File.separator + key);
    }

    /**
     * 获取该dir下可用目录大小
     * @param dir
     * @return
     * 版本在低版本编译不出错,也就是该代码适用于android3.0以上的系统
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File dir){
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD){
            return dir.getUsableSpace();
        }
        final StatFs statFs = new StatFs(dir.getPath());
        return (long)statFs.getBlockSize() * (long) statFs.getAvailableBlocks();
    }

    /**
     * 往内存里面添加Bitmap缓存,判断是否添加过，已添加不再添加
     * @param key
     * @param bitmap
     */
    private void addBitmapToMemoryCache(String key , Bitmap bitmap){
        if (getBitmapFromMemCahce(key) == null){
            mMemoryCache.put(key,bitmap);
        }
    }

    /**
     * 从内存缓存获取对应key的bitmap
     * @param key
     * @return
     */
    private Bitmap getBitmapFromMemCahce(String key){
        return mMemoryCache.get(key);
    }
}
