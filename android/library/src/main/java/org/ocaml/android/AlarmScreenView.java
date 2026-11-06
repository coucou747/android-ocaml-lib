package org.ocaml.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.os.*;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.core.widget.TextViewCompat;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.textview.MaterialTextView;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Material composition and bounded local preview, independent of alarm delivery. */
final class AlarmScreenView implements AutoCloseable {
    private static final ExecutorService images=Executors.newSingleThreadExecutor();
    final ScrollView root;
    private Future<?> loading;
    private ImageView image;
    private Bitmap bitmap;
    private ViewTreeObserver.OnGlobalLayoutListener awaitingLayout;
    private boolean closed;
    static boolean material(Context c,int attribute){TypedValue v=new TypedValue();return c.getTheme().resolveAttribute(attribute,v,true) && v.data!=0;}
    AlarmScreenView(Activity activity,AlarmPresentation p,Consumer<String> choose){
        boolean compact=p.layout.equals("compact"),expanded=p.layout.equals("expanded");
        int padding=activity.getResources().getDimensionPixelSize(compact?R.dimen.ocaml_alarm_compact_padding:expanded?R.dimen.ocaml_alarm_expanded_padding:R.dimen.ocaml_alarm_standard_padding);
        int gap=activity.getResources().getDimensionPixelSize(compact?R.dimen.ocaml_alarm_compact_gap:expanded?R.dimen.ocaml_alarm_expanded_gap:R.dimen.ocaml_alarm_standard_gap);
        boolean m3=material(activity,com.google.android.material.R.attr.isMaterial3Theme);
        root=new ScrollView(activity);root.setFillViewport(true);root.setBackgroundColor(MaterialColors.getColor(root,com.google.android.material.R.attr.colorSurface));
        root.setPadding(padding,padding,padding,padding);
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);if(expanded)body.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(body,new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
        if(!p.title.isEmpty()){
            TextView title=text(activity,p.title,m3?(compact?com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall:expanded?com.google.android.material.R.style.TextAppearance_Material3_HeadlineLarge:com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium):com.google.android.material.R.style.TextAppearance_MaterialComponents_Headline5);
            androidx.core.view.ViewCompat.setAccessibilityHeading(title,true);add(body,title,gap);
        }
        if(p.subtitle!=null && !p.subtitle.isEmpty())add(body,text(activity,p.subtitle,m3?com.google.android.material.R.style.TextAppearance_Material3_TitleMedium:com.google.android.material.R.style.TextAppearance_MaterialComponents_Subtitle1),gap);
        if(!p.message.isEmpty())add(body,text(activity,p.message,m3?com.google.android.material.R.style.TextAppearance_Material3_BodyLarge:com.google.android.material.R.style.TextAppearance_MaterialComponents_Body1),gap);
        if(p.media!=null){
            image=new ImageView(activity);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setContentDescription(p.media.description);image.setVisibility(View.GONE);add(body,image,gap);
            Context application=activity.getApplicationContext();
            awaitingLayout=()->{
                if(root.getWidth()<=0 || root.getHeight()<=0)return;
                root.getViewTreeObserver().removeOnGlobalLayoutListener(awaitingLayout);awaitingLayout=null;
                int width=Math.max(1,root.getWidth()-root.getPaddingLeft()-root.getPaddingRight());
                int height=Math.max(1,root.getHeight()-root.getPaddingTop()-root.getPaddingBottom());
                if(p.media.height!=null)height=Math.min(height,Math.max(1,Math.round(p.media.height*activity.getResources().getDisplayMetrics().density)));
                image.setMaxHeight(height);load(application,p.media.uri,width,height);
            };
            root.getViewTreeObserver().addOnGlobalLayoutListener(awaitingLayout);
        }
        LinearLayout actions=new LinearLayout(activity);actions.setOrientation(compact?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);actions.setBaselineAligned(false);
        for(AlarmPresentation.Action action:p.actions){
            MaterialButton button=button(activity,action,m3);button.setOnClickListener(v->choose.accept(action.id));
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(compact?0:ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT,compact?1:0);
            if(actions.getChildCount()>0){if(compact)params.setMarginStart(gap);else params.topMargin=gap;}
            actions.addView(button,params);
        }
        add(body,actions,gap);SystemBarInsets.applyAll(root);
    }
    private static TextView text(Context context,String value,int appearance){MaterialTextView view=new MaterialTextView(context);TextViewCompat.setTextAppearance(view,appearance);view.setText(value);return view;}
    private static void add(LinearLayout parent,View child,int gap){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);if(parent.getChildCount()>0)params.topMargin=gap;parent.addView(child,params);}
    private static MaterialButton button(Activity activity,AlarmPresentation.Action action,boolean m3){
        int style;
        if(action.role.equals("neutral"))style=m3?R.style.OcamlAlarmNeutralButtonTheme:R.style.OcamlAlarmNeutralM2Theme;
        else if(action.role.equals("secondary"))style=m3?R.style.OcamlSecondaryButtonTheme:R.style.OcamlAlarmSecondaryM2Theme;
        else style=m3?R.style.OcamlPrimaryButtonTheme:R.style.OcamlAlarmPrimaryM2Theme;
        MaterialButton view=new MaterialButton(new ContextThemeWrapper(activity,style),null,com.google.android.material.R.attr.materialButtonStyle);
        view.setText(action.label);view.setSingleLine(false);view.setMinHeight(activity.getResources().getDimensionPixelSize(R.dimen.ocaml_alarm_touch_target));
        if(action.role.equals("destructive")){
            view.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(view,androidx.appcompat.R.attr.colorError)));
            view.setTextColor(MaterialColors.getColor(view,com.google.android.material.R.attr.colorOnError));
        }
        return view;
    }
    private void load(Context context,String uri,int width,int height){
        loading=images.submit(()->{
            Bitmap decoded;
            try{decoded=UiSupport.image(context,uri,width,height);}catch(Exception unreadable){return;}
            if(Thread.currentThread().isInterrupted()){decoded.recycle();return;}
            new Handler(Looper.getMainLooper()).post(()->{
                if(closed){decoded.recycle();return;}
                bitmap=decoded;image.setImageBitmap(decoded);image.setVisibility(View.VISIBLE);
            });
        });
    }
    @Override public void close(){
        closed=true;if(loading!=null)loading.cancel(true);
        if(awaitingLayout!=null && root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnGlobalLayoutListener(awaitingLayout);
        if(image!=null)image.setImageDrawable(null);if(bitmap!=null){bitmap.recycle();bitmap=null;}
        SystemBarInsets.clear(root);
    }
}
