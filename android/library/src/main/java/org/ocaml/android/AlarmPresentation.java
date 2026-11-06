package org.ocaml.android;

import android.net.Uri;
import org.json.*;
import java.util.*;

/** Data contract only; OCaml supplies the model and Android rechecks intent data. */
final class AlarmPresentation {
    static final class Action {
        final String id,label,role,handler,payload;
        Action(JSONObject a) throws JSONException {
            id=a.getString("id");label=a.getString("label");role=a.getString("role");handler=a.getString("handler");payload=a.getString("payload");
            if(!identifier(id) || label.trim().isEmpty() || label.indexOf(0)>=0 || !identifier(handler) ||
                !Arrays.asList("primary","secondary","neutral","destructive").contains(role))throw new IllegalArgumentException("Invalid alarm action");
        }
    }
    static final class Media {
        final String uri,description;final Integer height;
        Media(JSONObject m) throws JSONException {
            uri=m.getString("uri");description=m.getString("content_description");
            height=m.isNull("max_height_dp")?null:integer(m,"max_height_dp");
            String scheme=Uri.parse(uri).getScheme();
            if(!Arrays.asList("content","file","android.resource").contains(scheme) || uri.indexOf(0)>=0 ||
                description.trim().isEmpty() || description.indexOf(0)>=0 || height!=null && height<=0)
                throw new IllegalArgumentException("Invalid local alarm media");
        }
    }
    final String source,id,title,subtitle,message,layout;
    final int notificationId;
    final Media media;
    final List<Action> actions;
    static boolean identifier(String value){
        if(value.trim().isEmpty())return false;
        for(int i=0;i<value.length();i++)if(value.charAt(i)<32 || value.charAt(i)==127)return false;
        return true;
    }
    private static int integer(JSONObject object,String key) throws JSONException {
        long value=object.getLong(key);if(value<Integer.MIN_VALUE || value>Integer.MAX_VALUE)throw new IllegalArgumentException("Android integer overflow");return (int)value;
    }
    AlarmPresentation(String json) throws JSONException {
        source=json;JSONObject p=new JSONObject(json);id=p.getString("id");notificationId=integer(p,"notification_id");
        title=p.getString("title");subtitle=p.isNull("subtitle")?null:p.getString("subtitle");message=p.getString("message");layout=p.getString("layout");
        if(!identifier(id) || !Arrays.asList("compact","standard","expanded").contains(layout))throw new IllegalArgumentException("Invalid alarm presentation");
        media=p.isNull("media")?null:new Media(p.getJSONObject("media"));
        JSONArray list=p.getJSONArray("actions");if(list.length()<1 || list.length()>3)throw new IllegalArgumentException("One to three alarm actions required");
        List<Action> parsed=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<list.length();i++){Action action=new Action(list.getJSONObject(i));if(!ids.add(action.id))throw new IllegalArgumentException("Duplicate alarm action id");parsed.add(action);}
        actions=Collections.unmodifiableList(parsed);
    }
    Action action(String id){for(Action action:actions)if(action.id.equals(id))return action;throw new IllegalArgumentException("Unknown alarm action");}
    static AlarmPresentation legacy(String[] values) throws JSONException {
        if(values.length!=9)throw new IllegalArgumentException("Invalid legacy alarm screen");
        JSONArray actions=new JSONArray();String[] ids={"stop","snooze"},roles={"primary","secondary"};
        for(int i=0;i<2;i++)actions.put(new JSONObject().put("id",ids[i]).put("label",values[4+i]).put("role",roles[i]).put("handler",values[6+i]).put("payload",values[8]));
        return new AlarmPresentation(new JSONObject().put("id",values[0]).put("notification_id",Integer.parseInt(values[1])).put("title",values[2])
            .put("subtitle",JSONObject.NULL).put("message",values[3]).put("media",JSONObject.NULL).put("layout","standard").put("actions",actions).toString());
    }
}
