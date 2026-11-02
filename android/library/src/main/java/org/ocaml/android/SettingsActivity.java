package org.ocaml.android;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/** System-discoverable app preferences. The schema is registered at each OCaml startup. */
public final class SettingsActivity extends BridgeActivity {
    static String configuredName(Activity owner) throws Exception {
        Bundle metadata=owner.getPackageManager().getApplicationInfo(owner.getPackageName(),PackageManager.GET_META_DATA).metaData;
        String name=metadata==null?null:metadata.getString("android.ocaml.settings");
        if(name==null || name.isEmpty())throw new IllegalArgumentException("Missing android.ocaml.settings application metadata");
        return name;
    }
    static void open(Activity owner) throws Exception {
        configuredName(owner);
        Intent intent=new Intent(owner,SettingsActivity.class).setAction(Intent.ACTION_APPLICATION_PREFERENCES);
        if(intent.resolveActivity(owner.getPackageManager())==null)throw new UnsupportedOperationException("SettingsActivity must be enabled in the client manifest");
        owner.startActivity(intent);
    }
    @Override public void onCreate(Bundle state){
        // Rebuild the OCaml schema/subscriptions; never restore stale native handles.
        super.onCreate(null);
        try{
            setTitle(getApplicationInfo().loadLabel(getPackageManager()));
            Bridge.deliver(this,"android.settings."+configuredName(this),bridgeId).whenComplete((result,error)->{
                if(error!=null || !"success".equals(result))runOnUiThread(()->{
                    android.util.Log.e("OCamlSettings","Preferences schema could not be opened",error);finish();
                });
            });
        }catch(Exception error){android.util.Log.e("OCamlSettings","Invalid preferences configuration",error);finish();}
    }
    @Override public void onResume(){super.onResume();SettingsSupport.refreshFor(this);}
}
