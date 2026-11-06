package org.ocaml.android;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.*;

/** Regression: use AndroidX preference classes and a system-discoverable entry. */
public final class SettingsChecks {
    private static void check(boolean c,String m){if(!c)throw new AssertionError(m);}
    private static void dialogs(FragmentActivity owner){owner.getSupportFragmentManager().executePendingTransactions();}
    public static void run(Activity activity) throws Exception {
        FragmentActivity owner=(FragmentActivity)activity;owner.setTheme(androidx.appcompat.R.style.Theme_AppCompat_DayNight);
        Intent entry=new Intent(Intent.ACTION_APPLICATION_PREFERENCES).setPackage(owner.getPackageName());
        android.content.pm.ResolveInfo target=owner.getPackageManager().resolveActivity(entry,PackageManager.MATCH_DEFAULT_ONLY);
        check(target!=null && target.activityInfo.name.equals(SettingsActivity.class.getName()) && target.activityInfo.exported,"System app preferences entry");
        check(SettingsActivity.configuredName(owner).equals("integration"),"Application selects schema without Java");
        String json="[{\"kind\":\"section\",\"title\":\"Section\",\"description\":null},"
          +"{\"kind\":\"text\",\"title\":\"Text\",\"description\":null,\"multiline\":false,\"value\":\"old\"},"
          +"{\"kind\":\"choice\",\"title\":\"Choice\",\"description\":null,\"value\":\"a\",\"options\":[[\"a\",\"A\"],[\"b\",\"B\"]]},"
          +"{\"kind\":\"toggle\",\"title\":\"Toggle\",\"description\":null,\"value\":\"false\"},"
          +"{\"kind\":\"info\",\"title\":\"Permissions\",\"description\":\"Unknown\"}]";
        SettingsSupport page=new SettingsSupport(owner,"settings-ui-test",json);
        owner.setContentView(page);page.mount();
        PreferenceFragmentCompat screen=page.screen;
        check(screen.findPreference("0") instanceof PreferenceCategory,"Native preference category");
        EditTextPreference text=screen.findPreference("1");ListPreference choice=screen.findPreference("2");SwitchPreferenceCompat toggle=screen.findPreference("3");
        Preference info=screen.findPreference("4");
        check(!info.isSelectable() && info.isEnabled() && !info.isPersistent(),"Information is a native read-only row, without disabled styling or storage");
        check("Unknown".contentEquals(info.getSummary()) && info.getOnPreferenceChangeListener()==null && info.getOnPreferenceClickListener()==null,"Information has no edit or action callback");
        check(!text.isPersistent() && !choice.isPersistent() && !toggle.isPersistent(),"Only acknowledged OCaml commits persist");
        page.screen.onDisplayPreferenceDialog(text);dialogs(owner);
        SettingsSupport.Editor editor=(SettingsSupport.Editor)owner.getSupportFragmentManager().findFragmentByTag("ocaml.preference.dialog");
        AlertDialog dialog=(AlertDialog)editor.getDialog();editor.edit.setText("new");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        check(!text.isEnabled(),"Await storage acknowledgement");page.result(1,false,"Invalid input");
        check(dialog.isShowing() && editor.edit.getError()!=null,"Validation stays in standard preference dialog");
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();dialogs(owner);check(text.getText().equals("old"),"Cancel preserves stored value");
        page.screen.onDisplayPreferenceDialog(text);dialogs(owner);
        editor=(SettingsSupport.Editor)owner.getSupportFragmentManager().findFragmentByTag("ocaml.preference.dialog");
        dialog=(AlertDialog)editor.getDialog();editor.edit.setText("new");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        page.result(1,true,"new");dialogs(owner);check(text.getText().equals("new"),"Confirmed value updates preference");
        page.screen.onDisplayPreferenceDialog(choice);dialogs(owner);
        SettingsSupport.Chooser chooser=(SettingsSupport.Chooser)owner.getSupportFragmentManager().findFragmentByTag("ocaml.preference.dialog");
        ((AlertDialog)chooser.getDialog()).getListView().performItemClick(null,1,1);
        page.result(2,true,"b");dialogs(owner);check(choice.getValue().equals("b"),"Stable choice value");
        toggle.callChangeListener(true);page.result(3,false,"Storage failed");check(!toggle.isChecked(),"Rejected switch retains old value");
        page.update(json);check(text.getText().equals("old"),"Refresh from persisted model");
        page.update(json.replace("Unknown","Granted"));
        check("Granted".contentEquals(info.getSummary()) && !info.isSelectable(),"Read-only summary refreshes without becoming editable");
        page.screen.onDisplayPreferenceDialog(text);dialogs(owner);
        editor=(SettingsSupport.Editor)owner.getSupportFragmentManager().findFragmentByTag("ocaml.preference.dialog");dialog=(AlertDialog)editor.getDialog();
        page.close();page.close();dialogs(owner);check(!dialog.isShowing(),"Close dismisses preference dialogs");
    }
}
