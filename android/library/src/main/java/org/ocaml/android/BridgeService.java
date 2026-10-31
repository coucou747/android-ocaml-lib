package org.ocaml.android;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.util.concurrent.*;

/** Consumer must declare the appropriate foregroundServiceType and permissions. */
public class BridgeService extends Service {
    private final java.util.Set<CompletableFuture<String>> running=ConcurrentHashMap.newKeySet();
    @Override public IBinder onBind(Intent i){return null;}
    @Override public int onStartCommand(Intent i,int flags,int startId){
        if(i==null){stopSelf(startId);return START_NOT_STICKY;}
        try {
        Bridge.initialize(this);
        startForeground(i.getIntExtra("id",0),Bridge.notification(i.getIntExtra("id",0),i.getStringExtra("channel"),i.getStringExtra("title"),"",i.getIntExtra("icon",0),"",""));
        CompletableFuture<String> f=Bridge.deliver(this,i.getStringExtra("handler"),i.getStringExtra("payload"));running.add(f);
        f.whenComplete((v,e)->new android.os.Handler(getMainLooper()).post(()->{running.remove(f);if(running.isEmpty())stopSelf();}));
        return START_NOT_STICKY;
        } catch (Exception e) {
            android.util.Log.e("OCaml","Foreground service failed to start",e);
            stopSelf(startId);return START_NOT_STICKY;
        }
    }
    @Override public void onDestroy(){for(CompletableFuture<String> f:running)f.cancel(false);running.clear();super.onDestroy();}
}
