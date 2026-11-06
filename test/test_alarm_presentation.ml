open Android
module A = Alarm.Screen
let action : A.action = {id="start";label="Commencer";role=Primary;handler="task.start";payload=""}
let presentation : A.presentation = {id="task";notification_id=41;title="Tâche";subtitle=None;
  message="Description";media=None;actions=[action];layout=Standard}
let invalid p = match A.validate p with Error (Error.Invalid_argument _) -> () | _ -> failwith "Expected invalid alarm presentation"
let () =
  Printexc.record_backtrace true;
  assert (A.validate presentation=Ok ());
  invalid {presentation with id=" "};
  invalid {presentation with id="a\000b"};
  assert (A.validate {presentation with notification_id=0}=Ok ());
  assert (A.validate {presentation with notification_id=(-1)}=Ok ());
  invalid {presentation with notification_id=Int64.to_int 2147483648L};
  invalid {presentation with actions=[]};
  invalid {presentation with actions=[action;action]};
  invalid {presentation with actions=List.init 4 (fun n -> {action with id=string_of_int n})};
  List.iter (fun a -> invalid {presentation with actions=[a]})
    [{action with id=""};{action with label=" "};{action with handler=""};{action with handler="bad\000handler"}];
  List.iter (fun uri -> invalid {presentation with media=Some {uri;content_description="Photo";max_height_dp=None}})
    ["";"https://example.invalid/photo";"relative.jpg";"file:///a\000b"];
  invalid {presentation with media=Some {uri="content://photos/1";content_description=" ";max_height_dp=None}};
  invalid {presentation with media=Some {uri="content://photos/1";content_description="Photo";max_height_dp=Some 0}};
  List.iter (fun layout -> List.iter (fun role ->
    assert (A.validate {presentation with layout;actions=[{action with role}];media=Some {
      uri="content://photos/été.jpg";content_description="Photo de la tâche";max_height_dp=Some 240}}=Ok ()))
    [A.Primary;Secondary;Neutral;Destructive]) [A.Compact;Standard;Expanded]
