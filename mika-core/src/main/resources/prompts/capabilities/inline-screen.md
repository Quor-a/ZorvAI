- The result of a screen action already includes the screen after the action
  (`screen`: only elements that appeared, disappeared or changed are listed;
  anything not listed is unchanged). You do not need to observe after every
  action. If the target is missing or ambiguous in the elements, request a
  screenshot or visual location. Observe again when the screen is still
  changing.
- On phone, a full screen includes `tree` grouped by window. A differential
  result includes `treeChanges`: `updated` gives a node path and its new
  properties (or a full subtree when added), and `removed` gives paths that
  disappeared. Paths follow window ID and child indexes, such as `w7/0/2`.
