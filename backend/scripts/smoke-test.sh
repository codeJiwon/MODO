#!/bin/bash
# MODO 그룹 동기화 서버 스모크 테스트. 사용: PORT=18080 backend/build/install/backend/bin/backend 실행 후 bash backend/scripts/smoke-test.sh
H=${MODO_SERVER:-http://127.0.0.1:18080}
set -u
j() { python3 -c "import sys,json;d=json.load(sys.stdin);print($1)"; }
echo "health: $(curl -s $H/health)"
R=$(curl -s -X POST $H/api/groups -H 'Content-Type: application/json' -d '{"name":"강릉 여행","destination":{"id":"dst","name":"강릉역","address":"강릉시","location":{"latitude":37.7644,"longitude":128.8995}},"creatorId":"A","displayName":"차량 A","vehicleLabel":"A","passengerCount":3}')
GID=$(echo "$R" | j "d['state']['group']['id']"); CODE=$(echo "$R" | j "d['state']['group']['inviteCode']")
echo "create: group=$GID invite=$CODE"
echo "join (wrong code): $(curl -s -o /dev/null -w '%{http_code}' -X POST $H/api/groups/join -H 'Content-Type: application/json' -d '{"command":{"@type":"join","commandId":"j0","actorId":"B","inviteCode":"ZZZZZZ","displayName":"B","vehicleLabel":"B"}}')"
JB='{"command":{"commandId":"j1","actorId":"B","inviteCode":"'$CODE'","displayName":"차량 B","vehicleLabel":"B","passengerCount":3}}'
echo "join: $(curl -s -o /dev/null -w '%{http_code}' -X POST $H/api/groups/join -H 'Content-Type: application/json' --data-binary "$JB")"
echo "join again (duplicate): $(curl -s -o /dev/null -w '%{http_code}' -X POST $H/api/groups/join -H 'Content-Type: application/json' --data-binary "${JB/j1/j9}")"
echo "request: $(curl -s -X POST $H/api/groups/$GID/requests -H 'Content-Type: application/json' -d '{"actorId":"B","text":"우리 잠깐 쉬었다 가자.","commandId":"r1"}' | j "d['outcome'], d['events'][0]['message']")"
echo "same commandId again: $(curl -s -X POST $H/api/groups/$GID/requests -H 'Content-Type: application/json' -d '{"actorId":"B","text":"우리 잠깐 쉬었다 가자.","commandId":"r1"}' | j "d['outcome']")"
echo "unrecognized: $(curl -s -X POST $H/api/groups/$GID/requests -H 'Content-Type: application/json' -d '{"actorId":"B","text":"라디오 켜줘"}' | j "d['outcome'], d['error']['code']")"
P=$(curl -s -X POST $H/api/groups/$GID/proposals -H 'Content-Type: application/json' -d '{"actorId":"A","place":{"id":"rest-h","name":"횡성휴게소","address":"횡성군","location":{"latitude":37.4649,"longitude":128.1346}}}' | j "d['state']['proposals'][0]['id']")
echo "dispatch before approval: $(curl -s -w ' HTTP%{http_code}' -X POST $H/api/groups/$GID/proposals/$P/dispatch -H 'Content-Type: application/json' -d '{"actorId":"A"}' | sed 's/.*"code":"\([A-Z_]*\)".*HTTP/\1 HTTP/')"
echo "approve B: $(curl -s -X POST $H/api/groups/$GID/proposals/$P/responses -H 'Content-Type: application/json' -d '{"actorId":"B","approve":true}' | j "d['state']['proposals'][0]['status']")"
echo "dispatch: $(curl -s -X POST $H/api/groups/$GID/proposals/$P/dispatch -H 'Content-Type: application/json' -d '{"actorId":"A"}' | j "[a['status'] for a in d['state']['navigationActions']]")"
echo "poll unchanged: $(curl -s -o /dev/null -w '%{http_code}' "$H/api/groups/$GID/state?since=999&member=A")"
echo "members: $(curl -s $H/api/groups/$GID/members | j "[m['vehicleLabel'] for m in d]")"
