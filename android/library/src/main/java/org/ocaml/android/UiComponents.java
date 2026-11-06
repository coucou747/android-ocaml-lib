package org.ocaml.android;

import android.app.Activity;
import android.content.*;
import android.text.InputType;
import android.util.TypedValue;
import android.view.*;
import android.widget.*;
import androidx.appcompat.view.ContextThemeWrapper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.*;

/** Shared Material defaults, scoped to the new components, with application
 * themes respected when they already provide Material 3. */
final class UiComponents {
    private UiComponents(){}
    static Context theme(Activity activity){
        TypedValue value=new TypedValue();
        if(activity.getTheme().resolveAttribute(com.google.android.material.R.attr.isMaterial3Theme,value,true) && value.data!=0)return activity;
        return new ContextThemeWrapper(activity,com.google.android.material.R.style.Theme_Material3_DayNight_NoActionBar);
    }
    static int dp(Context context,int value){
        if(value<0)throw new IllegalArgumentException("Expected nonnegative dp dimension");
        double pixels=value*(double)context.getResources().getDisplayMetrics().density;
        if(pixels>Integer.MAX_VALUE)throw new IllegalArgumentException("Android dimension overflow");
        return (int)Math.round(pixels);
    }
    static MaterialButton button(Activity activity,String text,boolean primary){
        Context context=theme(activity);
        ContextThemeWrapper widget=new ContextThemeWrapper(context,primary?R.style.OcamlPrimaryButtonTheme:R.style.OcamlSecondaryButtonTheme);
        MaterialButton button=new MaterialButton(widget,null,com.google.android.material.R.attr.materialButtonStyle);
        button.setText(text);return button;
    }
    static void padding(View view,int left,int top,int right,int bottom){
        Context c=view.getContext();SystemBarInsets.padding(view,dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));
    }
    static void margins(View view,int left,int top,int right,int bottom){
        ViewGroup.LayoutParams old=view.getLayoutParams();
        ViewGroup.MarginLayoutParams params=old instanceof ViewGroup.MarginLayoutParams?(ViewGroup.MarginLayoutParams)old:
            old==null?new ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT):new ViewGroup.MarginLayoutParams(old);
        Context c=view.getContext();params.setMargins(dp(c,left),dp(c,top),dp(c,right),dp(c,bottom));view.setLayoutParams(params);
    }
    static View spacer(Activity activity,int width,int height){
        View view=new View(activity);view.setLayoutParams(new ViewGroup.LayoutParams(dp(activity,width),dp(activity,height)));
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return view;
    }
    static final class Form extends LinearLayout {
        Form(Activity activity){super(theme(activity));setOrientation(VERTICAL);
            int padding=getResources().getDimensionPixelSize(R.dimen.ocaml_form_padding);setPadding(padding,padding,padding,padding);
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(this,com.google.android.material.R.attr.colorSurface));}
        @Override public void addView(View child){
            ViewGroup.LayoutParams params=child.getLayoutParams();
            LinearLayout.LayoutParams row=params==null?new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT):
                params instanceof LinearLayout.LayoutParams?(LinearLayout.LayoutParams)params:
                params instanceof ViewGroup.MarginLayoutParams?new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams)params):new LinearLayout.LayoutParams(params);
            if(!(params instanceof ViewGroup.MarginLayoutParams) && getChildCount()>0)
                row.topMargin=getResources().getDimensionPixelSize(R.dimen.ocaml_form_gap);
            super.addView(child,-1,row);
        }
    }
    static final class Field extends TextInputLayout {
        final TextInputEditText input;
        Field(Activity activity,String label,String help,String text,String keyboard,boolean signed,boolean decimal,boolean multiline){
            super(theme(activity),null,com.google.android.material.R.attr.textInputStyle);
            if(label.isEmpty() || (multiline && !keyboard.equals("text")))throw new IllegalArgumentException("Labeled field; multiline requires text keyboard");
            setHint(label);setHelperText(help);setErrorEnabled(true);
            input=new TextInputEditText(getContext());int type;
            switch(keyboard){
            case "text":type=InputType.TYPE_CLASS_TEXT|(multiline?InputType.TYPE_TEXT_FLAG_MULTI_LINE:0);break;
            case "email":type=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;break;
            case "password":type=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD;break;
            case "phone":type=InputType.TYPE_CLASS_PHONE;break;
            case "url":type=InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI;break;
            case "number":type=InputType.TYPE_CLASS_NUMBER|(signed?InputType.TYPE_NUMBER_FLAG_SIGNED:0)|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0);break;
            default:throw new IllegalArgumentException("Unknown field keyboard");
            }
            input.setInputType(type);input.setSingleLine(!multiline);input.setText(text);
            addView(input,new LayoutParams(LayoutParams.MATCH_PARENT,LayoutParams.WRAP_CONTENT));
        }
    }
}
