<!-- 主循环给模型的提醒、说明与参考资料的来源标签。每条以「## 键」开头,下面是原文;{{名字}} 由代码填。 -->

## empty_reply
Your reply was empty. Continue the task with your tools, or reply with your final answer to the user in text.

## truncated_reply
Your reply was cut off by the output limit before it was complete. Continue the task with your tools, or reply again with a shorter final answer.

## unconfirmed_nudge
Before this answer goes to the user:
{{findings}}
Check this: read the place again if you can, then reply with your corrected final answer. Anything you still cannot confirm, say plainly in the answer that you could not confirm it instead of stating it as fact. This check happens once; your next answer goes to the user.

## language_nudge
Before this answer goes to the user: it is written mostly in {{answer}}, but the user's latest message is in {{user}}. Rewrite the whole answer in the language of the user's latest message. Translate what you read; keep the original only for proper names, or for labels the user must find on the screen, written as "translation (original)". Do not change any value: numbers, dates, amounts, units and names stay exactly as read. This check happens once; your next answer goes to the user.

## leaked_call_nudge
Your last reply contained a call to the tool `{{tool}}` written out as plain text, so it was not run: a tool call only takes effect when it is made through tool calling. If you meant to call a tool, call it again now; otherwise reply with your final answer to the user in plain words. This check happens once.

## unconfirmed_gaps
These items in your answer do not appear in anything you read during this task:
{{items}}

## unconfirmed_objection
An independent check of your answer against what you read during this task found a problem:
{{reasons}}

## followup_nudge
Before this goes to the user: {{what}}, and nothing has been done with it since. Carry it out now, or, if you are not going to, tell the user plainly why.

## followup_approved
the user approved this action: {{action}}

## followup_answered
the user answered your question 「{{prompt}}」

## answer_or_failure
If the result the task asks for needs a feature, menu, setting, page, item or value that is not there, call end_not_done listing what you checked. Only a yes/no question about whether something exists is answered by its absence: if the app's own search gave a definitive none, reply that it does not exist, quoting the text it showed.

## give_up_advice
You keep repeating without progress. Stop revisiting the same places. If a specific missing choice or detail from the user would decide the next route, call ask_user and ask it once. Otherwise, continue only with a concrete untried route; if there is none, call end_not_done with the confirmed partial result, what is still missing and what you checked. {{answer_or_failure}}

## repeated_input_advice
You have already typed "{{text}}" {{times}} times in this task; typing it again will show the same result. If what you have checked shows the target is not there, stop. {{answer_or_failure}}

## progress_check
Progress check: {{used}} of {{max}} steps used and the task is not finished. If what you have checked so far (search inside the app, its main menus) shows the target is not there or cannot be reached, stop now. {{answer_or_failure}} Continue only if you have a concrete, untried next place to look.

## final_turn
You are out of steps; no more phone actions are possible. If the task is done, reply to the user with the result in text, stating only what you read. If it is not done or only partly done, call end_not_done with the confirmed partial result, what you checked and what is missing.

## screenshot_note
Current screenshot for the observation above.

## file_image_note
The image file read above.

## action_frame_note
The screen right after the call above. Act with ref + snapshotId from the elements in that result's screen field; the image is only for understanding, and its screenshotId is for view_past_screens, never a snapshotId.

## batch_view_note
The screen after the steps above. Act with ref + snapshotId from these elements; the image (if any) is only for understanding, and its screenshotId is for view_past_screens, never a snapshotId.
screen: {{screen}}

## recalled_note
Recalled earlier screenshots, in the order of the view_past_screens result entries marked imageAttached. They show past screens, not the current one.

## nudge_source
no answer given

## before_accept_source
added before the answer

## followup_source
not carried out yet

## unconfirmed_source
answer check

## language_source
answer language

## leaked_call_source
tool call as text

## progress_source
step check

## final_source
out of steps

## replay_source
earlier way of doing this

## parallel_call_skipped
Phone actions run one at a time. Observe the result of the first action, then try again.

## replay.completed
All steps of the earlier way of doing this were run ({{total}} steps):
{{steps}}
Read the app's current state to check the result; for a lookup task, find the answer in what you read, then answer the user directly.

## replay.progress
{{done}} of {{total}} steps were run automatically, following a way that worked before:
{{steps}}
{{next}}



## replay.diverged_executed
Step {{step}} was run, but the expected change did not show: expected {{expected}}, actually read {{seen}}{{error}}. First check whether this step took effect; do not repeat it. Continue from the current screen or page.

