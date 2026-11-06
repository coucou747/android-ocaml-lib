package org.ocaml.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.json.*;

/** One persisted claim shared by all actions, screen instances and process restarts. */
final class AlarmDelivery {
    private AlarmDelivery() {}
    private static SharedPreferences store(Context c){return c.getSharedPreferences("org.ocaml.android.alarm.presentations",Context.MODE_PRIVATE);}
    private static String digest(String value) throws Exception {
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    private static JSONObject state(Context c,String id) throws JSONException {
        String value=store(c).getString(id,null);return value==null?null:new JSONObject(value);
    }
    static synchronized String activate(Context c,AlarmPresentation p) throws Exception {
        JSONObject previous=state(c,p.id);String fingerprint=digest(p.source);
        if(previous!=null && fingerprint.equals(previous.getString("fingerprint")))return previous.getString("token");
        String token=UUID.randomUUID().toString();JSONObject next=new JSONObject().put("fingerprint",fingerprint).put("token",token);
        if(!store(c).edit().putString(p.id,next.toString()).commit())throw new IllegalStateException("Cannot persist alarm presentation");
        return token;
    }
    static synchronized boolean active(Context c,String id,String token) throws JSONException {
        JSONObject state=state(c,id);return state!=null && token.equals(state.getString("token"));
    }
    static synchronized boolean claim(Context c,String id,String token) throws JSONException {
        JSONObject state=state(c,id);
        if(state==null || !token.equals(state.getString("token")))return false;
        // Absence invalidates this token even after a restart. A later explicit
        // publication generates a new token, so completed alarms need no ledger entry.
        if(!store(c).edit().remove(id).commit())throw new IllegalStateException("Cannot persist alarm action claim");
        return true;
    }
    static synchronized void dismiss(Context c,String id){
        if(!store(c).edit().remove(id).commit())throw new IllegalStateException("Cannot dismiss alarm presentation");
    }
}
