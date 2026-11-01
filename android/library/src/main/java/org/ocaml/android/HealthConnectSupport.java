package org.ocaml.android;

import android.content.Context;
import androidx.health.connect.client.*;
import androidx.health.connect.client.records.*;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.aggregate.*;
import androidx.health.connect.client.units.*;
import androidx.health.connect.client.request.*;
import androidx.health.connect.client.response.*;
import androidx.health.connect.client.time.TimeRangeFilter;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import kotlin.coroutines.*;
import kotlin.jvm.JvmClassMappingKt;
import kotlin.reflect.KClass;
import kotlinx.coroutines.BuildersKt;
import org.json.*;

/** Read-only Health Connect adapter. Blocking calls run on the OCaml worker pool. */
final class HealthConnectSupport {
    private HealthConnectSupport() {}
    static final List<String> KINDS=Collections.unmodifiableList(Arrays.asList("steps","heart","sleep","exercise","distance","active_calories","total_calories"));
    static int status(Context context){return HealthConnectClient.getSdkStatus(context);}
    static HealthConnectClient client(Context context){
        if(status(context)!=HealthConnectClient.SDK_AVAILABLE)throw new UnsupportedOperationException("Health Connect unavailable or provider update required");
        return HealthConnectClient.getOrCreate(context);
    }
    static Class<? extends Record> recordClass(String kind){
        switch(kind){case "steps":return StepsRecord.class;case "heart":return HeartRateRecord.class;
        case "sleep":return SleepSessionRecord.class;case "exercise":return ExerciseSessionRecord.class;
        case "distance":return DistanceRecord.class;case "active_calories":return ActiveCaloriesBurnedRecord.class;
        case "total_calories":return TotalCaloriesBurnedRecord.class;
        default:throw new IllegalArgumentException("Unsupported health record type");}
    }
    static String permission(String kind){return HealthPermission.getReadPermission(JvmClassMappingKt.getKotlinClass(recordClass(kind)));}
    static <T> T await(Function<Continuation<? super T>,Object> action) throws InterruptedException {
        return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,continuation)->action.apply(continuation));
    }
    static String[] granted(Context context) throws InterruptedException {
        Set<String> permissions=HealthConnectSupport.<Set<String>>await(c->client(context).getPermissionController().getGrantedPermissions(c));
        ArrayList<String> kinds=new ArrayList<>();for(String kind:KINDS)if(permissions.contains(permission(kind)))kinds.add(kind);
        return kinds.toArray(new String[0]);
    }
    static boolean backgroundAvailable(Context context){
        return status(context)==HealthConnectClient.SDK_AVAILABLE && client(context).getFeatures().getFeatureStatus(
            HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)==HealthConnectFeatures.FEATURE_STATUS_AVAILABLE;
    }
    static void requireBackgroundAvailable(Context context){
        if(!backgroundAvailable(context))throw new UnsupportedOperationException("Health Connect background read feature unavailable");
    }
    static boolean backgroundGranted(Context context) throws InterruptedException {
        requireBackgroundAvailable(context);
        Set<String> permissions=HealthConnectSupport.<Set<String>>await(c->client(context).getPermissionController().getGrantedPermissions(c));
        return permissions.contains(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND);
    }
    static void require(Context context,String kind) throws InterruptedException {
        if(!Arrays.asList(granted(context)).contains(kind))throw new SecurityException("Health Connect read permission is not granted for "+kind);
    }
    private static TimeRangeFilter range(String start,String end){
        long begin=Long.parseLong(start),finish=Long.parseLong(end);
        if(begin>=finish)throw new IllegalArgumentException("Health time interval must be nonempty");
        return TimeRangeFilter.between(Instant.ofEpochMilli(begin),Instant.ofEpochMilli(finish));
    }
    static String read(Context context,String[] args) throws Exception {
        require(context,args[0]);int size=Integer.parseInt(args[3]);if(size<=0)throw new IllegalArgumentException("Positive page size required");
        return readPage(client(context),recordClass(args[0]),range(args[1],args[2]),size,args[4].isEmpty()?null:args[4]);
    }
    private static <T extends Record> String readPage(HealthConnectClient client,Class<T> type,TimeRangeFilter range,int pageSize,String token) throws Exception {
        KClass<T> recordType=JvmClassMappingKt.getKotlinClass(type);
        ReadRecordsRequest<T> request=new ReadRecordsRequest<>(recordType,range,Collections.emptySet(),true,pageSize,token);
        ReadRecordsResponse<T> response=HealthConnectSupport.<ReadRecordsResponse<T>>await(c->client.readRecords(request,c));
        JSONArray rows=new JSONArray();for(T record:response.getRecords())rows.put(encodeRecord(record));
        String next=response.getPageToken();
        return new JSONObject().put("records",rows).put("next_page_token",next==null || next.isEmpty()?JSONObject.NULL:next).toString();
    }
    static String aggregateSteps(Context context,String start,String end) throws Exception {
        require(context,"steps");
        AggregateRequest request=new AggregateRequest(Collections.singleton(StepsRecord.COUNT_TOTAL),range(start,end),Collections.emptySet());
        androidx.health.connect.client.aggregate.AggregationResult result=HealthConnectSupport.<androidx.health.connect.client.aggregate.AggregationResult>await(c->client(context).aggregate(request,c));
        Long value=result.get(StepsRecord.COUNT_TOTAL);return value==null?null:Long.toString(value);
    }
    static AggregateMetric<?> amountMetric(String kind){
        switch(kind){case "distance":return DistanceRecord.DISTANCE_TOTAL;
        case "active_calories":return ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL;
        case "total_calories":return TotalCaloriesBurnedRecord.ENERGY_TOTAL;
        default:throw new IllegalArgumentException("Unsupported health amount aggregate");}
    }
    static Double amount(AggregationResult result,String kind){
        if(kind.equals("distance")){Length value=result.get(DistanceRecord.DISTANCE_TOTAL);return value==null?null:value.getMeters();}
        AggregateMetric<Energy> metric;
        switch(kind){case "active_calories":metric=ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL;break;
        case "total_calories":metric=TotalCaloriesBurnedRecord.ENERGY_TOTAL;break;
        default:throw new IllegalArgumentException("Unsupported health energy aggregate");}
        Energy value=result.get(metric);return value==null?null:value.getKilocalories();
    }
    static String aggregateAmount(Context context,String kind,String start,String end) throws Exception {
        AggregateMetric<?> metric=amountMetric(kind);require(context,kind);
        AggregateRequest request=new AggregateRequest(Collections.singleton(metric),range(start,end),Collections.emptySet());
        AggregationResult result=HealthConnectSupport.<AggregationResult>await(c->client(context).aggregate(request,c));
        Double value=amount(result,kind);return value==null?null:Double.toString(value);
    }
    private static Object optional(Object value){return value==null?JSONObject.NULL:value;}
    private static Object offset(ZoneOffset value){return value==null?JSONObject.NULL:value.getTotalSeconds();}
    static JSONObject encodeRecord(Record record) throws JSONException {
        IntervalRecord interval=(IntervalRecord)record;
        JSONObject json=new JSONObject().put("id",record.getMetadata().getId())
            .put("origin",record.getMetadata().getDataOrigin().getPackageName())
            .put("last_modified_ms",Long.toString(record.getMetadata().getLastModifiedTime().toEpochMilli()))
            .put("start_ms",Long.toString(interval.getStartTime().toEpochMilli())).put("end_ms",Long.toString(interval.getEndTime().toEpochMilli()))
            .put("start_offset_s",offset(interval.getStartZoneOffset())).put("end_offset_s",offset(interval.getEndZoneOffset()));
        if(record instanceof StepsRecord)json.put("count",Long.toString(((StepsRecord)record).getCount()));
        else if(record instanceof HeartRateRecord){JSONArray samples=new JSONArray();for(HeartRateRecord.Sample s:((HeartRateRecord)record).getSamples())samples.put(new JSONObject().put("at_ms",Long.toString(s.getTime().toEpochMilli())).put("bpm",Long.toString(s.getBeatsPerMinute())));json.put("samples",samples);}
        else if(record instanceof SleepSessionRecord){SleepSessionRecord sleep=(SleepSessionRecord)record;JSONArray stages=new JSONArray();
            for(SleepSessionRecord.Stage s:sleep.getStages())stages.put(new JSONObject().put("start_ms",Long.toString(s.getStartTime().toEpochMilli())).put("end_ms",Long.toString(s.getEndTime().toEpochMilli())).put("stage",s.getStage()));
            json.put("title",optional(sleep.getTitle())).put("notes",optional(sleep.getNotes())).put("stages",stages);
        }else if(record instanceof ExerciseSessionRecord){ExerciseSessionRecord exercise=(ExerciseSessionRecord)record;
            json.put("exercise_type",exercise.getExerciseType()).put("title",optional(exercise.getTitle())).put("notes",optional(exercise.getNotes()));}
        return json;
    }
}
