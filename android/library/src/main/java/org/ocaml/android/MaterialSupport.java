package org.ocaml.android;

import android.app.Activity;
import android.content.Context;
import android.view.*;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.materialswitch.MaterialSwitch;
import org.json.*;
import java.util.*;

final class MaterialSupport {
    private MaterialSupport(){}
    static MaterialToolbar toolbar(Activity activity,String title,String subtitle,String icon,String description,String actions) throws JSONException {
        MaterialToolbar toolbar=new MaterialToolbar(UiComponents.theme(activity));toolbar.setTitle(title);toolbar.setSubtitle(subtitle);
        if(!icon.isEmpty()){int id=Integer.parseInt(icon);if(id<=0 || description.isEmpty())throw new IllegalArgumentException("Navigation icon requires resource and description");toolbar.setNavigationIcon(id);toolbar.setNavigationContentDescription(description);}
        actions(toolbar,actions);return toolbar;
    }
    static void actions(MaterialToolbar toolbar,String json) throws JSONException {
        JSONArray actions=new JSONArray(json);Set<Integer> ids=new HashSet<>();
        // Validate before replacing a live menu.
        for(int i=0;i<actions.length();i++){
            JSONObject action=actions.getJSONObject(i);int id=action.getInt("id");
            if(id<=0 || !ids.add(id) || action.getString("title").isEmpty())throw new IllegalArgumentException("Invalid toolbar menu");
            if(!action.isNull("icon"))toolbar.getContext().getDrawable(action.getInt("icon"));
            String display=action.getString("display");if(!display.equals("always") && !display.equals("if_room") && !display.equals("never"))throw new IllegalArgumentException("Toolbar action display");
        }
        toolbar.getMenu().clear();
        for(int i=0;i<actions.length();i++){
            JSONObject action=actions.getJSONObject(i);MenuItem item=toolbar.getMenu().add(Menu.NONE,action.getInt("id"),Menu.NONE,action.getString("title"));
            if(!action.isNull("icon"))item.setIcon(action.getInt("icon"));
            switch(action.getString("display")){case "always":item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);break;case "if_room":item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);break;default:item.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);}
        }
    }
    static MaterialCardView card(Activity activity,String padding) throws JSONException {
        MaterialCardView card=new MaterialCardView(UiComponents.theme(activity));
        if(!padding.isEmpty()){JSONArray a=new JSONArray(padding);Context c=card.getContext();card.setContentPadding(UiComponents.dp(c,a.getInt(0)),UiComponents.dp(c,a.getInt(1)),UiComponents.dp(c,a.getInt(2)),UiComponents.dp(c,a.getInt(3)));}return card;
    }
    static final class Navigation extends BottomNavigationView {
        boolean muted;
        Navigation(Activity activity,String json,int selected) throws JSONException {
            super(UiComponents.theme(activity));JSONArray items=new JSONArray(json);Set<Integer> ids=new HashSet<>();
            if(items.length()==0 || items.length()>getMaxItemCount())throw new IllegalArgumentException("Unsupported navigation item count");
            for(int i=0;i<items.length();i++){
                JSONObject item=items.getJSONObject(i);int id=item.getInt("id");
                if(id<=0 || !ids.add(id) || item.getString("title").isEmpty() || item.getInt("icon")<=0)throw new IllegalArgumentException("Invalid navigation item");
                getMenu().add(Menu.NONE,id,Menu.NONE,item.getString("title")).setIcon(item.getInt("icon"));
            }
            select(selected);
        }
        void select(int id){
            MenuItem item=getMenu().findItem(id);if(item==null)throw new IllegalArgumentException("Unknown navigation ID");
            muted=true;try{setSelectedItemId(id);}finally{muted=false;}
        }
    }
    static final class Check extends MaterialCheckBox {
        boolean muted;
        Check(Activity activity,String label,int state){super(UiComponents.theme(activity));setText(label);state(state);}
        void state(int state){if(state<0 || state>2)throw new IllegalArgumentException("Checkbox state");muted=true;try{setCheckedState(state);}finally{muted=false;}}
    }
    static final class Toggle extends MaterialSwitch {
        boolean muted;
        Toggle(Activity activity,String label,boolean checked){super(UiComponents.theme(activity));setText(label);setChecked(checked);}
        void checked(boolean checked){muted=true;try{setChecked(checked);}finally{muted=false;}}
    }
}
