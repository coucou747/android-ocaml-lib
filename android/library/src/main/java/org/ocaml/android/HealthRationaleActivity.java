package org.ocaml.android;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;

/** The consuming application supplies both texts as manifest string resources. */
public final class HealthRationaleActivity extends Activity {
    static CharSequence[] texts(Context context) throws Exception {
        Bundle data=context.getPackageManager().getApplicationInfo(context.getPackageName(),PackageManager.GET_META_DATA).metaData;
        int title=data==null?0:data.getInt("android.ocaml.health.rationale_title");
        int text=data==null?0:data.getInt("android.ocaml.health.rationale_text");
        if(title==0 || text==0)throw new IllegalArgumentException("Configure android.ocaml.health.rationale_title and rationale_text string resources");
        CharSequence a=context.getText(title),b=context.getText(text);
        if(a.length()==0 || b.length()==0)throw new IllegalArgumentException("Health rationale texts must not be empty");
        return new CharSequence[]{a,b};
    }
    @Override public void onCreate(Bundle state){super.onCreate(state);try{
        CharSequence[] texts=texts(this);setTitle(texts[0]);LinearLayout layout=new LinearLayout(this);layout.setOrientation(LinearLayout.VERTICAL);
        TextView text=new TextView(this);text.setText(texts[1]);layout.addView(text);
        Button close=new Button(this);close.setText(android.R.string.ok);close.setOnClickListener(v->finish());layout.addView(close);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);setContentView(scroll);
    }catch(Exception e){android.util.Log.e("OCaml","Health rationale configuration missing",e);finish();}}
}
