package org.ocaml.android;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.os.*;
import androidx.core.location.LocationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.os.CancellationSignal;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

final class LocationSupport {
    private LocationSupport() {}
    static double distanceBetween(double latitude1,double longitude1,double latitude2,double longitude2){
        if(!coordinate(latitude1,longitude1) || !coordinate(latitude2,longitude2))throw new IllegalArgumentException("Invalid geographic coordinates");
        float[] result=new float[1];Location.distanceBetween(latitude1,longitude1,latitude2,longitude2,result);
        return result[0];
    }
    private static boolean coordinate(double latitude,double longitude){
        return Double.isFinite(latitude) && Double.isFinite(longitude) && Math.abs(latitude)<=90 && Math.abs(longitude)<=180;
    }
    static LocationManager manager(Context c){return c.getSystemService(LocationManager.class);}
    static void validate(Context c,String provider,long interval,double distance){
        if(!provider.equals(LocationManager.GPS_PROVIDER) && !provider.equals(LocationManager.NETWORK_PROVIDER))throw new IllegalArgumentException("Unsupported location provider");
        if(interval<0 || !Double.isFinite(distance) || distance<0 || distance>Float.MAX_VALUE)throw new IllegalArgumentException("Invalid location interval or distance");
        if(manager(c)==null || !manager(c).getAllProviders().contains(provider))throw new UnsupportedOperationException("Location provider unavailable: "+provider);
        boolean fine=c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        boolean coarse=c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED;
        if((provider.equals(LocationManager.GPS_PROVIDER) && !fine) || (!fine && !coarse))throw new SecurityException("Location permission not granted for "+provider);
    }
    static JSONObject fix(Location location) throws JSONException {
        return new JSONObject().put("provider",location.getProvider()==null?JSONObject.NULL:location.getProvider())
            .put("latitude",location.getLatitude()).put("longitude",location.getLongitude())
            .put("accuracy_m",location.hasAccuracy()?location.getAccuracy():JSONObject.NULL)
            .put("altitude_m",location.hasAltitude()?location.getAltitude():JSONObject.NULL)
            .put("speed_mps",location.hasSpeed()?location.getSpeed():JSONObject.NULL)
            .put("bearing_deg",location.hasBearing()?location.getBearing():JSONObject.NULL)
            .put("at_ms",Long.toString(location.getTime())).put("elapsed_realtime_ns",Long.toString(location.getElapsedRealtimeNanos()))
            .put("is_mock",Build.VERSION.SDK_INT>=31?location.isMock():location.isFromMockProvider());
    }
    static JSONObject event(String type,String provider,Location location) {
        try{return new JSONObject().put("type",type).put("provider",provider).put("fix",location==null?JSONObject.NULL:fix(location));}
        catch(JSONException e){throw new IllegalArgumentException("Invalid platform location",e);}
    }
    static String last(Context c,String provider) throws Exception {
        validate(c,provider,0,0);Location location=manager(c).getLastKnownLocation(provider);
        return location==null?null:fix(location).toString();
    }
    static boolean enabled(Context c,String provider){
        if(manager(c)==null || !manager(c).getAllProviders().contains(provider))return false;
        return manager(c).isProviderEnabled(provider);
    }
    static Runnable watch(Context c,String provider,long interval,float distance,Consumer<JSONObject> callback){
        validate(c,provider,interval,distance);
        var listener=new LocationListener(){
            private boolean active=true;
            @Override public void onLocationChanged(Location location){if(active)callback.accept(event("fix",provider,location));}
            @Override public void onProviderEnabled(String name){if(active)callback.accept(event("enabled",name,null));}
            @Override public void onProviderDisabled(String name){if(active)callback.accept(event("disabled",name,null));}
            @Override public void onStatusChanged(String name,int status,Bundle extras){}
            void stop(){active=false;manager(c).removeUpdates(this);}
        };
        manager(c).requestLocationUpdates(provider,interval,distance,listener,Looper.getMainLooper());
        // Suppress queued callbacks after unsubscribe as well as unregistering.
        return listener::stop;
    }
    static final class Current {
        final CompletableFuture<String> result=new CompletableFuture<>();
        final CancellationSignal cancellation=new CancellationSignal();
        final Handler main=new Handler(Looper.getMainLooper());
        Runnable timeout;
        Current(Context context,String provider,long timeoutMs){
            validate(context,provider,0,0);if(timeoutMs<=0)throw new IllegalArgumentException("Positive location timeout required");
            timeout=()->result.complete(null);
            result.whenComplete((value,error)->{main.removeCallbacks(timeout);cancellation.cancel();});
            main.postDelayed(timeout,timeoutMs);
            try{LocationManagerCompat.getCurrentLocation(manager(context),provider,cancellation,ContextCompat.getMainExecutor(context),location->{
                try{result.complete(location==null?null:fix(location).toString());}catch(Exception e){result.completeExceptionally(e);}
            });}catch(Exception e){close();throw e;}
        }
        void close(){result.complete(null);main.removeCallbacks(timeout);cancellation.cancel();}
    }
}
