package org.ocaml.android;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.*;
/** Test transport only: exercises real C/JNI and OCaml runtime on a host JVM. */
public final class Bridge {
    static native void nativeStart();
    static final BlockingQueue<byte[][]> events=new LinkedBlockingQueue<>();
    static final CountDownLatch done=new CountDownLatch(1);
    static volatile String failure;
    static int alarmReads;
    static byte[] binary=new byte[]{0,(byte)255,42,0};
    static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static byte[][] takeEvent() throws InterruptedException{return events.take();}
    public static byte[][] dispatch(byte[] op,byte[][] args){
        String name=new String(op,StandardCharsets.UTF_8);
        switch(name){
        case "activity.displaySize":return new byte[][]{text("ok"),text("1080"),text("1920")};
        case "ui.image":case "ui.scroll":return new byte[][]{text("ok"),text("new-view")};
        case "ui.imageUri":case "ui.inputType":case "ui.multiline":case "ui.hint":case "ui.error":case "ui.add":case "ui.release":return new byte[][]{text("ok")};
        case "resources.resolve":return new byte[][]{text("ok"),text("17301536")};
        case "locale.timeZone":return new byte[][]{text("ok"),text("Europe/Paris")};
        case "locale.languageTag":return new byte[][]{text("ok"),text("fr-FR")};
        case "locale.is24Hour":return new byte[][]{text("ok"),text("true")};
        case "locale.date":case "locale.time":case "locale.dateTime":
            if(!new String(args[0],StandardCharsets.UTF_8).equals("2147483648000"))failure="64-bit date transport";
            return new byte[][]{text("ok"),text("date locale")};
        case "audio.defaultAlarm":return ++alarmReads==1?new byte[][]{text("ok"),text("content://media/alarm")}:new byte[][]{text("ok")};
        case "camera.capture":return new String(args[1],StandardCharsets.UTF_8).equals("cancel.jpg")
            ?new byte[][]{text("ok")}:new byte[][]{text("ok"),text("content://test/photo.jpg")};
        case "camera.open":return new byte[][]{text("ok"),args[0]};
        case "camera.read":return new byte[][]{text("ok"),binary};
        case "camera.delete":case "alarm.screen.show":case "alarm.screen.dismiss":case "alarm.screen.notify":return new byte[][]{text("ok")};
        case "storage.read":return new byte[][]{text("ok"),binary};
        case "storage.write":
            if(!Arrays.equals(args[1],binary))failure="Binary JNI write mismatch";
            done.countDown();return new byte[][]{text("ok")};
        case "activity.lifecycle":
            events.add(new byte[][]{args[0],text("activity"),text("resumed")});
            return new byte[][]{text("ok")};
        case "ui.text":
            if(!new String(args[1],StandardCharsets.UTF_8).equals("Été 🐫"))failure="Unicode JNI mismatch";
            return new byte[][]{text("ok"),args[1]};
        case "ui.getText":return new byte[][]{text("ok"),args[0]};
        default:failure="Unexpected command: "+name;done.countDown();return new byte[][]{text("error"),text("platform"),text(failure)};
        }
    }
    public static void main(String[] args) throws Exception{
        System.load(args[0]);nativeStart();nativeStart();
        boolean completed=done.await(Long.parseLong(args[1]),TimeUnit.SECONDS);
        if(!completed || failure!=null){System.err.println(failure==null?"JNI test timed out":failure);System.exit(1);}
        System.out.println("JNI binary, Unicode, callback and repeated-start checks passed");System.exit(0);
    }
}
