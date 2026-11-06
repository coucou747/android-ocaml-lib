module U=Android.Ui
let invalid = function Error (Android.Error.Invalid_argument _) -> () | _ -> failwith "Expected invalid layout"
let () =
  invalid (U.layout ~width:(Dp (-1)) ());
  invalid (U.layout ~weight:nan ());
  invalid (U.layout ~weight:(-1.) ());
  invalid (U.layout ~weight:infinity ());
  invalid (U.layout ~margin:{left_dp=0;top_dp=(-1);right_dp=0;bottom_dp=0} ());
  assert (Result.is_ok (U.layout ~width:Match_parent ~height:Wrap_content ~weight:1. ()));
  assert (Result.is_ok (U.layout ()))
