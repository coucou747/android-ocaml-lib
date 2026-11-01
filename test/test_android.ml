open Lwt.Infix
let expect_invalid label task = task >|= function
  | Error (Android.Error.Invalid_argument _) -> ()
  | _ -> failwith ("Expected argument error: " ^ label)
let () =
  Printexc.record_backtrace true;
  Lwt_main.run (
    expect_invalid "empty alarm id"
      (Android.Alarm.schedule ~id:"" ~at_ms:1L ~mode:Exact ~handler:"test" ~payload:"") >>= fun () ->
    expect_invalid "negative timestamp"
      (Android.Alarm.schedule ~id:"test" ~at_ms:(-1L) ~mode:Exact ~handler:"test" ~payload:"") >>= fun () ->
    expect_invalid "empty background name"
      (Android.Background.enqueue ~name:"" ~handler:"test" ~payload:"" ~constraints:{network=false;charging=false} ~interval_ms:None) >>= fun () ->
    expect_invalid "HTTP timeout"
      (Android.Network.Http.request ~url:"https://unused.invalid" ~meth:"GET" ~headers:[] ~body:"" ~connect_timeout_ms:0 ~read_timeout_ms:1) >>= fun () ->
    expect_invalid "negative vibration" (Android.Vibrator.vibrate ~duration_ms:(-1)) >>= fun () ->
    expect_invalid "empty alarm screen id" (Android.Alarm.Screen.dismiss ~id:"" ~notification_id:1) >>= fun () ->
    expect_invalid "invalid alarm screen notification" (Android.Alarm.Screen.notify ~channel:"" ~small_icon:1 ~full_screen:true
      {id="test"; notification_id=1; title=""; text=""; stop_label="Stop"; snooze_label="Snooze";
       stop_handler="stop"; snooze_handler="snooze"; payload=""}) >>= fun () ->
    expect_invalid "qualified resource name" (Android.Resources.resolve ~scope:Android ~kind:Drawable ~name:"android:drawable/icon") >>= fun () ->
    expect_invalid "missing resource name" (Android.Resources.resolve ~scope:Application ~kind:Mipmap ~name:"") >>= fun () ->
    expect_invalid "invalid health interval" (Android.Health_connect.read_steps {start_ms=2L;end_ms=1L;page_size=1;page_token=None}) >>= fun () ->
    expect_invalid "invalid health page" (Android.Health_connect.read_sleep {start_ms=1L;end_ms=2L;page_size=0;page_token=None}) >>= fun () ->
    expect_invalid "invalid aggregate interval" (Android.Health_connect.aggregate_steps ~start_ms:2L ~end_ms:1L) >>= fun () ->
    Lwt_list.iter_s (fun aggregate -> expect_invalid "invalid scalar aggregate interval" (aggregate ~start_ms:2L ~end_ms:1L))
      [Android.Health_connect.aggregate_distance;Android.Health_connect.aggregate_active_calories;Android.Health_connect.aggregate_total_calories] >>= fun () ->
    Lwt_list.iter_s (fun (latitude1,longitude1,latitude2,longitude2) ->
      expect_invalid "invalid geodesic coordinates" (Android.Location.distance_between ~latitude1 ~longitude1 ~latitude2 ~longitude2))
      [nan,0.,0.,0.;0.,infinity,0.,0.;91.,0.,0.,0.;0.,0.,0.,181.] >>= fun () ->
    expect_invalid "GPS timeout" (Android.Location.current ~provider:Gps ~timeout_ms:0) >>= fun () ->
    expect_invalid "GPS negative sampling" (Android.Location.watch ~provider:Gps ~interval_ms:(-1L) ~min_distance_m:0. (fun _ -> Lwt.return_unit)) >>= fun () ->
    expect_invalid "GPS nonfinite distance" (Android.Location.watch ~provider:Gps ~interval_ms:1L ~min_distance_m:nan (fun _ -> Lwt.return_unit)) >>= fun () ->
    expect_invalid "storage URI path traversal" (Android.Storage.file_uri ~name:"../outside") >>= fun () ->
    expect_invalid "storage URI NUL" (Android.Storage.file_uri ~name:"bad\000name") >>= fun () ->
    Android.Activity.launch_main () >>= (function
      | Error (Android.Error.Unavailable _) -> Lwt.return_unit
      | _ -> Lwt.fail_with "Host launch must report Android unavailable") >>= fun () ->
    Android.Storage.read_file ~name:"host-check" >|= function
    | Error (Android.Error.Unavailable _) -> ()
    | _ -> failwith "Host must report Android unavailable");
  assert (Android.Runtime.register ~name:"test" (fun _ -> Lwt.return Android.Background.Success) = Ok ());
  assert (match Android.Runtime.register ~name:"test" (fun _ -> Lwt.return Android.Background.Success) with Error (Android.Error.Invalid_argument _) -> true | _ -> false)
