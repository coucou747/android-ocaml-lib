package org.ocaml.android;

import android.app.*;
import android.content.*;
import android.os.*;
import android.util.Log;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Foreground location service. The caller provides every notification and sampling parameter. */
public final class LocationTrackingService extends Service {
    private static final ConcurrentHashMap<String,CompletableFuture<Void>> starts=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,String> events=new ConcurrentHashMap<>();
    private Runnable unsubscribe;
    private CompletableFuture<String> delivery;
    private JSONObject queued;
    private Object session;
    private String handler;
    static CompletableFuture<Void> start(Context c,String[] args){
        if(args.length!=9 || args[8].isEmpty())throw new IllegalArgumentException("Tracking handler is required");
        LocationSupport.validate(c,args[0],Long.parseLong(args[1]),Double.parseDouble(args[2]));
        NotificationChannel channel=c.getSystemService(NotificationManager.class).getNotificationChannel(args[4]);
        if(channel==null || channel.getImportance()==NotificationManager.IMPORTANCE_NONE)throw new IllegalArgumentException("Create and enable the tracking notification channel first");
        int id=Integer.parseInt(args[3]),icon=Integer.parseInt(args[7]);
        if(id<=0 || icon<=0)throw new IllegalArgumentException("Positive foreground notification ID and icon required");
        String token=UUID.randomUUID().toString();CompletableFuture<Void> result=new CompletableFuture<>();starts.put(token,result);
        try{if(c.startForegroundService(new Intent(c,LocationTrackingService.class).putExtra("args",args).putExtra("start",token))==null)throw new UnsupportedOperationException("Location tracking service unavailable");}
        catch(Exception e){starts.remove(token);throw e;}return result;
    }
    static void stop(Context context){
        starts.forEach((token,future)->{if(starts.remove(token,future))future.completeExceptionally(new CancellationException("Location tracking stopped"));});
        context.stopService(new Intent(context,LocationTrackingService.class));
    }
    static String event(String token){String value=events.get(token);if(value==null)throw new NoSuchElementException("Expired location event");return value;}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String token=intent==null?null:intent.getStringExtra("start");
        CompletableFuture<Void> ready=token==null?null:starts.remove(token);
        try {
            if(intent==null)throw new IllegalArgumentException("Missing tracking configuration");
            String[] args=intent.getStringArrayExtra("args");
            if(args==null || args.length!=9)throw new IllegalArgumentException("Missing tracking configuration");
            LocationSupport.validate(this,args[0],Long.parseLong(args[1]),Double.parseDouble(args[2]));
            stopTracking();session=new Object();handler=args[8];
            Notification.Builder notification=new Notification.Builder(this,args[4]).setContentTitle(args[5]).setContentText(args[6])
                .setSmallIcon(Integer.parseInt(args[7])).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE);
            Intent main=getPackageManager().getLaunchIntentForPackage(getPackageName());
            if(main!=null)notification.setContentIntent(PendingIntent.getActivity(this,0,main.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE));
            startForeground(Integer.parseInt(args[3]),notification.build());
            Bridge.initialize(this);
            unsubscribe=LocationSupport.watch(this,args[0],Long.parseLong(args[1]),Float.parseFloat(args[2]),this::deliver);
            if(ready!=null)ready.complete(null);
            return START_REDELIVER_INTENT;
        }catch(Exception e){if(ready!=null)ready.completeExceptionally(e);else Log.e("OCaml","Cannot restart location tracking",e);
            stopTracking();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY;}
    }
    private void deliver(JSONObject event){
        if(session==null)return;
        // Completion can run on a worker before its cleanup reaches the main loop.
        // Keep the slot reserved until that cleanup has actually cleared delivery.
        if(delivery!=null){queued=event;return;}
        String token=UUID.randomUUID().toString();events.put(token,event.toString());Object active=session;
        try {
            delivery=Bridge.deliver(this,handler,token);
            delivery.whenComplete((result,error)->{
                events.remove(token);
                new Handler(getMainLooper()).post(()->{
                    if(session!=active)return;
                    if(error!=null || !"success".equals(result))Log.w("OCaml","Location handler did not succeed");
                    delivery=null;
                    if(queued!=null){JSONObject next=queued;queued=null;deliver(next);}
                });
            });
        }catch(Exception e){events.remove(token);Log.e("OCaml","Cannot deliver location event",e);}
    }
    private void stopTracking(){
        session=null;queued=null;
        if(unsubscribe!=null){Runnable close=unsubscribe;unsubscribe=null;
            try{close.run();}catch(RuntimeException error){Log.w("OCaml","Cannot unregister location listener",error);}}
        if(delivery!=null){delivery.cancel(false);delivery=null;}
    }
    @Override public void onDestroy(){stopTracking();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
}
