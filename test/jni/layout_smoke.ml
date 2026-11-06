open Lwt.Infix
module U=Android.Ui
let require = function Ok v -> Lwt.return v | Error _ -> Lwt.fail_with "Layout smoke failed"
let pure = function Ok v -> v | Error _ -> failwith "Layout fixture invalid"
let wait_event subscribe = let task,wake=Lwt.wait () in
  subscribe (fun value -> if Lwt.is_sleeping task then Lwt.wakeup_later wake value;Lwt.return_unit) >>= require >>= fun sub ->
  task >>= fun value -> Android.Subscription.cancel sub >>= require >|= fun () -> value
let run activity =
  let insets:U.insets={left_dp=16;right_dp=16;top_dp=8;bottom_dp=8} in
  let weighted=pure (U.layout ~width:(Dp 0) ~height:Wrap_content ~weight:1. ~margin:insets ~alignment:{horizontal=End;vertical=Middle} ()) in
  U.column activity ~spacing_dp:8 ~padding:insets >>= require >>= fun column ->
  U.apply_system_bar_insets column >>= require >>= fun () ->
  U.apply_system_bar_insets column >>= require >>= fun () ->
  U.row activity ~spacing_dp:8 ~alignment:{horizontal=Start;vertical=Middle} >>= require >>= fun row ->
  U.stack activity ~alignment:{horizontal=End;vertical=Bottom} >>= require >>= fun stack ->
  U.nested_scroll_view activity ~fill_viewport:true >>= require >>= fun scroll ->
  U.constraint_layout activity >>= require >>= fun constraints ->
  U.add ~layout:weighted row stack >>= require >>= fun () -> U.set_layout row weighted >>= require >>= fun () ->
  Android.Constraint.connect ~parent:constraints stack ~edge:Start ~target:Parent ~target_edge:Start ~margin_dp:8 >>= require >>= fun () ->
  U.set_visibility stack Gone >>= require >>= fun () -> U.set_visibility stack Visible >>= require >>= fun () ->
  Android.Constraint.clear ~parent:constraints stack ~edge:Start >>= require >>= fun () ->
  Android.Constraint.set_bias ~parent:constraints stack ~horizontal:0.25 ~vertical:0.5 >>= require >>= fun () ->
  let invalid task = task >>= function Error (Android.Error.Invalid_argument _) -> Lwt.return_unit | _ -> Lwt.fail_with "Invalid layout accepted" in
  invalid (U.row activity ~spacing_dp:(-1)) >>= fun () ->
  invalid (Android.Constraint.connect ~parent:constraints stack ~edge:Start ~target:Parent ~target_edge:Bottom ~margin_dp:0) >>= fun () ->
  invalid (Android.Constraint.set_bias ~parent:constraints stack ~horizontal:nan ~vertical:0.) >>= fun () ->
  let actions:Android.Toolbar.action list=[{id=101;title="Add";icon=Some 1;display=If_room}] in
  Android.Toolbar.create activity ~title:"Tasks" ~subtitle:(Some "Today") ~navigation_icon:(Some 1) ~navigation_description:(Some "Back") ~actions >>= require >>= fun toolbar ->
  wait_event (Android.Toolbar.on_action toolbar) >>= fun id -> assert (id=101);
  wait_event (fun callback -> Android.Toolbar.on_navigation toolbar (fun () -> callback ())) >>= fun () ->
  Android.Toolbar.set_title toolbar "Updated" >>= require >>= fun () -> Android.Toolbar.set_subtitle toolbar None >>= require >>= fun () ->
  Android.Toolbar.set_actions toolbar [] >>= require >>= fun () ->
  invalid (Android.Toolbar.set_actions toolbar [List.hd actions;List.hd actions]) >>= fun () ->
  Android.Card.create activity ~content_padding:insets >>= require >>= fun card ->
  let items:Android.Bottom_navigation.item list=[{id=1;title="Tasks";icon=1};{id=2;title="Settings";icon=2}] in
  Android.Bottom_navigation.create activity ~items ~selected:1 >>= require >>= fun nav ->
  Android.Bottom_navigation.selected nav >>= require >>= fun initial -> assert (initial=1);
  wait_event (Android.Bottom_navigation.on_select nav) >>= fun id -> assert (id=2);
  Android.Bottom_navigation.select nav 1 >>= require >>= fun () -> Android.Bottom_navigation.selected nav >>= require >>= fun id -> assert (id=1);
  invalid (Android.Bottom_navigation.create activity ~items:[List.hd items;List.hd items] ~selected:1) >>= fun () ->
  Android.Check_box.create activity ~label:"Task" ~state:Indeterminate >>= require >>= fun check ->
  Android.Check_box.state check >>= require >>= fun state -> assert (state=Indeterminate);
  wait_event (Android.Check_box.on_change check) >>= fun state -> assert (state=Checked);
  Android.Check_box.set_state check Unchecked >>= require >>= fun () -> Android.Check_box.state check >>= require >>= fun state -> assert (state=Unchecked);
  Android.Switch.create activity ~label:"Show completed" ~checked:false >>= require >>= fun switch ->
  wait_event (Android.Switch.on_change switch) >>= fun checked -> assert checked;
  Android.Switch.set_checked switch false >>= require >>= fun () -> Android.Switch.checked switch >>= require >>= fun checked -> assert (not checked);
  Android.Divider.create activity >>= require >>= fun divider ->
  Lwt_list.iter_s (fun v -> U.release v >>= require) [column;row;stack;scroll;constraints;toolbar;card;nav;check;switch;divider]
