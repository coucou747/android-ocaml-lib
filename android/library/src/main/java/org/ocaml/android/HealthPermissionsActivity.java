package org.ocaml.android;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.permission.HealthPermission;
import java.util.*;
import java.util.concurrent.*;

/** Retains the consent result across activity recreation without replacing BridgeActivity. */
public final class HealthPermissionsActivity extends ComponentActivity {
    private static final ConcurrentHashMap<String,Request> pending=new ConcurrentHashMap<>();
    private Request request;private String token;
    private static final class Request {
        final boolean background;
        final Set<String> permissions=new LinkedHashSet<>();final CompletableFuture<String[]> result=new CompletableFuture<>();
        Request(String[] kinds,boolean background){
            this.background=background;
            if(background)permissions.add(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND);
            else for(String kind:kinds)permissions.add(HealthConnectSupport.permission(kind));
        }
    }
    static CompletableFuture<String[]> request(Activity owner,String[] kinds) throws Exception {
        return request(owner,kinds,false);
    }
    static CompletableFuture<String[]> requestBackground(Activity owner) throws Exception {
        HealthConnectSupport.requireBackgroundAvailable(owner);
        return request(owner,new String[0],true);
    }
    private static CompletableFuture<String[]> request(Activity owner,String[] kinds,boolean background) throws Exception {
        HealthConnectSupport.client(owner);HealthRationaleActivity.texts(owner);
        Request request=new Request(kinds,background);String token=UUID.randomUUID().toString();pending.put(token,request);
        try{owner.startActivity(new Intent(owner,HealthPermissionsActivity.class).putExtra("request",token));}
        catch(Exception e){pending.remove(token);throw e;}return request.result;
    }
    // Register unconditionally and in the same order on every recreation. The
    // registry handles Android 14's RequestMultiplePermissions pseudo-intent.
    private final ActivityResultLauncher<Set<String>> launcher=registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),granted->{
            if(request!=null)try{complete(granted);}catch(Exception e){fail(e);}
        });
    @Override public void onCreate(Bundle state){
        super.onCreate(state);token=getIntent().getStringExtra("request");request=token==null?null:pending.get(token);
        if(request==null){finish();return;}
        if(state==null)try{launcher.launch(request.permissions);}catch(Exception e){fail(e);}
    }
    private void complete(Set<String> granted){
        if(request.background){
            if(!granted.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)){
                fail(new SecurityException("Health Connect background read consent denied"));return;
            }
            pending.remove(token);request.result.complete(new String[]{"background"});finish();return;
        }
        ArrayList<String> kinds=new ArrayList<>();for(String kind:HealthConnectSupport.KINDS)
            if(request.permissions.contains(HealthConnectSupport.permission(kind)) && granted.contains(HealthConnectSupport.permission(kind)))kinds.add(kind);
        pending.remove(token);request.result.complete(kinds.toArray(new String[0]));finish();
    }
    private void fail(Exception e){pending.remove(token);request.result.completeExceptionally(e);finish();}
    @Override public void onDestroy(){if(request!=null && isFinishing() && !isChangingConfigurations() && !request.result.isDone())fail(new IllegalStateException("Health consent interrupted"));super.onDestroy();}
}
