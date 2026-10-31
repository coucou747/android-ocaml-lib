package org.ocaml.android;

import android.app.Activity;
import android.os.Bundle;
import android.content.pm.PackageManager;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** May be subclassed by an application. Forward overridden callbacks to super. */
public class BridgeActivity extends Activity {
    String bridgeId;
    volatile String bridgeState="created";
    private int nextRequest;
    private final Map<Integer,CompletableFuture<String[]>> requests=new HashMap<>();
    @Override public void onCreate(Bundle state){super.onCreate(state);Bridge.activity(this,"created");Bridge.initialize(this);}
    @Override public void onStart(){super.onStart();Bridge.activity(this,"started");}
    @Override public void onResume(){super.onResume();Bridge.activity(this,"resumed");}
    @Override public void onPause(){Bridge.activity(this,"paused");super.onPause();}
    @Override public void onStop(){Bridge.activity(this,"stopped");super.onStop();}
    @Override public void onDestroy(){Bridge.activity(this,"destroyed");for(CompletableFuture<String[]> f:requests.values())f.completeExceptionally(new IllegalStateException("Activity destroyed"));requests.clear();super.onDestroy();}
    CompletableFuture<String[]> request(String[] permissions){
        CompletableFuture<String[]> result=new CompletableFuture<>();
        int id=++nextRequest;requests.put(id,result);
        try{requestPermissions(permissions,id);}catch(Exception e){requests.remove(id);result.completeExceptionally(e);}
        return result;
    }
    @Override public void onRequestPermissionsResult(int id,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(id,permissions,grants);
        CompletableFuture<String[]> f=requests.remove(id);
        if(f!=null){ArrayList<String> granted=new ArrayList<>();for(int i=0;i<grants.length;i++)if(grants[i]==PackageManager.PERMISSION_GRANTED)granted.add(permissions[i]);f.complete(granted.toArray(new String[0]));}
    }
}
