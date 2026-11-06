package org.ocaml.android;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.*;
import androidx.preference.*;
import java.util.*;
import org.json.*;

/** AndroidX owns rows, categories, switches, selection and editor presentation.
 * OCaml owns validation and persistence, acknowledged asynchronously. */
final class SettingsSupport extends FrameLayout {
    private final FragmentActivity owner;
    private final String subscription;
    private JSONArray specs;
    private boolean active=true;
    final Screen screen=new Screen();
    private final List<Row> rows=new ArrayList<>();
    private static final Set<SettingsSupport> pages=Collections.newSetFromMap(new WeakHashMap<>());
    private static final class Row {
        JSONObject spec;Preference preference;PreferenceDialogFragmentCompat dialog;boolean pending;
    }
    SettingsSupport(Activity activity,String subscription,String json) throws JSONException {
        super(activity);
        if(!(activity instanceof FragmentActivity))throw new IllegalArgumentException("Preferences require a FragmentActivity");
        owner=(FragmentActivity)activity;this.subscription=subscription;specs=new JSONArray(json);
        setId(View.generateViewId());screen.support=this;pages.add(this);
    }
    @Override protected void onAttachedToWindow(){super.onAttachedToWindow();post(this::mount);}
    void mount(){
        if(active && !screen.isAdded() && !owner.isDestroyed() && !owner.getSupportFragmentManager().isStateSaved())
            owner.getSupportFragmentManager().beginTransaction().add(getId(),screen,"ocaml.preferences."+getId()).commitNow();
    }
    static void refreshFor(Activity activity){
        for(SettingsSupport page:new ArrayList<>(pages))if(page.owner==activity && page.active)Bridge.emit(page.subscription,"refresh","");
    }
    private void populate(Screen fragment){
        try{
            PreferenceScreen root=fragment.getPreferenceManager().createPreferenceScreen(owner);fragment.setPreferenceScreen(root);
            PreferenceGroup group=root;rows.clear();
            for(int index=0;index<specs.length();index++){
                final int i=index;Row row=new Row();row.spec=specs.getJSONObject(i);rows.add(row);
                String kind=row.spec.getString("kind");Preference pref;
                switch(kind){
                case "section":pref=new PreferenceCategory(fragment.requireContext());group=root;break;
                case "toggle":pref=new SwitchPreferenceCompat(fragment.requireContext());break;
                case "text":case "integer":pref=new EditTextPreference(fragment.requireContext());break;
                case "choice":{
                    ListPreference list=new ListPreference(fragment.requireContext());JSONArray options=row.spec.getJSONArray("options");
                    String[] entries=new String[options.length()],values=new String[options.length()];
                    for(int j=0;j<entries.length;j++){JSONArray option=options.getJSONArray(j);values[j]=option.getString(0);entries[j]=option.getString(1);}
                    list.setEntries(entries);list.setEntryValues(values);pref=list;break;
                }
                default:pref=new Preference(fragment.requireContext());
                }
                row.preference=pref;pref.setKey(Integer.toString(i));pref.setTitle(row.spec.getString("title"));pref.setPersistent(false);
                if(pref instanceof DialogPreference){((DialogPreference)pref).setDialogTitle(pref.getTitle());}
                if(kind.equals("info")){pref.setSelectable(false);}
                else if(kind.equals("action"))pref.setOnPreferenceClickListener(p->{submit(i,"");return true;});
                else pref.setOnPreferenceChangeListener((p,value)->{submit(i,String.valueOf(value));return false;});
                group.addPreference(pref);if(pref instanceof PreferenceCategory)group=(PreferenceGroup)pref;
                render(row);
            }
        }catch(JSONException error){throw new IllegalArgumentException("Invalid preference schema",error);}
    }
    private void render(Row row) throws JSONException {
        Preference p=row.preference;String kind=row.spec.getString("kind"),value=row.spec.optString("value","");
        if(p instanceof SwitchPreferenceCompat)((SwitchPreferenceCompat)p).setChecked(Boolean.parseBoolean(value));
        if(p instanceof EditTextPreference)((EditTextPreference)p).setText(value);
        if(p instanceof ListPreference)((ListPreference)p).setValue(value);
        String description=row.spec.isNull("description")?"":row.spec.getString("description");
        CharSequence summary=(p instanceof ListPreference)?((ListPreference)p).getEntry():
            (p instanceof EditTextPreference)?value:"";
        p.setSummary(description.isEmpty()?summary:summary==null || summary.length()==0?description:description+"\n"+summary);
    }
    private void submit(int index,String value){
        Row row=rows.get(index);if(!active || row.pending || row.spec.optString("kind").equals("info") || row.spec.optString("kind").equals("section"))return;
        row.pending=true;row.preference.setEnabled(false);
        if(row.dialog!=null && row.dialog.getDialog()!=null){
            row.dialog.setCancelable(false);AlertDialog d=(AlertDialog)row.dialog.getDialog();
            if(d.getButton(AlertDialog.BUTTON_POSITIVE)!=null)d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            if(d.getButton(AlertDialog.BUTTON_NEGATIVE)!=null)d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        }
        Bridge.emit(subscription,Integer.toString(index),value);
    }
    void result(int index,boolean accepted,String value) throws JSONException {
        if(!active || rows.isEmpty())return;Row row=rows.get(index);row.pending=false;row.preference.setEnabled(true);
        if(accepted)row.spec.put("value",value);render(row);
        if(row.dialog!=null){
            if(accepted)row.dialog.dismissAllowingStateLoss();
            else if(row.dialog.getDialog()!=null){
                row.dialog.setCancelable(true);AlertDialog d=(AlertDialog)row.dialog.getDialog();
                if(d.getButton(AlertDialog.BUTTON_POSITIVE)!=null)d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if(d.getButton(AlertDialog.BUTTON_NEGATIVE)!=null)d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                if(row.dialog instanceof Editor && ((Editor)row.dialog).edit!=null)((Editor)row.dialog).edit.setError(value);
            }
        }
    }
    void update(String json) throws JSONException {
        if(!active)return;JSONArray next=new JSONArray(json);
        if(next.length()!=specs.length())throw new IllegalArgumentException("Preference schema changed");specs=next;
        for(int i=0;i<rows.size();i++){Row row=rows.get(i);row.spec=specs.getJSONObject(i);render(row);}
    }
    void close(){
        if(!active)return;active=false;pages.remove(this);Bridge.emit(subscription,"closed","");
        for(Row row:rows){row.preference.setEnabled(false);row.preference.setOnPreferenceChangeListener(null);row.preference.setOnPreferenceClickListener(null);
            if(row.dialog!=null)row.dialog.dismissAllowingStateLoss();}
        FragmentManager manager=owner.getSupportFragmentManager();
        if(screen.isAdded() && !manager.isDestroyed())manager.beginTransaction().remove(screen).commitAllowingStateLoss();
    }
    public static final class Screen extends PreferenceFragmentCompat {
        SettingsSupport support;
        @Override public void onCreatePreferences(Bundle state,String rootKey){
            if(support!=null)support.populate(this);
            else setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        }
        @Override public void onDisplayPreferenceDialog(Preference preference){
            if(support==null || !support.active)return;
            int index=Integer.parseInt(preference.getKey());Row row=support.rows.get(index);if(row.pending || row.dialog!=null)return;
            PreferenceDialogFragmentCompat dialog=preference instanceof ListPreference?new Chooser():new Editor();
            Bundle args=new Bundle();args.putString("key",preference.getKey());dialog.setArguments(args);dialog.setTargetFragment(this,0);
            row.dialog=dialog;dialog.show(getParentFragmentManager(),"ocaml.preference.dialog");
        }
    }
    public static final class Editor extends EditTextPreferenceDialogFragmentCompat {
        EditText edit;
        private Screen screen(){return (Screen)getTargetFragment();}
        private int index(){return Integer.parseInt(requireArguments().getString("key"));}
        @Override protected void onBindDialogView(View view){
            super.onBindDialogView(view);edit=view.findViewById(android.R.id.edit);
            try{JSONObject spec=screen().support.rows.get(index()).spec;
                boolean integer=spec.getString("kind").equals("integer"),multiline=!integer && spec.getBoolean("multiline");
                edit.setInputType(integer?InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_SIGNED:InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0));edit.setSingleLine(!multiline);
            }catch(JSONException e){throw new IllegalArgumentException(e);}
        }
        @Override public void onStart(){super.onStart();AlertDialog d=(AlertDialog)getDialog();
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->screen().support.submit(index(),edit.getText().toString()));}
        @Override public void onDialogClosed(boolean positiveResult){}
        @Override public void onDismiss(android.content.DialogInterface dialog){super.onDismiss(dialog);
            if(screen().support!=null)screen().support.rows.get(index()).dialog=null;}
    }
    public static final class Chooser extends ListPreferenceDialogFragmentCompat {
        private Screen screen(){return (Screen)getTargetFragment();}
        private int index(){return Integer.parseInt(requireArguments().getString("key"));}
        @Override protected void onPrepareDialogBuilder(AlertDialog.Builder builder){
            super.onPrepareDialogBuilder(builder);ListPreference pref=(ListPreference)getPreference();
            builder.setSingleChoiceItems(pref.getEntries(),pref.findIndexOfValue(pref.getValue()),(dialog,which)->
                screen().support.submit(index(),pref.getEntryValues()[which].toString()));
        }
        @Override public void onDialogClosed(boolean positiveResult){}
        @Override public void onDismiss(android.content.DialogInterface dialog){super.onDismiss(dialog);
            if(screen().support!=null)screen().support.rows.get(index()).dialog=null;}
    }
}
