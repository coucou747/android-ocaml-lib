package org.ocaml.android;

import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** Root safe area. Padding stays relative to a stable application-supplied base. */
final class SystemBarInsets implements OnApplyWindowInsetsListener,View.OnAttachStateChangeListener {
    private int left,top,right,bottom,barLeft,barTop,barRight,barBottom;
    private boolean allSides;
    private SystemBarInsets(View view){left=view.getPaddingLeft();top=view.getPaddingTop();right=view.getPaddingRight();bottom=view.getPaddingBottom();}
    static void apply(View view){apply(view,false);}
    static void applyAll(View view){apply(view,true);}
    private static void apply(View view,boolean allSides){
        SystemBarInsets state=(SystemBarInsets)view.getTag(R.id.ocaml_system_bar_insets);
        if(state==null){
            state=new SystemBarInsets(view);view.setTag(R.id.ocaml_system_bar_insets,state);
            ViewCompat.setOnApplyWindowInsetsListener(view,state);view.addOnAttachStateChangeListener(state);
        }
        state.allSides=allSides;
        WindowInsetsCompat current=ViewCompat.getRootWindowInsets(view);
        if(current!=null)state.onApplyWindowInsets(view,current);
        ViewCompat.requestApplyInsets(view);
    }
    static void padding(View view,int left,int top,int right,int bottom){
        SystemBarInsets state=(SystemBarInsets)view.getTag(R.id.ocaml_system_bar_insets);
        if(state==null){view.setPadding(left,top,right,bottom);return;}
        state.left=left;state.top=top;state.right=right;state.bottom=bottom;state.update(view);
    }
    private void update(View view){
        view.setPadding((int)Math.min(Integer.MAX_VALUE,(long)left+barLeft),(int)Math.min(Integer.MAX_VALUE,(long)top+barTop),
            (int)Math.min(Integer.MAX_VALUE,(long)right+barRight),(int)Math.min(Integer.MAX_VALUE,(long)bottom+barBottom));
    }
    @SuppressWarnings("deprecation")
    @Override public WindowInsetsCompat onApplyWindowInsets(View view,WindowInsetsCompat insets){
        Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|(allSides?WindowInsetsCompat.Type.displayCutout():0));
        barLeft=allSides?bars.left:0;barRight=allSides?bars.right:0;barTop=bars.top;barBottom=bars.bottom;update(view);
        WindowInsetsCompat.Builder remaining=new WindowInsetsCompat.Builder(insets);
        // Legacy Android derives IME from system-window insets; preserve it before
        // removing the bar contribution used by those same compatibility fields.
        remaining.setInsets(WindowInsetsCompat.Type.ime(),insets.getInsets(WindowInsetsCompat.Type.ime()));
        for(int type:new int[]{WindowInsetsCompat.Type.statusBars(),WindowInsetsCompat.Type.navigationBars(),WindowInsetsCompat.Type.captionBar()}){
            Insets visible=insets.getInsets(type),maximum=insets.getInsetsIgnoringVisibility(type);
            remaining.setInsets(type,Insets.of(allSides?0:visible.left,0,allSides?0:visible.right,0));
            remaining.setInsetsIgnoringVisibility(type,Insets.of(allSides?0:maximum.left,0,allSides?0:maximum.right,0));
        }
        if(allSides){remaining.setDisplayCutout(null);remaining.setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.NONE);remaining.setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(),Insets.NONE);}
        // Before API 30, ignoring-visibility values use the legacy stable fields.
        if(android.os.Build.VERSION.SDK_INT<30){
            Insets stable=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());
            remaining.setStableInsets(Insets.of(allSides?0:stable.left,0,allSides?0:stable.right,0));
        }
        return remaining.build();
    }
    @Override public void onViewAttachedToWindow(View view){ViewCompat.requestApplyInsets(view);}
    @Override public void onViewDetachedFromWindow(View view){}
    static void clear(View view){
        SystemBarInsets state=(SystemBarInsets)view.getTag(R.id.ocaml_system_bar_insets);
        if(state==null)return;
        ViewCompat.setOnApplyWindowInsetsListener(view,null);view.removeOnAttachStateChangeListener(state);view.setTag(R.id.ocaml_system_bar_insets,null);
    }
}
