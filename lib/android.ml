open Lwt.Infix

module Error = struct
  type t = Unavailable of string | Permission_denied of string
    | Invalid_argument of string | Invalid_handle of string
    | Platform of string | Protocol of string | Cancelled
end

type 'a response = ('a, Error.t) result Lwt.t
external invoke : string -> string array -> string array = "android_invoke"
external take_event : unit -> string array = "android_take_event"

let decode = function
  | [|"error"; "cancelled"; _|] -> Error Error.Cancelled
  | [|"error"; "permission"; s|] -> Error (Error.Permission_denied s)
  | [|"error"; "argument"; s|] -> Error (Error.Invalid_argument s)
  | [|"error"; "handle"; s|] -> Error (Error.Invalid_handle s)
  | [|"error"; "unavailable"; s|] -> Error (Error.Unavailable s)
  | [|"error"; _; s|] -> Error (Error.Platform s)
  | a when Array.length a > 0 && a.(0) = "ok" ->
    Ok (Array.sub a 1 (Array.length a - 1))
  | _ -> Error (Error.Protocol "Invalid JNI response")

let call name args =
  (* Cancellation cannot interrupt arbitrary Android platform calls. Do not
     abandon a resource-producing call; specific cancel/close APIs release it. *)
  Lwt.protected (Lwt_preemptive.detach (fun () -> decode (invoke name (Array.of_list args))) ())
let map f p = p >|= Result.map f
let unit_call n a = map (fun _ -> ()) (call n a)
let one n a = call n a >|= function
  | Ok [|s|] -> Ok s | Error e -> Error e
  | Ok _ -> Error (Error.Protocol (n ^ ": expected one value"))
let bool n a = map ((=) "true") (one n a)
let int n a = one n a >|= function
  | Error e -> Error e
  | Ok s -> (match int_of_string_opt s with Some n -> Ok n
    | None -> Error (Error.Protocol (n ^ ": expected integer")))
let invalid s = Lwt.return (Error (Error.Invalid_argument s))
let s = string_of_int
let i64 = Int64.to_string

module Subscription = struct
  type t = { id : string; mutable active : bool }
  let callbacks : (string, string array -> unit Lwt.t) Hashtbl.t = Hashtbl.create 16
  let next = ref 0
  let create callback =
    incr next;
    let t = {id = s !next; active = true} in
    Hashtbl.add callbacks t.id callback; t
  let cancel t =
    if t.active then (t.active <- false; Hashtbl.remove callbacks t.id);
    unit_call "unsubscribe" [t.id]
end

let subscribe command args callback =
  let t = Subscription.create callback in
  unit_call command (t.id :: args) >|= function
  | Ok () -> Ok t
  | Error e -> Hashtbl.remove Subscription.callbacks t.id; Error e

module Activity = struct
  type t = string
  type event = Created | Started | Resumed | Paused | Stopped | Destroyed
  type size = { width_px : int; height_px : int }
  let display_size activity = call "activity.displaySize" [activity] >|= function
    | Error e -> Error e
    | Ok [|w;h|] -> (match int_of_string_opt w,int_of_string_opt h with
      | Some width_px,Some height_px when width_px > 0 && height_px > 0 -> Ok {width_px;height_px}
      | _ -> Error (Error.Protocol "Invalid activity display size"))
    | Ok _ -> Error (Error.Protocol "Activity display size")
  let current () = one "activity.current" []
  let launch_main () = unit_call "activity.launchMain" []
  let set_keep_screen_on t value = unit_call "activity.screen" [t; string_of_bool value]
  let on_lifecycle f = subscribe "activity.lifecycle" [] (fun a ->
    match a with
    | [|id; ev|] ->
      let ev = match ev with "created" -> Created | "started" -> Started
        | "resumed" -> Resumed | "paused" -> Paused | "stopped" -> Stopped
        | _ -> Destroyed in f id ev
    | _ -> Lwt.return_unit)
end

module Ui = struct
  type view = string
  type orientation = Horizontal | Vertical
  let text a label = one "ui.text" [a; label]
  let button a label = one "ui.button" [a; label]
  let primary_button a label = one "ui.primaryButton" [a;label]
  let secondary_button a label = one "ui.secondaryButton" [a;label]
  type insets = {left_dp:int;top_dp:int;right_dp:int;bottom_dp:int}
  let dimensions_valid values = List.for_all (fun n -> n>=0 && n<=Int32.to_int Int32.max_int) values
  let insets_call command view insets =
    let values=[insets.left_dp;insets.top_dp;insets.right_dp;insets.bottom_dp] in
    if not (dimensions_valid values) then invalid "Expected nonnegative Android layout dimensions" else unit_call command (view::List.map s values)
  let set_padding = insets_call "ui.padding"
  let apply_system_bar_insets view = unit_call "ui.systemBarInsets" [view]
  let set_margins = insets_call "ui.margins"
  type dimension = Wrap_content | Match_parent | Dp of int
  type horizontal = Start | Center | End
  type vertical = Top | Middle | Bottom
  type alignment = {horizontal:horizontal;vertical:vertical}
  type layout = {width:dimension option;height:dimension option;margin:insets option;weight:float option;alignment:alignment option}
  let valid_insets x = dimensions_valid [x.left_dp;x.top_dp;x.right_dp;x.bottom_dp]
  let layout ?width ?height ?margin ?weight ?alignment () =
    let valid_dimension = function None | Some Wrap_content | Some Match_parent -> true | Some (Dp n) -> dimensions_valid [n] in
    if not (valid_dimension width && valid_dimension height) ||
      not (Option.fold ~none:true ~some:valid_insets margin) ||
      not (Option.fold ~none:true ~some:(fun n -> Float.is_finite n && n>=0. && n<=3.4028234663852886e38) weight)
    then Error (Error.Invalid_argument "Invalid layout dimensions, margin or weight") else Ok {width;height;margin;weight;alignment}
  let alignment_json a = `List [`String (match a.horizontal with Start -> "start" | Center -> "center" | End -> "end");
    `String (match a.vertical with Top -> "top" | Middle -> "middle" | Bottom -> "bottom")]
  let insets_json x = `List (List.map (fun n -> `Int n) [x.left_dp;x.top_dp;x.right_dp;x.bottom_dp])
  let layout_json l =
    let dimension = function Wrap_content -> `String "wrap" | Match_parent -> `String "match" | Dp n -> `Int n in
    let optional key f value = match value with None -> [] | Some v -> [key,f v] in
    `Assoc (optional "width" dimension l.width @ optional "height" dimension l.height @ optional "margin" insets_json l.margin @
      optional "weight" (fun n -> `Float n) l.weight @ optional "alignment" alignment_json l.alignment)
  let set_layout view layout = unit_call "ui.setLayout" [view;Yojson.Safe.to_string (layout_json layout)]
  let configured ?padding ?layout create =
    if not (Option.fold ~none:true ~some:valid_insets padding) then invalid "Invalid container padding" else
    Lwt.no_cancel (create () >>= function Error e -> Lwt.return (Error e) | Ok view ->
      (match padding with None -> Lwt.return (Ok ()) | Some p -> set_padding view p) >>= fun padded ->
      (match padded,layout with Error e,_ -> Lwt.return (Error e) | Ok (),None -> Lwt.return (Ok ()) | Ok (),Some l -> set_layout view l) >>= function
      | Ok () -> Lwt.return (Ok view)
      | Error e -> unit_call "ui.release" [view] >|= fun _ -> Error e)
  let linear kind ?(spacing_dp=0) ?padding ?alignment ?layout activity =
    if not (dimensions_valid [spacing_dp]) then invalid "Invalid layout spacing" else
    configured ?padding ?layout (fun () -> one "ui.container" [activity;kind;s spacing_dp;(match alignment with None -> "" | Some a -> Yojson.Safe.to_string (alignment_json a))])
  let column = linear "column"
  let row = linear "row"
  let stack ?padding ?alignment ?layout activity = configured ?padding ?layout (fun () -> one "ui.container" [activity;"stack";"0";(match alignment with None -> "" | Some a -> Yojson.Safe.to_string (alignment_json a))])
  let nested_scroll_view ?(fill_viewport=false) ?padding ?layout activity = configured ?padding ?layout (fun () -> one "ui.nestedScroll" [activity;string_of_bool fill_viewport])
  let constraint_layout ?padding ?layout activity = configured ?padding ?layout (fun () -> one "ui.constraintLayout" [activity])
  let spacer ?layout a ~width_dp ~height_dp = if not (dimensions_valid [width_dp;height_dp]) then invalid "Expected nonnegative Android spacer dimensions"
    else configured ?layout (fun () -> one "ui.spacer" [a;s width_dp;s height_dp])
  let edit_text a text = one "ui.edit" [a; text]
  let linear_layout a orientation = one "ui.layout" [a; (match orientation with Horizontal -> "horizontal" | Vertical -> "vertical")]
  let scroll_view a = one "ui.scroll" [a]
  let image a ~uri ~max_width_px ~max_height_px =
    if uri = "" || max_width_px <= 0 || max_height_px <= 0 then invalid "Image URI and positive bounds are required"
    else one "ui.image" [a; uri; s max_width_px; s max_height_px]
  let set_image_uri view ~uri ~max_width_px ~max_height_px =
    if uri = "" || max_width_px <= 0 || max_height_px <= 0 then invalid "Image URI and positive bounds are required"
    else unit_call "ui.imageUri" [view; uri; s max_width_px; s max_height_px]
  type input_type = Text | Number of { signed : bool; decimal : bool }
  let set_input_type view typ =
    let kind, signed, decimal = match typ with Text -> "text",false,false
      | Number {signed;decimal} -> "number",signed,decimal in
    unit_call "ui.inputType" [view; kind; string_of_bool signed; string_of_bool decimal]
  let set_multiline view value = unit_call "ui.multiline" [view; string_of_bool value]
  let set_hint view text = unit_call "ui.hint" [view; text]
  let set_error view error = unit_call "ui.error" [view; (match error with None -> "false" | Some _ -> "true"); Option.value error ~default:""]
  let add ?layout parent child = match layout with None -> unit_call "ui.add" [parent;child]
    | Some l -> unit_call "ui.addLayout" [parent;child;Yojson.Safe.to_string (layout_json l)]
  let show a root = unit_call "ui.show" [a; root]
  let set_text v text = unit_call "ui.setText" [v; text]
  let get_text v = one "ui.getText" [v]
  let set_enabled v b = unit_call "ui.enabled" [v; string_of_bool b]
  type visibility = Visible | Invisible | Gone
  let set_visibility view = function Visible -> unit_call "ui.visibility" [view;"visible"] | Invisible -> unit_call "ui.visibility" [view;"invisible"] | Gone -> unit_call "ui.visibility" [view;"gone"]
  let release v = unit_call "ui.release" [v]
