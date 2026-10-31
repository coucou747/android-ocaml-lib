(** Android bindings. All calls are made from the library's Lwt runtime thread.
    Binary strings cross JNI as byte arrays; text is UTF-8. *)
module Error : sig
  type t = Unavailable of string | Permission_denied of string
    | Invalid_argument of string | Invalid_handle of string
    | Platform of string | Protocol of string
end
type 'a response = ('a, Error.t) result Lwt.t
module Subscription : sig
  type t
  val cancel : t -> unit response
end
module Activity : sig
  type t
  type event = Created | Started | Resumed | Paused | Stopped | Destroyed
  type size = { width_px : int; height_px : int }
  (** Current window dimensions in physical pixels, suitable for image bounds. *)
  val display_size : t -> size response
  val current : unit -> t response
  val set_keep_screen_on : t -> bool -> unit response
  val on_lifecycle : (t -> event -> unit Lwt.t) -> Subscription.t response
end
module Ui : sig
  type view
  type orientation = Horizontal | Vertical
  val text : Activity.t -> string -> view response
  val button : Activity.t -> string -> view response
  val edit_text : Activity.t -> string -> view response
  val linear_layout : Activity.t -> orientation -> view response
  (** Vertical ScrollView: use [add] once with a LinearLayout containing the rows.
      This container does not virtualize rows. *)
  val scroll_view : Activity.t -> view response
  (** Decode a local content/file/android.resource URI off the UI thread.
      Explicit pixel bounds limit the decoded preview; orientation is respected. *)
  val image : Activity.t -> uri:string -> max_width_px:int -> max_height_px:int -> view response
  val set_image_uri : view -> uri:string -> max_width_px:int -> max_height_px:int -> unit response
  type input_type = Text | Number of { signed : bool; decimal : bool }
  (** Numeric input uses a single line. Text preserves its multiline setting. *)
  val set_input_type : view -> input_type -> unit response
  (** Multiline is supported on text input only. *)
  val set_multiline : view -> bool -> unit response
  val set_hint : view -> string -> unit response
  (** [None] clears the inline validation error. *)
  val set_error : view -> string option -> unit response
  val add : view -> view -> unit response
  val show : Activity.t -> view -> unit response
  val set_text : view -> string -> unit response
  val get_text : view -> string response
  val set_enabled : view -> bool -> unit response
  val release : view -> unit response
end
module Resources : sig
  type scope = Application | Android
  type kind = Drawable | Mipmap | Raw | String | Color
  (** Resolve an unqualified name. A missing resource is [Error.Unavailable],
      never the invalid resource identifier zero. *)
  val resolve : scope:scope -> kind:kind -> name:string -> int response
