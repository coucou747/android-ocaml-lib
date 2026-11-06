package org.ocaml.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import androidx.constraintlayout.widget.*;
import com.google.android.material.card.MaterialCardView;
import org.json.*;

/** Adapts native LayoutParams. Measurement, spacing and constraints are SDK-owned. */
final class LayoutSupport {
    private LayoutSupport(){}
    static int gravity(JSONArray a) throws JSONException {
        int horizontal,vertical;
        switch(a.getString(0)){case "start":horizontal=Gravity.START;break;case "center":horizontal=Gravity.CENTER_HORIZONTAL;break;case "end":horizontal=Gravity.END;break;default:throw new IllegalArgumentException("Horizontal alignment");}
        switch(a.getString(1)){case "top":vertical=Gravity.TOP;break;case "middle":vertical=Gravity.CENTER_VERTICAL;break;case "bottom":vertical=Gravity.BOTTOM;break;default:throw new IllegalArgumentException("Vertical alignment");}
        return horizontal|vertical;
    }
    static View container(Activity activity,String kind,int spacing,String alignment) throws JSONException {
        Context context=UiComponents.theme(activity);
        if(kind.equals("stack"))return new Stack(context,alignment.isEmpty()?Gravity.START|Gravity.TOP:gravity(new JSONArray(alignment)));
        if(!kind.equals("row") && !kind.equals("column"))throw new IllegalArgumentException("Unknown container");
        LinearLayout layout=new LinearLayout(context);layout.setOrientation(kind.equals("row")?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);
        layout.setGravity(alignment.isEmpty()?Gravity.START|Gravity.TOP:gravity(new JSONArray(alignment)));
        int gap=UiComponents.dp(context,spacing);
        if(gap>0){layout.setDividerDrawable(new ColorDrawable(Color.TRANSPARENT){
            @Override public int getIntrinsicWidth(){return gap;}
            @Override public int getIntrinsicHeight(){return gap;}
        });layout.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);}
        return layout;
    }
    static final class Stack extends FrameLayout {
        final int alignment;Stack(Context context,int alignment){super(context);this.alignment=alignment;}
        @Override protected LayoutParams generateDefaultLayoutParams(){LayoutParams params=super.generateDefaultLayoutParams();params.gravity=alignment;return params;}
    }
    private static JSONObject merge(View view,String json) throws JSONException {
        JSONObject old=(JSONObject)view.getTag(R.id.ocaml_layout_spec);
        JSONObject merged=old==null?new JSONObject():new JSONObject(old.toString()),next=new JSONObject(json);
        java.util.Iterator<String> keys=next.keys();while(keys.hasNext()){String key=keys.next();merged.put(key,next.get(key));}return merged;
    }
    private static int dimension(JSONObject spec,String key,int fallback,Context context) throws JSONException {
        if(!spec.has(key))return fallback;Object value=spec.get(key);
        if(value instanceof Number)return UiComponents.dp(context,spec.getInt(key));
        switch(spec.getString(key)){case "wrap":return ViewGroup.LayoutParams.WRAP_CONTENT;case "match":return ViewGroup.LayoutParams.MATCH_PARENT;default:throw new IllegalArgumentException("Layout dimension");}
    }
    private static ViewGroup.LayoutParams parameters(View view,ViewGroup parent,JSONObject spec,boolean root) throws JSONException {
        ViewGroup.LayoutParams old=view.getLayoutParams();Context context=view.getContext();
        int defaultWidth=root || parent instanceof FrameLayout || parent instanceof NestedScrollView || (parent instanceof LinearLayout && ((LinearLayout)parent).getOrientation()==LinearLayout.VERTICAL)?ViewGroup.LayoutParams.MATCH_PARENT:ViewGroup.LayoutParams.WRAP_CONTENT;
        int width=dimension(spec,"width",old==null?defaultWidth:old.width,context);
        int height=dimension(spec,"height",old==null?(root || parent instanceof FrameLayout && !(parent instanceof ScrollView) && !(parent instanceof NestedScrollView) && !(parent instanceof MaterialCardView)?ViewGroup.LayoutParams.MATCH_PARENT:ViewGroup.LayoutParams.WRAP_CONTENT):old.height,context);
        boolean weighted=spec.has("weight");double weight=weighted?spec.getDouble("weight"):0;
        if(weighted && (!(parent instanceof LinearLayout) || !Double.isFinite(weight) || weight<0 || weight>Float.MAX_VALUE))throw new IllegalArgumentException("Weight requires a row/column and a finite nonnegative value");
        if(weight>0){if(((LinearLayout)parent).getOrientation()==LinearLayout.HORIZONTAL && !spec.has("width"))width=0;
            if(((LinearLayout)parent).getOrientation()==LinearLayout.VERTICAL && !spec.has("height"))height=0;}
        ViewGroup.MarginLayoutParams params;
        if(parent instanceof LinearLayout){
            LinearLayout.LayoutParams p=old instanceof LinearLayout.LayoutParams?new LinearLayout.LayoutParams((LinearLayout.LayoutParams)old):new LinearLayout.LayoutParams(width,height);
            if(weighted)p.weight=(float)weight;if(spec.has("alignment"))p.gravity=gravity(spec.getJSONArray("alignment"));params=p;
        }else if(parent instanceof ConstraintLayout){
            if(spec.has("alignment"))throw new IllegalArgumentException("Use constraints/bias for ConstraintLayout child alignment");
            params=old instanceof ConstraintLayout.LayoutParams?new ConstraintLayout.LayoutParams((ConstraintLayout.LayoutParams)old):new ConstraintLayout.LayoutParams(width,height);
        }else if(parent==null || parent instanceof FrameLayout || parent instanceof NestedScrollView){
            FrameLayout.LayoutParams p=old instanceof FrameLayout.LayoutParams?new FrameLayout.LayoutParams((FrameLayout.LayoutParams)old):new FrameLayout.LayoutParams(width,height);
            if(spec.has("alignment"))p.gravity=gravity(spec.getJSONArray("alignment"));else if(parent instanceof Stack && p.gravity==-1)p.gravity=((Stack)parent).alignment;params=p;
        }else throw new IllegalArgumentException("Unsupported layout parent");
        params.width=width;params.height=height;
        if(old instanceof ViewGroup.MarginLayoutParams){ViewGroup.MarginLayoutParams margin=(ViewGroup.MarginLayoutParams)old;params.setMargins(margin.leftMargin,margin.topMargin,margin.rightMargin,margin.bottomMargin);}
        if(spec.has("margin")){JSONArray a=spec.getJSONArray("margin");params.setMargins(UiComponents.dp(context,a.getInt(0)),UiComponents.dp(context,a.getInt(1)),UiComponents.dp(context,a.getInt(2)),UiComponents.dp(context,a.getInt(3)));}
        return params;
    }
    static void margins(View view,int left,int top,int right,int bottom) throws JSONException {
        UiComponents.margins(view,left,top,right,bottom);
        JSONObject update=new JSONObject().put("margin",new JSONArray().put(left).put(top).put(right).put(bottom));
        view.setTag(R.id.ocaml_layout_spec,merge(view,update.toString()));
    }
    static void set(View view,String json) throws JSONException {
        JSONObject spec=merge(view,json);
        if(view.getParent() instanceof ViewGroup)view.setLayoutParams(parameters(view,(ViewGroup)view.getParent(),spec,false));
        view.setTag(R.id.ocaml_layout_spec,spec);
    }
    static void add(ViewGroup parent,View child,String json) throws JSONException {
        if(child.getParent()!=null)throw new IllegalArgumentException("Child is already attached");
        if(!UiSupport.sameOwner(parent.getContext(),child.getContext()))throw new IllegalArgumentException("Views belong to different activities");
        if((parent instanceof ScrollView || parent instanceof NestedScrollView || parent instanceof MaterialCardView) && parent.getChildCount()!=0)
            throw new IllegalArgumentException("Scroll/card containers accept one content root");
        JSONObject spec=json==null?(JSONObject)child.getTag(R.id.ocaml_layout_spec):merge(child,json);
        if(spec==null && parent instanceof Stack)spec=new JSONObject();
        ViewGroup.LayoutParams params=spec==null?null:parameters(child,parent,spec,false);
        if(parent instanceof ConstraintLayout && child.getId()==View.NO_ID)child.setId(View.generateViewId());
        if(params!=null)child.setLayoutParams(params);
        parent.addView(child);if(spec!=null)child.setTag(R.id.ocaml_layout_spec,spec);
    }
    static void show(Activity activity,View view) throws JSONException {
        JSONObject spec=(JSONObject)view.getTag(R.id.ocaml_layout_spec);
        if(spec==null)activity.setContentView(view);else activity.setContentView(view,parameters(view,null,spec,true));
    }
    static void child(ConstraintLayout parent,View view){
        if(view.getParent()!=parent)throw new IllegalArgumentException("Constraint view must be a direct child");
        if(view.getId()==View.NO_ID)view.setId(View.generateViewId());
    }
    static int edge(String side){switch(side){case "start":return ConstraintSet.START;case "end":return ConstraintSet.END;case "top":return ConstraintSet.TOP;case "bottom":return ConstraintSet.BOTTOM;case "baseline":return ConstraintSet.BASELINE;default:throw new IllegalArgumentException("Constraint edge");}}
    static void connect(ConstraintLayout parent,View view,String edge,View target,String targetEdge,int margin){
        child(parent,view);if(target!=null)child(parent,target);
        int start=edge(edge),end=edge(targetEdge);
        boolean horizontal=(start==ConstraintSet.START || start==ConstraintSet.END) && (end==ConstraintSet.START || end==ConstraintSet.END);
        boolean vertical=(start==ConstraintSet.TOP || start==ConstraintSet.BOTTOM) && (end==ConstraintSet.TOP || end==ConstraintSet.BOTTOM);
        if(!horizontal && !vertical && !(start==ConstraintSet.BASELINE && end==ConstraintSet.BASELINE && margin==0))throw new IllegalArgumentException("Incompatible constraint anchors");
        if(start==ConstraintSet.BASELINE && target==null)throw new IllegalArgumentException("Baseline needs a sibling target");
        if(target==view)throw new IllegalArgumentException("Cannot constrain a view to itself");
        ConstraintSet set=new ConstraintSet();set.clone(parent);set.connect(view.getId(),start,target==null?ConstraintSet.PARENT_ID:target.getId(),end,UiComponents.dp(parent.getContext(),margin));set.applyTo(parent);
    }
    static void clear(ConstraintLayout parent,View view,String edge){child(parent,view);ConstraintSet set=new ConstraintSet();set.clone(parent);set.clear(view.getId(),edge(edge));set.applyTo(parent);}
    static void bias(ConstraintLayout parent,View view,float horizontal,float vertical){
        child(parent,view);if(!Float.isFinite(horizontal) || !Float.isFinite(vertical) || horizontal<0 || horizontal>1 || vertical<0 || vertical>1)throw new IllegalArgumentException("Constraint bias");
        ConstraintSet set=new ConstraintSet();set.clone(parent);set.setHorizontalBias(view.getId(),horizontal);set.setVerticalBias(view.getId(),vertical);set.applyTo(parent);
    }
}
