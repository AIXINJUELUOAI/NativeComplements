package dev.local.sunwindowdemo.features.iconlayers;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;

/** Content-addressed user images; a failed selection leaves the last published choice intact. */
public final class IconOverrides {
    private IconOverrides() {}
    static File root(Context c) { return new File(c.createDeviceProtectedStorageContext().getFilesDir(),"icon-overrides"); }
    static File image(Context c,String id) throws FileNotFoundException {
        if(IconPolicy.asset(id).isEmpty())throw new FileNotFoundException("image id");
        return new File(root(c),id+".png");
    }
    // Callers hold IconPolicy.class from import/store through policy publication.
    public static String importImage(Context c, Uri uri) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(InputStream in=c.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IOException("读取图片失败");IconArchive.copy(in,bytes,8*1024*1024,new AtomicBoolean());
        }
        byte[] raw=bytes.toByteArray();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(raw,0,raw.length,bounds);
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw new IOException("图片尺寸应为 1–2048 像素");
        Bitmap decoded=BitmapFactory.decodeByteArray(raw,0,raw.length);
        if(decoded==null)throw new IOException("图片解码失败");
        try{return store(c,decoded);}finally{decoded.recycle();}
    }
    /** Only after a durable policy commit, under the same lock as imports/generation. */
    static void prune(Context c,java.util.Collection<IconPolicy> policies){
        java.util.Set<String> referenced=new java.util.HashSet<>();
        for(IconPolicy p:policies)java.util.Collections.addAll(referenced,p.background,p.foreground,p.monoImage,
            p.notificationImage,p.generatedBackground,p.generatedForeground);
        File[] files=root(c).listFiles();
        if(files!=null)for(File file:files){
            String name=file.getName();
            if(name.matches("[a-f0-9]{64}\\.png")&&!referenced.contains(name.substring(0,64)))file.delete();
        }
    }
    static String store(Context c,Bitmap bitmap)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IOException("图片转换失败");
        byte[] raw=bytes.toByteArray();if(raw.length>8*1024*1024)throw new IOException("图片超过 8 MiB");
        String id=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        if(!root(c).isDirectory()&&!root(c).mkdirs())throw new IOException("创建图片目录失败");
        File destination=image(c,id);
        if(!destination.exists()){
            File pending=File.createTempFile("image-",".png",root(c));
            try{Files.write(pending.toPath(),raw);if(!pending.renameTo(destination)&&!destination.exists())throw new IOException("保存图片失败");}
            finally{pending.delete();}
        }
        return id;
    }
}
