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
    static int alarmReads,healthReads,backgroundRequests,settingsStage,qrReads;
    static byte[] settingsSubscription;
    static boolean settingsOpened;
    static boolean informationPage,resumeSent,informationUpdated;
    static final ConcurrentHashMap<String,byte[][]> preferences=new ConcurrentHashMap<>();
    static boolean backgroundGranted;
    static volatile boolean awaitingQr;
    static final CompletableFuture<byte[][]> qrCompletion=new CompletableFuture<>();
    static final ConcurrentHashMap<String,CompletableFuture<byte[][]>> locations=new ConcurrentHashMap<>();
    static volatile boolean awaitingLocation;
    static final String fix="{\"provider\":\"gps\",\"latitude\":1.25,\"longitude\":-2.5,\"accuracy_m\":5,\"altitude_m\":null,\"speed_mps\":null,\"bearing_deg\":null,\"at_ms\":\"2147483648000\",\"elapsed_realtime_ns\":\"9876543210123\",\"is_mock\":true}";
    static final String healthBase="\"id\":\"record-1\",\"origin\":\"test.source\",\"last_modified_ms\":\"2147483648000\",\"start_ms\":\"100\",\"end_ms\":\"200\",\"start_offset_s\":3600,\"end_offset_s\":null";
    static byte[][] ok(byte[]... values){byte[][] out=new byte[values.length+1][];out[0]=text("ok");System.arraycopy(values,0,out,1,values.length);return out;}
    static byte[][] health(byte[][] args){
        String kind=new String(args[0],StandardCharsets.UTF_8),token=new String(args[4],StandardCharsets.UTF_8);
        if(token.equals("broken"))return ok(text("{"));
        if(token.equals("next"))return ok(text("{\"records\":[],\"next_page_token\":null}"));
        String extra;
        switch(kind){
        case "steps":extra=",\"count\":\"9007199254740993\"";break;
        case "heart":extra=",\"samples\":[{\"at_ms\":\"120\",\"bpm\":\"60\"},{\"at_ms\":\"180\",\"bpm\":\"61\"}]";break;
        case "sleep":extra=",\"title\":\"\",\"notes\":null,\"stages\":[{\"start_ms\":\"100\",\"end_ms\":\"200\",\"stage\":9999}]";break;
        default:extra=",\"exercise_type\":56,\"title\":\"Course\",\"notes\":null";
        }
        return ok(text("{\"records\":[{"+healthBase+extra+"}],\"next_page_token\":\"next\"}"));
    }
    static byte[] binary=new byte[]{0,(byte)255,42,0};
    static byte[] text(String s){return s.getBytes(StandardCharsets.UTF_8);}
    public static byte[][] takeEvent() throws InterruptedException{return events.take();}
    public static byte[][] dispatch(byte[] op,byte[][] args){
        String name=new String(op,StandardCharsets.UTF_8);
        if(SettingsBackend.handles(name,args))return SettingsBackend.dispatch(name,args);
        if(LayoutBackend.handles(name,args))return LayoutBackend.dispatch(name,args);
        if(UiBackend.handles(name,args))return UiBackend.dispatch(name,args);
        if(name.startsWith("auth.") || name.startsWith("http.") || name.startsWith("keystore."))return AuthBackend.dispatch(name,args);
        switch(name){
        case "qr.scan":
            if(args.length!=1 || !Arrays.equals(args[0],text("activity")))throw new AssertionError("QR activity transport");
            switch(qrReads++){
            case 0:return ok(text("https://exemple.invalid/été?code=🐫\n\u0000"));
            case 1:return ok(text(""));
            case 2:return new byte[][]{text("error"),text("cancelled"),text("Scan cancelled")};
            case 3:return new byte[][]{text("error"),text("permission"),text("Provider camera denied")};
            case 4:return new byte[][]{text("error"),text("unavailable"),text("Scanner unavailable")};
            case 5:return new byte[][]{text("error"),text("platform"),text("Scanner failed")};
            case 6:return new byte[][]{text("error"),text("handle"),text("Owner destroyed")};
            case 8:
                awaitingQr=true;
                try{return qrCompletion.get();}catch(Exception error){throw new IllegalStateException(error);}finally{awaitingQr=false;}
            default:return ok();
            }
        case "preferences.get":{if(Arrays.equals(args[0],text("qr-test")))return ok(text("bool"),text(Boolean.toString(awaitingQr)));if(Arrays.equals(args[0],text("native-launch")))return ok(text("bool"),text(Boolean.toString(settingsOpened)));byte[][] v=preferences.get(new String(args[0],StandardCharsets.UTF_8)+"/"+new String(args[1],StandardCharsets.UTF_8));return v==null?ok():ok(v);}
        case "preferences.set":{
            if(Arrays.equals(args[0],text("qr-test"))){qrCompletion.complete(ok(text("completed after cancellation")));return ok();}
            if(Arrays.equals(args[1],text("fail")))return new byte[][]{text("error"),text("platform"),text("Commit failed")};
            preferences.put(new String(args[0],StandardCharsets.UTF_8)+"/"+new String(args[1],StandardCharsets.UTF_8),new byte[][]{args[2],args[3]});return ok();
        }
        case "settings.open":events.add(new byte[][]{text("handler"),text("settings-start"),text("android.settings.jni-native"),args[0]});return ok();
        case "settings.create":{
            settingsSubscription=args[0];String json=new String(args[2],StandardCharsets.UTF_8);informationPage=json.contains("Current permissions");
            if(informationPage && !json.contains("\"description\":\"Unknown\""))throw new AssertionError("Information must restart from its declaration on a new page");
            return ok(text("settings-page"));
        }
        case "settings.update":{
            String json=new String(args[1],StandardCharsets.UTF_8);
            if(informationPage){
                if(!json.contains("\"kind\":\"info\""))throw new AssertionError("Read-only row serialization");
                if(json.contains("permission state 2"))informationUpdated=true;
                if(informationUpdated && !json.contains("permission state 2"))throw new AssertionError("Refresh lost transient information");
                if(!resumeSent && json.contains("permission state 1")){resumeSent=true;events.add(new byte[][]{settingsSubscription,text("refresh"),text("")});}
            }
            if(settingsStage==0){settingsStage=1;events.add(new byte[][]{settingsSubscription,text("0"),text("true")});}return ok();
        }
        case "settings.result":
            if(settingsStage==1){settingsStage++;events.add(new byte[][]{settingsSubscription,text("1"),text("invalid")});}
            else if(settingsStage==2){if(!Arrays.equals(args[2],text("false")))throw new AssertionError("Invalid number accepted");settingsStage++;events.add(new byte[][]{settingsSubscription,text("1"),text("9223372036854775807")});}
            return ok();
        case "health.status":return ok(text(new String[]{"available","update_required","unavailable"}[healthReads++%3]));
        case "health.permission":{
            String kind=new String(args[0],StandardCharsets.UTF_8);
            String suffix=kind.equals("distance")?"DISTANCE":kind.equals("active_calories")?"ACTIVE_CALORIES_BURNED":kind.equals("total_calories")?"TOTAL_CALORIES_BURNED":"STEPS";
            return ok(text("android.permission.health.READ_"+suffix));
        }
        case "health.granted":return ok(text("steps"),text("heart"),text("sleep"),text("exercise"),text("distance"),text("active_calories"),text("total_calories"));
        case "health.backgroundAvailable":return ok(text(Boolean.toString(backgroundRequests<3)));
        case "health.backgroundGranted":return backgroundRequests>=3?new byte[][]{text("error"),text("unavailable"),text("Unsupported")}:ok(text(Boolean.toString(backgroundGranted)));
        case "health.backgroundRequest":
            if(backgroundRequests++==0){backgroundGranted=true;return ok(text("true"));}
            backgroundGranted=false;
            return new byte[][]{text("error"),text(backgroundRequests==2?"permission":"unavailable"),text("Background consent not granted")};
        case "health.aggregateAmount":{
            String start=new String(args[1],StandardCharsets.UTF_8),kind=new String(args[0],StandardCharsets.UTF_8);
            if(!new String(args[2],StandardCharsets.UTF_8).equals(Long.toString(Long.parseLong(start)+1)))throw new AssertionError("Aggregate interval transport");
            if(start.equals("3"))return ok();if(start.equals("5"))return ok(text("0.0"));
            if(start.equals("7"))return ok(text("NaN"));if(start.equals("9"))return ok(text("-1"));
            if(start.equals("11"))return new byte[][]{text("error"),text("permission"),text("Health read denied")};
            return ok(text(kind.equals("distance")?"1234.5":kind.equals("active_calories")?"12.25":"234.75"));
        }
        case "location.distanceBetween":
            if(!Arrays.equals(args[0],text("0.")) || !Arrays.equals(args[1],text("0.")) || !Arrays.equals(args[2],text("0.")) || !Arrays.equals(args[3],text("1.")))throw new AssertionError("Coordinate transport");
            return ok(text("111319.4921875"));
        case "permissions.special":
            if(!Arrays.equals(args[0],text("background_location")))throw new AssertionError("Background GPS consent mapping");return ok(text("false"));
        case "permissions.settings":
            if(!Arrays.equals(args[1],text("background_location")))throw new AssertionError("Background GPS settings mapping");return ok();
        case "health.request":return ok(Arrays.copyOfRange(args,1,args.length));
        case "health.settings":return ok();
        case "health.read":return health(args);
        case "health.aggregateSteps":return new String(args[0],StandardCharsets.UTF_8).equals("3")?ok():ok(text("42"));
        case "location.enabled":return ok(text(Boolean.toString(new String(args[0],StandardCharsets.UTF_8).equals("gps") || awaitingLocation)));
        case "location.last":return new String(args[0],StandardCharsets.UTF_8).equals("gps")?ok(text(fix)):ok();
        case "location.currentStart":{
            String token=UUID.randomUUID().toString();CompletableFuture<byte[][]> result=new CompletableFuture<>();
            if(!new String(args[1],StandardCharsets.UTF_8).equals("999"))result.complete(new String(args[0],StandardCharsets.UTF_8).equals("gps")?ok(text(fix)):ok());
            locations.put(token,result);return ok(text(token));
        }
        case "location.currentAwait":{
            CompletableFuture<byte[][]> result=locations.get(new String(args[0],StandardCharsets.UTF_8));
            awaitingLocation=true;try{return result.get();}catch(Exception e){throw new IllegalStateException(e);}
        }
        case "location.currentClose":{awaitingLocation=false;CompletableFuture<byte[][]> result=locations.remove(new String(args[0],StandardCharsets.UTF_8));if(result!=null)result.complete(ok());return ok();}
        case "location.watch":events.add(new byte[][]{args[0],text("{\"type\":\"fix\",\"fix\":"+fix+"}")});return ok();
        case "unsubscribe":return ok();
        case "location.serviceStart":events.add(new byte[][]{text("handler"),text("gps-token"),args[8],text("gps-event")});return ok();
        case "location.event":return ok(text("{\"type\":\"fix\",\"fix\":"+fix+"}"));
        case "location.serviceStop":return ok();
        case "ui.show":if(!Arrays.equals(args[1],text("settings-page")))throw new AssertionError("Registered preference page");return ok();
        case "runtime.complete":
            if(Arrays.equals(args[0],text("settings-start"))){if(!Arrays.equals(args[1],text("success")))throw new AssertionError("Settings cold handler failed");settingsOpened=true;}return ok();
        case "activity.current":return new byte[][]{text("error"),text("handle"),text("No current activity")};
        case "activity.launchMain":
            if(args.length!=0)failure="Main launch must not require an activity handle";
            return new byte[][]{text("ok")};
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
        case "storage.fileUri":return ok(text("file:///private/photo%20%C3%A9t%C3%A9.jpg"));
        case "storage.read":return new byte[][]{text("ok"),binary};
        case "storage.write":
            if(awaitingQr)failure="QR wait did not finish";
            if(!locations.isEmpty())failure="Location request handle leaked";
            if(!Arrays.equals(args[1],binary))failure="Binary JNI write mismatch";
            AuthBackend.checkClean();UiBackend.checkClean();SettingsBackend.checkClean();done.countDown();return new byte[][]{text("ok")};
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
