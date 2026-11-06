package org.ocaml.android;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.*;

/** Synthetic insets exercise repeat calls, base padding and keyboard propagation. */
public final class SystemBarInsetsChecks {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    /** Check the actual content root of a library-owned activity. */
    public static void screen(Activity activity){
        android.view.ViewGroup content=activity.findViewById(android.R.id.content);
        check(content.getChildCount()==1,"Screen has one content root");
        View root=content.getChildAt(0);
        Object tag=root.getTag(R.id.ocaml_system_bar_insets);
        check(tag instanceof OnApplyWindowInsetsListener,activity.getClass().getSimpleName()+" handles system bar insets on its root");
        OnApplyWindowInsetsListener listener=(OnApplyWindowInsetsListener)tag;
        WindowInsetsCompat original=ViewCompat.getRootWindowInsets(root);
        try{
            listener.onApplyWindowInsets(root,new WindowInsetsCompat.Builder().build());
            int top=root.getPaddingTop(),bottom=root.getPaddingBottom();
            WindowInsetsCompat bars=new WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,31,0,0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(0,0,0,47)).build();
            listener.onApplyWindowInsets(root,bars);
            check(root.getPaddingTop()==top+31 && root.getPaddingBottom()==bottom+47,"Screen keeps content inside both system bars");
            listener.onApplyWindowInsets(root,bars);
            check(root.getPaddingTop()==top+31 && root.getPaddingBottom()==bottom+47,"Screen insets do not accumulate");
        }finally{
            if(original!=null)listener.onApplyWindowInsets(root,original);
            ViewCompat.requestApplyInsets(root);
        }
    }
    public static void run(Activity owner){
        View root=new FrameLayout(owner);root.setPadding(3,5,4,7);
        SystemBarInsets.apply(root);
        OnApplyWindowInsetsListener listener=(OnApplyWindowInsetsListener)root.getTag(R.id.ocaml_system_bar_insets);
        WindowInsetsCompat source=new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,10,0,0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(8,0,0,20))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars(),Insets.of(0,10,0,0))
            .setInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars(),Insets.of(8,0,0,20))
            .setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,200)).build();
        WindowInsetsCompat remaining=listener.onApplyWindowInsets(root,source);
        check(root.getPaddingTop()==15 && root.getPaddingBottom()==27,"Bars added to base padding");
        check(root.getPaddingLeft()==3 && root.getPaddingRight()==4,"Horizontal padding unchanged");
        check(remaining.getInsets(WindowInsetsCompat.Type.systemBars()).top==0 && remaining.getInsets(WindowInsetsCompat.Type.systemBars()).bottom==0,"Children do not apply vertical bars twice");
        check(remaining.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).top==0 && remaining.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()).bottom==0,"Stable vertical bars are handled on legacy Android too");
        check(remaining.getInsets(WindowInsetsCompat.Type.systemBars()).left==8,"Horizontal bar insets remain available");
        check(remaining.getInsets(WindowInsetsCompat.Type.ime()).bottom==200,"Keyboard insets remain available");
        listener.onApplyWindowInsets(root,source);SystemBarInsets.apply(root);
        check(root.getTag(R.id.ocaml_system_bar_insets)==listener,"Repeated installation reuses handler");
        listener.onApplyWindowInsets(root,source);
        check(root.getPaddingTop()==15 && root.getPaddingBottom()==27,"Insets do not accumulate");
        WindowInsetsCompat changed=new WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,6,0,0)).build();
        listener.onApplyWindowInsets(root,changed);
        check(root.getPaddingTop()==11 && root.getPaddingBottom()==7,"Changed bars replace previous contribution");
        UiComponents.padding(root,1,2,3,4);
        check(root.getPaddingTop()==UiComponents.dp(owner,2)+6 && root.getPaddingBottom()==UiComponents.dp(owner,4),"set_padding updates base with current insets");
        listener.onApplyWindowInsets(root,new WindowInsetsCompat.Builder().build());
        check(root.getPaddingTop()==UiComponents.dp(owner,2) && root.getPaddingBottom()==UiComponents.dp(owner,4),"Hidden bars restore base padding");
        SystemBarInsets.clear(root);check(root.getTag(R.id.ocaml_system_bar_insets)==null,"Release removes handler state");
        SystemBarInsets.clear(root);
        View alarmRoot=new FrameLayout(owner);alarmRoot.setPadding(1,2,3,4);SystemBarInsets.applyAll(alarmRoot);
        OnApplyWindowInsetsListener all=(OnApplyWindowInsetsListener)alarmRoot.getTag(R.id.ocaml_system_bar_insets);
        WindowInsetsCompat landscape=new WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,10,0,0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(8,0,14,20)).build();
        WindowInsetsCompat unused=all.onApplyWindowInsets(alarmRoot,landscape);all.onApplyWindowInsets(alarmRoot,landscape);
        check(alarmRoot.getPaddingLeft()==9 && alarmRoot.getPaddingTop()==12 && alarmRoot.getPaddingRight()==17 && alarmRoot.getPaddingBottom()==24,"Alarm mode handles all sides without accumulation");
        check(unused.getInsets(WindowInsetsCompat.Type.systemBars()).equals(Insets.NONE),"Alarm descendants do not repeat handled side insets");
        SystemBarInsets.clear(alarmRoot);
    }
}
