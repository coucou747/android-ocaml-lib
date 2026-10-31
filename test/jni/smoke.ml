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
