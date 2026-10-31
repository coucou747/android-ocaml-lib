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
    private void photoTests(String owner) throws Exception {
        final byte[] jpeg={ (byte)0xff,(byte)0xd8,42,(byte)0xff,(byte)0xd9 };
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
        ok("camera.delete",uri);
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
            photoTests(owner);
            alarmScreenTests(owner);
            final Activity closing=activity;runOnMainSync(closing::finish);waitForIdleSync();
            check(new String(call("ui.getText",text)[1],StandardCharsets.UTF_8).equals("handle"),"Destroyed activity invalidates views");
            result.putString("stream","Platform binding tests passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
}
