#!/usr/bin/env bash
set -euo pipefail

# Integration test: POST to backend, expect a non-empty answer and consistent usage metrics.
# Sends three different questions in sequence; each answer must carry its own usage object.
# Every request has its own sessionId (it-<pid>-<n>) so that no two requests share a conversation (feature 006).
URL="http://localhost:8080/api/v1/chat"
SEQ=0
next_session() {
  SEQ=$((SEQ + 1))
  SESSION_ID="it-$$-$SEQ"
}

# Prints the integer value of a usage field, or an empty string if the value is null/absent.
int_field() {
  echo "$1" | sed -n "s/.*\"$2\":\([0-9][0-9]*\).*/\1/p"
}

for QUESTION in "Integration test" "Say hi in one sentence" "Name one colour"; do
  next_session
  PAYLOAD="{\"question\":\"$QUESTION\",\"sessionId\":\"$SESSION_ID\"}"
  RESPONSE=$(curl -s -H "Content-Type: application/json" -d "$PAYLOAD" "$URL" || true)

  if [ -z "$RESPONSE" ]; then
    echo "No response"
    exit 2
  fi

  echo "Response: $RESPONSE"
  echo "$RESPONSE" | grep -q 'answer' || { echo "Missing answer field"; exit 3; }
  echo "$RESPONSE" | grep -q '"usage"' || { echo "Missing usage object"; exit 4; }

  PROMPT=$(int_field "$RESPONSE" promptTokens)
  COMPLETION=$(int_field "$RESPONSE" completionTokens)
  TOTAL=$(int_field "$RESPONSE" totalTokens)

  # Token values may legitimately be null (not available); when all three exist, total = prompt + completion.
  if [ -n "$PROMPT" ] && [ -n "$COMPLETION" ] && [ -n "$TOTAL" ]; then
    [ "$TOTAL" -eq $((PROMPT + COMPLETION)) ] || {
      echo "totalTokens ($TOTAL) != promptTokens ($PROMPT) + completionTokens ($COMPLETION)"
      exit 5
    }
  else
    echo "Note: some token values are not available for '$QUESTION'"
  fi
done

# Generation settings (feature 004): numPredict truncates only the request that sends it.
LONG_QUESTION="Explain photosynthesis in detail"

next_session
CUT=$(curl -s --max-time 170 -H "Content-Type: application/json" \
  -d "{\"question\":\"$LONG_QUESTION\",\"sessionId\":\"$SESSION_ID\",\"numPredict\":5}" "$URL" || true)
echo "Response (numPredict=5): $CUT"
echo "$CUT" | grep -q '"finishReason":"length"' || { echo "numPredict=5 did not end with finishReason length"; exit 6; }
CUT_TOKENS=$(int_field "$CUT" completionTokens)
if [ -n "$CUT_TOKENS" ] && [ "$CUT_TOKENS" -gt 5 ]; then
  echo "completionTokens ($CUT_TOKENS) exceeds numPredict (5)"
  exit 7
fi

next_session
FULL=$(curl -s --max-time 170 -H "Content-Type: application/json" \
  -d "{\"question\":\"$LONG_QUESTION\",\"sessionId\":\"$SESSION_ID\"}" "$URL" || true)
echo "Response (no numPredict): $FULL"
if echo "$FULL" | grep -q '"finishReason":"length"'; then
  echo "The request without numPredict was truncated: the previous setting leaked or the default limit was hit"
  exit 8
fi

# Prompt templates (feature 005): a registered template id is accepted and keeps the same response shape.
for TEMPLATE in concise tutor; do
  next_session
  TPL=$(curl -s --max-time 170 -H "Content-Type: application/json" \
    -d "{\"question\":\"Explain how recursion works\",\"sessionId\":\"$SESSION_ID\",\"templateId\":\"$TEMPLATE\",\"numPredict\":120}" "$URL" || true)
  echo "Response (templateId=$TEMPLATE): $TPL"
  echo "$TPL" | grep -q '"answer"' || { echo "templateId=$TEMPLATE: missing answer field"; exit 9; }
  echo "$TPL" | grep -q '"usage"' || { echo "templateId=$TEMPLATE: missing usage object"; exit 10; }
  echo "$TPL" | grep -q '"templateId"' && { echo "templateId=$TEMPLATE: the response must not report the template"; exit 11; }
done

