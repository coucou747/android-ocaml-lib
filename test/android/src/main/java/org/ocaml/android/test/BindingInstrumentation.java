package org.ocaml.android.test;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.provider.MediaStore;
import android.net.Uri;
import java.io.OutputStream;
import org.ocaml.android.Bridge;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** No test server or external test framework is needed for platform round trips. */
public final class BindingInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    static byte[] b(String s){return s.getBytes(StandardCharsets.UTF_8);}
    static byte[][] call(String op,Object... args){
        byte[][] a=new byte[args.length][];
        for(int i=0;i<a.length;i++)a[i]=args[i] instanceof byte[]?(byte[])args[i]:b(String.valueOf(args[i]));
        return Bridge.dispatch(b(op),a);
    }
    static byte[][] ok(String op,Object... args){byte[][] r=call(op,args);check(new String(r[0],StandardCharsets.UTF_8).equals("ok"),op+" failed: "+Arrays.deepToString(r));return r;}
    static String value(String op,Object... args){return new String(ok(op,args)[1],StandardCharsets.UTF_8);}
    static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static <T> T nativeView(String handle,Class<T> type) throws Exception {
        // Inspect real widget state without adding inspection-only public APIs.
        java.lang.reflect.Field registry=Bridge.class.getDeclaredField("objects");registry.setAccessible(true);
        return type.cast(((java.util.Map<?,?>)registry.get(null)).get(handle));
    }
    private void onUi(java.util.concurrent.Callable<Void> check) throws Exception {
        java.util.concurrent.FutureTask<Void> task=new java.util.concurrent.FutureTask<>(check);runOnMainSync(task);task.get();
    }
    private void utilityTests(String owner) throws Exception {
        String scroll=value("ui.scroll",owner),layout=value("ui.layout",owner,"vertical"),edit=value("ui.edit",owner,"");
        ok("ui.add",scroll,layout);ok("ui.add",layout,edit);
        String other=value("ui.text",owner,"extra");
        check(new String(call("ui.add",scroll,other)[1],StandardCharsets.UTF_8).equals("argument"),"ScrollView single-child contract");
        ok("ui.inputType",edit,"number","true","true");
        android.widget.EditText field=nativeView(edit,android.widget.EditText.class);
        onUi(()->{int type=field.getInputType();check((type&android.text.InputType.TYPE_MASK_CLASS)==android.text.InputType.TYPE_CLASS_NUMBER,"Numeric input");
            check((type&android.text.InputType.TYPE_NUMBER_FLAG_SIGNED)!=0 && (type&android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL)!=0,"Signed decimal flags");return null;});
        check(new String(call("ui.multiline",edit,"true")[1],StandardCharsets.UTF_8).equals("argument"),"Numeric multiline rejected");
        ok("ui.inputType",edit,"text","false","false");ok("ui.multiline",edit,"true");
        ok("ui.hint",edit,"Indication");ok("ui.error",edit,"true","Erreur");
        onUi(()->{check((field.getInputType()&android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)!=0,"Multiline text");
            check("Indication".contentEquals(field.getHint()),"Input hint");check("Erreur".contentEquals(field.getError()),"Input error");return null;});
        ok("ui.error",edit,"false","");onUi(()->{check(field.getError()==null,"Error cleared");return null;});
        check(Integer.parseInt(value("resources.resolve","android","drawable","ic_lock_idle_alarm"))==android.R.drawable.ic_lock_idle_alarm,"System icon resolution");
        check(Integer.parseInt(value("resources.resolve","app","drawable","integration_alarm_icon"))>0,"Application icon resolution");
        check(new String(call("resources.resolve","app","drawable","does_not_exist_in_integration")[1],StandardCharsets.UTF_8).equals("unavailable"),"Missing resource does not become ID zero");
        byte[][] sound=ok("audio.defaultAlarm");check(sound.length==1 || (sound.length==2 && Uri.parse(new String(sound[1],StandardCharsets.UTF_8)).getScheme()!=null),"Nullable system alarm URI");
        java.util.TimeZone previous=java.util.TimeZone.getDefault();
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
            check(value("locale.timeZone").equals("UTC"),"UTC read from system");String utc=value("locale.time","0");
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Tokyo"));
            check(value("locale.timeZone").equals("Asia/Tokyo"),"Time zone refreshed");
            check(!value("locale.time","0").equals(utc),"Formatting follows new time zone");
            check(!value("locale.date","2147483648000").isEmpty() && !value("locale.dateTime","-1").isEmpty(),"64-bit and pre-epoch dates");
        }finally{java.util.TimeZone.setDefault(previous);}
        check(!value("locale.languageTag").isEmpty(),"Application language tag");
        String clock=value("locale.is24Hour");check(clock.equals("true") || clock.equals("false"),"12/24 hour preference");
        ok("ui.release",edit);ok("ui.release",layout);ok("ui.release",scroll);ok("ui.release",other);
    }
    private void photoTests(String owner) throws Exception {
        android.graphics.Bitmap fixture=android.graphics.Bitmap.createBitmap(40,20,android.graphics.Bitmap.Config.ARGB_8888);
        fixture.eraseColor(android.graphics.Color.RED);
        java.io.ByteArrayOutputStream encoded=new java.io.ByteArrayOutputStream();
        check(fixture.compress(android.graphics.Bitmap.CompressFormat.JPEG,100,encoded),"JPEG fixture encoding");fixture.recycle();
        final byte[] jpeg=encoded.toByteArray();
        ActivityMonitor camera=new ActivityMonitor(){
            @Override public ActivityResult onStartActivity(Intent intent){
                if(!MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction()))return null;
                try(OutputStream out=getTargetContext().getContentResolver().openOutputStream((Uri)intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT))){out.write(jpeg);}
                catch(Exception e){throw new AssertionError(e);}
                return new ActivityResult(Activity.RESULT_OK,null);
            }
        };
        addMonitor(camera);
        String name=java.util.UUID.randomUUID()+".jpg";
        String uri;
        try{uri=value("camera.capture",owner,name);}finally{removeMonitor(camera);}
        check(value("camera.open",uri).equals(uri),"Photo restored from saved URI");
        check(Arrays.equals(ok("camera.read",uri)[1],jpeg),"Full-size photo output and provider URI");
        try(android.os.ParcelFileDescriptor file=getTargetContext().getContentResolver().openFileDescriptor(Uri.parse(uri),"rw")){
            android.media.ExifInterface exif=new android.media.ExifInterface(file.getFileDescriptor());
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION,Integer.toString(android.media.ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();
        }
        String image=value("ui.image",owner,uri,13,13);
        android.widget.ImageView preview=nativeView(image,android.widget.ImageView.class);
        onUi(()->{android.graphics.Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap();
            check(bitmap.getWidth()<=13 && bitmap.getHeight()<=13 && bitmap.getHeight()>bitmap.getWidth(),"Preview pixel bounds and EXIF orientation");return null;});
        ok("ui.imageUri",image,uri,7,7);
        onUi(()->{android.graphics.Bitmap bitmap=((android.graphics.drawable.BitmapDrawable)preview.getDrawable()).getBitmap();check(bitmap.getWidth()<=7 && bitmap.getHeight()<=7,"Preview updated");return null;});
        check(new String(call("ui.imageUri",image,"https://unsupported.invalid/photo.jpg",7,7)[1],StandardCharsets.UTF_8).equals("argument"),"Remote image URI rejected");
        onUi(()->{check(preview.getDrawable()!=null,"Failed image replacement preserves preview");return null;});
        ok("ui.release",image);ok("camera.delete",uri);
        ActivityMonitor cancelled=new ActivityMonitor(){
            @Override public ActivityResult onStartActivity(Intent intent){
                return MediaStore.ACTION_IMAGE_CAPTURE.equals(intent.getAction())?new ActivityResult(Activity.RESULT_CANCELED,null):null;
            }
        };
        addMonitor(cancelled);
        try{check(ok("camera.capture",owner,java.util.UUID.randomUUID()+".jpg").length==1,"User camera cancellation");}
        finally{removeMonitor(cancelled);}
        check(new String(call("camera.capture",owner,"../escape.jpg")[1],StandardCharsets.UTF_8).equals("argument"),"Photo path traversal rejected");
        check(new String(call("camera.read","content://other/secret")[1],StandardCharsets.UTF_8).equals("argument"),"Foreign provider rejected");
    }
    private void alarmScreenTests(String owner) {
        ActivityMonitor monitor=addMonitor("org.ocaml.android.AlarmScreenActivity",null,false);
        try {
            ok("alarm.screen.show",owner,"instrumentation",41,"Alarm","Test","Stop","Snooze","integration.stop","integration.snooze","payload");
            Activity screen=monitor.waitForActivity();waitForIdleSync();
            check(screen instanceof org.ocaml.android.AlarmScreenActivity,"Alarm activity displayed");
            ok("alarm.screen.dismiss","instrumentation",41);waitForIdleSync();
            check(screen.isFinishing() || screen.isDestroyed(),"Alarm screen dismissed");
        } finally {removeMonitor(monitor);}
    }
    @Override public void onStart(){
        Bundle result=new Bundle();
        Activity activity=null;
        try{
            activity=startActivitySync(new Intent(getTargetContext(),org.ocaml.android.BridgeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            String owner=value("activity.current");
            String text=value("ui.text",owner,"Été 🐫");
            check(value("ui.getText",text).equals("Été 🐫"),"Unicode text");
            byte[] binary=new byte[]{0,(byte)255,42,0};
            ok("storage.write","roundtrip.bin",binary);
            check(Arrays.equals(ok("storage.read","roundtrip.bin")[1],binary),"Binary file");
            check(new String(call("storage.read","../escape")[1],StandardCharsets.UTF_8).equals("argument"),"Path traversal rejected");
            ok("preferences.set","integration","answer","int","9223372036854775807");
            check(new String(ok("preferences.get","integration","answer")[2],StandardCharsets.UTF_8).equals("9223372036854775807"),"64 bit preference");
            String db=value("sqlite.open","integration.db");
            ok("sqlite.execute",db,"DROP TABLE IF EXISTS sample");
            ok("sqlite.execute",db,"CREATE TABLE sample (id INTEGER PRIMARY KEY, body BLOB)");
            ok("sqlite.execute",db,"INSERT INTO sample VALUES (?, ?)","int","1","blob",binary);
            byte[][] rows=ok("sqlite.query",db,"SELECT body FROM sample WHERE id = ?","int","1");
            check(Arrays.equals(rows[3],binary),"SQLite BLOB");
            byte[][] failed=call("sqlite.transaction",db,"2","INSERT INTO sample VALUES (?, ?)","int","2","blob",binary,"2","INSERT INTO sample VALUES (?, ?)","int","1","blob",binary);
            check(new String(failed[0],StandardCharsets.UTF_8).equals("error"),"Transaction should fail");
            check(new String(ok("sqlite.query",db,"SELECT count(*) FROM sample")[3],StandardCharsets.UTF_8).equals("1"),"Transaction rollback");
            ok("sqlite.close",db);
            ok("keystore.delete","integration");ok("keystore.create","integration");
            String encrypted=value("keystore.encrypt","integration",binary);
            check(Arrays.equals(ok("keystore.decrypt","integration",encrypted)[1],binary),"Keystore roundtrip");
            ok("keystore.delete","integration");ok("storage.remove","roundtrip.bin");
            ok("preferences.remove","integration","answer");
            utilityTests(owner);
            photoTests(owner);
            alarmScreenTests(owner);
            final Activity closing=activity;runOnMainSync(closing::finish);waitForIdleSync();
            check(new String(call("ui.getText",text)[1],StandardCharsets.UTF_8).equals("handle"),"Destroyed activity invalidates views");
            result.putString("stream","Platform binding tests passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
}
