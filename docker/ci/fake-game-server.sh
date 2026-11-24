#!/bin/bash
# Stand-in for the game server: says when it starts and stops; IGNORE_TERM makes it ignore polite stops
echo "fake server $$ up"
if [ -n "${IGNORE_TERM:-}" ]; then trap '' TERM; else trap 'echo "fake server $$ got TERM"; exit 0' TERM; fi
[ -n "${EXIT_AFTER:-}" ] && { sleep "$EXIT_AFTER"; exit 3; }
while true; do sleep 1; done
