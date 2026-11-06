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
        String previewName="preview-"+java.util.UUID.randomUUID()+".jpg";
        ok("storage.write",previewName,ok("camera.read",uri)[1]);
        String fileUri=value("storage.fileUri",previewName);
        ok("ui.imageUri",image,fileUri,7,7);ok("storage.remove",previewName);
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
    private void componentTests(String owner) throws Exception {
        String form=value("form.container",owner),label=value("ui.text",owner,"Form title");
        byte[][] field=ok("form.field",owner,"Email","true","Helper","","email","false","false","false");
        String root=new String(field[1],StandardCharsets.UTF_8),input=new String(field[2],StandardCharsets.UTF_8);
        com.google.android.material.textfield.TextInputLayout material=nativeView(root,com.google.android.material.textfield.TextInputLayout.class);
        android.widget.EditText editor=nativeView(input,android.widget.EditText.class);
        String primary=value("ui.primaryButton",owner,"Save"),secondary=value("ui.secondaryButton",owner,"Cancel");
        check(nativeView(primary,android.view.View.class) instanceof com.google.android.material.button.MaterialButton,"Material primary button");
        check(nativeView(secondary,android.view.View.class) instanceof com.google.android.material.button.MaterialButton,"Material secondary button");
        ok("ui.add",form,label);ok("ui.margins",root,"1","12","3","4");ok("ui.add",form,root);ok("ui.add",form,primary);ok("ui.add",form,secondary);
        onUi(()->{
            check("Email".contentEquals(material.getHint()) && "Helper".contentEquals(material.getHelperText()),"Label and helper below field");
            check((editor.getInputType()&android.text.InputType.TYPE_MASK_VARIATION)==android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,"Email keyboard");
            float density=material.getResources().getDisplayMetrics().density;
            android.view.ViewGroup.MarginLayoutParams margins=(android.view.ViewGroup.MarginLayoutParams)material.getLayoutParams();
            check(margins.topMargin==Math.round(12*density) && margins.leftMargin==Math.round(density),"Explicit margins survive addition to form");
            android.view.ViewGroup.MarginLayoutParams automatic=(android.view.ViewGroup.MarginLayoutParams)nativeView(primary,android.view.View.class).getLayoutParams();
            int gap=getTargetContext().getResources().getDimensionPixelSize(getTargetContext().getResources().getIdentifier("ocaml_form_gap","dimen",getTargetContext().getPackageName()));
            check(automatic.topMargin==gap,"Shared form row spacing");return null;
        });
        ok("form.error",root,"true","Invalid email");
        onUi(()->{check("Invalid email".contentEquals(material.getError()) && editor.getError()==null,"Error uses supporting text, not EditText popup");return null;});
        ok("form.error",root,"false","");ok("form.help",root,"false","");
        onUi(()->{check(material.getError()==null && material.getHelperText()==null,"Helper/error clearing");return null;});
        check(new String(call("ui.spacer",owner,"-1","0")[1],StandardCharsets.UTF_8).equals("argument"),"Native dimension validation");
        String dialog=value("dialog.show","ui-dialog-test",owner,"Custom form",form,"true","positive","Save","negative","Cancel");
        Object nativeDialog=nativeView(dialog,Object.class);
        java.lang.reflect.Field dialogField=nativeDialog.getClass().getDeclaredField("dialog");dialogField.setAccessible(true);
        androidx.appcompat.app.AlertDialog shown=(androidx.appcompat.app.AlertDialog)dialogField.get(nativeDialog);
        onUi(()->{shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick();
            check(shown.isShowing() && !shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled(),"Button waits for OCaml validation");return null;});
        ok("dialog.resolve",dialog,"positive","false");
        onUi(()->{check(shown.isShowing() && shown.getButton(android.content.DialogInterface.BUTTON_POSITIVE).isEnabled(),"Keep_open reenables dialog");return null;});
        ok("dialog.resolve",dialog,"positive","true");
        onUi(()->{check(!shown.isShowing() && material.getParent()!=null && nativeView(form,android.view.View.class).getParent()==null,"Dismiss detaches reusable OCaml content");return null;});
        ok("dialog.close",dialog);ok("dialog.close",dialog);ok("unsubscribe","ui-dialog-test");
        ok("events.text","ui-field-test",input);ok("ui.release",form);
        check(new String(call("ui.getText",input)[1],StandardCharsets.UTF_8).equals("handle"),"Releasing form invalidates descendant input handles");
        java.lang.reflect.Field listeners=Bridge.class.getDeclaredField("subscriptionViews");listeners.setAccessible(true);
        check(!((java.util.Map<?,?>)listeners.get(null)).containsKey("ui-field-test"),"Releasing form removes descendant subscriptions");
        String content=value("form.container",owner);
        String dismissed=value("dialog.show","ui-dismiss-test",owner,"Dismiss",content,"true","positive","OK");
        Object n=nativeView(dismissed,Object.class);androidx.appcompat.app.AlertDialog d=(androidx.appcompat.app.AlertDialog)dialogField.get(n);
        onUi(()->{d.cancel();check(!d.isShowing() && nativeView(content,android.view.View.class).getParent()==null,"System cancellation detaches content");return null;});
        ok("dialog.close",dismissed);ok("unsubscribe","ui-dismiss-test");ok("ui.release",content);
    }
    private void authenticationTests() throws Exception {
        org.ocaml.android.AuthenticationChecks.run(getTargetContext());
        String name="auth-"+java.util.UUID.randomUUID(),alias=name+"-key";
        try{
            ok("auth.keyEnsure",alias);ok("auth.keyEnsure",alias);
            check(ok("auth.secretRead",name).length==1,"Missing token is absent");
            String ciphertext=value("keystore.encrypt",alias,"fixture-session-token");
            check(!ciphertext.contains("fixture-session-token"),"Token encrypted with Keystore");
            ok("auth.secretWrite",name,ciphertext);
            check(value("auth.secretRead",name).equals(ciphertext),"Atomic ciphertext roundtrip");
            check(new java.io.File(getTargetContext().getNoBackupFilesDir(),name).isFile(),"Session excluded from backup directory");
            check(value("keystore.decrypt",alias,ciphertext).equals("fixture-session-token"),"Keystore decrypt roundtrip");
            ok("auth.secretDelete",name);ok("auth.secretDelete",name);
            check(ok("auth.secretRead",name).length==1,"Logout removes session");
            ok("auth.secretWrite",name,"corrupt-ciphertext");
            check(new String(call("keystore.decrypt",alias,"corrupt-ciphertext")[0],StandardCharsets.UTF_8).equals("error"),"Corrupt ciphertext is an error");
        }finally{ok("auth.secretDelete",name);ok("keystore.delete",alias);}
    }
    private void healthLocationTests(String owner) throws Exception {
        org.ocaml.android.HealthLocationChecks.run();
        String health=value("health.status");
        check(health.equals("available") || health.equals("unavailable") || health.equals("update_required"),"Health SDK availability");
        check(value("health.permission","steps").equals("android.permission.health.READ_STEPS"),"Read-only steps permission");
        check(value("health.permission","distance").equals("android.permission.health.READ_DISTANCE"),"Read distance permission");
        check(value("health.permission","active_calories").equals("android.permission.health.READ_ACTIVE_CALORIES_BURNED"),"Read active energy permission");
        check(value("health.permission","total_calories").equals("android.permission.health.READ_TOTAL_CALORIES_BURNED"),"Read total energy permission");
        boolean background=Boolean.parseBoolean(value("health.backgroundAvailable"));
        byte[][] backgroundGranted=call("health.backgroundGranted");
        if(!background){
            check(new String(backgroundGranted[1],StandardCharsets.UTF_8).equals("unavailable"),"Background feature absence explicit");
            check(new String(call("health.backgroundRequest",owner)[1],StandardCharsets.UTF_8).equals("unavailable"),"Unsupported background consent has no UI");
        }else check(new String(backgroundGranted[0],StandardCharsets.UTF_8).equals("ok"),"Background grant queried independently");
        check(Math.abs(Double.parseDouble(value("location.distanceBetween","0","0","0","1"))-111319.49)<1,"Distance bridge needs no GPS permission");
        check(ok("permissions.special","background_location").length==2,"Background GPS grant checked explicitly");
        check(ok("health.request",owner).length==1,"Empty permission request has no UI");
        byte[][] granted=call("health.granted");
        if(!health.equals("available"))check(new String(granted[1],StandardCharsets.UTF_8).equals("unavailable"),"Unavailable Health SDK gives structured error");
        else check(new String(granted[0],StandardCharsets.UTF_8).equals("ok"),"Health granted permissions queried");
        byte[][] gps=call("location.last","gps");String result=new String(gps[0],StandardCharsets.UTF_8);
        if(result.equals("error")){String kind=new String(gps[1],StandardCharsets.UTF_8);check(kind.equals("permission") || kind.equals("unavailable"),"GPS refusal or absence explicit");}
        else check(gps.length==1 || gps.length==2,"GPS cache nullable");
        byte[][] current=call("location.currentStart","gps","1");
        if(new String(current[0],StandardCharsets.UTF_8).equals("ok")){
            String token=new String(current[1],StandardCharsets.UTF_8);try{ok("location.currentAwait",token);}finally{ok("location.currentClose",token);}
        }else check(new String(current[1],StandardCharsets.UTF_8).equals("permission") || new String(current[1],StandardCharsets.UTF_8).equals("unavailable"),"Current GPS guarded");
        check(new String(call("location.serviceStart","gps","-1","0","1","none","GPS","Test","1","test")[1],StandardCharsets.UTF_8).equals("argument"),"Service rejects invalid sampling before launch");
        ok("location.serviceStop");
        Activity rationale=startActivitySync(new Intent(getTargetContext(),org.ocaml.android.HealthRationaleActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        check("Démonstration API".contentEquals(rationale.getTitle()),"Configured Health rationale title");
        runOnMainSync(rationale::finish);waitForIdleSync();
    }
    private Activity launchMainAfterAlarm(Activity main,String owner) {
        ActivityMonitor alarm=addMonitor("org.ocaml.android.AlarmScreenActivity",null,false);
        ActivityMonitor launcher=null;
        try {
            ok("alarm.screen.show",owner,"cold-launch",42,"Cold alarm","Test","Stop","Snooze","integration.stop","integration.snooze","payload");
            Activity alarmActivity=alarm.waitForActivity();waitForIdleSync();
            runOnMainSync(main::finish);waitForIdleSync();
            check(main.isDestroyed(),"Previous main activity destroyed");
            check(new String(call("activity.current")[1],StandardCharsets.UTF_8).equals("handle"),"Alarm screen without a current BridgeActivity");
            Intent entry=getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            check(entry!=null,"Application entry configured in manifest");
            launcher=addMonitor(entry.getComponent().getClassName(),null,false);
            ok("activity.launchMain");
            Activity resumed=launcher.waitForActivity();waitForIdleSync();
            check(resumed!=main && !resumed.isFinishing(),"Main activity created from application context");
            String handle=value("activity.current");
            ok("activity.launchMain");waitForIdleSync();
            check(value("activity.current").equals(handle),"Repeated launch reuses main instance");
            ok("alarm.screen.dismiss","cold-launch",42);waitForIdleSync();
            check(alarmActivity.isFinishing() || alarmActivity.isDestroyed(),"Cold alarm screen dismissed");
            return resumed;
        } finally {removeMonitor(alarm);if(launcher!=null)removeMonitor(launcher);}
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
            String privateName="copie été.bin";ok("storage.write",privateName,binary);
            Uri privateUri=Uri.parse(value("storage.fileUri",privateName));
            check("file".equals(privateUri.getScheme()) && privateName.equals(privateUri.getLastPathSegment()),"Private file URI escaping");
            try(java.io.InputStream in=getTargetContext().getContentResolver().openInputStream(privateUri)){
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[16];int n;
                while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);check(Arrays.equals(bytes.toByteArray(),binary),"File URI points at Storage.write_file output");
            }
            ok("storage.remove",privateName);
            String missing="absent-"+java.util.UUID.randomUUID()+".bin";
            Uri absent=Uri.parse(value("storage.fileUri",missing));check(!new java.io.File(absent.getPath()).exists(),"URI construction does not create a file");
            check(new String(call("storage.fileUri","../escape")[1],StandardCharsets.UTF_8).equals("argument"),"Storage URI rejects path traversal");
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
            componentTests(owner);
            authenticationTests();
            healthLocationTests(owner);
            final Activity settingsOwner=activity;onUi(()->{org.ocaml.android.SettingsChecks.run(settingsOwner);return null;});
            utilityTests(owner);
            photoTests(owner);
            alarmScreenTests(owner);
            activity=launchMainAfterAlarm(activity,owner);
            final Activity closing=activity;runOnMainSync(closing::finish);waitForIdleSync();
            check(new String(call("ui.getText",text)[1],StandardCharsets.UTF_8).equals("handle"),"Destroyed activity invalidates views");
            result.putString("stream","Platform binding tests passed\n");finish(Activity.RESULT_OK,result);
        }catch(Throwable e){result.putString("stream",android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}
    }
}
