open Lwt.Infix
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "JNI smoke failure"
let binary = "\000\255\042\000"
let () = Android.Runtime.set_entry (fun () ->
  Android.Activity.on_lifecycle (fun activity _ ->
    Android.Ui.text activity "Été 🐫" >>= require >>= fun view ->
    Android.Ui.get_text view >>= require >>= fun text ->
    assert (text = "Été 🐫");
    Lwt_list.iter_p (fun _ ->
      Android.Storage.read_file ~name:"binary" >>= require >|= fun data -> assert (data = binary))
      [(); (); (); (); (); (); (); ()] >>= fun () ->
    Android.Activity.display_size activity >>= require >>= fun bounds ->
    assert (bounds.width_px = 1080 && bounds.height_px = 1920);
    Android.Ui.scroll_view activity >>= require >>= fun scroll ->
    Android.Ui.add scroll view >>= require >>= fun () ->
    Android.Ui.set_input_type view (Number {signed=true;decimal=true}) >>= require >>= fun () ->
    Android.Ui.set_input_type view Text >>= require >>= fun () ->
    Android.Ui.set_multiline view true >>= require >>= fun () ->
    Android.Ui.set_hint view "Indication" >>= require >>= fun () ->
    Android.Ui.set_error view (Some "Erreur") >>= require >>= fun () ->
    Android.Ui.set_error view None >>= require >>= fun () ->
    Android.Ui.image activity ~uri:"content://test/photo.jpg" ~max_width_px:bounds.width_px ~max_height_px:bounds.height_px >>= require >>= fun image ->
    Android.Ui.set_image_uri image ~uri:"content://test/other.jpg" ~max_width_px:bounds.width_px ~max_height_px:bounds.height_px >>= require >>= fun () ->
    Android.Ui.release image >>= require >>= fun () ->
    Android.Ui.image activity ~uri:"content://test/photo.jpg" ~max_width_px:0 ~max_height_px:1 >>= (function
      | Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Image bounds validation") >>= fun () ->
    Android.Resources.resolve ~scope:Android ~kind:Drawable ~name:"ic_lock_idle_alarm" >>= require >>= fun icon -> assert (icon > 0);
    Android.Audio.default_alarm_uri () >>= require >>= fun ringtone -> assert (ringtone = Some "content://media/alarm");
    Android.Audio.default_alarm_uri () >>= require >>= fun ringtone -> assert (ringtone = None);
    Android.Locale.time_zone () >>= require >>= fun zone -> assert (zone = "Europe/Paris");
    Android.Locale.language_tag () >>= require >>= fun language -> assert (language = "fr-FR");
    Android.Locale.uses_24_hour_clock () >>= require >>= fun clock -> assert clock;
    Lwt_list.iter_s (fun f -> f ~at_ms:2147483648000L >>= require >|= fun text -> assert (text = "date locale"))
      [Android.Locale.format_date;Android.Locale.format_time;Android.Locale.format_date_time] >>= fun () ->
    Android.Camera.capture activity ~name:"photo.jpg" >>= require >>= (function
      | Android.Camera.Cancelled -> Lwt.fail_with "Expected captured photo"
      | Captured photo ->
        assert (Android.Camera.uri photo = "content://test/photo.jpg");
        Android.Camera.of_uri (Android.Camera.uri photo) >>= require >>= fun photo ->
        Android.Camera.read photo >>= require >>= fun data ->
        assert (data = binary); Android.Camera.delete photo >>= require) >>= fun () ->
    Android.Camera.capture activity ~name:"cancel.jpg" >>= require >>= fun result ->
    assert (result = Android.Camera.Cancelled);
    Android.Camera.capture activity ~name:"../escape.jpg" >>= (function
      | Error (Android.Error.Invalid_argument _) -> Lwt.return_unit
      | _ -> Lwt.fail_with "Expected invalid camera path") >>= fun () ->
    let screen : Android.Alarm.Screen.t = {id="test"; notification_id=1; title="Alarm"; text="";
      stop_label="Stop"; snooze_label="Snooze"; stop_handler="stop"; snooze_handler="snooze"; payload="test"} in
    Android.Alarm.Screen.show activity screen >>= require >>= fun () ->
    Android.Alarm.Screen.notify ~channel:"test" ~small_icon:1 ~full_screen:false screen >>= require >>= fun () ->
    Android.Alarm.Screen.dismiss ~id:"test" ~notification_id:1 >>= require >>= fun () ->
    Gc.full_major ();
    Android.Storage.write_file ~name:"done" ~contents:binary >>= require) >>= require >|= fun _ -> ())