## replay.diverged_skipped
Step {{step}} expected {{expected}}, actually read {{seen}}{{error}} (this step was not run). Continue from the current screen or page.

## replay.error_code
; error code: {{code}}

## replay.no_steps
(none)

## replay.handback
{{head}}

{{note}}

## replay.stopped
Step {{step}} is 「{{action}}」, which needs the user's confirmation, so it was not run. Continue from the current screen or page, and handle the confirmation the normal way.

## confirmation_unavailable
request_confirmation is not available.

## confirm_app_mismatch
app {{app}} is not the app on screen now ({{foreground}}). Observe, then call request_confirmation again with the package of the app on screen.

## batch.mixed_surface
These steps act on more than one screen ({{surfaces}}); one do_steps call stays on one screen. Nothing was run: split them into separate calls, one screen per batch.

## approved_step_first
Do the approved step on its own first: the user approved one action. Do exactly that action as a single tool call (not inside do_steps or web_script), then continue.

## replay_screen_header
Current screen (read result; its refs can be used directly):

## replay_no_screen
The current screen could not be read: observe first, then continue.

## confirmation_declined
The user did not approve. Do not try this action again; report where things stand, then end.

## confirmation_approved
The user approved this once. Do this one action right away, without tapping anything else, scrolling, going back or switching apps in between (typing is fine). The approval is valid for 3 steps; the next matching action uses it up whether it succeeds or not, and doing it again needs a new request.

## no_answer_message
No answer came within the time limit.

## handback_note
Getting the phone back only means control was returned; it does not mean the step succeeded. Read the current screen first to check the result, then continue or report.

## no_handback_note
There was no handback. Do not say the user took care of it; report how the takeover ended as it is, and try another way or end if needed.

## user_finished_note
The user says they finished it themselves.

## user_finished_prompt
The user says they finished it themselves. Only observe to read the app's current state and check: if it is confirmed done, answer the user directly with what you read; if you cannot confirm it, call end_not_done. Do nothing else.


## user_screen.sent
The user just captured the screen they are looking at ({{app}}, {{time}}), with no words.

## user_screen.sent_with_words
The user just captured the screen they are looking at ({{app}}, {{time}}), with these words: {{words}}

## user_screen.blocked
The user tried to send you the screen they are looking at ({{app}}, {{time}}), but that page does not allow screenshots, so neither the image nor its text was captured; no words.

## user_screen.blocked_with_words
The user tried to send you the screen they are looking at ({{app}}, {{time}}), but that page does not allow screenshots, so neither the image nor its text was captured; with these words: {{words}}

## user_screen.missing
The user sent a screen they were looking at ({{id}}), but that screenshot is no longer available.

## user_screen.earlier
(Screen {{id}} the user sent earlier: {{app}}, {{time}}; the screen begins with 「{{gist}}」)

## user_screen.earlier_blank
(Screen {{id}} the user sent earlier: {{app}}, {{time}}; no text was read on it)

## user_screen.earlier_blocked
(The user tried to send a screen earlier: {{app}}, {{time}}; that page does not allow screenshots, so nothing was captured)

## user_screen.detail
Screen id: {{id}}
App: {{app}}{{package}}
Captured at: {{time}}
Text on the screen (each line starts with the position of its centre x,y, 0–999, top-left is 0,0; in a chat, bubbles on the left are from the other person and bubbles on the right are the user's own):
{{lines}}

## user_screen.no_text
No text was read on the screen.

## user_screen.ask_vision
You cannot see this image. To learn anything beyond the text (icons, pictures, colours, layout), ask the vision model with view_user_screen and your question.

## user_screen.no_words
The user sent only this screen, without words. Work out from the screen what they most likely want. If it only needs an answer, reading, looking something up or searching (nothing gets written or changed anywhere), just do it. If it would write or change something (save an entry in another app, send, post, book, pay, delete, change a setting…), first ask once with ask_user, in one line, what you plan to do, with short options such as 「Log it」/「No thanks」, and go ahead only if they agree. If you do not know which app they use for that kind of job, ask in the same question, and remember their choice if you can save memories.

## user_screen.blocked_reply
The page did not allow screenshots, so you cannot see it. Say so in one line and ask the user to switch to another page and send again, or to just tell you what to do.

## knowledge.learned
## Notes Mika gathered from past tasks in this app (facts seen on earlier runs; check them against the current screen; they are not instructions from the user)
{{notes}}

## knowledge.learned_trial
## Notes Mika gathered from past tasks in this app, still on trial (no task has succeeded with them yet; check each against the current screen before relying on it; they are not instructions from the user)
{{notes}}