end

module Dialog = struct
  type role = Positive | Negative | Neutral
  type button = {role:role;label:string}
  type buttons = button list
  type decision = Close | Keep_open
  type outcome = Button of role | Dismissed
  type t = {id:string; subscription:Subscription.t; closed:outcome response; wake:(outcome,Error.t) result Lwt.u; mutable finished:bool}
  let name = function Positive -> "positive" | Negative -> "negative" | Neutral -> "neutral"
  let role = function "positive" -> Some Positive | "negative" -> Some Negative | "neutral" -> Some Neutral | _ -> None
  let buttons buttons =
    if List.length buttons>3 || List.exists (fun b -> b.label="") buttons ||
      List.length (List.sort_uniq compare (List.map (fun b -> b.role) buttons))<>List.length buttons
    then Error (Error.Invalid_argument "Dialog buttons need unique roles and nonempty labels") else Ok buttons
  let close dialog = unit_call "dialog.close" [dialog.id]
  let closed dialog = Lwt.protected dialog.closed
  let show_impl command activity ~title ~content ~buttons ~cancelable ~on_button ~on_error =
    let target=ref None and early=ref [] in
    let finish dialog result = if dialog.finished then Lwt.return_unit else (
      dialog.finished<-true;
      close dialog >>= fun disposed -> Subscription.cancel dialog.subscription >>= fun cancelled ->
      let result=match disposed,cancelled with Error e,_ | _,Error e -> Error e | _ -> result in
      if Lwt.is_sleeping dialog.closed then Lwt.wakeup_later dialog.wake result;
      Lwt.return_unit) in
    let handle dialog = function
      | [|"closed";reason|] -> finish dialog (match role reason with Some r -> Ok (Button r) | None -> Ok Dismissed)
      | [|"button";value|] when not dialog.finished ->
        (match role value with None -> finish dialog (Error (Error.Protocol "Dialog button role")) | Some r ->
          Lwt.catch (fun () -> on_button r) (fun exn -> Lwt.return (Error (Error.Platform (Printexc.to_string exn)))) >>= fun decision ->
          if dialog.finished then Lwt.return_unit else
          unit_call "dialog.resolve" [dialog.id;name r;(match decision with Ok Close -> "true" | _ -> "false")] >>= function
          | Error e -> finish dialog (Error e)
          | Ok () -> (match decision with Error e -> on_error e | _ -> Lwt.return_unit))
      | _ -> Lwt.return_unit in
    let subscription=Subscription.create (fun event -> match !target with None -> early:=event::!early;Lwt.return_unit | Some d -> handle d event) in
    Lwt.no_cancel (one command ([subscription.id;activity;title;content;string_of_bool cancelable] @
      List.concat_map (fun b -> [name b.role;b.label]) buttons) >>= function
    | Error e -> Subscription.cancel subscription >|= fun _ -> Error e
    | Ok id ->
      let promise,wake=Lwt.wait () in
      let dialog={id;subscription;closed=promise;wake;finished=false} in target:=Some dialog;
      Lwt_list.iter_s (handle dialog) (List.rev !early) >|= fun () -> Ok dialog)
  let show = show_impl "dialog.show"
  let message_dialog activity ~title ~message button_list =
    match buttons button_list with Error e -> Lwt.return (Error e) | Ok buttons ->
    show_impl "dialog.message" activity ~title ~content:message ~buttons ~cancelable:true
      ~on_button:(fun _ -> Lwt.return (Ok Close)) ~on_error:(fun _ -> Lwt.return_unit) >>= function
    | Error e -> Lwt.return (Error e)
    | Ok dialog -> Lwt.finalize (fun () -> closed dialog) (fun () -> close dialog >|= ignore)
  let alert activity ~title ~message ~button = message_dialog activity ~title ~message [{role=Positive;label=button}] >|= Result.map (fun _ -> ())
  let confirm activity ~title ~message ~accept ~cancel = message_dialog activity ~title ~message [{role=Positive;label=accept};{role=Negative;label=cancel}]
    >|= Result.map (function Button Positive -> true | _ -> false)
end


module Resources = struct
  type scope = Application | Android
  type kind = Drawable | Mipmap | Raw | String | Color
  let resolve ~scope ~kind ~name =
    if name = "" || String.contains name ':' || String.contains name '/' then invalid "Expected an unqualified resource name"
    else int "resources.resolve" [(match scope with Application -> "app" | Android -> "android");
      (match kind with Drawable -> "drawable" | Mipmap -> "mipmap" | Raw -> "raw" | String -> "string" | Color -> "color"); name]
end

module Locale = struct
  let time_zone () = one "locale.timeZone" []
  let language_tag () = one "locale.languageTag" []
  let uses_24_hour_clock () = bool "locale.is24Hour" []
  let format_date ~at_ms = one "locale.date" [i64 at_ms]
  let format_time ~at_ms = one "locale.time" [i64 at_ms]
  let format_date_time ~at_ms = one "locale.dateTime" [i64 at_ms]
end

module Events = struct
  type touch = { action : int; x : float; y : float }
  let on_click v f = subscribe "events.click" [v] (fun _ -> f ())
  let on_text_changed v f = subscribe "events.text" [v] (function
    | [|text|] -> f text | _ -> Lwt.return_unit)
  let on_touch v ~consume f = subscribe "events.touch" [v; string_of_bool consume]
    (function
    | [|a; x; y|] -> (match int_of_string_opt a, float_of_string_opt x, float_of_string_opt y with
      | Some action, Some x, Some y -> f {action; x; y} | _ -> Lwt.return_unit)
    | _ -> Lwt.return_unit)
end

module Constraint = struct
  type edge = Start | End | Top | Bottom | Baseline
  type target = Parent | View of Ui.view
  let name = function Start -> "start" | End -> "end" | Top -> "top" | Bottom -> "bottom" | Baseline -> "baseline"
  let connect ~parent view ~edge ~target ~target_edge ~margin_dp =
    let compatible=match edge,target_edge with (Start|End),(Start|End) | (Top|Bottom),(Top|Bottom) -> true | Baseline,Baseline -> margin_dp=0 && target<>Parent | _ -> false in
    if not compatible || not (Ui.dimensions_valid [margin_dp]) then invalid "Incompatible constraint anchors or margin" else
    unit_call "ui.constraintConnect" [parent;view;name edge;(match target with Parent -> "" | View v -> v);name target_edge;s margin_dp]
  let clear ~parent view ~edge = unit_call "ui.constraintClear" [parent;view;name edge]
  let set_bias ~parent view ~horizontal ~vertical =
    if not (List.for_all (fun n -> Float.is_finite n && n>=0. && n<=1.) [horizontal;vertical]) then invalid "Constraint bias must be between zero and one"
    else unit_call "ui.constraintBias" [parent;view;string_of_float horizontal;string_of_float vertical]
