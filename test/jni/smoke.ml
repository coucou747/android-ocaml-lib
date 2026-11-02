open Lwt.Infix
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "JNI smoke failure"
let binary = "\000\255\042\000"
let check_fix (fix : Android.Location.fix) =
  assert (fix.latitude=1.25 && fix.longitude = -2.5 && fix.accuracy_m=Some 5. && fix.altitude_m=None);
  assert (fix.at_ms=2147483648000L && fix.elapsed_realtime_ns=9876543210123L && fix.is_mock)
let background,background_done=Lwt.wait ()
let () = assert (Android.Location.register_background_handler ~name:"gps.handler" (function
  | Fix fix -> check_fix fix;Lwt.wakeup_later background_done ();Lwt.return Android.Background.Success
  | _ -> Lwt.fail_with "Unexpected background GPS event") = Ok ())
let () = Android.Runtime.set_entry (fun () ->
  Android.Activity.current () >>= (function
    | Error (Android.Error.Invalid_handle _) -> Lwt.return_unit
    | _ -> Lwt.fail_with "Expected cold start without activity") >>= fun () ->
  Android.Activity.launch_main () >>= require >>= fun () ->
  Android.Activity.on_lifecycle (fun activity _ ->
    Android.Ui.text activity "Été 🐫" >>= require >>= fun view ->
    Android.Ui.get_text view >>= require >>= fun text ->
    assert (text = "Été 🐫");
    Lwt_list.iter_p (fun _ ->
      Android.Storage.read_file ~name:"binary" >>= require >|= fun data -> assert (data = binary))
      [(); (); (); (); (); (); (); ()] >>= fun () ->
    Android.Storage.file_uri ~name:"photo été.jpg" >>= require >>= fun uri -> assert (uri="file:///private/photo%20%C3%A9t%C3%A9.jpg");
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
    Android.Health_connect.status () >>= require >>= fun status -> assert (status = Available);
    Android.Health_connect.status () >>= require >>= fun status -> assert (status = Update_required);
    Android.Health_connect.status () >>= require >>= fun status -> assert (status = Unavailable);
    Android.Health_connect.read_permission Steps >>= require >>= fun permission -> assert (permission = "android.permission.health.READ_STEPS");
    Android.Health_connect.granted_permissions () >>= require >>= fun granted -> assert (granted=[Steps;Heart_rate;Sleep;Exercise;Distance;Active_calories;Total_calories]);
    Android.Health_connect.request_permissions activity [Steps;Sleep;Distance;Active_calories;Total_calories] >>= require >>= fun granted -> assert (granted=[Steps;Sleep;Distance;Active_calories;Total_calories]);
    Lwt_list.iter_s (fun (kind,suffix) -> Android.Health_connect.read_permission kind >>= require >|= fun permission ->
      assert (permission="android.permission.health.READ_"^suffix))
      [Distance,"DISTANCE";Active_calories,"ACTIVE_CALORIES_BURNED";Total_calories,"TOTAL_CALORIES_BURNED"] >>= fun () ->
    Android.Health_connect.background_read_available () >>= require >>= fun available -> assert available;
    Android.Health_connect.background_read_granted () >>= require >>= fun granted -> assert (not granted);
    Android.Health_connect.request_background_read activity >>= require >>= fun granted -> assert granted;
    Android.Health_connect.background_read_granted () >>= require >>= fun granted -> assert granted;
    Android.Health_connect.request_background_read activity >>= (function Error (Android.Error.Permission_denied _) -> Lwt.return_unit | _ -> Lwt.fail_with "Background read refusal") >>= fun () ->
    Android.Health_connect.background_read_granted () >>= require >>= fun granted -> assert (not granted);
    Android.Health_connect.request_background_read activity >>= (function Error (Android.Error.Unavailable _) -> Lwt.return_unit | _ -> Lwt.fail_with "Background read unsupported") >>= fun () ->
    Android.Health_connect.background_read_available () >>= require >>= fun available -> assert (not available);
    Android.Health_connect.background_read_granted () >>= (function Error (Android.Error.Unavailable _) -> Lwt.return_unit | _ -> Lwt.fail_with "Background feature missing") >>= fun () ->
    Lwt_list.iter_s (fun (aggregate,expected) ->
      aggregate ~start_ms:1L ~end_ms:2L >>= require >>= fun value -> assert (value=Some expected);
      aggregate ~start_ms:3L ~end_ms:4L >>= require >>= fun value -> assert (value=None);
      aggregate ~start_ms:5L ~end_ms:6L >>= require >>= fun value -> assert (value=Some 0.);
      Lwt_list.iter_s (fun start_ms -> aggregate ~start_ms ~end_ms:(Int64.succ start_ms) >>= function
        | Error (Android.Error.Protocol _) -> Lwt.return_unit | _ -> Lwt.fail_with "Invalid scalar aggregate") [7L;9L] >>= fun () ->
      aggregate ~start_ms:11L ~end_ms:12L >>= function
        | Error (Android.Error.Permission_denied _) -> Lwt.return_unit | _ -> Lwt.fail_with "Health permission error")
      [Android.Health_connect.aggregate_distance,1234.5;Android.Health_connect.aggregate_active_calories,12.25;Android.Health_connect.aggregate_total_calories,234.75] >>= fun () ->
    Android.Location.distance_between ~latitude1:0. ~longitude1:0. ~latitude2:0. ~longitude2:1. >>= require >>= fun meters -> assert (meters=111319.4921875);
    Android.Permissions.check_special Background_location >>= require >>= fun granted -> assert (not granted);
    Android.Permissions.open_settings activity Background_location >>= require >>= fun () ->
    let query : Android.Health_connect.query = {start_ms=1L;end_ms=2L;page_size=2;page_token=None} in
    Android.Health_connect.read_steps query >>= require >>= fun page ->
    assert (page.next_page_token=Some "next");
    let record=List.hd page.records in assert (record.count=9007199254740993L && record.metadata.origin="test.source");
    assert (record.interval.start_offset_s=Some 3600 && record.interval.end_offset_s=None);
    Android.Health_connect.read_steps {query with page_token=page.next_page_token} >>= require >>= fun page -> assert (page.records=[] && page.next_page_token=None);
    Android.Health_connect.read_heart_rate query >>= require >>= fun page -> assert (List.length (List.hd page.records).samples=2);
    Android.Health_connect.read_sleep query >>= require >>= fun page -> let sleep=List.hd page.records in assert (sleep.title=Some "" && sleep.notes=None && (List.hd sleep.stages).stage_type=9999);
    Android.Health_connect.read_exercise query >>= require >>= fun page -> assert ((List.hd page.records).exercise_type=56);
    Android.Health_connect.read_steps {query with page_token=Some "broken"} >>= (function Error (Android.Error.Protocol _) -> Lwt.return_unit | _ -> Lwt.fail_with "Malformed health response") >>= fun () ->
    Android.Health_connect.aggregate_steps ~start_ms:1L ~end_ms:2L >>= require >>= fun count -> assert (count=Some 42L);
    Android.Health_connect.aggregate_steps ~start_ms:3L ~end_ms:4L >>= require >>= fun count -> assert (count=None);
    Android.Location.last_known ~provider:Gps >>= require >>= (function Some fix -> check_fix fix;Lwt.return_unit | None -> Lwt.fail_with "GPS fix") >>= fun () ->
    Android.Location.last_known ~provider:Network >>= require >>= fun fix -> assert (fix=None);
    Android.Location.current ~provider:Gps ~timeout_ms:1000 >>= require >>= (function Some fix -> check_fix fix;Lwt.return_unit | None -> Lwt.fail_with "GPS current") >>= fun () ->
    Android.Location.current ~provider:Network ~timeout_ms:1000 >>= require >>= fun fix -> assert (fix=None);
    let request=Android.Location.current ~provider:Gps ~timeout_ms:999 in
    let rec ready () = Android.Location.enabled ~provider:Network >>= require >>= fun is_ready ->
      if is_ready then Lwt.return_unit else Lwt.pause () >>= ready in
    ready () >>= fun () -> Lwt.cancel request;
    Lwt.catch (fun () -> request >>= fun _ -> Lwt.fail_with "Expected cancelled location") (function Lwt.Canceled -> Lwt.return_unit | e -> Lwt.fail e) >>= fun () ->
    let update,wake=Lwt.wait () in
    Android.Location.watch ~provider:Gps ~interval_ms:1000L ~min_distance_m:1. (function
      | Fix fix -> check_fix fix;Lwt.wakeup_later wake ();Lwt.return_unit
      | _ -> Lwt.fail_with "GPS event") >>= require >>= fun subscription ->
    update >>= fun () -> Android.Subscription.cancel subscription >>= require >>= fun () ->
    Android.Location.start_tracking_service ~provider:Gps ~interval_ms:1000L ~min_distance_m:1. ~notification_id:7 ~channel:"tracking" ~title:"GPS" ~text:"Test" ~small_icon:1 ~handler:"gps.handler" >>= require >>= fun () ->
    background >>= fun () -> Android.Location.stop_tracking_service () >>= require >>= fun () ->
    let schema=match Android.Settings.define ~store:"jni-settings" [
      Toggle {key="enabled";title="Enabled";description=None;initial=false};
      Integer {key="count";title="Count";description=None;initial=1L;validate=Some (fun n -> if n>0L then Ok () else Error "Positive required")};
      Text {key="fail";title="Failure";description=None;initial="";multiline=false;validate=None};
      Choice {key="mode";title="Mode";description=None;initial="a";options=["a","A";"b","B"]}]
      with Ok s -> s | Error _ -> failwith "Settings schema" in
    Android.Settings.get schema ~key:"enabled" >>= require >>= fun v -> assert (v=Bool false);
    Android.Storage.Preferences.get ~store:"jni-settings" ~key:"enabled" >>= require >>= fun v -> assert (v=None);
    Android.Settings.set schema ~key:"mode" (Text "b") >>= require >>= fun () ->
    Android.Settings.set schema ~key:"fail" (Text "x") >>= (function Error (Android.Error.Platform _) -> Lwt.return_unit | _ -> Lwt.fail_with "Expected storage failure") >>= fun () ->
    Android.Settings.set schema ~key:"mode" (Text "bad") >>= (function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Invalid choice") >>= fun () ->
    let changed,changed_wake=Lwt.wait () in let changes=ref 0 in
    Android.Settings.create activity schema ~on_error:(fun _ -> Lwt.fail_with "Unexpected settings error")
      ~on_change:(fun ~key value -> incr changes;
        Android.Settings.get schema ~key >>= require >>= fun stored -> assert (stored=value);
        if !changes=2 then Lwt.wakeup_later changed_wake ();Lwt.return_unit) >>= require >>= fun page ->
    Android.Settings.refresh page >>= require >>= fun () -> changed >>= fun () ->
    Android.Settings.get schema ~key:"count" >>= require >>= fun v -> assert (v=Int Int64.max_int);
    Android.Storage.Preferences.set ~store:"jni-settings" ~key:"count" (Text "wrong type") >>= require >>= fun () ->
    Android.Settings.get schema ~key:"count" >>= (function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Invalid persisted type") >>= fun () ->
    Android.Settings.release page >>= require >>= fun () -> Android.Settings.release page >>= require >>= fun () ->
    let registered_schema=match Android.Settings.define ~store:"jni-native" [] with Ok s -> s | Error _ -> assert false in
    assert (Android.Settings.register ~name:"jni-native" registered_schema
      ~on_change:(fun ~key:_ _ -> Lwt.return_unit)
      ~on_error:(fun _ -> Lwt.fail_with "Native settings launch failed")=Ok ());
    Android.Settings.open_ activity >>= require >>= fun () ->
    let rec opened () = Android.Storage.Preferences.get ~store:"native-launch" ~key:"complete" >>= require >>= function
      | Some (Bool true) -> Lwt.return_unit | _ -> Lwt.pause () >>= opened in
    opened () >>= fun () ->
    Auth_smoke.run activity >>= fun () ->
    Gc.full_major ();
    Android.Storage.write_file ~name:"done" ~contents:binary >>= require) >>= require >|= fun _ -> ())
