# Adjusts the API's published description for the Kotlin client generated
# from it (OpenAPI Generator's "kotlin" generator, in core/build.gradle.kts).
# scripts/update-api.sh runs this on the pinned release's openapi.json.
#
# The generator already keeps to the API's promises (decision 0023 in the
# server repository) without help: responses ignore fields they don't know,
# an enumerated value they don't know decodes as the enum's
# `unknown_default_open_api` case (the build's enumUnknownDefaultCase), and a
# property that may be null (`anyOf: [X, {type: "null"}]`) is nullable. So
# this changes only what the generator can't turn into working code:
#
# - A request body offered as both JSON and something else (activating a stop
#   takes the typed code as JSON, or a camera frame as multipart form data)
#   keeps only JSON. The generator writes one method per operation and can't
#   name the second body's type; the app always sends JSON, decoding QR codes
#   on the phone.
# - Each alternative of a live-update message (LiveUpdate, oneOf) and of a
#   subscription (LiveUpdateSubscription, anyOf) is given a title, which the
#   generator names its class by: LiveUpdatePuzzleSolved,
#   LiveUpdateQuestSubscription and so on, rather than numbers that would
#   shift as messages are added. (The generator's class for LiveUpdate itself
#   merges every alternative into one that can't read any of them, so
#   core/LiveUpdates.kt reads `message` and decodes the alternative it names.)

def json_only:
  if type == "object" and (.content | type) == "object"
     and (.content | has("application/json")) and (.content | length) > 1
  then .content |= { "application/json": .["application/json"] }
  else . end;

.paths |= map_values(map_values(
  if type == "object" and has("requestBody") then .requestBody |= json_only else . end))
| .components.schemas.LiveUpdate.oneOf |= map(
    (.properties.message | .const // .enum[0]) as $message
    | .title = "LiveUpdate" + ($message | .[0:1] | ascii_upcase) + ($message | .[1:]))
| .components.schemas.LiveUpdateSubscription.anyOf |= map(
    .title = "LiveUpdate"
      + (if .properties | has("questName") then "Quest" else "User" end) + "Subscription")
