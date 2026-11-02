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
  (** Request the application's manifest entry activity using the application
      context, including when no [t] exists (for example a cold alarm handler).
      Reuses an existing main instance and clears activities above it.
      No entry activity yields [Error.Unavailable]. [Ok ()] acknowledges the
      request, not visibility: Android's background/lock-screen restrictions
      still apply. Observe [on_lifecycle] before interacting with the new UI. *)
  val launch_main : unit -> unit response
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
  type special = Exact_alarm | Notifications | Full_screen_intent | Background_location
  val check : string -> bool response
  val request : Activity.t -> string list -> string list response
  val check_special : special -> bool response
  (** [Background_location] opens the app permission settings; consent remains an
      explicit user action. Check foreground permissions separately. *)
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
  (** Local [file://] URI of the same private basename used by read/write_file.
      Escapes Unicode and spaces. Does not create the file or require its existence.
      Suitable for Ui.image; external sharing requires a content URI provider. *)
  val file_uri : name:string -> string response
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


module Health_connect : sig
  type availability = Available | Unavailable | Update_required
  type record_type = Steps | Heart_rate | Sleep | Exercise | Distance | Active_calories | Total_calories
  type interval = { start_ms : int64; end_ms : int64; start_offset_s : int option; end_offset_s : int option }
  type metadata = { id : string; origin : string; last_modified_ms : int64 }
  type steps = { metadata : metadata; interval : interval; count : int64 }
  type sample = { at_ms : int64; bpm : int64 }
  type heart_rate = { metadata : metadata; interval : interval; samples : sample list }
  (** [stage_type] and [exercise_type] retain the Health Connect integer codes,
      including unknown values added by newer platform versions. *)
  type stage = { start_ms : int64; end_ms : int64; stage_type : int }
  type sleep = { metadata : metadata; interval : interval; title : string option; notes : string option; stages : stage list }
  (** Session summary only; routes, laps and segments are not exposed. *)
  type exercise = { metadata : metadata; interval : interval; exercise_type : int; title : string option; notes : string option }
  type query = { start_ms : int64; end_ms : int64; page_size : int; page_token : string option }
  type 'a page = { records : 'a list; next_page_token : string option }
  val status : unit -> availability response
  val read_permission : record_type -> string response
  val granted_permissions : unit -> record_type list response
  (** Dedicated Health Connect consent UI, with only the requested READ permissions.
      The application supplies rationale text resources in its manifest. *)
  val request_permissions : Activity.t -> record_type list -> record_type list response
  (** Feature probe: [Ok false] when the SDK or background-read feature is absent. *)
  val background_read_available : unit -> bool response
  (** [Ok false] if denied; [Error Unavailable] when unsupported. *)
  val background_read_granted : unit -> bool response
  (** Explicit SDK consent. Refusal is [Error Permission_denied]; an absent feature
      is [Error Unavailable]. Success is [Ok true]. Requires manifest permission
      android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND. *)
  val request_background_read : Activity.t -> bool response
  val open_settings : Activity.t -> unit response
  (** Read one SDK page, chronologically ascending, without merging data origins.
      Times are Unix milliseconds. The SDK applies its permission/history limits. *)
  val read_steps : query -> steps page response
  val read_heart_rate : query -> heart_rate page response
  val read_sleep : query -> sleep page response
  val read_exercise : query -> exercise page response
  (** SDK aggregation, with its source-priority rules, rather than summing raw records. *)
  val aggregate_steps : start_ms:int64 -> end_ms:int64 -> int64 option response
  (** SDK aggregates with source priorities. [None] means no data; an actual zero
      remains [Some 0.]. Distance is in metres, energy in kilocalories. *)
  val aggregate_distance : start_ms:int64 -> end_ms:int64 -> float option response
  val aggregate_active_calories : start_ms:int64 -> end_ms:int64 -> float option response
  val aggregate_total_calories : start_ms:int64 -> end_ms:int64 -> float option response
end

