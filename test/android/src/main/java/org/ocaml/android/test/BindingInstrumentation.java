package org.ocaml.android.test;

import android.app.*;
import android.content.*;
import android.os.Bundle;
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
            final Activity closing=activity;runOnMainSync(closing::finish);waitForIdleSync();
            check(new String(call("ui.getText",text)[1],StandardCharsets.UTF_8).equals("handle"),"Destroyed activity invalidates views");
            result.putString("stream","Platform binding tests passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
}
