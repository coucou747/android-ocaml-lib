open Lwt.Infix
module B=Android.Bluetooth
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "Connectivity smoke failure"
let run activity =
 B.available () >>= require >>= fun available -> assert available;
 B.Permissions.required Connect >>= require >>= fun permissions -> assert (permissions=["android.permission.BLUETOOTH_CONNECT"]);
 B.bonded_devices () >>= require >>= fun devices -> let device=List.hd devices in assert (device.name=Some "Écouteur 🐫");
 let found,wake=Lwt.wait () in
 B.scan activity ~mode:Without_location ~timeout_ms:100 ~filters:[{service_uuid=None;name=None;manufacturer=Some (1,"\000\255")}] (fun e -> assert (e.kind="found" && e.status = -60);Lwt.wakeup_later wake ();Lwt.return_unit) >>= require >>= fun scan ->
 found >>= fun () -> Android.Subscription.cancel scan >>= require >>= fun () ->
 B.Gatt.connect activity device ~connect_timeout_ms:100 ~operation_timeout_ms:100 >>= require >>= fun gatt ->
 B.Gatt.discover gatt >>= require >>= fun attributes -> let characteristic=List.hd attributes in
 B.Gatt.read gatt characteristic >>= require >>= fun data -> assert (data="\000\255\007");
 B.Gatt.write gatt characteristic ~with_response:true data >>= require >>= fun () ->
 B.Gatt.notifications gatt characteristic ~indicate:false ~enabled:true >>= require >>= fun () ->
 B.Gatt.mtu gatt 100 >>= require >>= fun mtu -> assert (mtu=100);
 B.Gatt.rssi gatt >>= require >>= fun rssi -> assert (rssi = -45);
 let received,wake=Lwt.wait () in B.Gatt.on_event gatt (fun e -> assert (e.data="data");Lwt.wakeup_later wake ();Lwt.return_unit) >>= require >>= fun sub -> received >>= fun () -> Android.Subscription.cancel sub >>= require >>= fun () ->
 B.Rfcomm.connect activity device ~uuid:"00001101-0000-1000-8000-00805f9b34fb" ~secure:true ~connect_timeout_ms:100 ~operation_timeout_ms:100 >>= require >>= fun serial ->
 B.Rfcomm.write serial data >>= require >>= fun () -> B.Rfcomm.read serial ~max_bytes:3 >>= require >>= fun result -> assert (result=Some data);
 B.Rfcomm.read serial ~max_bytes:3 >>= require >>= fun result -> assert (result=None);
 B.Rfcomm.close serial >>= require >>= fun () ->
 let waiting=B.Gatt.connect activity {device with address="wait"} ~connect_timeout_ms:100 ~operation_timeout_ms:100 in
 let rec started () = B.enabled () >>= require >>= fun yes -> if yes then Lwt.return_unit else Lwt.pause () >>= started in
 started () >>= fun () -> Lwt.cancel waiting;
 Lwt.catch (fun () -> waiting >|= fun _ -> ()) (function Lwt.Canceled -> Lwt.return_unit | exn -> Lwt.fail exn) >>= fun () ->
 Android.Audio.create activity ~usage:Media ~uri:"file:///audio" ~loop:false >>= require >>= fun player ->
 Android.Audio.set_volume player 0.5 >>= require >>= fun () -> Android.Audio.pause player >>= require >>= fun () -> Android.Audio.resume player >>= require >>= fun () ->
 Android.Audio.seek player ~position_ms:200 >>= require >>= fun () -> Android.Audio.position player >>= require >>= fun position -> assert (position=200);
 Android.Audio.outputs () >>= require >>= fun outputs -> assert ((List.hd outputs).name="Bluetooth");
 Android.Audio.preferred_output player (Some 1) >>= require >>= fun () ->
 let bundle=Android.Connectivity_settings.volume ~key:"volume" ~title:"Volume" ~description:"Percentage" ~initial:50L ~player:(fun () -> Some player) in
 assert (match Android.Settings.define ~store:"connectivity-test" bundle.items with Ok _ -> true | _ -> false);
 let event,wake=Lwt.wait () in
 assert (Android.Connectivity.register_handler ~name:"connectivity-test" (fun e -> assert (e.kind="service_started");Lwt.wakeup_later wake ();Lwt.return Android.Background.Success)=Ok ());
 let notification:Android.Connectivity.notification={id=1;channel="test";small_icon=1;title="Session";text="Connected";pause_label="Pause";resume_label="Play";stop_label="Stop"} in
 Android.Connectivity.start_bluetooth activity ~notification ~handler:"connectivity-test" [Gatt gatt] >>= require >>= fun () -> event >>= fun () ->
 Android.Connectivity.stop_bluetooth () >>= require >>= fun () -> B.Gatt.close gatt >>= require >>= fun () -> Android.Audio.stop player >>= require