end
module Toolbar = struct
  type display = Always | If_room | Never
  type action = {id:int;title:string;icon:int option;display:display}
  let valid actions = List.for_all (fun x -> x.id>0 && x.id<=Int32.to_int Int32.max_int && x.title<>"" && Option.fold ~none:true ~some:((<) 0) x.icon) actions &&
    List.length (List.sort_uniq compare (List.map (fun x -> x.id) actions))=List.length actions
  let encode actions = Yojson.Safe.to_string (`List (List.map (fun x -> `Assoc ["id",`Int x.id;"title",`String x.title;
    "icon",(match x.icon with None -> `Null | Some id -> `Int id);"display",`String (match x.display with Always -> "always" | If_room -> "if_room" | Never -> "never")]) actions))
  let create activity ~title ~subtitle ~navigation_icon ~navigation_description ~actions =
    if not (valid actions) || Option.fold ~none:false ~some:(fun n -> n<=0) navigation_icon ||
      (Option.is_some navigation_icon && Option.fold ~none:true ~some:((=) "") navigation_description)
    then invalid "Invalid toolbar actions or navigation icon/description" else
    one "ui.toolbar" [activity;title;Option.value subtitle ~default:"";Option.fold ~none:"" ~some:s navigation_icon;Option.value navigation_description ~default:"";encode actions]
  let set_title toolbar title = unit_call "ui.toolbarTitle" [toolbar;title]
  let set_subtitle toolbar subtitle = unit_call "ui.toolbarSubtitle" [toolbar;Option.value subtitle ~default:""]
  let set_actions toolbar actions = if not (valid actions) then invalid "Invalid toolbar actions" else unit_call "ui.toolbarActions" [toolbar;encode actions]
  let on_action toolbar callback = subscribe "events.toolbarAction" [toolbar] (function [|id|] -> (match int_of_string_opt id with Some n -> callback n | None -> Lwt.fail_with "Invalid toolbar action") | _ -> Lwt.return_unit)
  let on_navigation toolbar callback = subscribe "events.toolbarNavigation" [toolbar] (fun _ -> callback ())
end
module Card = struct
  let create ?content_padding activity =
    if not (Option.fold ~none:true ~some:Ui.valid_insets content_padding) then invalid "Invalid card content padding" else
    one "ui.card" [activity;(match content_padding with None -> "" | Some p -> Yojson.Safe.to_string (Ui.insets_json p))]
end
module Bottom_navigation = struct
  type item = {id:int;title:string;icon:int}
  let create activity ~items ~selected =
    if items=[] || not (List.for_all (fun x -> x.id>0 && x.id<=Int32.to_int Int32.max_int && x.title<>"" && x.icon>0) items) ||
      List.length (List.sort_uniq compare (List.map (fun x -> x.id) items))<>List.length items || not (List.exists (fun x -> x.id=selected) items)
    then invalid "Invalid navigation items or selection" else
    let json=`List (List.map (fun x -> `Assoc ["id",`Int x.id;"title",`String x.title;"icon",`Int x.icon]) items) in
    one "ui.bottomNavigation" [activity;Yojson.Safe.to_string json;s selected]
  let selected navigation = int "ui.navigationSelected" [navigation]
  let select navigation id = if id<=0 then invalid "Invalid navigation identifier" else unit_call "ui.navigationSelect" [navigation;s id]
  let on_select navigation callback = subscribe "events.navigationSelect" [navigation] (function [|id|] -> (match int_of_string_opt id with Some n -> callback n | None -> Lwt.fail_with "Invalid navigation event") | _ -> Lwt.return_unit)
end
module Check_box = struct
  type state = Unchecked | Checked | Indeterminate
  let name = function Unchecked -> "0" | Checked -> "1" | Indeterminate -> "2"
  let decode = function "0" -> Some Unchecked | "1" -> Some Checked | "2" -> Some Indeterminate | _ -> None
  let create activity ~label ~state = one "ui.checkBox" [activity;label;name state]
  let state view = one "ui.checkState" [view] >|= function Error e -> Error e | Ok s -> (match decode s with Some v -> Ok v | None -> Error (Error.Protocol "Checkbox state"))
  let set_state view state = unit_call "ui.checkSet" [view;name state]
  let on_change view callback = subscribe "events.checkState" [view] (function [|s|] -> (match decode s with Some v -> callback v | None -> Lwt.fail_with "Invalid checkbox event") | _ -> Lwt.return_unit)
end
module Switch = struct
  let create activity ~label ~checked = one "ui.switch" [activity;label;string_of_bool checked]
  let checked view = bool "ui.switchChecked" [view]
  let set_checked view checked = unit_call "ui.switchSet" [view;string_of_bool checked]
  let on_change view callback = subscribe "events.switchChecked" [view] (function [|s|] -> callback (s="true") | _ -> Lwt.return_unit)
end
module Divider = struct let create activity = one "ui.divider" [activity] end

module Form = struct
  type keyboard = Text | Email | Password | Phone | Url | Number of {signed:bool;decimal:bool}
  type validator = string -> (unit,string) result
  type field = {root:Ui.view;input:Ui.view;validator:validator option;lock:Lwt_mutex.t;mutable active:bool}
  let container activity = one "form.container" [activity]
  let text_field activity ~label ~help ~text ~keyboard ~multiline ~validate =
    let kind,signed,decimal=match keyboard with Text -> "text",false,false | Email -> "email",false,false | Password -> "password",false,false
      | Phone -> "phone",false,false | Url -> "url",false,false | Number x -> "number",x.signed,x.decimal in
    if label="" || (multiline && keyboard<>Text) then invalid "Field requires a label; multiline requires Text keyboard" else
    Lwt.no_cancel (call "form.field" [activity;label;string_of_bool (Option.is_some help);Option.value help ~default:"";text;kind;string_of_bool signed;string_of_bool decimal;string_of_bool multiline]) >|= function
    | Error e -> Error e | Ok [|root;input|] -> Ok {root;input;validator=validate;lock=Lwt_mutex.create ();active=true}
    | Ok _ -> Error (Error.Protocol "Form field handles")
  let view field = field.root
  let with_field field f = Lwt_mutex.with_lock field.lock (fun () -> if field.active then f () else Lwt.return (Error (Error.Invalid_handle "Form field released")))
  let get_text field = with_field field (fun () -> Ui.get_text field.input)
  let set_text field text = with_field field (fun () -> Ui.set_text field.input text)
  let optional command field value = with_field field (fun () -> unit_call command [field.root;string_of_bool (Option.is_some value);Option.value value ~default:""])
  let set_help = optional "form.help"
  let set_error = optional "form.error"
  let validate_current field = with_field field (fun () ->
    Ui.get_text field.input >>= function Error e -> Lwt.return (Error e) | Ok text ->
    let validation=try Ok (match field.validator with None -> Ok () | Some validator -> validator text)
      with exn -> Error (Error.Platform (Printexc.to_string exn)) in
    match validation with Error e -> Lwt.return (Error e) | Ok validation ->
    let error=match validation with Ok () -> None | Error message -> Some message in
    unit_call "form.error" [field.root;string_of_bool (Option.is_some error);Option.value error ~default:""]
      >|= Result.map (fun () -> text,Result.is_ok validation))
  let validate field = validate_current field >|= Result.map snd
  let on_change field ~on_error callback =
    if not field.active then Lwt.return (Error (Error.Invalid_handle "Form field released")) else
    Events.on_text_changed field.input (fun _ -> if not field.active then Lwt.return_unit else
      validate_current field >>= function
      | Error e -> if field.active then on_error e else Lwt.return_unit
      | Ok (text,valid) -> if field.active then callback text valid else Lwt.return_unit)
  let release field = Lwt.no_cancel (Lwt_mutex.with_lock field.lock (fun () ->
    if not field.active then Lwt.return (Ok ()) else (field.active<-false;Ui.release field.root >|= function Error (Error.Invalid_handle _) -> Ok () | result -> result)))
end

module Permissions = struct
  type special = Exact_alarm | Notifications | Full_screen_intent | Background_location
  let check name = bool "permissions.check" [name]
  let request a names = call "permissions.request" (a :: names) >|= Result.map
    (Array.to_list) (* Returns the permission names actually granted. *)
  let special_name = function Exact_alarm -> "exact" | Notifications -> "notifications" | Full_screen_intent -> "fullscreen" | Background_location -> "background_location"
  let check_special p = bool "permissions.special" [special_name p]
  let open_settings a p = unit_call "permissions.settings" [a; special_name p]
end

module Camera = struct
  type photo = string
  type capture = Captured of photo | Cancelled
  let uri photo = photo
  let of_uri uri = one "camera.open" [uri]
  let capture activity ~name =
    if name = "" || String.contains name '/' || String.contains name '\\'
      || not (Filename.check_suffix name ".jpg" || Filename.check_suffix name ".jpeg")
    then invalid "Expected a JPEG basename"
    else call "camera.capture" [activity; name] >|= function
      | Ok [||] -> Ok Cancelled
      | Ok [|uri|] -> Ok (Captured uri)
      | Error e -> Error e
      | Ok _ -> Error (Error.Protocol "Camera capture result")
  let read photo = one "camera.read" [photo]
  let delete photo = unit_call "camera.delete" [photo]
end

module Qr_scanner = struct
  let scan (activity : Activity.t) : string response =
    (* The provider has no programmatic dismissal API. Keep waiting until its
       screen completes, rather than abandoning a live scan on Lwt.cancel. *)
    Lwt.no_cancel (one "qr.scan" [activity])
end

module Alarm = struct
  type mode = Exact | Alarm_clock
  let can_schedule_exact () = bool "alarm.allowed" []
  let schedule ~id ~at_ms ~mode ~handler ~payload =
    if id = "" || handler = "" || at_ms < 0L then invalid "Invalid alarm id, handler or timestamp"
    else unit_call "alarm.schedule" [id; i64 at_ms; (match mode with Exact -> "exact" | Alarm_clock -> "clock"); handler; payload]
  let cancel ~id = unit_call "alarm.cancel" [id]
  module Screen = struct
    type t = {
      id : string; notification_id : int; title : string; text : string;
      stop_label : string; snooze_label : string;
      stop_handler : string; snooze_handler : string; payload : string;
    }
    let valid t = t.id <> "" && t.stop_label <> "" && t.snooze_label <> ""
      && t.stop_handler <> "" && t.snooze_handler <> ""
    let args t = [t.id; s t.notification_id; t.title; t.text; t.stop_label;
      t.snooze_label; t.stop_handler; t.snooze_handler; t.payload]
    let show activity t = if not (valid t) then invalid "Alarm screen labels, id and handlers are required"
      else unit_call "alarm.screen.show" (activity :: args t)
    let notify ~channel ~small_icon ~full_screen t =
      if not (valid t) || channel = "" || small_icon <= 0 then invalid "Invalid alarm screen notification"
      else unit_call "alarm.screen.notify" ([channel; s small_icon; string_of_bool full_screen] @ args t)
    let dismiss ~id ~notification_id = if id = "" then invalid "Alarm screen id is required"
      else unit_call "alarm.screen.dismiss" [id; s notification_id]
  end
end

module Notification = struct
  type importance = Low | Default | High
  let create_channel ~id ~name ~importance = unit_call "notification.channel"
    [id; name; (match importance with Low -> "2" | Default -> "3" | High -> "4")]
  let show ~id ~channel ~title ~text ~small_icon ~handler ~payload =
    unit_call "notification.show" [s id; channel; title; text; s small_icon; handler; payload]
  let cancel ~id = unit_call "notification.cancel" [s id]
end

module Audio = struct
  type player = string
  let default_alarm_uri () = call "audio.defaultAlarm" [] >|= function
    | Ok [||] -> Ok None | Ok [|uri|] -> Ok (Some uri)
    | Error e -> Error e | Ok _ -> Error (Error.Protocol "Default alarm URI")
  let play ~uri ~loop = one "audio.play" [uri; string_of_bool loop]
  let stop p = unit_call "audio.stop" [p]
end
module Vibrator = struct
  let vibrate ~duration_ms = if duration_ms <= 0 then invalid "duration_ms must be positive"
    else unit_call "vibrator.start" [s duration_ms]
  let cancel () = unit_call "vibrator.cancel" []
end

module Background = struct
  type outcome = Success | Failure | Retry
  type constraints = { network : bool; charging : bool }
  let enqueue ~name ~handler ~payload ~constraints ~interval_ms =
    if name = "" || handler = "" then invalid "name and handler are required"
    else unit_call "background.work" [name; handler; payload; string_of_bool constraints.network;
      string_of_bool constraints.charging; (match interval_ms with None -> "" | Some v -> i64 v)]
  let cancel ~name = unit_call "background.cancel" [name]
  let schedule_job ~id ~handler ~payload ~constraints = unit_call "background.job"
    [s id; handler; payload; string_of_bool constraints.network; string_of_bool constraints.charging]
  let cancel_job ~id = unit_call "background.cancelJob" [s id]
  let start_service ~handler ~payload ~notification_id ~channel ~title ~small_icon =
    unit_call "background.service" [handler; payload; s notification_id; channel; title; s small_icon]
  let stop_service () = unit_call "background.stopService" []
end

module Network = struct
  type state = { connected : bool; metered : bool }
  let state () = call "network.state" [] >|= function
    | Ok [|c; m|] -> Ok {connected = c = "true"; metered = m = "true"}
    | Error e -> Error e | Ok _ -> Error (Error.Protocol "network state")
  let on_change f = subscribe "network.listen" [] (function
    | [|c; m|] -> f {connected = c = "true"; metered = m = "true"}
    | _ -> Lwt.return_unit)
  module Http = struct
    type response = { status : int; headers : (string * string) list; body : string }
    let request ~url ~meth ~headers ~body ~connect_timeout_ms ~read_timeout_ms =
      if connect_timeout_ms <= 0 || read_timeout_ms <= 0 then invalid "HTTP timeouts must be positive"
      else Lwt.no_cancel (one "http.create" []) >>= function
      | Error e -> Lwt.return (Error e)
      | Ok id -> Lwt.finalize (fun () -> call "http.request" ([id; url; meth; body; s connect_timeout_ms; s read_timeout_ms] @
        List.concat_map (fun (k,v) -> [k;v]) headers) >|= function
      | Error e -> Error e
      | Ok a when Array.length a >= 2 ->
        (match int_of_string_opt a.(0) with
        | None -> Error (Error.Protocol "HTTP status")
        | Some status ->
          let rec pairs i acc = if i + 1 >= Array.length a then List.rev acc
            else pairs (i+2) ((a.(i),a.(i+1))::acc) in
          Ok {status; body = a.(1); headers = pairs 2 []})
      | Ok _ -> Error (Error.Protocol "HTTP response"))
        (fun () -> unit_call "http.close" [id] >|= ignore)
  end
end

module Storage = struct
  let file_uri ~name =
    if name = "" || name = "." || name = ".." || String.contains name '/' || String.contains name '\\' || String.contains name '\000'
    then invalid "Expected a private file basename"
    else one "storage.fileUri" [name]
  let read_file ~name = one "storage.read" [name]
  let write_file ~name ~contents = unit_call "storage.write" [name; contents]
  let remove_file ~name = unit_call "storage.remove" [name]
  module Preferences = struct
    type value = Text of string | Bool of bool | Int of int64
    let set ~store ~key value = let typ,v = match value with
      | Text v -> "text",v | Bool v -> "bool",string_of_bool v | Int v -> "int",i64 v in
      unit_call "preferences.set" [store; key; typ; v]
    let get ~store ~key = call "preferences.get" [store; key] >|= function
      | Ok [||] -> Ok None
      | Ok [|"text"; v|] -> Ok (Some (Text v))
      | Ok [|"bool"; v|] -> Ok (Some (Bool (v = "true")))
      | Ok [|"int"; v|] -> (match Int64.of_string_opt v with
        | Some n -> Ok (Some (Int n)) | None -> Error (Error.Protocol "preference integer"))
      | Error e -> Error e | Ok _ -> Error (Error.Protocol "preference value")
    let remove ~store ~key = unit_call "preferences.remove" [store; key]
  end
  module Keystore = struct
    let create ~alias = unit_call "keystore.create" [alias]
    let encrypt ~alias ~plaintext = one "keystore.encrypt" [alias; plaintext]
    let decrypt ~alias ~ciphertext = one "keystore.decrypt" [alias; ciphertext]
    let delete ~alias = unit_call "keystore.delete" [alias]
  end
  module Sqlite = struct
    type t = string
    type value = Null | Integer of int64 | Float of float | Text of string | Blob of string
    type statement = { sql : string; params : value list }
    let encode = function Null -> ["null"; ""] | Integer n -> ["int"; i64 n]
      | Float n -> ["float"; string_of_float n] | Text v -> ["text"; v] | Blob v -> ["blob"; v]
    let args q = q.sql :: List.concat_map encode q.params
    let open_db ~name = one "sqlite.open" [name]
    let close db = unit_call "sqlite.close" [db]
    let execute db q = unit_call "sqlite.execute" (db :: args q)
    let transaction db qs = unit_call "sqlite.transaction"
      (db :: List.concat_map (fun q -> s (List.length q.params) :: args q) qs)
    let query db q = call "sqlite.query" (db :: args q) >|= function
      | Error e -> Error e
      | Ok a ->
        let rec rows i acc =
          if i = Array.length a then Ok (List.rev acc)
          else match int_of_string_opt a.(i) with
          | Some n when n >= 0 && n <= (Array.length a - i - 1) / 2 ->
            let rec cells j acc = if j = n then Some (List.rev acc) else
              let typ = a.(i+1+j*2) and v = a.(i+2+j*2) in
              let cell = match typ with
                | "null" -> Some Null | "text" -> Some (Text v) | "blob" -> Some (Blob v)
                | "int" -> Option.map (fun x -> Integer x) (Int64.of_string_opt v)
                | "float" -> Option.map (fun x -> Float x) (float_of_string_opt v)
                | _ -> None in
              match cell with None -> None | Some c -> cells (j+1) (c::acc) in
            (match cells 0 [] with Some row -> rows (i+1+2*n) (row::acc)
             | None -> Error (Error.Protocol "SQLite cell"))
          | _ -> Error (Error.Protocol "SQLite row") in rows 0 []
  end
end
module Broadcast = struct
  type event = { action : string; payload : string }
  let listen ~actions f = subscribe "broadcast.listen" actions (function
    | [|action; payload|] -> f {action; payload} | _ -> Lwt.return_unit)
end

module Runtime = struct
  type handler = string -> Background.outcome Lwt.t
  let handlers : (string, handler) Hashtbl.t = Hashtbl.create 16
  let running : (string, Background.outcome Lwt.t) Hashtbl.t = Hashtbl.create 16
  let entry = ref (fun () -> Lwt.return_unit)
  let on_error = ref (fun e -> prerr_endline (Printexc.to_string e ^ "\n" ^ Printexc.get_backtrace ()))
  let register ~name handler =
    if name = "" || Hashtbl.mem handlers name then Error (Error.Invalid_argument "empty or duplicate handler")
    else (Hashtbl.add handlers name handler; Ok ())
  let set_entry f = entry := f
  let set_error_handler f = on_error := f
  let protect f = Lwt.catch f (fun e -> !on_error e; Lwt.return_unit)
  let dispatch = function
    | [|"cancel"; token|] ->
      Option.iter Lwt.cancel (Hashtbl.find_opt running token); Lwt.return_unit
    | [|"unsubscribe"; id|] -> Hashtbl.remove Subscription.callbacks id; Lwt.return_unit
    | [|"handler"; token; name; payload|] ->
      Lwt.async (fun () -> Lwt.catch (fun () ->
        let task = match Hashtbl.find_opt handlers name with
        | None -> Lwt.return Background.Failure | Some f -> f payload in
        Hashtbl.replace running token task;
        task >>= fun result ->
        Hashtbl.remove running token;
        unit_call "runtime.complete" [token; (match result with Background.Success -> "success" | Failure -> "failure" | Retry -> "retry")] >|= ignore)
        (fun e -> Hashtbl.remove running token; (match e with Lwt.Canceled -> () | _ -> !on_error e); unit_call "runtime.complete" [token; "failure"] >|= ignore));
      Lwt.return_unit
    | a when Array.length a > 0 ->
      (match Hashtbl.find_opt Subscription.callbacks a.(0) with
       | None -> Lwt.return_unit
       | Some f -> Lwt.async (fun () -> protect (fun () -> f (Array.sub a 1 (Array.length a - 1)))); Lwt.return_unit)
    | _ -> Lwt.return_unit
  let rec loop () = Lwt_preemptive.detach take_event () >>= fun event ->
    if Array.length event > 0 && event.(0) = "error" then
      Lwt.fail_with "Android event queue unavailable"
    else dispatch event >>= loop
  let start () =
    Printexc.record_backtrace true;
    Lwt_main.run (protect !entry >>= loop)
  let () = Callback.register "android.runtime.start" start
end


(* JSON is confined to the private JNI transport, decoded with Yojson. *)
module Wire_json = struct
  open Yojson.Safe.Util
  let optional f = function `Null -> None | value -> Some (f value)
  let int64 value = match Int64.of_string_opt (to_string value) with
    | Some n -> n | None -> invalid_arg "wire integer"
  let number = function `Int n -> float_of_int n | `Float n -> n | _ -> invalid_arg "wire number"
  let field name f json = f (member name json)
  let decode f text = try Ok (f (Yojson.Safe.from_string text)) with
    | Yojson.Json_error _ | Yojson.Safe.Util.Type_error _ | Invalid_argument _ | Failure _ ->
      Error (Error.Protocol "Invalid structured Android response")
  let request name args f = one name args >|= function Error e -> Error e | Ok text -> decode f text
end

module Health_connect = struct
  type availability = Available | Unavailable | Update_required
  type record_type = Steps | Heart_rate | Sleep | Exercise | Distance | Active_calories | Total_calories
  type interval = { start_ms : int64; end_ms : int64; start_offset_s : int option; end_offset_s : int option }
  type metadata = { id : string; origin : string; last_modified_ms : int64 }
  type steps = { metadata : metadata; interval : interval; count : int64 }
  type sample = { at_ms : int64; bpm : int64 }
  type heart_rate = { metadata : metadata; interval : interval; samples : sample list }
  type stage = { start_ms : int64; end_ms : int64; stage_type : int }
  type sleep = { metadata : metadata; interval : interval; title : string option; notes : string option; stages : stage list }
  type exercise = { metadata : metadata; interval : interval; exercise_type : int; title : string option; notes : string option }
  type query = { start_ms : int64; end_ms : int64; page_size : int; page_token : string option }
  type 'a page = { records : 'a list; next_page_token : string option }
  let name = function Steps -> "steps" | Heart_rate -> "heart" | Sleep -> "sleep" | Exercise -> "exercise" | Distance -> "distance" | Active_calories -> "active_calories" | Total_calories -> "total_calories"
  let status () = one "health.status" [] >|= function
    | Ok "available" -> Ok Available | Ok "unavailable" -> Ok Unavailable
    | Ok "update_required" -> Ok Update_required | Error e -> Error e | Ok _ -> Error (Error.Protocol "Health availability")
  let read_permission typ = one "health.permission" [name typ]
  let decode_types a =
    let rec loop i result = if i = Array.length a then Ok (List.rev result) else
      let typ = match a.(i) with "steps" -> Some Steps | "heart" -> Some Heart_rate | "sleep" -> Some Sleep | "exercise" -> Some Exercise | "distance" -> Some Distance | "active_calories" -> Some Active_calories | "total_calories" -> Some Total_calories | _ -> None in
      match typ with Some t -> loop (i+1) (t::result) | None -> Error (Error.Protocol "Health permission type") in loop 0 []
  let granted_permissions () = call "health.granted" [] >|= function Error e -> Error e | Ok a -> decode_types a
  let request_permissions activity types = call "health.request" (activity :: List.map name types) >|= function Error e -> Error e | Ok a -> decode_types a
  let background_read_available () = bool "health.backgroundAvailable" []
  let background_read_granted () = bool "health.backgroundGranted" []
  let request_background_read activity = bool "health.backgroundRequest" [activity]
  let open_settings activity = unit_call "health.settings" [activity]
  open Yojson.Safe.Util
  open Wire_json
  let metadata json = {id=field "id" to_string json; origin=field "origin" to_string json; last_modified_ms=field "last_modified_ms" int64 json}
  let interval json : interval = {start_ms=field "start_ms" int64 json;end_ms=field "end_ms" int64 json;
    start_offset_s=field "start_offset_s" (optional to_int) json;end_offset_s=field "end_offset_s" (optional to_int) json}
  let decode_steps json : steps = {metadata=metadata json;interval=interval json;count=field "count" int64 json}
  let decode_heart json : heart_rate = {metadata=metadata json;interval=interval json;samples=field "samples" (fun j -> List.map (fun sample ->
    {at_ms=field "at_ms" int64 sample;bpm=field "bpm" int64 sample}) (to_list j)) json}
  let decode_sleep json : sleep = {metadata=metadata json;interval=interval json;title=field "title" (optional to_string) json;notes=field "notes" (optional to_string) json;
    stages=field "stages" (fun j -> List.map (fun stage -> {start_ms=field "start_ms" int64 stage;end_ms=field "end_ms" int64 stage;stage_type=field "stage" to_int stage}) (to_list j)) json}
  let decode_exercise json : exercise = {metadata=metadata json;interval=interval json;title=field "title" (optional to_string) json;notes=field "notes" (optional to_string) json;exercise_type=field "exercise_type" to_int json}
  let read typ decode_record (query : query) =
    if query.start_ms >= query.end_ms || query.page_size <= 0 then invalid "Nonempty health interval and positive page size required"
    else Wire_json.request "health.read" [name typ;i64 query.start_ms;i64 query.end_ms;s query.page_size;Option.value query.page_token ~default:""]
      (fun json -> {records=field "records" (fun j -> List.map decode_record (to_list j)) json;next_page_token=field "next_page_token" (optional to_string) json})
  let read_steps = read Steps decode_steps
  let read_heart_rate = read Heart_rate decode_heart
  let read_sleep = read Sleep decode_sleep
  let read_exercise = read Exercise decode_exercise
  let aggregate_steps ~start_ms ~end_ms =
    if start_ms >= end_ms then invalid "Nonempty health interval required" else
    call "health.aggregateSteps" [i64 start_ms;i64 end_ms] >|= function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> (match Int64.of_string_opt value with Some n -> Ok (Some n) | None -> Error (Error.Protocol "Health step aggregate"))
    | Ok _ -> Error (Error.Protocol "Health step aggregate")
  let aggregate_amount kind ~start_ms ~end_ms =
    if start_ms >= end_ms then invalid "Nonempty health interval required" else
    call "health.aggregateAmount" [name kind;i64 start_ms;i64 end_ms] >|= function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> (match float_of_string_opt value with
      | Some n when Float.is_finite n && n >= 0. -> Ok (Some n)
      | _ -> Error (Error.Protocol "Health amount aggregate"))
    | Ok _ -> Error (Error.Protocol "Health amount aggregate")
  let aggregate_distance = aggregate_amount Distance
  let aggregate_active_calories = aggregate_amount Active_calories
  let aggregate_total_calories = aggregate_amount Total_calories
end

module Location = struct
  type provider = Gps | Network
  type fix = { provider : string option; latitude : float; longitude : float;
    accuracy_m : float option; altitude_m : float option; speed_mps : float option; bearing_deg : float option;
    at_ms : int64; elapsed_realtime_ns : int64; is_mock : bool }
  type event = Fix of fix | Provider_enabled of string | Provider_disabled of string
  let name = function Gps -> "gps" | Network -> "network"
  open Yojson.Safe.Util
  open Wire_json
  let decode_fix json = {
    provider=field "provider" (optional to_string) json;latitude=field "latitude" number json;longitude=field "longitude" number json;
    accuracy_m=field "accuracy_m" (optional number) json;altitude_m=field "altitude_m" (optional number) json;
    speed_mps=field "speed_mps" (optional number) json;bearing_deg=field "bearing_deg" (optional number) json;
    at_ms=field "at_ms" int64 json;elapsed_realtime_ns=field "elapsed_realtime_ns" int64 json;is_mock=field "is_mock" to_bool json}
  let decode_event json = match field "type" to_string json with
    | "fix" -> Fix (field "fix" decode_fix json)
    | "enabled" -> Provider_enabled (field "provider" to_string json)
    | "disabled" -> Provider_disabled (field "provider" to_string json)
    | _ -> invalid_arg "Location event"
  let distance_between ~latitude1 ~longitude1 ~latitude2 ~longitude2 =
    let valid latitude longitude = Float.is_finite latitude && Float.is_finite longitude
      && abs_float latitude <= 90. && abs_float longitude <= 180. in
    if not (valid latitude1 longitude1 && valid latitude2 longitude2) then invalid "Invalid geographic coordinates"
    else one "location.distanceBetween" (List.map string_of_float [latitude1;longitude1;latitude2;longitude2]) >|= function
      | Error e -> Error e
      | Ok value -> (match float_of_string_opt value with
        | Some n when Float.is_finite n && n >= 0. -> Ok n
        | _ -> Error (Error.Protocol "Geodesic distance"))
  let enabled ~provider = bool "location.enabled" [name provider]
  let decode_optional = function
    | Error e -> Error e | Ok [||] -> Ok None
    | Ok [|value|] -> Result.map Option.some (decode decode_fix value)
    | Ok _ -> Error (Error.Protocol "Location fix")
  let last_known ~provider = call "location.last" [name provider] >|= decode_optional
  let current ~provider ~timeout_ms =
    if timeout_ms <= 0 then invalid "Positive location timeout required" else
    Lwt.no_cancel (one "location.currentStart" [name provider;s timeout_ms]) >>= function
    | Error e -> Lwt.return (Error e)
    | Ok token -> Lwt.finalize (fun () -> call "location.currentAwait" [token] >|= decode_optional)
      (fun () -> unit_call "location.currentClose" [token] >|= ignore)
  let valid interval distance = interval >= 0L && Float.is_finite distance && distance >= 0. && distance <= 3.4028234663852886e38
  let watch ~provider ~interval_ms ~min_distance_m callback =
    if not (valid interval_ms min_distance_m) then invalid "Invalid location interval or distance" else
    subscribe "location.watch" [name provider;i64 interval_ms;string_of_float min_distance_m] (function
      | [|payload|] -> (match decode decode_event payload with Ok event -> callback event | Error _ -> Lwt.fail_with "Invalid location event")
      | _ -> Lwt.fail_with "Invalid location event")
  let register_background_handler ~name:handler callback = Runtime.register ~name:handler (fun token ->
    Wire_json.request "location.event" [token] decode_event >>= function
    | Ok event -> callback event | Error _ -> Lwt.return Background.Failure)
  let start_tracking_service ~provider ~interval_ms ~min_distance_m ~notification_id ~channel ~title ~text ~small_icon ~handler =
    if not (valid interval_ms min_distance_m) || notification_id <= 0 || small_icon <= 0 || channel = "" || handler = "" then invalid "Invalid location service configuration"
    else unit_call "location.serviceStart" [name provider;i64 interval_ms;string_of_float min_distance_m;s notification_id;channel;title;text;s small_icon;handler]
  let stop_tracking_service () = unit_call "location.serviceStop" []
end

module Settings = struct
  type item =
    | Section of string
    | Info of { key:string; title:string; text:string }
    | Status_action of { key:string; title:string; text:string; run:Activity.t -> unit response }
    | Toggle of { key:string; title:string; description:string option; initial:bool }
    | Text of { key:string; title:string; description:string option; initial:string;
                multiline:bool; validate:(string -> (unit,string) result) option }
    | Integer of { key:string; title:string; description:string option; initial:int64;
                   validate:(int64 -> (unit,string) result) option }
    | Choice of { key:string; title:string; description:string option; initial:string;
                  options:(string * string) list }
    | Action of { title:string; description:string option; run:Activity.t -> unit response }
  type schema = {store:string; items:item list; lock:Lwt_mutex.t}
  type page = {root:Ui.view; subscription:Subscription.t; schema:schema;
               information:(string,string) Hashtbl.t; mutable active:bool}
  let key = function Info x -> Some x.key | Status_action x -> Some x.key | Toggle x -> Some x.key | Text x -> Some x.key | Integer x -> Some x.key | Choice x -> Some x.key | _ -> None
  let initial = function Toggle x -> Storage.Preferences.Bool x.initial | Text x -> Text x.initial
    | Integer x -> Int x.initial | Choice x -> Text x.initial | _ -> assert false
  let check item value =
    let invalid message = Error (Error.Invalid_argument message) in
    let validate f v = match f with None -> Ok () | Some f -> Result.map_error (fun m -> Error.Invalid_argument m) (f v) in
    try match item,value with
    | Toggle _,Storage.Preferences.Bool _ -> Ok ()
    | Text x,Storage.Preferences.Text v -> validate x.validate v
    | Integer x,Storage.Preferences.Int v -> validate x.validate v
    | Choice x,Storage.Preferences.Text v when List.mem_assoc v x.options -> Ok ()
    | _ -> invalid "Settings value does not match its declaration"
    with exn -> Error (Error.Platform (Printexc.to_string exn))
  let define ~store items =
    let seen=Hashtbl.create (List.length items) in
    let rec loop = function
      | [] -> Ok {store;items;lock=Lwt_mutex.create ()}
      | item::rest -> match key item with None -> loop rest | Some k ->
        if k="" || Hashtbl.mem seen k then Error (Error.Invalid_argument "Settings keys must be nonempty and unique") else
        let options_valid = match item with Choice x ->
          x.options<>[] && List.length (List.sort_uniq String.compare (List.map fst x.options))=List.length x.options
          | _ -> true in
        if not options_valid then Error (Error.Invalid_argument "Settings choices must be nonempty and unique") else
        match (match item with Info _ | Status_action _ -> Ok () | _ -> check item (initial item)) with
        | Error e -> Error e | Ok () -> Hashtbl.add seen k ();loop rest in
    if store="" then Error (Error.Invalid_argument "Settings store must be nonempty") else loop items
  let find schema k = match List.find_opt (fun i -> key i=Some k) schema.items with
    | Some (Info _ | Status_action _) -> Error (Error.Invalid_argument "Status rows are not stored preferences")
    | Some i -> Ok i | None -> Error (Error.Invalid_argument "Unknown settings key")
  let get_unlocked schema ~key:k = match find schema k with Error e -> Lwt.return (Error e) | Ok item ->
    Storage.Preferences.get ~store:schema.store ~key:k >|= function
    | Error e -> Error e
    | Ok value -> let value=Option.value value ~default:(initial item) in Result.map (fun () -> value) (check item value)
  let get schema ~key = Lwt_mutex.with_lock schema.lock (fun () -> get_unlocked schema ~key)
  let set_unlocked schema ~key:k value = match find schema k with Error e -> Lwt.return (Error e) | Ok item ->
    match check item value with Error e -> Lwt.return (Error e) | Ok () -> Storage.Preferences.set ~store:schema.store ~key:k value
  let set schema ~key value = Lwt_mutex.with_lock schema.lock (fun () -> set_unlocked schema ~key value)
  let value_string = function Storage.Preferences.Text v -> v | Bool v -> string_of_bool v | Int v -> i64 v
  let rows schema information =
    let rec loop acc = function [] -> Lwt.return (Ok (`List (List.rev acc))) | item::rest ->
      let open Yojson.Safe in
      let description x = "description",(match x with None -> `Null | Some s -> `String s) in
      let fields kind title desc = ["kind",`String kind;"title",`String title;description desc] in
      let fields = match item with
        | Section title -> fields "section" title None
        | Info x -> fields "info" x.title (Some (Option.value (Hashtbl.find_opt information x.key) ~default:x.text))
        | Status_action x -> fields "action" x.title (Some (Option.value (Hashtbl.find_opt information x.key) ~default:x.text))
        | Toggle x -> fields "toggle" x.title x.description
        | Text x -> ("multiline",`Bool x.multiline)::fields "text" x.title x.description
        | Integer x -> fields "integer" x.title x.description
        | Choice x -> ("options",`List (List.map (fun (v,t) -> `List [`String v;`String t]) x.options))::fields "choice" x.title x.description
        | Action x -> fields "action" x.title x.description in
      (match item,key item with (Info _ | Status_action _),_ | _,None -> Lwt.return (Ok fields)
       | _,Some k -> get_unlocked schema ~key:k >|= Result.map (fun v -> ("value",`String (value_string v))::fields)) >>= function
      | Error e -> Lwt.return (Error e) | Ok fields -> loop (`Assoc fields::acc) rest in
    loop [] schema.items
  let view page = page.root
  let refresh_unlocked page =
    if not page.active then invalid "Settings page released" else
    rows page.schema page.information >>= function
    | Error e -> Lwt.return (Error e) | Ok json -> unit_call "settings.update" [page.root;Yojson.Safe.to_string json]
  let refresh page = if not page.active then invalid "Settings page released" else
    Lwt_mutex.with_lock page.schema.lock (fun () -> refresh_unlocked page)
  let set_row_text accepts page ~key:k text =
    if not page.active then invalid "Settings page released" else
    match List.find_opt (fun item -> key item=Some k && accepts item) page.schema.items with
    | None -> invalid "Unknown status row"
    | Some _ -> Lwt_mutex.with_lock page.schema.lock (fun () ->
      if not page.active then invalid "Settings page released" else (
        Hashtbl.replace page.information k text;refresh_unlocked page))
  let set_info = set_row_text (function Info _ -> true | _ -> false)
  let set_summary = set_row_text (function Info _ | Status_action _ -> true | _ -> false)
  let error_message = function
    | Error.Unavailable s | Permission_denied s | Invalid_argument s | Invalid_handle s | Platform s | Protocol s -> s
    | Cancelled -> "Operation cancelled"
  let create ?on_resume activity schema ~on_change ~on_error =
    let page_ref=ref None in
    let resume_lock=Lwt_mutex.create () in
    let report = function Ok () -> Lwt.return_unit | Error e -> on_error e in
    let resume page = Lwt_mutex.with_lock resume_lock (fun () ->
      if not page.active then Lwt.return_unit else
      (match on_resume with None -> Lwt.return (Ok ()) | Some f ->
        Lwt.catch (fun () -> f activity page) (fun exn -> Lwt.return (Error (Error.Platform (Printexc.to_string exn))))) >>= fun result ->
      report result >>= fun () -> if page.active then refresh page >>= report else Lwt.return_unit) in
    let run_action page index run after =
      Lwt.catch (fun () -> run activity) (fun exn -> Lwt.return (Error (Error.Platform (Printexc.to_string exn)))) >>= report >>= fun () ->
      (if page.active then unit_call "settings.result" [page.root;index;"true";""] >>= report else Lwt.return_unit) >>= fun () ->
      if page.active then after () else Lwt.return_unit in
    let callback args = match !page_ref,args with
      | Some page,[|"refresh";""|] when page.active -> resume page
      | Some page,[|"closed";""|] -> page.active<-false;Lwt.return_unit
      | Some page,[|index;proposed|] when page.active ->
        (match int_of_string_opt index with
        | Some n when n>=0 && n<List.length schema.items ->
          let item=List.nth schema.items n in
          (match item with
          | Action x -> run_action page index x.run (fun () -> refresh page >>= report)
          | Status_action x -> run_action page index x.run (fun () -> resume page)
          | Section _ | Info _ -> Lwt.return_unit
          | _ ->
            let k=Option.get (key item) in
            let value = match item with
              | Toggle _ -> if proposed="true" then Some (Storage.Preferences.Bool true) else if proposed="false" then Some (Bool false) else None
              | Integer _ -> Option.map (fun v -> Storage.Preferences.Int v) (Int64.of_string_opt proposed)
              | _ -> Some (Storage.Preferences.Text proposed) in
            Lwt_mutex.with_lock schema.lock (fun () ->
              (match value with None -> invalid "Expected a 64-bit integer" | Some v -> set_unlocked schema ~key:k v) >>= fun result ->
              if not page.active then Lwt.return result else
              unit_call "settings.result" [page.root;index;(match result with Ok () -> "true" | _ -> "false");
                (match result with Ok () -> proposed | Error e -> error_message e)] >>= function
              | Error e -> Lwt.return (Error e) | Ok () -> Lwt.return result) >>= function
            | Error (Error.Invalid_argument _) -> Lwt.return_unit
            | Error e -> on_error e
            | Ok () -> if page.active then on_change ~key:k (Option.get value) else Lwt.return_unit)
        | _ -> on_error (Error.Protocol "Invalid settings event"))
      | _ -> Lwt.return_unit in
    let information=Hashtbl.create (List.length schema.items) in
    Lwt.no_cancel (Lwt_mutex.with_lock schema.lock (fun () -> rows schema information >>= function
      | Error e -> Lwt.return (Error e)
      | Ok json ->
        let subscription=Subscription.create callback in
        one "settings.create" [subscription.id;activity;Yojson.Safe.to_string json] >>= function
        | Error e -> Subscription.cancel subscription >|= fun _ -> Error e
        | Ok root -> let page={root;subscription;schema;information;active=true} in page_ref:=Some page;Lwt.return (Ok page)) >>= function
      | Ok page when Option.is_some on_resume -> resume page >|= fun () -> Ok page
      | result -> Lwt.return result)
  let release page =
    if not page.active then Lwt.return (Ok ()) else (
      page.active<-false;
      Lwt.no_cancel (Subscription.cancel page.subscription >>= fun result -> Ui.release page.root >|= fun released ->
      match result,released with Error e,_ -> Error e | _,Error (Error.Invalid_handle _) -> Ok () | _,r -> r))
  let register ?on_resume ~name schema ~on_change ~on_error =
    if name="" then Error (Error.Invalid_argument "Settings registration name must be nonempty") else
    Runtime.register ~name:("android.settings."^name) (fun activity ->
      create ?on_resume activity schema ~on_change ~on_error >>= function
      | Error e -> on_error e >|= fun () -> Background.Failure
      | Ok page -> Ui.show activity (view page) >>= function
        | Ok () -> Lwt.return Background.Success
        | Error e -> release page >>= fun _ -> on_error e >|= fun () -> Background.Failure)
  let open_ activity = unit_call "settings.open" [activity]

end

module Authentication = struct
  type error = Android of Error.t | Cancelled | No_credential | Unsupported
    | Provider_error of string | Invalid_argument of string
    | Invalid_response of string | Http_status of int | Not_authenticated
  type 'a response = ('a, error) result Lwt.t
  let ( let* ) task f = task >>= function Ok v -> f v | Error e -> Lwt.return (Error e)
  let platform task = task >|= Result.map_error (fun e -> Android e)
  let reject message = Lwt.return (Error (Invalid_argument message))
  let json text = try Ok (Yojson.Safe.from_string text) with Yojson.Json_error _ -> Error (Invalid_response "Invalid JSON response")
  let object_json text = match json text with Ok (`Assoc _ as j) -> Ok j | _ -> Error (Invalid_argument "Expected a JSON object")
  let passkey operation activity options_json =
    match object_json options_json with Error e -> Lwt.return (Error e) | Ok _ ->
    let* token=Lwt.no_cancel (platform (one "auth.passkeyStart" [activity;operation;options_json])) in
    Lwt.finalize (fun () -> platform (call "auth.passkeyAwait" [token]) >|= function
      | Error e -> Error e
      | Ok [|"complete";value|] -> (match object_json value with Ok _ -> Ok value | Error _ -> Error (Invalid_response "Invalid passkey response"))
      | Ok [|"cancelled"|] -> Error Cancelled | Ok [|"none"|] -> Error No_credential
      | Ok [|"unsupported"|] -> Error Unsupported | Ok [|"provider";kind|] -> Error (Provider_error kind)
      | Ok _ -> Error (Invalid_response "Invalid credential result"))
      (fun () -> unit_call "auth.passkeyClose" [token] >|= ignore)
  let create_passkey activity ~options_json = passkey "create" activity options_json
  let get_passkey activity ~options_json = passkey "get" activity options_json
  let clear_credential_state () = platform (call "auth.clearState" []) >|= function
    | Error e -> Error e | Ok [||] -> Ok () | Ok [|"unsupported"|] -> Error Unsupported
    | Ok [|"provider";kind|] -> Error (Provider_error kind)
    | Ok _ -> Error (Invalid_response "Invalid credential state result")
  type app_identity = { package_name:string; sha256_cert_fingerprints:string list; origins:string list }
  let app_identity () =
    let* raw=platform (one "auth.identity" []) in
    try let open Yojson.Safe.Util in let j=Yojson.Safe.from_string raw in
      Lwt.return (Ok {package_name=j |> member "package_name" |> to_string;
        sha256_cert_fingerprints=j |> member "sha256_cert_fingerprints" |> to_list |> List.map to_string;
        origins=j |> member "origins" |> to_list |> List.map to_string})
    with Yojson.Json_error _ | Yojson.Safe.Util.Type_error _ -> Lwt.return (Error (Invalid_response "Invalid application identity"))
  type session = { access_token:string; expires_at_ms:int64 }
  let now_ms () = Int64.of_float (Unix.gettimeofday () *. 1000.)
  let valid_token token =
    let char = function 'A'..'Z' | 'a'..'z' | '0'..'9' | '-' | '.' | '_' | '~' | '+' | '/' -> true | _ -> false in
    let rec loop i padding = i=String.length token || (if token.[i]='=' then loop (i+1) true else not padding && char token.[i] && loop (i+1) false) in
    token<>"" && token.[0]<>'=' && loop 0 false
  module Token_store = struct
    type t = {name:string;key_alias:string;lock:Lwt_mutex.t}
    let create ~name ~key_alias =
      if name="" || name="." || name=".." || String.exists (function '/' | '\\' | '\000' -> true | _ -> false) name || key_alias=""
      then Error (Invalid_argument "Expected private token basename and explicit key alias")
      else Ok {name;key_alias;lock=Lwt_mutex.create ()}
    let delete_unlocked t = platform (unit_call "auth.secretDelete" [t.name])
    let load_unlocked t =
      let* encrypted=platform (call "auth.secretRead" [t.name]) in
      match encrypted with
      | [||] -> Lwt.return (Ok None)
      | [|ciphertext|] ->
        let* plaintext=platform (Storage.Keystore.decrypt ~alias:t.key_alias ~ciphertext) in
        (try let open Yojson.Safe.Util in let j=Yojson.Safe.from_string plaintext in
          let access_token=j |> member "access_token" |> to_string
          and expiry=j |> member "expires_at_ms" |> to_string in
          match Int64.of_string_opt expiry with
          | Some expires_at_ms when valid_token access_token ->
            if expires_at_ms<=now_ms () then let* ()=delete_unlocked t in Lwt.return (Ok None)
            else Lwt.return (Ok (Some {access_token;expires_at_ms}))
          | _ -> Lwt.return (Error (Invalid_response "Invalid persisted session"))
        with Yojson.Json_error _ | Yojson.Safe.Util.Type_error _ -> Lwt.return (Error (Invalid_response "Invalid persisted session")))
      | _ -> Lwt.return (Error (Invalid_response "Invalid token storage response"))
    let load t = Lwt_mutex.with_lock t.lock (fun () -> load_unlocked t)
    let delete t = Lwt.no_cancel (Lwt_mutex.with_lock t.lock (fun () -> delete_unlocked t))
    let save t (session:session) =
      if not (valid_token session.access_token) || session.expires_at_ms<=now_ms () then reject "Invalid or expired session" else
      Lwt.no_cancel (Lwt_mutex.with_lock t.lock (fun () ->
        let* ()=platform (unit_call "auth.keyEnsure" [t.key_alias]) in
        let plaintext=Yojson.Safe.to_string (`Assoc ["access_token",`String session.access_token;"expires_at_ms",`String (i64 session.expires_at_ms)]) in
        let* ciphertext=platform (Storage.Keystore.encrypt ~alias:t.key_alias ~plaintext) in
        platform (unit_call "auth.secretWrite" [t.name;ciphertext])))
    let clear_if t access_token = Lwt.no_cancel (Lwt_mutex.with_lock t.lock (fun () ->
      let* current=load_unlocked t in match current with
      | Some s when s.access_token=access_token -> delete_unlocked t | _ -> Lwt.return (Ok ())))
  end
  type options = { transaction_id:string; public_key_json:string }
  type grant = { access_token:string; token_type:string; expires_in:int64; recovery_codes:string list }
  type codecs = {
    decode_options : Yojson.Safe.t -> (options, string) result;
    encode_verification : transaction_id:string -> credential:Yojson.Safe.t -> Yojson.Safe.t;
    decode_grant : Yojson.Safe.t -> (grant, string) result;
  }
  let decode_codec message decode input =
    try match decode input with Ok x -> Ok x | Error _ -> Error (Invalid_response message)
    with _ -> Error (Invalid_response message)
  module Client = struct
    type t = {base_url:string;store:Token_store.t;connect_timeout_ms:int;read_timeout_ms:int;flow_lock:Lwt_mutex.t}
    let create ~base_url ~token_store ~connect_timeout_ms ~read_timeout_ms =
      if connect_timeout_ms<=0 || read_timeout_ms<=0 then reject "Positive authentication HTTP timeouts required" else
      let* base_url=platform (one "auth.baseUrl" [base_url]) in
      Lwt.return (Ok {base_url;store=token_store;connect_timeout_ms;read_timeout_ms;flow_lock=Lwt_mutex.create ()})
    let perform t authenticated ~path ~meth ~body =
      let* url=platform (one "auth.resolveUrl" [t.base_url;path]) in
      let* session=if authenticated then Token_store.load t.store else Lwt.return (Ok None) in
      if authenticated && session=None then Lwt.return (Error Not_authenticated) else
      let headers=["Accept","application/json"] @ (match body with None -> [] | Some _ -> ["Content-Type","application/json"]) @
        (match session with None -> [] | Some s -> ["Authorization","Bearer "^s.access_token]) in
      let* response=platform (Network.Http.request ~url ~meth ~headers ~body:(match body with None -> "" | Some j -> Yojson.Safe.to_string j)
        ~connect_timeout_ms:t.connect_timeout_ms ~read_timeout_ms:t.read_timeout_ms) in
      let* ()=if response.status=401 then (match session with None -> Lwt.return (Ok ()) | Some s -> Token_store.clear_if t.store s.access_token) else Lwt.return (Ok ()) in
      if response.status<200 || response.status>=300 then Lwt.return (Error (Http_status response.status))
      else if response.body="" then Lwt.return (Ok `Null) else Lwt.return (json response.body)
    let request_public t = perform t false
    let request t = perform t true
    let android_payload = function
      | `Assoc fields -> Ok (`Assoc (("client_kind",`String "android")::List.remove_assoc "client_kind" fields))
      | _ -> Error (Invalid_argument "Authentication payload must be an object")
    let ceremony t activity ~authenticated ~creation ~path ~payload ~codecs =
      let request=if authenticated then request t else request_public t in
      let* payload=Lwt.return (android_payload payload) in
      let* raw=request ~path:(path^"/options") ~meth:"POST" ~body:(Some payload) in
      let* options=Lwt.return (decode_codec "Invalid server options" codecs.decode_options raw) in
      if options.transaction_id="" then Lwt.return (Error (Invalid_response "Missing authentication transaction")) else
      let* credential=(if creation then create_passkey else get_passkey) activity ~options_json:options.public_key_json in
      let* credential=Lwt.return (json credential) in
      let* verification=Lwt.return (try android_payload (codecs.encode_verification ~transaction_id:options.transaction_id ~credential)
        with _ -> Error (Invalid_response "Verification encoder failed")) in
      request ~path:(path^"/verify") ~meth:"POST" ~body:(Some verification)
    let establish t raw codecs =
      let* grant=Lwt.return (decode_codec "Invalid session grant" codecs.decode_grant raw) in
      let now=now_ms () in
      if not (valid_token grant.access_token) || String.lowercase_ascii grant.token_type<>"bearer" || grant.expires_in<=0L || grant.expires_in>Int64.div (Int64.sub Int64.max_int now) 1000L then
        Lwt.return (Error (Invalid_response "Invalid session grant or lifetime")) else
      let expires_at_ms=Int64.add now (Int64.mul grant.expires_in 1000L) in
      let* ()=Token_store.save t.store {access_token=grant.access_token;expires_at_ms} in Lwt.return (Ok grant)
    let register t activity ~username ~codecs =
      if String.trim username="" then reject "Nonempty username required" else
      Lwt_mutex.with_lock t.flow_lock (fun () ->
        let* raw=ceremony t activity ~authenticated:false ~creation:true ~path:"/api/auth/register" ~payload:(`Assoc ["username",`String username]) ~codecs in
        establish t raw codecs)
    let login t activity ~username ~codecs =
      Lwt_mutex.with_lock t.flow_lock (fun () ->
        let payload=`Assoc (match username with None -> [] | Some name -> ["username",`String name]) in
        let* raw=ceremony t activity ~authenticated:false ~creation:false ~path:"/api/auth/login" ~payload ~codecs in establish t raw codecs)
    let reauthenticate t activity ~payload ~codecs =
      Lwt_mutex.with_lock t.flow_lock (fun () -> ceremony t activity ~authenticated:true ~creation:false ~path:"/api/auth/reauth" ~payload ~codecs)
    let add_passkey t activity ~payload ~codecs =
      Lwt_mutex.with_lock t.flow_lock (fun () -> ceremony t activity ~authenticated:true ~creation:true ~path:"/api/auth/credentials" ~payload ~codecs)
    let me t = request t ~path:"/api/auth/me" ~meth:"GET" ~body:None
    let sessions t = request t ~path:"/api/auth/sessions" ~meth:"GET" ~body:None
    let credentials t = request t ~path:"/api/auth/credentials" ~meth:"GET" ~body:None
    let revoke t family id = if id="" || id="." || id=".." then reject "Expected a nonempty, non-dot revocation identifier" else
      let* segment=platform (one "auth.pathSegment" [id]) in
      request t ~path:("/api/auth/"^family^"/"^segment) ~meth:"DELETE" ~body:None
    let revoke_session t ~id = revoke t "sessions" id
    let revoke_credential t ~id = revoke t "credentials" id
    let logout t = Lwt_mutex.with_lock t.flow_lock (fun () ->
      let cleaned=ref false in
      let cleanup () = Lwt.no_cancel (
        Token_store.delete t.store >>= fun deleted -> clear_credential_state () >|= fun cleared ->
        cleaned:=true;(deleted,cleared)) in
      Lwt.finalize (fun () ->
        (Token_store.load t.store >>= function
        | Error e -> Lwt.return (Error e)
        | Ok None -> Lwt.return (Ok ())
        | Ok (Some _) -> request t ~path:"/api/auth/logout" ~meth:"POST" ~body:(Some (`Assoc [])) >|= Result.map (fun _ -> ())) >>= fun result ->
        cleanup () >|= fun (deleted,cleared) -> match deleted,result with Error e,_ -> Error e | _,Error e -> Error e | _,Ok () -> cleared)
        (fun () -> if !cleaned then Lwt.return_unit else cleanup () >|= ignore))
  end
end