module Location : sig
  type provider = Gps | Network
  type fix = { provider : string option; latitude : float; longitude : float;
    accuracy_m : float option; altitude_m : float option; speed_mps : float option; bearing_deg : float option;
    at_ms : int64; elapsed_realtime_ns : int64; is_mock : bool }
  type event = Fix of fix | Provider_enabled of string | Provider_disabled of string
  (** Android WGS84 geodesic distance in metres. No position acquisition or permission
      required. Coordinates must be finite and within latitude/longitude bounds. *)
  val distance_between : latitude1:float -> longitude1:float -> latitude2:float -> longitude2:float -> float response
  val enabled : provider:provider -> bool response
  (** A cached fix may be stale; inspect its timestamps. *)
  val last_known : provider:provider -> fix option response
  (** One fix via LocationManagerCompat, possibly cached; [None] on timeout or
      no fix. Cancelling the Lwt promise cancels the underlying request. *)
  val current : provider:provider -> timeout_ms:int -> fix option response
  (** Process-local updates, stopped with Subscription.cancel. No provider fallback.
      Actual delivery frequency is controlled by Android; interval is a minimum. *)
  val watch : provider:provider -> interval_ms:int64 -> min_distance_m:float -> (event -> unit Lwt.t) -> Subscription.t response
  (** Register this stable name at every process start, before returning from entry.
      A slow handler coalesces intermediate events to the most recent event.
      Failure/Retry acknowledges an unsuccessful delivery; it does not replay a fix. *)
  val register_background_handler : name:string -> (event -> Background.outcome Lwt.t) -> (unit, Error.t) result
  (** Start while the application is visible. The location foreground service keeps
      updates while the screen is off; it requires the client's manifest permissions,
      a notification channel and icon. One service per app; a new start replaces it.
      OS restart is attempted via redelivery, subject to current permissions. *)
  val start_tracking_service : provider:provider -> interval_ms:int64 -> min_distance_m:float -> notification_id:int -> channel:string -> title:string -> text:string -> small_icon:int -> handler:string -> unit response
  val stop_tracking_service : unit -> unit response
end

(** Application-defined settings, persisted in Storage.Preferences. No permissions
    are requested implicitly. Recreate pages after activity recreation. *)
module Settings : sig
  type item =
    | Section of string
    | Toggle of { key:string; title:string; description:string option; initial:bool }
    | Text of { key:string; title:string; description:string option; initial:string;
                multiline:bool; validate:(string -> (unit,string) result) option }
    | Integer of { key:string; title:string; description:string option; initial:int64;
                   validate:(int64 -> (unit,string) result) option }
    | Choice of { key:string; title:string; description:string option; initial:string;
                  options:(string * string) list }
    | Action of { title:string; description:string option; run:Activity.t -> unit response }
  type schema
  type page
  (** Register once at process startup, including cold starts. The application
      manifest selects this name with android.ocaml.settings metadata. Callbacks
      use the dedicated preferences activity; normal lifecycle subscribers are
      not notified for this activity. No main activity needs to be opened. *)
  val register : name:string -> schema ->
    on_change:(key:string -> Storage.Preferences.value -> unit Lwt.t) ->
    on_error:(Error.t -> unit Lwt.t) -> (unit, Error.t) result
  (** Open the registered preferences screen selected by application metadata.
      Also declared as ACTION_APPLICATION_PREFERENCES for system discovery.
      Enable SettingsActivity in the client manifest; otherwise Unavailable.
      Missing metadata is Invalid_argument. System shortcut placement varies. *)
  val open_ : Activity.t -> unit response
  val define : store:string -> item list -> (schema, Error.t) result
  (** Missing keys return their declared initial value, without writing it.
      Invalid stored values are errors, never silently reset. *)
  val get : schema -> key:string -> Storage.Preferences.value response
  val set : schema -> key:string -> Storage.Preferences.value -> unit response
  (** Standard AndroidX PreferenceFragmentCompat screen. The host must be a
      FragmentActivity (BridgeActivity supports this); use an AppCompat theme.
      Prefer register/open_ for the dedicated system-discoverable activity.
      Each field commits independently. on_change runs only after a successful UI
      write. Programmatic set requires refresh and does not invoke on_change.
      Validators are pure and should not raise; exceptions become Platform errors. *)
  val create : Activity.t -> schema ->
    on_change:(key:string -> Storage.Preferences.value -> unit Lwt.t) ->
    on_error:(Error.t -> unit Lwt.t) -> page response
  val view : page -> Ui.view
  val refresh : page -> unit response
  (** Idempotent. Dismisses dialogs, cancels listeners and releases the root view. *)
  val release : page -> unit response
end
