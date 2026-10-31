package org.ocaml.android;

import android.content.Context;
import android.graphics.*;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.text.InputType;
import android.widget.*;
import java.io.*;

/** Platform decoding and input configuration, without an image-loading dependency. */
final class UiSupport {
    private UiSupport() {}
    static Bitmap image(Context context,String value,int width,int height) throws IOException {
        if(width<=0 || height<=0)throw new IllegalArgumentException("Positive image bounds required");
        Uri uri=Uri.parse(value);
        String scheme=uri.getScheme();
        if(!"content".equals(scheme) && !"file".equals(scheme) && !"android.resource".equals(scheme))
            throw new IllegalArgumentException("Expected content, file or android.resource URI");
        if(Build.VERSION.SDK_INT>=28){
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(),uri),(decoder,info,source)->{
                int[] size=fit(info.getSize().getWidth(),info.getSize().getHeight(),width,height);
                decoder.setTargetSize(size[0],size[1]);
            });
        }
        return legacyImage(context,uri,width,height);
    }
    private static int[] fit(int width,int height,int maxWidth,int maxHeight){
        double scale=Math.min(1.,Math.min((double)maxWidth/width,(double)maxHeight/height));
        return new int[]{Math.max(1,(int)(width*scale)),Math.max(1,(int)(height*scale))};
    }
    private static InputStream open(Context c,Uri uri) throws IOException {
        InputStream in=c.getContentResolver().openInputStream(uri);
        if(in==null)throw new FileNotFoundException(uri.toString());return in;
    }
    private static Bitmap legacyImage(Context c,Uri uri,int width,int height) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=open(c,uri)){BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0 || bounds.outHeight<=0)throw new IOException("Unsupported or corrupt image");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        // ExifInterface supports fewer formats than BitmapFactory on API 26/27.
        // Non-EXIF images retain their normal pixel orientation.
        try(InputStream in=open(c,uri)){orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}
        catch(IOException unsupportedExif){/* Decode formats without EXIF using BitmapFactory. */}
        boolean swap=orientation>=ExifInterface.ORIENTATION_TRANSPOSE && orientation<=ExifInterface.ORIENTATION_ROTATE_270;
        int sourceWidth=swap?bounds.outHeight:bounds.outWidth,sourceHeight=swap?bounds.outWidth:bounds.outHeight;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inSampleSize=(int)Math.max(1L,Math.max(((long)sourceWidth+width-1)/width,((long)sourceHeight+height-1)/height));
        Bitmap bitmap;
        try(InputStream in=open(c,uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}
        if(bitmap==null)throw new IOException("Cannot decode image");
        Matrix matrix=new Matrix();
        switch(orientation){
        case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
        case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
        case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
        case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
        case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
        case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(-90);matrix.postScale(-1,1);break;
        case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(-90);break;
        }
        Bitmap oriented=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
        if(oriented!=bitmap)bitmap.recycle();
        int[] size=fit(oriented.getWidth(),oriented.getHeight(),width,height);
        Bitmap scaled=Bitmap.createScaledBitmap(oriented,size[0],size[1],true);
        if(scaled!=oriented)oriented.recycle();return scaled;
    }
    static void inputType(EditText view,String kind,boolean signed,boolean decimal){
        int type;
        boolean multiline=kind.equals("text") && (view.getInputType()&InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0;
        if(kind.equals("text"))type=InputType.TYPE_CLASS_TEXT;
        else if(kind.equals("number"))type=InputType.TYPE_CLASS_NUMBER
            |(signed?InputType.TYPE_NUMBER_FLAG_SIGNED:0)|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0);
        else throw new IllegalArgumentException("Unknown input type");
        view.setInputType(type);view.setSingleLine(!multiline);
    }
    static void multiline(EditText view,boolean enabled){
        if(enabled && (view.getInputType()&InputType.TYPE_MASK_CLASS)!=InputType.TYPE_CLASS_TEXT)
            throw new IllegalArgumentException("Multiline requires text input");
        view.setSingleLine(!enabled);
    }
}
