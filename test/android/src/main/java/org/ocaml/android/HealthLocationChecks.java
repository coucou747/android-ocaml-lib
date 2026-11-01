package org.ocaml.android;

import android.location.Location;
import androidx.health.connect.client.records.*;
import androidx.health.connect.client.records.metadata.Metadata;
import java.time.*;
import java.util.Collections;
import java.util.HashMap;
import androidx.health.connect.client.aggregate.*;
import org.json.JSONObject;

/** SDK model/JSON codec checks without reading a person's health records. */
public final class HealthLocationChecks {
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void completedDeliveryStillCoalesces() throws Exception {
        LocationTrackingService service=new LocationTrackingService();
        java.lang.reflect.Field session=LocationTrackingService.class.getDeclaredField("session");session.setAccessible(true);session.set(service,new Object());
        java.lang.reflect.Field delivery=LocationTrackingService.class.getDeclaredField("delivery");delivery.setAccessible(true);
        java.util.concurrent.CompletableFuture<String> complete=java.util.concurrent.CompletableFuture.completedFuture("success");delivery.set(service,complete);
        java.lang.reflect.Field queued=LocationTrackingService.class.getDeclaredField("queued");queued.setAccessible(true);
        java.lang.reflect.Method deliver=LocationTrackingService.class.getDeclaredMethod("deliver",JSONObject.class);deliver.setAccessible(true);
        JSONObject event=LocationSupport.event("disabled","gps",null);deliver.invoke(service,event);
        check(queued.get(service)==event && delivery.get(service)==complete,"Completed delivery remains reserved until its main-thread completion runs");
    }
    public static void run() throws Exception {
        completedDeliveryStillCoalesces();
        AggregationResult missing=new AggregationResult(Collections.emptyMap(),Collections.emptyMap(),Collections.emptySet());
        for(String kind:new String[]{"distance","active_calories","total_calories"}){
            check(HealthConnectSupport.amount(missing,kind)==null,"Missing aggregate is absent, not zero: "+kind);
            AggregateMetric<?> metric=HealthConnectSupport.amountMetric(kind);
            HashMap<String,Double> values=new HashMap<>();values.put(metric.getMetricKey(),1234.5);
            AggregationResult result=new AggregationResult(Collections.emptyMap(),values,Collections.emptySet());
            check(HealthConnectSupport.amount(result,kind)==1234.5,"SDK metric converted to metres/kilocalories: "+kind);
            values.put(metric.getMetricKey(),0.0);
            check(HealthConnectSupport.amount(new AggregationResult(Collections.emptyMap(),values,Collections.emptySet()),kind)==0.0,"Measured zero remains zero");
        }
        check(HealthConnectSupport.amountMetric("distance")==DistanceRecord.DISTANCE_TOTAL,"SDK distance metric");
        check(HealthConnectSupport.amountMetric("active_calories")==ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL,"SDK active energy metric");
        check(HealthConnectSupport.amountMetric("total_calories")==TotalCaloriesBurnedRecord.ENERGY_TOTAL,"SDK total energy metric");
        check(LocationSupport.distanceBetween(48,2,48,2)==0,"Coincident coordinates");
        check(Math.abs(LocationSupport.distanceBetween(0,0,0,1)-111319.49)<1,"WGS84 equatorial distance in metres");
        check(Math.abs(LocationSupport.distanceBetween(0,179.5,0,-179.5)-111319.49)<1,"Date-line crossing");
        for(double[] coordinates:new double[][]{{Double.NaN,0,0,0},{0,Double.POSITIVE_INFINITY,0,0},{91,0,0,0},{0,0,0,181}}){
            boolean rejected=false;try{LocationSupport.distanceBetween(coordinates[0],coordinates[1],coordinates[2],coordinates[3]);}catch(IllegalArgumentException expected){rejected=true;}
            check(rejected,"Invalid coordinates rejected by native adapter");
        }
        Metadata metadata=Metadata.unknownRecordingMethod();Instant start=Instant.ofEpochMilli(100),end=Instant.ofEpochMilli(200);
        JSONObject steps=HealthConnectSupport.encodeRecord(new StepsRecord(start,ZoneOffset.ofHours(1),end,null,123,metadata));
        check(steps.getString("count").equals("123") && steps.getInt("start_offset_s")==3600 && steps.isNull("end_offset_s"),"Steps and optional time offsets");
        JSONObject heart=HealthConnectSupport.encodeRecord(new HeartRateRecord(start,null,end,null,Collections.singletonList(new HeartRateRecord.Sample(Instant.ofEpochMilli(150),60)),metadata));
        check(heart.getJSONArray("samples").getJSONObject(0).getString("bpm").equals("60"),"Heart-rate samples");
        JSONObject sleep=HealthConnectSupport.encodeRecord(new SleepSessionRecord(start,null,end,null,metadata,"",null,Collections.singletonList(new SleepSessionRecord.Stage(start,end,SleepSessionRecord.STAGE_TYPE_DEEP))));
        check(sleep.getString("title").isEmpty() && sleep.isNull("notes") && sleep.getJSONArray("stages").length()==1,"Sleep stages and nullable text");
        JSONObject exercise=HealthConnectSupport.encodeRecord(new ExerciseSessionRecord(start,null,end,null,metadata,ExerciseSessionRecord.EXERCISE_TYPE_RUNNING));
        check(exercise.getInt("exercise_type")==ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,"Exercise session summary");
        Location fix=new Location("gps");fix.setLatitude(1.25);fix.setLongitude(-2.5);fix.setAccuracy(5);fix.setTime(2147483648000L);fix.setElapsedRealtimeNanos(9876543210123L);
        JSONObject location=LocationSupport.fix(fix);
        check(location.getDouble("latitude")==1.25 && location.getString("at_ms").equals("2147483648000") && location.isNull("altitude_m"),"GPS fix codec");
        check(LocationSupport.event("disabled","gps",null).getString("type").equals("disabled"),"Provider event");
    }
}