end
module Locale : sig
  (** Read the current phone/application configuration at each call. *)
  val time_zone : unit -> string response
  val language_tag : unit -> string response
  val uses_24_hour_clock : unit -> bool response
  (** Unix milliseconds, formatted in the current device time zone and locale.
      Time formatting follows the user's 12/24-hour preference. *)
  val format_date : at_ms:int64 -> string response
  val format_time : at_ms:int64 -> string response
  val format_date_time : at_ms:int64 -> string response
end
module Events : sig
  type touch = { action : int; x : float; y : float }
  val on_click : Ui.view -> (unit -> unit Lwt.t) -> Subscription.t response
  val on_text_changed : Ui.view -> (string -> unit Lwt.t) -> Subscription.t response
  val on_touch : Ui.view -> consume:bool -> (touch -> unit Lwt.t) -> Subscription.t response
end
module Permissions : sig
  type special = Exact_alarm | Notifications | Full_screen_intent
  val check : string -> bool response
  val request : Activity.t -> string list -> string list response
  val check_special : special -> bool response
  val open_settings : Activity.t -> special -> unit response
end
module Camera : sig
  type photo
  type capture = Captured of photo | Cancelled
  (** Delegate to the system camera. [name] is a fresh private JPEG basename.
      Cancellation by the user is [Ok Cancelled]. Rotation preserves the request;
      process death does not preserve the OCaml continuation. *)
  val capture : Activity.t -> name:string -> capture response
  val uri : photo -> string
  (** Restore a saved URI, checking ownership and file existence. *)
  val of_uri : string -> photo response
  val read : photo -> string response
  val delete : photo -> unit response
end
module Alarm : sig
  type mode = Exact | Alarm_clock
  val can_schedule_exact : unit -> bool response
  val schedule : id:string -> at_ms:int64 -> mode:mode -> handler:string -> payload:string -> unit response
  val cancel : id:string -> unit response
  module Screen : sig
    type t = {
      id : string; notification_id : int; title : string; text : string;
      stop_label : string; snooze_label : string;
      stop_handler : string; snooze_handler : string; payload : string;
    }
    (** Show from a foreground activity. Buttons dispatch the named Runtime
        handlers with [payload]; the client stops sound or schedules snooze. *)
    val show : Activity.t -> t -> unit response
    (** Post an alarm notification with direct activity actions. Requires a HIGH
        importance channel. [full_screen=true] requires the matching authorization;
        denial is explicit, without downgrading the request. Android decides
        whether an authorized request opens full screen or appears heads-up. *)
    val notify : channel:string -> small_icon:int -> full_screen:bool -> t -> unit response
    val dismiss : id:string -> notification_id:int -> unit response
  end
end
module Notification : sig
  type importance = Low | Default | High
  val create_channel : id:string -> name:string -> importance:importance -> unit response
  val show : id:int -> channel:string -> title:string -> text:string -> small_icon:int -> handler:string -> payload:string -> unit response
  val cancel : id:int -> unit response
end
module Audio : sig
  type player
  (** The configured alarm sound URI; [None] means no default sound (silent).
      No ringtone or notification sound is substituted. *)
  val default_alarm_uri : unit -> string option response
  val play : uri:string -> loop:bool -> player response
  val stop : player -> unit response
end
module Vibrator : sig
  val vibrate : duration_ms:int -> unit response
  val cancel : unit -> unit response
end
module Background : sig
  type outcome = Success | Failure | Retry
  type constraints = { network : bool; charging : bool }
  val enqueue : name:string -> handler:string -> payload:string -> constraints:constraints -> interval_ms:int64 option -> unit response
  val cancel : name:string -> unit response
  val schedule_job : id:int -> handler:string -> payload:string -> constraints:constraints -> unit response
  val cancel_job : id:int -> unit response
  val start_service : handler:string -> payload:string -> notification_id:int -> channel:string -> title:string -> small_icon:int -> unit response
  val stop_service : unit -> unit response
end
module Network : sig
  type state = { connected : bool; metered : bool }
  val state : unit -> state response
  val on_change : (state -> unit Lwt.t) -> Subscription.t response
  module Http : sig
    type response = { status : int; headers : (string * string) list; body : string }
    val request : url:string -> meth:string -> headers:(string * string) list -> body:string -> connect_timeout_ms:int -> read_timeout_ms:int -> (response, Error.t) result Lwt.t
  end
end
module Storage : sig
  val read_file : name:string -> string response
  val write_file : name:string -> contents:string -> unit response
  val remove_file : name:string -> unit response
  module Preferences : sig
    type value = Text of string | Bool of bool | Int of int64
    val set : store:string -> key:string -> value -> unit response
    val get : store:string -> key:string -> value option response
    val remove : store:string -> key:string -> unit response
  end
  module Keystore : sig
    val create : alias:string -> unit response
    (** Ciphertexts contain their IV and are encoded using Android Base64. *)
    val encrypt : alias:string -> plaintext:string -> string response
    val decrypt : alias:string -> ciphertext:string -> string response
    val delete : alias:string -> unit response
  end
  module Sqlite : sig
    type t
    type value = Null | Integer of int64 | Float of float | Text of string | Blob of string
    type statement = { sql : string; params : value list }
    val open_db : name:string -> t response
    val close : t -> unit response
    val execute : t -> statement -> unit response
    (** Execute a batch atomically; rollback on any error. *)
    val transaction : t -> statement list -> unit response
    val query : t -> statement -> value list list response
  end
end
module Broadcast : sig
  type event = { action : string; payload : string }
  val listen : actions:string list -> (event -> unit Lwt.t) -> Subscription.t response
end
module Runtime : sig
  type handler = string -> Background.outcome Lwt.t
  val register : name:string -> handler -> (unit, Error.t) result
  val set_entry : (unit -> unit Lwt.t) -> unit
  val set_error_handler : (exn -> unit) -> unit
end
