package org.ocaml.android;

import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

/** Explicit alarm intents, or system events opted into by the consuming manifest. */
public class BridgeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        PendingResult pending=goAsync();
        try {
            String handler=intent.getStringExtra("handler");
            String payload=intent.getStringExtra("payload");
            if(handler==null){
                Bundle metadata=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
                handler=metadata==null?null:metadata.getString("android.ocaml.broadcast."+intent.getAction());
                payload=intent.getAction();
            }
            if(handler==null) {pending.finish();return;}
            Bridge.deliver(context,handler,payload==null?"":payload).whenComplete((v,e)->{if(e!=null)Log.e("OCaml","Receiver handler",e);pending.finish();});
        }catch(Exception e){Log.e("OCaml","Receiver startup",e);pending.finish();}
    }
}
