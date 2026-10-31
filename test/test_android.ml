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
    Android.Storage.read_file ~name:"host-check" >|= function
    | Error (Android.Error.Unavailable _) -> ()
    | _ -> failwith "Host must report Android unavailable");
  assert (Android.Runtime.register ~name:"test" (fun _ -> Lwt.return Android.Background.Success) = Ok ());
  assert (match Android.Runtime.register ~name:"test" (fun _ -> Lwt.return Android.Background.Success) with Error (Android.Error.Invalid_argument _) -> true | _ -> false)
