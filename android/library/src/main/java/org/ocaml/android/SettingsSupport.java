package org.ocaml.android;

import android.app.*;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import java.util.*;
import org.json.*;

/** UI only: OCaml owns validation and persistence. All methods run on the UI thread. */
final class SettingsSupport extends ScrollView {
    private final Activity owner;
    private final String subscription;
    private final List<Row> rows=new ArrayList<>();
    private boolean active=true;
    private static final class Row {
        JSONObject spec;View view;TextView summary;Switch toggle;AlertDialog dialog;EditText edit;
        boolean pending,updating;
    }
    SettingsSupport(Activity owner,String subscription,String json) throws JSONException {
        super(owner);this.owner=owner;this.subscription=subscription;
        LinearLayout layout=new LinearLayout(owner);layout.setOrientation(LinearLayout.VERTICAL);addView(layout);
        JSONArray specs=new JSONArray(json);
        for(int i=0;i<specs.length();i++){
            final int index=i;Row row=new Row();row.spec=specs.getJSONObject(i);rows.add(row);
            String kind=row.spec.getString("kind"),title=row.spec.getString("title");
            if(kind.equals("section")){
                TextView heading=new TextView(owner);heading.setText(title);heading.setTypeface(null,Typeface.BOLD);row.view=heading;
            }else if(kind.equals("toggle")){
                LinearLayout group=new LinearLayout(owner);group.setOrientation(LinearLayout.VERTICAL);
                row.toggle=new Switch(owner);row.toggle.setText(title);group.addView(row.toggle);
                row.summary=new TextView(owner);group.addView(row.summary);row.view=group;
                row.toggle.setOnCheckedChangeListener((button,checked)->{if(active && !row.updating && !row.pending)send(index,Boolean.toString(checked));});
            }else {
                Button button=new Button(owner);button.setText(title);row.view=button;
                LinearLayout group=new LinearLayout(owner);group.setOrientation(LinearLayout.VERTICAL);group.addView(button);
                row.summary=new TextView(owner);group.addView(row.summary);layout.addView(group);
                button.setOnClickListener(v->{if(!active || row.pending)return;
                    if(kind.equals("action"))send(index,"");else open(index);
                });
            }
            if(kind.equals("section") || kind.equals("toggle"))layout.addView(row.view);
            render(row);
        }
    }
    private void render(Row row) throws JSONException {
        String kind=row.spec.getString("kind");
        if(row.toggle!=null){row.updating=true;try{row.toggle.setChecked(Boolean.parseBoolean(row.spec.getString("value")));}finally{row.updating=false;}}
        if(row.summary!=null){
            String description=row.spec.isNull("description")?"":row.spec.getString("description");
            String value="";
            if(kind.equals("text") || kind.equals("integer"))value=row.spec.getString("value");
            if(kind.equals("choice")){JSONArray options=row.spec.getJSONArray("options");for(int i=0;i<options.length();i++){
                JSONArray option=options.getJSONArray(i);if(option.getString(0).equals(row.spec.getString("value")))value=option.getString(1);
            }}
            row.summary.setText(description.isEmpty()?value:value.isEmpty()?description:description+"\n"+value);
        }
    }
    private void send(int index,String value){
        Row row=rows.get(index);row.pending=true;row.view.setEnabled(false);if(row.toggle!=null)row.toggle.setEnabled(false);
        if(row.dialog!=null){row.dialog.setCancelable(false);Button positive=row.dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(positive!=null)positive.setEnabled(false);row.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);}
        Bridge.emit(subscription,Integer.toString(index),value);
    }
    private void open(int index){
        Row row=rows.get(index);if(row.dialog!=null)return;
        try{
            AlertDialog.Builder builder=new AlertDialog.Builder(owner).setTitle(row.spec.getString("title"))
                .setNegativeButton(android.R.string.cancel,(d,w)->{});
            if(row.spec.getString("kind").equals("choice")){
                JSONArray options=row.spec.getJSONArray("options");String[] labels=new String[options.length()];int selected=-1;
                for(int i=0;i<labels.length;i++){JSONArray option=options.getJSONArray(i);labels[i]=option.getString(1);if(option.getString(0).equals(row.spec.getString("value")))selected=i;}
                builder.setSingleChoiceItems(labels,selected,(dialog,which)->{
                    if(row.pending)return;
                    try{send(index,options.getJSONArray(which).getString(0));}catch(JSONException e){throw new IllegalStateException(e);}
                });
            }else{
                row.edit=new EditText(owner);
                boolean integer=row.spec.getString("kind").equals("integer");
                boolean multiline=!integer && row.spec.getBoolean("multiline");
                row.edit.setInputType(integer?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED:
                    InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));
                row.edit.setSingleLine(!multiline);row.edit.setText(row.spec.getString("value"));
                builder.setView(row.edit).setPositiveButton(android.R.string.ok,null);
            }
            row.dialog=builder.create();row.dialog.setOnDismissListener(d->{row.dialog=null;row.edit=null;});row.dialog.show();
            if(row.edit!=null)row.dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!row.pending)send(index,row.edit.getText().toString());});
        }catch(JSONException e){throw new IllegalArgumentException("Invalid settings row",e);}
    }
    void result(int index,boolean accepted,String value) throws JSONException {
        if(!active)return;
        Row row=rows.get(index);row.pending=false;row.view.setEnabled(true);if(row.toggle!=null)row.toggle.setEnabled(true);
        if(accepted)row.spec.put("value",value);
        render(row);
        if(row.dialog!=null){
            if(accepted)row.dialog.dismiss();
            else {
                row.dialog.setCancelable(true);
                Button positive=row.dialog.getButton(AlertDialog.BUTTON_POSITIVE);if(positive!=null)positive.setEnabled(true);
                row.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if(row.edit!=null)row.edit.setError(value);
            }
        }
    }
    void update(String json) throws JSONException {
        if(!active)return;JSONArray specs=new JSONArray(json);
        if(specs.length()!=rows.size())throw new IllegalArgumentException("Settings schema changed");
        for(int i=0;i<rows.size();i++){Row row=rows.get(i);row.spec=specs.getJSONObject(i);
            if(row.spec.getString("kind").equals("action")){row.pending=false;row.view.setEnabled(true);}render(row);}
    }
    void close(){
        if(!active)return;active=false;Bridge.emit(subscription,"closed","");for(Row row:rows){row.view.setEnabled(false);if(row.toggle!=null){row.toggle.setOnCheckedChangeListener(null);row.toggle.setEnabled(false);}
            row.view.setOnClickListener(null);if(row.dialog!=null)row.dialog.dismiss();}
    }
}
