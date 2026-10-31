package org.ocaml.android;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.*;
import android.media.*;
import android.net.*;
import android.os.*;
import android.provider.Settings;
import android.security.keystore.*;
import android.text.*;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import androidx.work.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Shared platform implementation. Applications supply android.ocaml.library metadata. */
public final class Bridge {
    private Bridge() {}
    private static Context context;
    private static volatile BridgeActivity current;
    private static boolean initialized;
    private static final AtomicLong ids = new AtomicLong();
    private static final ConcurrentHashMap<String,Object> objects = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Runnable> subscriptions = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,View> subscriptionViews = new ConcurrentHashMap<>();
    private static final Map<String,String> listenerSlots = new HashMap<>();
    private static final Set<String> lifecycle = ConcurrentHashMap.newKeySet();
    private static final BlockingQueue<byte[][]> events = new LinkedBlockingQueue<>();
    private static final ConcurrentHashMap<String,CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private static native void nativeStart();
    private static byte[] utf(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    private static String text(byte[] b) { return new String(b, StandardCharsets.UTF_8); }
    private static byte[][] pack(Object... values) {
        byte[][] out = new byte[values.length][];
        for (int i=0;i<values.length;i++) out[i] = values[i] instanceof byte[] ? (byte[])values[i] : utf(String.valueOf(values[i]));
        return out;
    }
    private static byte[][] ok(Object... values) {
        Object[] out = new Object[values.length+1]; out[0]="ok";
        System.arraycopy(values,0,out,1,values.length); return pack(out);
    }
    private static String put(Object o) { String id=Long.toString(ids.incrementAndGet()); objects.put(id,o); return id; }
    private static <T> T get(String id, Class<T> cls) {
        Object o=objects.get(id);
        if (!cls.isInstance(o)) throw new NoSuchElementException("Invalid or expired handle: "+id);
        if (o instanceof View && ((View)o).getContext() instanceof Activity && ((Activity)((View)o).getContext()).isDestroyed())
            throw new NoSuchElementException("Activity destroyed");
        return cls.cast(o);
    }
    static synchronized void initialize(Context c) {
        if(initialized) return;
        context=c.getApplicationContext();
        try {
            ApplicationInfoCompat.start(context);
            initialized=true;
        } catch (Exception e) { throw new IllegalStateException("Cannot initialize OCaml",e); }
    }
    private static final class ApplicationInfoCompat {
        static void start(Context c) throws Exception {
            Bundle metadata=c.getPackageManager().getApplicationInfo(c.getPackageName(),PackageManager.GET_META_DATA).metaData;
            String name=metadata==null?null:metadata.getString("android.ocaml.library");
            if(name==null || name.isEmpty()) throw new IllegalArgumentException("Missing android.ocaml.library metadata");
            System.loadLibrary(name); nativeStart();
        }
    }
    static void activity(BridgeActivity a, String event) {
        a.bridgeState=event;
        if(event.equals("created")) { a.bridgeId=put(a); current=a; }
        if(event.equals("resumed")) current=a;
        for(String sub:lifecycle) emit(sub,a.bridgeId,event);
        if(event.equals("destroyed")) {
            if(current==a) current=null;
            for(Map.Entry<String,View> e:subscriptionViews.entrySet()) if(e.getValue().getContext()==a) cleanupSubscription(e.getKey());
            objects.entrySet().removeIf(e -> e.getValue()==a || (e.getValue() instanceof View && ((View)e.getValue()).getContext()==a));
        }
    }
    private static void emit(String id,Object... values) {
        Object[] out=new Object[values.length+1]; out[0]=id; System.arraycopy(values,0,out,1,values.length); events.add(pack(out));
    }
    public static byte[][] takeEvent() throws InterruptedException { return events.take(); }
    static CompletableFuture<String> deliver(Context c,String handler,String payload) {
        initialize(c); String token=Long.toString(ids.incrementAndGet());
        CompletableFuture<String> result=new CompletableFuture<>(); pending.put(token,result);
        result.whenComplete((v,e)->{pending.remove(token);if(result.isCancelled())events.add(pack("cancel",token));});
        events.add(pack("handler",token,handler,payload)); return result;
    }
    private static void cleanupSubscription(String id) {
        Runnable cleanup=subscriptions.remove(id);subscriptionViews.remove(id);listenerSlots.values().removeIf(id::equals);
        if(cleanup!=null)cleanup.run();events.add(pack("unsubscribe",id));
    }
    private static void reserveListener(String view,String event,String id) {
        String key=view+":"+event;
        if(listenerSlots.containsKey(key))throw new IllegalArgumentException("Cancel the existing "+event+" subscription first");
        listenerSlots.put(key,id);
    }
    private static void ownSubscription(String id,View view) { subscriptionViews.put(id,view); }
    private static <T> T ui(Callable<T> fn) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) return fn.call();
        FutureTask<T> task=new FutureTask<>(fn); new Handler(Looper.getMainLooper()).post(task);
        try { return task.get(); } catch(ExecutionException e) {
            if(e.getCause() instanceof Exception) throw (Exception)e.getCause(); throw e;
        }
    }
    public static byte[][] dispatch(byte[] command,byte[][] bytes) {
        String op=text(command); String[] a=new String[bytes.length];
        for(int i=0;i<a.length;i++) a[i]=text(bytes[i]);
        try {
            if(op.startsWith("ui.") || op.startsWith("events.") || op.startsWith("activity.") || op.equals("permissions.settings"))
                return ui(()->execute(op,a,bytes));
            if(op.equals("permissions.request")) {
                CompletableFuture<String[]> result=ui(()->get(a[0],BridgeActivity.class).request(Arrays.copyOfRange(a,1,a.length)));
                return ok((Object[])result.get());
            }
            return execute(op,a,bytes);
        } catch(Exception e) {
            while(e instanceof ExecutionException && e.getCause() instanceof Exception) e=(Exception)e.getCause();
            String kind=e instanceof SecurityException?"permission":e instanceof NoSuchElementException?"handle":
                e instanceof IllegalArgumentException?"argument":e instanceof UnsupportedOperationException?"unavailable":"platform";
            return pack("error",kind,e.getClass().getSimpleName()+": "+String.valueOf(e.getMessage()));
        }
    }
    private static String[] networkState() {
        ConnectivityManager m=context.getSystemService(ConnectivityManager.class);
        android.net.Network n=m.getActiveNetwork(); NetworkCapabilities cap=n==null?null:m.getNetworkCapabilities(n);
        return new String[]{Boolean.toString(cap!=null && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)),Boolean.toString(m.isActiveNetworkMetered())};
    }
    private static Intent intent(String id,String handler,String payload) {
        return new Intent(context,BridgeReceiver.class).setAction("org.ocaml.android.DELIVER")
            .setData(new Uri.Builder().scheme("ocaml").authority(context.getPackageName()).appendPath(id).build())
            .putExtra("handler",handler).putExtra("payload",payload);
    }
    private static PendingIntent alarmIntent(String id,String handler,String payload) {
        return PendingIntent.getBroadcast(context,0,intent(id,handler,payload),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    static Notification notification(int id,String channel,String title,String body,int icon,String handler,String payload) {
        Notification.Builder b=new Notification.Builder(context,channel).setContentTitle(title).setContentText(body).setSmallIcon(icon);
        if(!handler.isEmpty()) {
            PendingIntent action=alarmIntent("notification/"+id,handler,payload);
            b.addAction(new Notification.Action.Builder(null,title,action).build());
        }
        return b.build();
    }
    private static File privateFile(String name) throws IOException {
        if(name.isEmpty() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Expected private file basename");
        return new File(context.getFilesDir(),name);
    }
    private static Vibrator vibrator() {
        Vibrator v=Build.VERSION.SDK_INT>=31?context.getSystemService(VibratorManager.class).getDefaultVibrator():context.getSystemService(Vibrator.class);
        if(v==null || !v.hasVibrator())throw new UnsupportedOperationException("No vibrator available");
        return v;
    }
    private static byte[][] execute(String op,String[] a,byte[][] raw) throws Exception {
        switch(op) {
        case "runtime.complete": { CompletableFuture<String> f=pending.remove(a[0]); if(f!=null) f.complete(a[1]); return ok(); }
        case "unsubscribe": { ui(()->{cleanupSubscription(a[0]);return null;}); lifecycle.remove(a[0]); return ok(); }
        case "activity.current": if(current==null) throw new NoSuchElementException("No current activity"); return ok(current.bridgeId);
        case "activity.lifecycle": lifecycle.add(a[0]); if(current!=null)emit(a[0],current.bridgeId,current.bridgeState); return ok();
        case "activity.screen": { Window w=get(a[0],Activity.class).getWindow(); if(Boolean.parseBoolean(a[1])) w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); else w.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);return ok(); }
        case "ui.text": case "ui.button": case "ui.edit": case "ui.layout": {
            Activity activity=get(a[0],Activity.class); View v;
            if(op.equals("ui.layout")) { LinearLayout l=new LinearLayout(activity);l.setOrientation(a[1].equals("vertical")?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);v=l; }
            else { TextView t=op.equals("ui.button")?new Button(activity):op.equals("ui.edit")?new EditText(activity):new TextView(activity);t.setText(a[1]);v=t; }
            return ok(put(v)); }
        case "ui.add": get(a[0],ViewGroup.class).addView(get(a[1],View.class)); return ok();
        case "ui.show": get(a[0],Activity.class).setContentView(get(a[1],View.class)); return ok();
        case "ui.setText": get(a[0],TextView.class).setText(a[1]); return ok();
        case "ui.getText": return ok(get(a[0],TextView.class).getText().toString());
        case "ui.enabled": get(a[0],View.class).setEnabled(Boolean.parseBoolean(a[1])); return ok();
        case "ui.release": {View v=get(a[0],View.class); for(Map.Entry<String,View> e:subscriptionViews.entrySet())if(e.getValue()==v)cleanupSubscription(e.getKey()); if(v.getParent() instanceof ViewGroup) ((ViewGroup)v.getParent()).removeView(v);objects.remove(a[0]);return ok();}
        case "events.click": {View v=get(a[1],View.class);reserveListener(a[1],"click",a[0]);v.setOnClickListener(w->emit(a[0]));ownSubscription(a[0],v);subscriptions.put(a[0],()->v.setOnClickListener(null));return ok();}
        case "events.touch": {View v=get(a[1],View.class);reserveListener(a[1],"touch",a[0]);v.setOnTouchListener((w,e)->{emit(a[0],e.getActionMasked(),e.getX(),e.getY());return Boolean.parseBoolean(a[2]);});ownSubscription(a[0],v);subscriptions.put(a[0],()->v.setOnTouchListener(null));return ok();}
        case "events.text": {TextView v=get(a[1],TextView.class);TextWatcher t=new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){} public void onTextChanged(CharSequence s,int start,int before,int count){emit(a[0],s.toString());}public void afterTextChanged(Editable e){}};v.addTextChangedListener(t);ownSubscription(a[0],v);subscriptions.put(a[0],()->v.removeTextChangedListener(t));return ok();}
        case "permissions.check": return ok(context.checkSelfPermission(a[0])==PackageManager.PERMISSION_GRANTED);
        case "permissions.special": {
            if(a[0].equals("exact")) return ok(Build.VERSION.SDK_INT<31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms());
            if(a[0].equals("fullscreen")) return ok(Build.VERSION.SDK_INT<34 || context.getSystemService(NotificationManager.class).canUseFullScreenIntent());
            return ok(context.getSystemService(NotificationManager.class).areNotificationsEnabled()); }
        case "permissions.settings": {
            Activity act=get(a[0],Activity.class);String action;
            if(a[1].equals("exact")){if(Build.VERSION.SDK_INT<31)return ok();action=Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM;}
            else if(a[1].equals("fullscreen")){if(Build.VERSION.SDK_INT<34)return ok();action=Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT;}
            else {act.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.getPackageName()));return ok();}
            act.startActivity(new Intent(action,Uri.parse("package:"+context.getPackageName())));return ok(); }
        case "alarm.allowed": return ok(Build.VERSION.SDK_INT<31 || context.getSystemService(AlarmManager.class).canScheduleExactAlarms());
        case "alarm.schedule": {
            AlarmManager m=context.getSystemService(AlarmManager.class);PendingIntent p=alarmIntent(a[0],a[3],a[4]);long at=Long.parseLong(a[1]);
            if(a[2].equals("clock")) {
                Intent show=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());
                if(show==null)throw new IllegalArgumentException("Alarm clock requires launchable activity");
                m.setAlarmClock(new AlarmManager.AlarmClockInfo(at,PendingIntent.getActivity(context,0,show,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)),p);
            } else m.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,at,p);return ok(); }
        case "alarm.cancel": {PendingIntent p=alarmIntent(a[0],"","");context.getSystemService(AlarmManager.class).cancel(p);p.cancel();return ok();}
        case "notification.channel": context.getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(a[0],a[1],Integer.parseInt(a[2])));return ok();
        case "notification.show": context.getSystemService(NotificationManager.class).notify(Integer.parseInt(a[0]),notification(Integer.parseInt(a[0]),a[1],a[2],a[3],Integer.parseInt(a[4]),a[5],a[6]));return ok();
        case "notification.cancel": context.getSystemService(NotificationManager.class).cancel(Integer.parseInt(a[0]));return ok();
        case "vibrator.start": vibrator().vibrate(VibrationEffect.createOneShot(Long.parseLong(a[0]),VibrationEffect.DEFAULT_AMPLITUDE));return ok();
        case "vibrator.cancel": vibrator().cancel();return ok();
        case "audio.play": return play(a[0],Boolean.parseBoolean(a[1]));
        case "audio.stop": {Player p=get(a[0],Player.class);p.close();objects.remove(a[0]);return ok();}
        case "network.state": return ok((Object[])networkState());
        case "network.listen": {ConnectivityManager m=context.getSystemService(ConnectivityManager.class);ConnectivityManager.NetworkCallback cb=new ConnectivityManager.NetworkCallback(){private void changed(){emit(a[0],(Object[])networkState());} public void onAvailable(android.net.Network n){changed();}public void onLost(android.net.Network n){changed();}public void onCapabilitiesChanged(android.net.Network n,NetworkCapabilities c){changed();}};m.registerDefaultNetworkCallback(cb);subscriptions.put(a[0],()->m.unregisterNetworkCallback(cb));return ok();}
        case "http.create": return ok(put(new HttpCall()));
        case "http.close": {Object value=objects.remove(a[0]);if(value instanceof HttpCall)((HttpCall)value).close();return ok();}
        case "http.request": return http(a,raw);
        case "storage.read": return ok(java.nio.file.Files.readAllBytes(privateFile(a[0]).toPath()));
        case "storage.write": {android.util.AtomicFile file=new android.util.AtomicFile(privateFile(a[0]));FileOutputStream stream=file.startWrite();try{stream.write(raw[1]);file.finishWrite(stream);}catch(Exception e){file.failWrite(stream);throw e;}return ok();}
        case "storage.remove": java.nio.file.Files.deleteIfExists(privateFile(a[0]).toPath());return ok();
        case "preferences.set": {SharedPreferences.Editor e=context.getSharedPreferences(a[0],Context.MODE_PRIVATE).edit();switch(a[2]){case "text":e.putString(a[1],a[3]);break;case "bool":e.putBoolean(a[1],Boolean.parseBoolean(a[3]));break;case "int":e.putLong(a[1],Long.parseLong(a[3]));break;default:throw new IllegalArgumentException("preference type");}if(!e.commit())throw new IOException("Preference commit failed");return ok();}
        case "preferences.get": {Object v=context.getSharedPreferences(a[0],Context.MODE_PRIVATE).getAll().get(a[1]);return v==null?ok():ok(v instanceof Boolean?"bool":v instanceof Long?"int":"text",v);}
        case "preferences.remove": if(!context.getSharedPreferences(a[0],Context.MODE_PRIVATE).edit().remove(a[1]).commit())throw new IOException("Preference commit failed");return ok();
        case "keystore.create": case "keystore.encrypt": case "keystore.decrypt": case "keystore.delete": return crypto(op,a,raw);
        case "sqlite.open": privateFile(a[0]);return ok(put(context.openOrCreateDatabase(a[0],Context.MODE_PRIVATE,null)));
        case "sqlite.close": get(a[0],SQLiteDatabase.class).close();objects.remove(a[0]);return ok();
        case "sqlite.execute": {SQLiteDatabase db=get(a[0],SQLiteDatabase.class);synchronized(db){sqlExecute(db,a,raw,1,2,(a.length-2)/2);}return ok();}
        case "sqlite.transaction": {SQLiteDatabase db=get(a[0],SQLiteDatabase.class);synchronized(db){db.beginTransaction();try{int p=1;while(p<a.length){int n=Integer.parseInt(a[p++]);sqlExecute(db,a,raw,p,p+1,n);p+=1+2*n;}db.setTransactionSuccessful();}finally{db.endTransaction();}}return ok();}
        case "sqlite.query": return query(a,raw);
        case "background.work": return work(a);
        case "background.cancel": WorkManager.getInstance(context).cancelUniqueWork(a[0]).getResult().get();return ok();
        case "background.job": {PersistableBundle b=new PersistableBundle();b.putString("handler",a[1]);b.putString("payload",a[2]);JobInfo.Builder j=new JobInfo.Builder(Integer.parseInt(a[0]),new ComponentName(context,BridgeJobService.class)).setExtras(b).setMinimumLatency(0).setRequiresCharging(Boolean.parseBoolean(a[4]));if(Boolean.parseBoolean(a[3]))j.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);if(context.getSystemService(JobScheduler.class).schedule(j.build())!=JobScheduler.RESULT_SUCCESS)throw new IOException("Job rejected");return ok();}
        case "background.cancelJob": context.getSystemService(JobScheduler.class).cancel(Integer.parseInt(a[0]));return ok();
        case "background.service": context.startForegroundService(new Intent(context,BridgeService.class).putExtra("handler",a[0]).putExtra("payload",a[1]).putExtra("id",Integer.parseInt(a[2])).putExtra("channel",a[3]).putExtra("title",a[4]).putExtra("icon",Integer.parseInt(a[5])));return ok();
        case "background.stopService": context.stopService(new Intent(context,BridgeService.class));return ok();
        case "broadcast.listen": {IntentFilter filter=new IntentFilter();for(int i=1;i<a.length;i++)filter.addAction(a[i]);BroadcastReceiver r=new BroadcastReceiver(){public void onReceive(Context c,Intent i){emit(a[0],i.getAction(),i.getDataString()==null?"":i.getDataString());}};if(Build.VERSION.SDK_INT>=33)context.registerReceiver(r,filter,Context.RECEIVER_NOT_EXPORTED);else context.registerReceiver(r,filter);subscriptions.put(a[0],()->context.unregisterReceiver(r));return ok();}
        default: throw new IllegalArgumentException("Unknown operation: "+op);
        }
    }
    private static final class Player {
        final MediaPlayer media=new MediaPlayer(); final AudioManager manager=context.getSystemService(AudioManager.class);
        final AudioFocusRequest focus;
        private boolean closed;
        Player() {
            AudioAttributes attributes=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
            media.setAudioAttributes(attributes);
            focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change->{synchronized(Player.this){if(!closed && change<0 && media.isPlaying())media.pause();}},new Handler(Looper.getMainLooper())).build();
        }
        synchronized void close(){if(!closed){closed=true;media.release();manager.abandonAudioFocusRequest(focus);}}
    }
    private static byte[][] play(String uri,boolean loop) throws Exception {
        Player p=new Player();
        try{p.media.setDataSource(context,Uri.parse(uri));p.media.setLooping(loop);p.media.prepare();
            if(p.manager.requestAudioFocus(p.focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED)throw new IOException("Audio focus denied");
            p.media.start();return ok(put(p));
        }catch(Exception e){p.close();throw e;}
    }
    private static final class HttpCall {
        private HttpURLConnection connection;
        private boolean closed;
        synchronized void connect(HttpURLConnection c) throws IOException {if(closed)throw new IOException("Cancelled");connection=c;}
        synchronized void close(){closed=true;if(connection!=null)connection.disconnect();}
    }
    private static byte[][] http(String[] a,byte[][] raw) throws Exception {
        HttpCall job=get(a[0],HttpCall.class);
        URL url=new URL(a[1]);if(!url.getProtocol().equals("https") && !url.getProtocol().equals("http"))throw new IllegalArgumentException("HTTP(S) URL required");
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        try{job.connect(c);c.setConnectTimeout(Integer.parseInt(a[4]));c.setReadTimeout(Integer.parseInt(a[5]));c.setRequestMethod(a[2]);
            c.setInstanceFollowRedirects(false);
            for(int i=6;i<a.length;i+=2)c.addRequestProperty(a[i],a[i+1]);
            if(raw[3].length>0){c.setDoOutput(true);try(OutputStream out=c.getOutputStream()){out.write(raw[3]);}}
            int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
            byte[] body;try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in!=null){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}body=out.toByteArray();}
            ArrayList<Object> values=new ArrayList<>();values.add(status);values.add(body);
            for(Map.Entry<String,List<String>> e:c.getHeaderFields().entrySet())if(e.getKey()!=null)for(String v:e.getValue()){values.add(e.getKey());values.add(v);}
            return ok(values.toArray());
        }finally{job.close();}
    }
    private static byte[][] crypto(String op,String[] a,byte[][] raw) throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(op.equals("keystore.create")) {if(ks.containsAlias(a[0]))throw new IllegalArgumentException("Alias exists");KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(a[0],KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());g.generateKey();return ok();}
        if(op.equals("keystore.delete")){ks.deleteEntry(a[0]);return ok();}
        java.security.Key key=ks.getKey(a[0],null);if(key==null)throw new NoSuchElementException("Key not found");
        Cipher c=Cipher.getInstance("AES/GCM/NoPadding");
        if(op.equals("keystore.encrypt")){c.init(Cipher.ENCRYPT_MODE,key);byte[] encrypted=c.doFinal(raw[1]);ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(c.getIV().length);out.write(c.getIV());out.write(encrypted);return ok(Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP));}
        byte[] value=Base64.decode(a[1],Base64.NO_WRAP);int n=value.length==0?0:value[0]&255;if(n==0 || value.length<=1+n)throw new IllegalArgumentException("Invalid ciphertext");
        c.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Arrays.copyOfRange(value,1,1+n)));return ok(c.doFinal(value,1+n,value.length-1-n));
    }
    private static void bind(android.database.sqlite.SQLiteProgram q,int index,String typ,String value,byte[] raw) {
        switch(typ){case "null":q.bindNull(index);break;case "int":q.bindLong(index,Long.parseLong(value));break;case "float":q.bindDouble(index,Double.parseDouble(value));break;case "text":q.bindString(index,value);break;case "blob":q.bindBlob(index,raw);break;default:throw new IllegalArgumentException("SQL parameter type");}
    }
    private static void sqlExecute(SQLiteDatabase db,String[] a,byte[][] raw,int sql,int start,int n) {
        try(SQLiteStatement q=db.compileStatement(a[sql])){for(int i=0;i<n;i++)bind(q,i+1,a[start+i*2],a[start+i*2+1],raw[start+i*2+1]);q.execute();}
    }
    private static byte[][] query(String[] a,byte[][] raw) {
        SQLiteDatabase db=get(a[0],SQLiteDatabase.class);
        synchronized(db){try(Cursor cursor=db.rawQueryWithFactory((database,driver,table,q)->{for(int i=2;i<a.length;i+=2)bind(q,(i-2)/2+1,a[i],a[i+1],raw[i+1]);return new SQLiteCursor(driver,table,q);},a[1],new String[0],null)) {
            ArrayList<Object> result=new ArrayList<>();
            while(cursor.moveToNext()){result.add(cursor.getColumnCount());for(int i=0;i<cursor.getColumnCount();i++)switch(cursor.getType(i)){
                case Cursor.FIELD_TYPE_NULL:result.add("null");result.add("");break;
                case Cursor.FIELD_TYPE_INTEGER:result.add("int");result.add(cursor.getLong(i));break;
                case Cursor.FIELD_TYPE_FLOAT:result.add("float");result.add(cursor.getDouble(i));break;
                case Cursor.FIELD_TYPE_BLOB:result.add("blob");result.add(cursor.getBlob(i));break;
                default:result.add("text");result.add(cursor.getString(i));}}
            return ok(result.toArray());}}
    }
    private static byte[][] work(String[] a) throws Exception {
        Data data=new Data.Builder().putString("handler",a[1]).putString("payload",a[2]).build();
        Constraints constraints=new Constraints.Builder().setRequiredNetworkType(Boolean.parseBoolean(a[3])?NetworkType.CONNECTED:NetworkType.NOT_REQUIRED).setRequiresCharging(Boolean.parseBoolean(a[4])).build();
        WorkManager m=WorkManager.getInstance(context);
        if(a[5].isEmpty())m.enqueueUniqueWork(a[0],ExistingWorkPolicy.REPLACE,new OneTimeWorkRequest.Builder(BridgeWorker.class).setInputData(data).setConstraints(constraints).build()).getResult().get();
        else{long interval=Long.parseLong(a[5]);if(interval<PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS)throw new IllegalArgumentException("Periodic interval below WorkManager minimum");m.enqueueUniquePeriodicWork(a[0],ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,new PeriodicWorkRequest.Builder(BridgeWorker.class,interval,TimeUnit.MILLISECONDS).setInputData(data).setConstraints(constraints).build()).getResult().get();}
        return ok();
    }
}
