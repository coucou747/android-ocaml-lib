open Lwt.Infix
module D=Android.Dialog
module F=Android.Form
let require = function Ok x -> Lwt.return x | Error _ -> Lwt.fail_with "UI component smoke failed"
let buttons = match D.buttons [{role=Positive;label="Save"};{role=Negative;label="Cancel"}] with Ok b -> b | _ -> assert false
let run activity =
  let invalid task = task >>= function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Expected UI argument error" in
  invalid (Android.Ui.spacer activity ~width_dp:(-1) ~height_dp:0) >>= fun () ->
  invalid (F.text_field activity ~label:"" ~help:None ~text:"" ~keyboard:Text ~multiline:false ~validate:None) >>= fun () ->
  invalid (F.text_field activity ~label:"Number" ~help:None ~text:"" ~keyboard:(Number {signed=true;decimal=true}) ~multiline:true ~validate:None) >>= fun () ->
  F.container activity >>= require >>= fun form ->
  F.text_field activity ~label:"Name" ~help:(Some "Required") ~text:"" ~keyboard:Text ~multiline:false
    ~validate:(Some (fun s -> if String.trim s="" then Error "Required" else Ok ())) >>= require >>= fun field ->
  Android.Ui.add form (F.view field) >>= require >>= fun () ->
  F.validate field >>= require >>= fun valid -> assert (not valid);
  let presses=ref 0 in
  D.show activity ~title:"custom" ~content:form ~buttons ~cancelable:true
    ~on_error:(fun _ -> Lwt.fail_with "Dialog failed")
    ~on_button:(fun _ -> incr presses; F.validate field >>= require >>= fun valid ->
      if valid then Lwt.return (Ok D.Close) else F.set_text field "Alice" >>= require >|= fun () -> Ok D.Keep_open) >>= require >>= fun dialog ->
  D.closed dialog >>= require >>= fun outcome -> assert (outcome=D.Button Positive && !presses=2);
  D.close dialog >>= require >>= fun () ->
  let edited,wake=Lwt.wait () in
  F.on_change field ~on_error:(fun _ -> Lwt.fail_with "Live validation failed") (fun text valid -> assert (text="Bob" && valid);if Lwt.is_sleeping edited then Lwt.wakeup_later wake ();Lwt.return_unit) >>= require >>= fun subscription ->
  F.set_text field "Bob" >>= require >>= fun () -> edited >>= fun () ->
  Android.Subscription.cancel subscription >>= require >>= fun () ->
  F.set_help field None >>= require >>= fun () -> F.set_error field (Some "Server error") >>= require >>= fun () ->
  Android.Ui.primary_button activity "Save" >>= require >>= fun primary ->
  Android.Ui.secondary_button activity "Cancel" >>= require >>= fun secondary ->
  Android.Ui.spacer activity ~width_dp:0 ~height_dp:8 >>= require >>= fun gap ->
  Android.Ui.set_padding form {left_dp=16;top_dp=16;right_dp=16;bottom_dp=16} >>= require >>= fun () ->
  Android.Ui.set_margins primary {left_dp=0;top_dp=8;right_dp=0;bottom_dp=0} >>= require >>= fun () ->
  F.release field >>= require >>= fun () -> F.release field >>= require >>= fun () ->
  (F.validate field >>= function Error (Android.Error.Invalid_handle _) -> Lwt.return_unit | _ -> Lwt.fail_with "Released field is usable") >>= fun () ->
  Lwt_list.iter_s (fun v -> Android.Ui.release v >>= require) [primary;secondary;gap;form] >>= fun () ->
  D.alert activity ~title:"alert" ~message:"component:message" ~button:"OK" >>= require >>= fun () ->
  D.confirm activity ~title:"confirm" ~message:"component:message" ~accept:"Yes" ~cancel:"No" >>= require >>= fun accepted -> assert accepted;
  D.confirm activity ~title:"negative" ~message:"component:message" ~accept:"Yes" ~cancel:"No" >>= require >>= fun accepted -> assert (not accepted);
  D.confirm activity ~title:"dismiss" ~message:"component:message" ~accept:"Yes" ~cancel:"No" >>= require >>= fun accepted -> assert (not accepted);
  F.text_field activity ~label:"Throwing validator" ~help:None ~text:"" ~keyboard:Text ~multiline:false
    ~validate:(Some (fun _ -> failwith "validator fixture")) >>= require >>= fun throwing ->
  (F.validate throwing >>= function Error (Android.Error.Platform _) -> Lwt.return_unit | _ -> Lwt.fail_with "Validator exception not structured") >>= fun () ->
  F.release throwing >>= require >>= fun () ->
  F.container activity >>= require >>= fun content ->
  D.show activity ~title:"wait" ~content ~buttons ~cancelable:true ~on_button:(fun _ -> Lwt.return (Ok D.Close))
    ~on_error:(fun _ -> Lwt.fail_with "Custom dialog failed") >>= require >>= fun custom ->
  let waiter=D.closed custom in Lwt.cancel waiter;
  Lwt.catch (fun () -> waiter >>= fun _ -> Lwt.fail_with "Expected cancelled waiter") (function Lwt.Canceled -> Lwt.return_unit | exn -> Lwt.fail exn) >>= fun () ->
  D.close custom >>= require >>= fun () -> D.closed custom >>= require >>= fun outcome -> assert (outcome=D.Dismissed);
  Android.Ui.release content >>= require >>= fun () ->
  Android.Ui.primary_button activity "component:ready" >>= require >>= fun probe ->
  let pending=D.alert activity ~title:"wait" ~message:"component:message" ~button:"OK" in
  let rec ready () = Android.Ui.get_text probe >>= require >>= fun value -> if value="true" then Lwt.return_unit else Lwt.pause () >>= ready in
  ready () >>= fun () -> Lwt.cancel pending;
  Lwt.catch (fun () -> pending >>= fun _ -> Lwt.fail_with "Dialog cancellation did not close") (function Lwt.Canceled -> Lwt.return_unit | exn -> Lwt.fail exn) >>= fun () ->
  Android.Ui.release probe >>= require
