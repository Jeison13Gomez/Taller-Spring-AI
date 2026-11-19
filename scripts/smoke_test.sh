#!/usr/bin/env bash
set -euo pipefail

URL="http://localhost:8080/api/v1/chat"
PAYLOAD='{"question":"Hello, demo","sessionId":"demo"}'

echo "Running smoke test against $URL"
RESPONSE=$(curl -s -H "Content-Type: application/json" -d "$PAYLOAD" "$URL" || true)

if [ -z "$RESPONSE" ]; then
  echo "No response from backend"
  exit 2
fi

# Basic check for non-empty answer field
echo "$RESPONSE" | grep -q "answer" || { echo "Response missing 'answer' field"; exit 3; }

# Usage metrics (feature 003): the usage object and its six fields must be present
echo "$RESPONSE" | grep -q '"usage"' || { echo "Response missing 'usage' object"; exit 4; }
for key in promptTokens completionTokens totalTokens model finishReason tokensPerSecond; do
  echo "$RESPONSE" | grep -q "\"$key\"" || { echo "Response missing usage field '$key'"; exit 4; }
done

# Conversation memory (feature 006): the first turn of a fresh session has no history, the second sees one exchange
SESSION="smoke-$$"
MEM1=$(curl -s -H "Content-Type: application/json" -d "{\"question\":\"My name is Ana\",\"sessionId\":\"$SESSION\",\"numPredict\":30}" "$URL" || true)
echo "$MEM1" | grep -q '"historyMessages":0' || { echo "Expected historyMessages 0 on the first turn of $SESSION: $MEM1"; exit 14; }
MEM2=$(curl -s -H "Content-Type: application/json" -d "{\"question\":\"What is my name?\",\"sessionId\":\"$SESSION\",\"numPredict\":30}" "$URL" || true)
echo "$MEM2" | grep -q '"historyMessages":2' || { echo "Expected historyMessages 2 on the second turn of $SESSION: $MEM2"; exit 15; }

# An empty sessionId is rejected with 400 naming the field
SID_BODY=$(mktemp)
SID_STATUS=$(curl -s -o "$SID_BODY" -w '%{http_code}' -H "Content-Type: application/json"   -d '{"question":"hi","sessionId":""}' "$URL" || true)
[ "$SID_STATUS" = "400" ] || { echo "Expected HTTP 400 for an empty sessionId, got '$SID_STATUS'"; rm -f "$SID_BODY"; exit 16; }
grep -q '"field":"sessionId"' "$SID_BODY" || { echo "400 response does not name the 'sessionId' field"; rm -f "$SID_BODY"; exit 17; }
rm -f "$SID_BODY"

# Generation settings (feature 004): an out-of-range value is rejected with 400 naming the field
BAD_BODY=$(mktemp)
BAD_STATUS=$(curl -s -o "$BAD_BODY" -w '%{http_code}' -H "Content-Type: application/json" \
  -d '{"question":"hi","temperature":2.5}' "$URL" || true)
[ "$BAD_STATUS" = "400" ] || { echo "Expected HTTP 400 for temperature 2.5, got '$BAD_STATUS'"; rm -f "$BAD_BODY"; exit 5; }
grep -q '"field":"temperature"' "$BAD_BODY" || { echo "400 response does not name the 'temperature' field"; rm -f "$BAD_BODY"; exit 6; }
rm -f "$BAD_BODY"

# Prompt templates (feature 005): an unknown template id is rejected with 400 naming the field
TPL_BODY=$(mktemp)
TPL_STATUS=$(curl -s -o "$TPL_BODY" -w '%{http_code}' -H "Content-Type: application/json" \
  -d '{"question":"hi","templateId":"pirata"}' "$URL" || true)
[ "$TPL_STATUS" = "400" ] || { echo "Expected HTTP 400 for an unknown templateId, got '$TPL_STATUS'"; rm -f "$TPL_BODY"; exit 7; }
grep -q '"field":"templateId"' "$TPL_BODY" || { echo "400 response does not name the 'templateId' field"; rm -f "$TPL_BODY"; exit 8; }
rm -f "$TPL_BODY"

# Classification (feature 005): a structured result with the default strategy
CLS_URL="${URL%/chat}/classify"
CLS_BODY=$(mktemp)
CLS_STATUS=$(curl -s --max-time 170 -o "$CLS_BODY" -w '%{http_code}' -H "Content-Type: application/json" \
  -d '{"text":"The app crashes every time I open the settings screen","temperature":0.0}' "$CLS_URL" || true)
[ "$CLS_STATUS" = "200" ] || { echo "Expected HTTP 200 from /classify, got '$CLS_STATUS': $(cat "$CLS_BODY")"; rm -f "$CLS_BODY"; exit 12; }
for key in '"result"' '"category"' '"confidence"' '"justification"' '"strategy":"instruction"' '"attempts":1'; do
  grep -q "$key" "$CLS_BODY" || { echo "Classification response missing $key"; rm -f "$CLS_BODY"; exit 13; }
done
rm -f "$CLS_BODY"

echo "Smoke test passed"
