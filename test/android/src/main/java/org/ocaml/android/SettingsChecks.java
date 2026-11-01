package org.ocaml.android;
import android.app.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.lang.reflect.*;
import java.util.List;
/** UI-only checks; no personal preferences or permission dialogs are used. */
public final class SettingsChecks {
    private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    private static Object row(SettingsSupport page,int i) throws Exception {
        Field f=SettingsSupport.class.getDeclaredField("rows");f.setAccessible(true);return ((List<?>)f.get(page)).get(i);
    }
    private static Object field(Object row,String name) throws Exception {Field f=row.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(row);}
    public static void run(Activity owner) throws Exception {
        String json="[{\"kind\":\"text\",\"title\":\"Text\",\"description\":null,\"multiline\":false,\"value\":\"old\"},"
          +"{\"kind\":\"choice\",\"title\":\"Choice\",\"description\":null,\"value\":\"a\",\"options\":[[\"a\",\"A\"],[\"b\",\"B\"]]},"
          +"{\"kind\":\"toggle\",\"title\":\"Toggle\",\"description\":null,\"value\":\"false\"}]";
        SettingsSupport page=new SettingsSupport(owner,"settings-ui-test",json);
        Object text=row(page,0);((View)field(text,"view")).performClick();
        AlertDialog dialog=(AlertDialog)field(text,"dialog");EditText edit=(EditText)field(text,"edit");
        edit.setText("new");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        check(!dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled(),"Disable until commit acknowledgement");
        page.result(0,false,"Invalid input");check(dialog.isShowing() && edit.getError()!=null,"Validation keeps editor open");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        check(((JSONObject)field(text,"spec")).getString("value").equals("old"),"Cancel preserves value");
        ((View)field(text,"view")).performClick();dialog=(AlertDialog)field(text,"dialog");
        ((EditText)field(text,"edit")).setText("new");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        page.result(0,true,"new");check(!dialog.isShowing(),"Commit closes editor");
        Object choice=row(page,1);((View)field(choice,"view")).performClick();
        dialog=(AlertDialog)field(choice,"dialog");dialog.getListView().performItemClick(null,1,1);
        page.result(1,true,"b");check(((JSONObject)field(choice,"spec")).getString("value").equals("b"),"Choice commits stable value");
        Object toggle=row(page,2);((Switch)field(toggle,"toggle")).setChecked(true);
        page.result(2,false,"Storage failure");check(!((Switch)field(toggle,"toggle")).isChecked(),"Failed commit restores toggle");
        page.update(json);check(((JSONObject)field(text,"spec")).getString("value").equals("old"),"Refresh updates value");
        ((View)field(text,"view")).performClick();dialog=(AlertDialog)field(text,"dialog");page.close();page.close();
        check(!dialog.isShowing(),"Release dismisses open dialog");
    }
}