# Structured classification (feature 005): the three strategies. A real model may break the format, so an
# HTTP 502 is reported as a failure of that strategy (with its "failure" field), not as a script error.
CLASSIFY_URL="http://localhost:8080/api/v1/classify"
for STRATEGY in instruction validate-retry native-schema; do
  BODY=$(curl -s --max-time 170 -w '\n%{http_code}' -H "Content-Type: application/json" \
    -d "{\"text\":\"My invoice has the wrong amount, I want a refund\",\"strategy\":\"$STRATEGY\",\"temperature\":0.0}" \
    "$CLASSIFY_URL" || true)
  CODE=$(echo "$BODY" | tail -n 1)
  RESPONSE=$(echo "$BODY" | sed '$d')
  echo "Response (strategy=$STRATEGY, HTTP $CODE): $RESPONSE"
  echo "$RESPONSE" | grep -q "\"strategy\":\"$STRATEGY\"" || { echo "strategy=$STRATEGY: the response does not name its strategy"; exit 12; }
  echo "$RESPONSE" | grep -q '"attempts"' || { echo "strategy=$STRATEGY: missing attempts"; exit 13; }
  case "$CODE" in
    200)
      echo "$RESPONSE" | grep -q '"result"' || { echo "strategy=$STRATEGY: missing result"; exit 14; }
      echo "$RESPONSE" | grep -q '"usage"' || { echo "strategy=$STRATEGY: missing usage object"; exit 15; }
      ;;
    502)
      echo "$RESPONSE" | grep -q '"failure"' || { echo "strategy=$STRATEGY: 502 without a failure field"; exit 16; }
      echo "Note: strategy=$STRATEGY did not meet the format (reported as a failure of that strategy)"
      ;;
    *)
      echo "strategy=$STRATEGY: unexpected HTTP $CODE"
      exit 17
      ;;
  esac
  if [ "$STRATEGY" != "validate-retry" ]; then
    echo "$RESPONSE" | grep -q '"retries":0' || { echo "strategy=$STRATEGY must report retries 0"; exit 18; }
  fi
done

# Conversation memory (feature 006). The history size is deterministic, so it is asserted; whether the model uses
# it is not, so the recall check only warns.
chat() {  # chat <question> [sessionId]: prints the response body; without a second argument no sessionId is sent
  if [ $# -ge 2 ]; then
    curl -s --max-time 170 -H "Content-Type: application/json" \
      -d "{\"question\":\"$1\",\"sessionId\":\"$2\",\"numPredict\":40}" "$URL" || true
  else
    curl -s --max-time 170 -H "Content-Type: application/json" -d "{\"question\":\"$1\",\"numPredict\":40}" "$URL" || true
  fi
}

next_session
SESSION_A="$SESSION_ID"
for EXPECTED in 0 2 4; do
  RESPONSE=$(chat "Tell me one short fact" "$SESSION_A")
  echo "Response (session A, historyMessages=$EXPECTED expected): $RESPONSE"
  echo "$RESPONSE" | grep -q "\"historyMessages\":$EXPECTED[,}]" || { echo "Expected historyMessages $EXPECTED in session $SESSION_A"; exit 19; }
done

next_session
RESPONSE=$(chat "Tell me one short fact" "$SESSION_ID")
echo "$RESPONSE" | grep -q '"historyMessages":0[,}]' || { echo "A second session must start with historyMessages 0: $RESPONSE"; exit 20; }

RESPONSE=$(chat "Tell me one short fact")
echo "$RESPONSE" | grep -q '"historyMessages":0[,}]' || { echo "A request without sessionId must report historyMessages 0: $RESPONSE"; exit 21; }

LONG_ID=$(printf 'a%.0s' $(seq 1 65))
for BAD_ID in "" "a b" "$LONG_ID"; do
  BAD_STATUS=$(curl -s -o /dev/null -w '%{http_code}' -H "Content-Type: application/json" \
    -d "{\"question\":\"hi\",\"sessionId\":\"$BAD_ID\"}" "$URL" || true)
  [ "$BAD_STATUS" = "400" ] || { echo "Expected HTTP 400 for sessionId '$BAD_ID', got '$BAD_STATUS'"; exit 22; }
done

next_session
chat "My name is Ana" "$SESSION_ID" > /dev/null
RECALL=$(chat "What is my name?" "$SESSION_ID")
echo "Response (recall): $RECALL"
echo "$RECALL" | grep -q 'Ana' || echo "Warning: the answer does not mention Ana (the model is not deterministic; not a failure)"

echo "Integration smoke test passed"
