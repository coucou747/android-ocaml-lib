package org.ocaml.android;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import java.io.*;
import java.util.*;
import org.json.*;

/** Native presentation, immutable intent identities, persisted claim and image bounds. */
public final class AlarmPresentationChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static JSONObject model(String id,String layout) throws Exception {
        JSONArray actions=new JSONArray();String[] roles={"primary","secondary","destructive"};
        for(int i=0;i<roles.length;i++)actions.put(new JSONObject().put("id",i==0?"show":"action-"+i).put("label","Action "+i).put("role",roles[i]).put("handler","handler-"+i).put("payload","payload-"+i));
        return new JSONObject().put("id",id).put("notification_id",41).put("title","Title").put("subtitle","Subtitle").put("message","Message")
            .put("media",JSONObject.NULL).put("actions",actions).put("layout",layout);
    }
    public static void run(Activity owner) throws Exception {
        owner.setTheme(com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
        String id="alarm-check-"+UUID.randomUUID();AlarmPresentation p=new AlarmPresentation(model(id,"standard").toString());
        try{
            String token=AlarmDelivery.activate(owner,p);
            check(token.equals(AlarmDelivery.activate(owner,p)),"Identical screen/notification share a claim");
            Notification notification=AlarmScreenActivity.notification(owner,"alarm-check",android.R.drawable.ic_lock_idle_alarm,true,p,token);
            check(notification.actions.length==3 && "Subtitle".contentEquals(notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)),"Notification uses configured actions and subtitle");
            Set<PendingIntent> intents=new HashSet<>();intents.add(notification.contentIntent);
            for(int i=0;i<p.actions.size();i++){
                AlarmPresentation.Action action=p.actions.get(i);intents.add(notification.actions[i].actionIntent);
                check(action.label.contentEquals(notification.actions[i].title),"Notification action label");
                Intent intent=AlarmScreenActivity.intent(owner,p,token,action);
                check(action.id.equals(intent.getStringExtra("alarm_action")),"Intent carries action identity");
                check(new AlarmPresentation(intent.getStringExtra("alarm_presentation")).action(action.id).payload.equals(action.payload),"Cold intent retains named handler and per-action payload");
                check(notification.actions[i].actionIntent.equals(AlarmScreenActivity.pending(owner,p,token,action)),"Notification and screen target the same action");
            }
            check(intents.size()==4,"Content and all action PendingIntents have distinct identities, even for an action named show");
            check(AlarmDelivery.claim(owner,id,token),"First action claims presentation");
            Context recreated=owner.createPackageContext(owner.getPackageName(),0);
            check(!AlarmDelivery.claim(recreated,id,token) && !AlarmDelivery.active(recreated,id,token),"Duplicate action suppressed using persisted state");
            String next=AlarmDelivery.activate(owner,p);check(!next.equals(token),"Explicit publication re-arms a handled presentation");
            AlarmPresentation updated=new AlarmPresentation(model(id,"expanded").toString());
            String replacement=AlarmDelivery.activate(owner,updated);
            check(!replacement.equals(next) && !AlarmDelivery.claim(owner,id,next),"Old notification cannot invoke a replaced presentation");
            AlarmDelivery.dismiss(owner,id);check(!AlarmDelivery.claim(owner,id,replacement),"Dismissal invalidates pending actions");
            for(String layout:new String[]{"compact","standard","expanded"}){
                AlarmPresentation model=new AlarmPresentation(model(id,layout).toString());List<String> selected=new ArrayList<>();
                AlarmScreenView view=new AlarmScreenView(owner,model,selected::add);
                try{
                    check(view.root.getTag(R.id.ocaml_system_bar_insets)!=null && view.root.getPaddingLeft()>0,"Screen has insets, scrolling and readable margins");
                    LinearLayout body=(LinearLayout)view.root.getChildAt(0),actions=(LinearLayout)body.getChildAt(body.getChildCount()-1);
                    check(actions.getChildCount()==3 && actions.getOrientation()==(layout.equals("compact")?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL),"Configured action count and layout");
                    MaterialButton button=(MaterialButton)actions.getChildAt(2);
                    check(button.getMinHeight()>=owner.getResources().getDimensionPixelSize(R.dimen.ocaml_alarm_touch_target),"Accessible button height");
                    check(button.getBackgroundTintList().getDefaultColor()==MaterialColors.getColor(button,androidx.appcompat.R.attr.colorError),"Destructive role uses host error color");
                    button.performClick();check(selected.equals(Collections.singletonList("action-2")),"Buttons dispatch their action ids");
                }finally{view.close();view.close();}
            }
            images(owner);
        }finally{AlarmDelivery.dismiss(owner,id);}
    }
    private static void images(Context owner) throws Exception {
        File photo=File.createTempFile("alarm-check-",".jpg",owner.getCacheDir());
        try{
            Bitmap source=Bitmap.createBitmap(40,20,Bitmap.Config.ARGB_8888);
            try(OutputStream out=new FileOutputStream(photo)){check(source.compress(Bitmap.CompressFormat.JPEG,100,out),"JPEG fixture");}finally{source.recycle();}
            ExifInterface exif=new ExifInterface(photo.getAbsolutePath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));exif.saveAttributes();
            Bitmap preview=UiSupport.image(owner,Uri.fromFile(photo).toString(),10,10);
            try{check(preview.getWidth()==5 && preview.getHeight()==10,"Bounded preview respects EXIF orientation");}finally{preview.recycle();}
        }finally{photo.delete();}
    }
    public static void replacement(AlarmScreenActivity screen) throws Exception {
        android.view.ViewGroup content=screen.findViewById(android.R.id.content);
        LinearLayout body=(LinearLayout)((ScrollView)content.getChildAt(0)).getChildAt(0);
        LinearLayout actions=(LinearLayout)body.getChildAt(body.getChildCount()-1);
        View oldButton=actions.getChildAt(0);
        JSONObject changed=new JSONObject(screen.getIntent().getStringExtra("alarm_presentation"));changed.put("title","Updated alarm");
        AlarmPresentation next=new AlarmPresentation(changed.toString());String token=AlarmDelivery.activate(screen,next);
        screen.onNewIntent(AlarmScreenActivity.intent(screen,next,token,null));
        oldButton.performClick();
        check(AlarmDelivery.active(screen,next.id,token),"A detached button cannot claim the replacement presentation");
    }
}
