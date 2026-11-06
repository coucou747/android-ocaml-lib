package org.ocaml.android;

import android.app.Activity;
import android.view.*;
import android.widget.*;
import androidx.core.widget.NestedScrollView;
import androidx.constraintlayout.widget.*;
import org.json.JSONObject;

/** Real SDK measurement/regressions, independent from backend wire fixtures. */
public final class LayoutChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void measure(View view,int width,int height){view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);}
    public static void run(Activity owner) throws Exception {
        int width=UiComponents.dp(owner,400),height=UiComponents.dp(owner,120),gap=UiComponents.dp(owner,8);
        LinearLayout row=(LinearLayout)LayoutSupport.container(owner,"row",8,"[\"start\",\"middle\"]");row.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        View first=new View(owner),second=new View(owner);
        LayoutSupport.set(first,"{\"weight\":1,\"height\":24}");LayoutSupport.add(row,first,null);
        LayoutSupport.add(row,second,"{\"weight\":2,\"height\":24}");measure(row,width,height);
        check(first.getWidth()+second.getWidth()+gap==width,"Weights divide remaining space after gap");
        check(Math.abs(second.getWidth()-2*first.getWidth())<=1,"SDK weight ratio 1:2");
        check(first.getTop()==(height-first.getHeight())/2,"Row vertical alignment");
        LayoutSupport.set(first,"{\"margin\":[2,0,3,0]}");measure(row,width,height);
        check(first.getWidth()+second.getWidth()+gap+UiComponents.dp(owner,2)+UiComponents.dp(owner,3)==width,"Margins and row spacing coexist");
        check(((LinearLayout.LayoutParams)first.getLayoutParams()).weight==1,"Updating margin preserves weight");
        LayoutSupport.margins(first,4,0,6,0);LayoutSupport.set(first,"{\"height\":32}");
        LinearLayout.LayoutParams updated=(LinearLayout.LayoutParams)first.getLayoutParams();
        check(updated.leftMargin==UiComponents.dp(owner,4) && updated.rightMargin==UiComponents.dp(owner,6),"Legacy margin setter survives later layout update");
        first.setVisibility(View.GONE);measure(row,width,height);check(second.getWidth()==width,"Gone child consumes no gap or weight");first.setVisibility(View.VISIBLE);
        FrameLayout stack=(FrameLayout)LayoutSupport.container(owner,"stack",0,"[\"end\",\"bottom\"]");stack.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        View badge=new View(owner);LayoutSupport.add(stack,badge,"{\"width\":20,\"height\":10,\"margin\":[0,0,4,5]}");measure(stack,width,height);
        check(badge.getRight()==width-UiComponents.dp(owner,4) && badge.getBottom()==height-UiComponents.dp(owner,5),"Stack alignment and margins");
        stack.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);measure(stack,width,height);check(badge.getLeft()==0,"End alignment follows RTL");
        View invalid=new View(owner);boolean rejected=false;try{LayoutSupport.add(stack,invalid,"{\"weight\":1}");}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected && invalid.getParent()==null,"Weight rejected on stack without attachment");
        NestedScrollView scroll=new NestedScrollView(owner);LayoutSupport.add(scroll,new LinearLayout(owner),null);
        rejected=false;try{LayoutSupport.add(scroll,new View(owner),null);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"NestedScrollView has one root");
        ConstraintLayout constraints=new ConstraintLayout(owner);View child=new View(owner);LayoutSupport.add(constraints,child,"{\"width\":0,\"height\":24}");
        LayoutSupport.connect(constraints,child,"start",null,"start",8);LayoutSupport.connect(constraints,child,"end",null,"end",8);LayoutSupport.connect(constraints,child,"top",null,"top",8);
        measure(constraints,width,height);check(child.getWidth()==width-2*gap && child.getTop()==gap,"Constraint SDK anchors and match-constraint width");
        LayoutSupport.set(child,"{\"height\":32}");ConstraintLayout.LayoutParams params=(ConstraintLayout.LayoutParams)child.getLayoutParams();
        check(params.startToStart==ConstraintSet.PARENT_ID && params.endToEnd==ConstraintSet.PARENT_ID,"Layout update preserves constraints");
        LayoutSupport.bias(constraints,child,0.25f,0.75f);params=(ConstraintLayout.LayoutParams)child.getLayoutParams();check(params.horizontalBias==0.25f && params.verticalBias==0.75f,"Constraint biases");
        LayoutSupport.clear(constraints,child,"end");check(((ConstraintLayout.LayoutParams)child.getLayoutParams()).endToEnd==ConstraintLayout.LayoutParams.UNSET,"Constraint removal");
        rejected=false;try{LayoutSupport.connect(constraints,child,"top",first,"bottom",0);}catch(IllegalArgumentException expected){rejected=true;}
        check(rejected,"Cross-parent constraint rejected");
        MaterialSupport.Check box=new MaterialSupport.Check(owner,"Done",2);check(box.getCheckedState()==2,"Tri-state checkbox");
        int[] changes={0};box.addOnCheckedStateChangedListener((button,state)->{if(!box.muted)changes[0]++;});
        box.state(0);check(changes[0]==0,"Programmatic checkbox state is silent");box.toggle();check(changes[0]==1,"User checkbox change");
        MaterialSupport.Toggle toggle=new MaterialSupport.Toggle(owner,"Completed",false);int[] toggles={0};toggle.setOnCheckedChangeListener((button,value)->{if(!toggle.muted)toggles[0]++;});
        toggle.checked(true);check(toggles[0]==0,"Programmatic switch is silent");toggle.performClick();check(toggles[0]==1,"User switch change");
        String items="[{\"id\":1,\"title\":\"Tasks\",\"icon\":"+android.R.drawable.ic_menu_agenda+"},{\"id\":2,\"title\":\"Settings\",\"icon\":"+android.R.drawable.ic_menu_preferences+"}]";
        MaterialSupport.Navigation navigation=new MaterialSupport.Navigation(owner,items,1);int[] selected={0};navigation.setOnItemSelectedListener(item->{if(!navigation.muted && navigation.getSelectedItemId()!=item.getItemId())selected[0]++;return true;});
        navigation.select(2);check(selected[0]==0 && navigation.getSelectedItemId()==2,"Silent programmatic navigation");
        navigation.findViewById(1).performClick();check(selected[0]==1,"User section selection");
        navigation.findViewById(1).performClick();check(selected[0]==1,"Reselection stays silent");
    }
}
